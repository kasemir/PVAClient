/*******************************************************************************
 * Copyright (c) 2019 Oak Ridge National Laboratory.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 ******************************************************************************/
package org.epics.pva.client;

import static org.epics.pva.PVASettings.logger;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import org.epics.pva.PVAConstants;
import org.epics.pva.PVAHeader;
import org.epics.pva.PVASettings;
import org.epics.pva.data.Hexdump;
import org.epics.pva.data.PVASize;
import org.epics.pva.data.PVAStatus;
import org.epics.pva.data.PVAString;
import org.epics.pva.data.PVATypeRegistry;

@SuppressWarnings("nls")
class TCPHandler
{
    private volatile int server_buffer_size = PVAConstants.TCP_BUFFER_SIZE;

    /** Client context */
    private final PVAClient client;

    /** Server's GUID */
    private final Guid guid;

    private final AtomicInteger server_changes = new AtomicInteger(-1);

    /** Description of data types used with this PVA server */
    private final PVATypeRegistry types = new PVATypeRegistry();

    /** TCP socket to PVA server */
    private final SocketChannel socket;

    /** Flag to indicate that 'close' was called to close the 'socket' */
    private volatile boolean closed = false;

    /** Buffer used to receive data via {@link TCPHandler#receive_thread} */
    private ByteBuffer receive_buffer = ByteBuffer.allocate(PVAConstants.TCP_BUFFER_SIZE);

    /** Buffer used to send data via {@link TCPHandler#send_thread} */
    private final ByteBuffer send_buffer = ByteBuffer.allocate(PVAConstants.TCP_BUFFER_SIZE);

    /** Queue of items to send to server */
    private final BlockingQueue<RequestEncoder> send_items = new LinkedBlockingQueue<>();

    /** Magic `send_items` value that asks send thread to exit */
    private static final RequestEncoder END_REQUEST = new RequestEncoder()
    {
        @Override
        public void encodeRequest(final byte version, final ByteBuffer buffer) throws Exception
        {
            throw new IllegalStateException("END_REQUEST not meant to be encoded");
        }
    };

    /** Map of response handlers by request ID
     *
     *  <p>When response for request ID is received,
     *  handler is removed from map and invoked
     */
    private final ConcurrentHashMap<Integer, ResponseHandler> response_handlers = new ConcurrentHashMap<>();

    /** Pool for sender and receiver threads */
    private static final ExecutorService thread_pool = Executors.newCachedThreadPool(runnable ->
    {
        final Thread thread = new Thread(runnable);
        thread.setDaemon(true);
        return thread;
    });

    /** Timer used to check if connection is still alive */
    private static final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(run ->
    {
        final Thread thread = new Thread(run, "TCP Alive Timer");
        thread.setDaemon(true);
        return thread;
    });

    /** Thread that runs {@link TCPHandler#receiver()} */
    private final Future<Void> receive_thread;

    /** Thread that runs {@link TCPHandler#sender()} */
    private volatile Future<Void> send_thread;

    private volatile ScheduledFuture<?> alive_check;

    private volatile long last_life_sign = System.currentTimeMillis();

    private static final RequestEncoder echo_request = new EchoRequest();

    /** Indicates completion of the connection validation:
     *  Server sent connection validation request,
     *  we replied, server confirmed with CMD_VALIDATED.
     *
     *  Client must not send get/put/.. messages until
     *  this flag is set.
     */
    private final AtomicBoolean connection_validated = new AtomicBoolean();

    /** Protocol version used by server.
     *
     *  <p>Set when we receive its first set-byte-order message
     */
    private volatile byte server_version = 0;

    public TCPHandler(final PVAClient client, final InetSocketAddress address, final Guid guid) throws Exception
    {
        logger.log(Level.FINE, () -> "TCPHandler " + guid + " for " + address + " created.");
        this.client = client;
        this.guid = guid;
        socket = SocketChannel.open(address);
        socket.configureBlocking(true);
        socket.socket().setTcpNoDelay(true);
        socket.socket().setKeepAlive(true);

        // Start receiving data
        receive_thread = thread_pool.submit(this::receiver);

        // For default EPICS_CA_CONN_TMO: 30 sec, send echo at ~15 sec:
        // Check every ~3 seconds
        final long period = Math.max(1, PVASettings.EPICS_CA_CONN_TMO * 1000L / 30 * 3);
        alive_check = timer.scheduleWithFixedDelay(this::checkResponsiveness, period, period, TimeUnit.MILLISECONDS);
        // Don't start the send thread, yet.
        // To prevent sending messages before the server is ready,
        // it's started when server confirms the connection.
    }

    /** @return Guid of server */
    public Guid getGuid()
    {
        return guid;
    }

    /** Check if the server's beacon indicates changes
     *  @param changes Change counter from beacon
     *  @return <code>true</code> if this suggests new channels on the server
     */
    public boolean checkBeaconChanges(final int changes)
    {
        return server_changes.getAndSet(changes) != changes;
    }

    public PVATypeRegistry getTypeRegistry()
    {
        return types;
    }

    public InetSocketAddress getAddress()
    {
        return new InetSocketAddress(socket.socket().getInetAddress(), socket.socket().getPort());
    }

    /** Submit item to be sent to server
     *  @param item {@link RequestEncoder}
     */
    public void submit(final RequestEncoder item)
    {
        if (! send_items.offer(item))
            logger.log(Level.WARNING, this + " send queue full");
    }

    /** Submit item to be sent to server and register handler for the response
     *
     *  <p>Handler will be invoked when the server replies to the request.
     *  @param item {@link RequestEncoder}
     *  @param handler {@link ResponseHandler}
     */
    public void submit(final RequestEncoder item, final ResponseHandler handler)
    {
        response_handlers.put(handler.getRequestID(), handler);
        if (! send_items.offer(item))
        {
            logger.log(Level.WARNING, this + " send queue full");
            removeHandler(handler);
        }
    }

    ResponseHandler getResponseHandler(final int request_id)
    {
        return response_handlers.get(request_id);
    }

    /** Unregister response handler
     *  @param handler {@link ResponseHandler} that will no longer be called
     */
    public void removeHandler(final ResponseHandler handler)
    {
        response_handlers.remove(handler.getRequestID());
    }

    /** Check responsiveness of this TCP connection */
    private void checkResponsiveness()
    {
        final long idle = System.currentTimeMillis() - last_life_sign;
        if (idle > PVASettings.EPICS_CA_CONN_TMO * 1000)
        {
            // If idle for full EPICS_CA_CONN_TMO, disconnect and start over
            logger.log(Level.FINE, () -> this + " idle for " + idle + "ms, closing");
            client.handleConnectionLost(this);
        }
        else if (idle >= PVASettings.EPICS_CA_CONN_TMO * 1000 / 2)
        {
            // With default EPICS_CA_CONN_TMO of 30 seconds,
            // Echo requested every 15 seconds.
            logger.log(Level.FINE, () -> this + " idle for " + idle + "ms, requesting echo");
            // Skip echo if the send queue already has items to avoid
            // filling queue which isn't emptied anyway.
            if (send_items.isEmpty())
                submit(echo_request);
            else
                logger.log(Level.FINE, () -> "Skipping echo, send queue already has items to send");
        }
    }

    /** Called whenever e.g. value is received and server is thus alive */
    private void markAlive()
    {
        last_life_sign = System.currentTimeMillis();
    }

    private Void sender()
    {
        try
        {
            Thread.currentThread().setName("TCP sender " + socket.getRemoteAddress());
            logger.log(Level.FINER, Thread.currentThread().getName() + " started");
            while (true)
            {
                send_buffer.clear();
                final RequestEncoder to_send = send_items.take();
                if (to_send == END_REQUEST)
                    break;
                to_send.encodeRequest(server_version, send_buffer);
                send_buffer.flip();
                send(send_buffer);
            }
        }
        catch (Exception ex)
        {
            logger.log(Level.WARNING, Thread.currentThread().getName() + " error", ex);
        }
        logger.log(Level.FINER, Thread.currentThread().getName() + " done.");
        return null;
    }

    private void send(final ByteBuffer buffer) throws Exception
    {
        logger.log(Level.FINER, () -> Thread.currentThread().getName() + ":\n" + Hexdump.toHexdump(buffer));

        // Original AbstractCodec.send() mentions
        // Microsoft KB article KB823764:
        // Limiting buffer size increases performance.
        final int batch_limit = server_buffer_size / 2;
        final int total = buffer.limit();
        int batch = total - buffer.position();
        if (batch > batch_limit)
        {
            batch = batch_limit;
            buffer.limit(buffer.position() + batch);
        }

        int tries = 0;
        while (batch > 0)
        {
            final int sent = socket.write(buffer);
            if (sent < 0)
                throw new Exception("Connection closed");
            else if (sent == 0)
            {
                logger.log(Level.FINER, "Send buffer full after " + buffer.position() + " of " + total + " bytes.");
                Thread.sleep(Math.max(++tries * 100, 1000));
            }
            else
            {
                // Wrote _something_
                tries = 0;
                // Determine next batch
                batch = total - buffer.position();
                if (batch > batch_limit)
                    batch = batch_limit;
                // In case batch > 0, move limit to write that batch
                buffer.limit(buffer.position() + batch);
            }
        }
    }

    private Void receiver()
    {
        try
        {
            Thread.currentThread().setName("TCP receiver " + socket.getRemoteAddress());
            logger.log(Level.FINER, Thread.currentThread().getName() + " started");
            logger.log(Level.FINER, "Native byte order " + receive_buffer.order());
            receive_buffer.clear();
            while (true)
            {
                // Read at least one complete message,
                // which requires the header..
                int message_size = checkMessageAndGetSize(receive_buffer);
                while (receive_buffer.position() < message_size)
                {
                    checkReceiveBufferSize(message_size);
                    final int read = socket.read(receive_buffer);
                    if (read < 0)
                        throw new Exception("TCP Socket closed");
                    if (read > 0)
                        logger.log(Level.FINER, () -> Thread.currentThread().getName() + ": " + read + " bytes");
                    // and once we get the header, it will tell
                    // us how large the message actually is
                    message_size = checkMessageAndGetSize(receive_buffer);
                }
                // .. then decode
                receive_buffer.flip();
                logger.log(Level.FINER, () -> Thread.currentThread().getName() + ":\n" + Hexdump.toHexdump(receive_buffer));

                handleMessage(receive_buffer, message_size - PVAHeader.HEADER_SIZE);

                // No matter if message handler read the complete message,
                // position at end of handled message
                receive_buffer.position(message_size);

                // Shift rest to start of buffer and handle next message
                receive_buffer.compact();
            }
        }
        catch (Exception ex)
        {
            if (!closed)
                logger.log(Level.WARNING, Thread.currentThread().getName() + " error", ex);
        }
        logger.log(Level.FINER, Thread.currentThread().getName() + " done.");
        if (! closed)
            client.handleConnectionLost(this);
        return null;
    }

    private void checkReceiveBufferSize(final int message_size)
    {
        if (receive_buffer.capacity() >= message_size)
            return;

        final ByteBuffer new_buffer = ByteBuffer.allocate(message_size);
        new_buffer.order(receive_buffer.order());
        receive_buffer.flip();
        new_buffer.put(receive_buffer);

        logger.log(Level.INFO,
                   Thread.currentThread().getName() + " extends receive buffer from " +
                   receive_buffer.capacity() + " to " + new_buffer.capacity() +
                   ", copied " + new_buffer.position() + " bytes to new buffer");

        receive_buffer = new_buffer;
    }

    /** Check message header for correct protocol identifier and version
     *  @param buffer
     *  @return Expected total message size (header + payload)
     *  @throws Exception on protocol violation
     */
    private int checkMessageAndGetSize(final ByteBuffer buffer) throws Exception
    {
        if (buffer.position() < PVAHeader.HEADER_SIZE)
            return PVAHeader.HEADER_SIZE;

        final byte magic = buffer.get(0);
        if (magic != PVAHeader.PVA_MAGIC)
            throw new Exception("Message lacks magic");

        final byte version = buffer.get(1);
        if (version < PVAHeader.REQUIRED_PVA_PROTOCOL_REVISION)
            throw new Exception("Cannot handle protocol version " + version +
                                ", expect version " +
                                PVAHeader.REQUIRED_PVA_PROTOCOL_REVISION +
                                " or higher");

        final byte flags = buffer.get(2);
        if ((flags & PVAHeader.FLAG_SERVER) == 0)
            throw new Exception("Expected server message");

        if ((flags & PVAHeader.FLAG_BIG_ENDIAN) == 0)
            buffer.order(ByteOrder.LITTLE_ENDIAN);
        else
            buffer.order(ByteOrder.BIG_ENDIAN);

        // Control messages use the 'payload' field itself for data
        if ((flags & PVAHeader.FLAG_CONTROL) != 0)
            return PVAHeader.HEADER_SIZE;

        // Application messages are followed by this number of data bytes
        final int payload = buffer.getInt(PVAHeader.HEADER_OFFSET_PAYLOAD_SIZE);

        // Total message size: Header followed by data
        return PVAHeader.HEADER_SIZE + payload;
    }

    private void handleMessage(final ByteBuffer buffer, final int payload_size) throws Exception
    {
        final boolean control = (buffer.get(2) & PVAHeader.FLAG_CONTROL) != 0;
        final byte version = buffer.get(1);
        final byte command = buffer.get(3);
        // Move to start of potential payload
        buffer.position(8);
        if (control)
            switch (command)
            {
            case PVAHeader.CTRL_SET_BYTE_ORDER:
                // First message received from server, remember its version
                server_version  = version;
                handleSetByteOrder(buffer);
                break;
            default:
                logger.log(Level.WARNING, "Cannot handle control command " + command);
            }
        else
            switch (command)
            {
            case PVAHeader.CMD_VALIDATION:
                handleValidationRequest(buffer, payload_size);
                break;
            case PVAHeader.CMD_ECHO:
                handleEcho(buffer, payload_size);
                break;
            case PVAHeader.CMD_VALIDATED:
                final PVAStatus status = PVAStatus.decode(buffer);
                logger.log(Level.FINE, "Received server connection validation: " + status);
                // Mark connection as validated, allow sending data
                if (status.isSuccess())
                {
                    if (connection_validated.getAndSet(true) == false)
                    {
                        if (send_thread == null)
                            send_thread = thread_pool.submit(this::sender);
                        else
                            throw new Exception("Send thread already running");
                    }
                }
                break;
            case PVAHeader.CMD_CREATE_CHANNEL:
                handleChannelCreated(buffer, payload_size);
                break;
            case PVAHeader.CMD_DESTROY_CHANNEL:
                handleChannelDestroyed(buffer, payload_size);
                break;
            case PVAHeader.CMD_GET:
                handleGet(buffer, payload_size);
                break;
            case PVAHeader.CMD_PUT:
                handlePut(buffer, payload_size);
                break;
            case PVAHeader.CMD_MONITOR:
                handleMonitor(buffer, payload_size);
                break;
            case PVAHeader.CMD_GET_TYPE:
                handleGetType(buffer, payload_size);
                break;
            default:
                logger.log(Level.WARNING, "Cannot handle reply for application command " + command);
            }
    }

    private void handleSetByteOrder(final ByteBuffer buffer)
    {
        // By the time we decode this message,
        // receive buffer byte order has been set to the
        // order sent by the server.
        // Send thread is not running, yet, so safe to
        // configure it
        send_buffer.order(buffer.order());

        logger.log(Level.FINE, "Received set-byte-order for " + send_buffer.order());
        // Payload indicates if the server will send messages in that same order,
        // or might change order for each message.
        // We always adapt based on the flags of each received message,
        // so ignore.
        // sendBuffer byte order is locked at this time, though.
    }

    private void handleEcho(final ByteBuffer buffer, final int payload_size)
    {
        if (payload_size > 0)
        {
            final byte[] payload = new byte[payload_size];
            buffer.get(payload);
            if (Arrays.equals(payload, EchoRequest.CHECK))
                logger.log(Level.FINE, () -> "Received ECHO:\n" + Hexdump.toHexdump(payload));
            else
            {
                logger.log(Level.WARNING, this + " received invalid echo reply:\n" +
                                          Hexdump.toHexdump(payload));
                return;
            }
        }
        else
            logger.log(Level.FINE, "Received ECHO");
        markAlive();
    }

    private void handleValidationRequest(final ByteBuffer buffer, final int payload_size) throws Exception
    {
        if (payload_size < 4+2+1)
            throw new Exception("Incomplete Validation Request");

        final int server_receive_buffer_size = buffer.getInt();
        final short server_introspection_registry_max_size = buffer.getShort();
        final List<String> auth = new ArrayList<>();
        final int size = PVASize.decodeSize(buffer);
        for (int i=0; i<size; ++i)
            auth.add(PVAString.decodeString(buffer));
        logger.fine("Received connection validation request");
        logger.finer("Server receive buffer size: " + server_receive_buffer_size);
        logger.finer("Server registry max size: " + server_introspection_registry_max_size);
        logger.finer("Server authorizations: " + auth);

        // Don't send more than the server can handle
        server_buffer_size = Math.min(server_buffer_size, server_receive_buffer_size);

        // Now that server has contacted us and awaits a reply,
        // client needs to send validation response.
        // If server does not receive validation response within 5 seconds,
        // it will send a CMD_VALIDATED = 9 message with StatusOK and close the TCP connection.

        // Reply to Connection Validation request.
        logger.log(Level.FINE, "Sending connection validation response");
        // Since send thread is not running, yet, send directly
        PVAHeader.encodeMessageHeader(send_buffer, PVAHeader.FLAG_NONE, PVAHeader.CMD_VALIDATION, 4+2+2+1);
        // Inform server about our receive buffer size
        send_buffer.putInt(receive_buffer.capacity());
        // Unclear, just echo the server's size
        send_buffer.putShort(server_introspection_registry_max_size);
        // QoS = Connection priority
        final short quos = 0;
        send_buffer.putShort(quos);

        // Selected authNZ plug-in
        PVAString.encodeString("", send_buffer);

        send_buffer.flip();
        send(send_buffer);
    }

    private void handleChannelCreated(final ByteBuffer buffer, final int payload_size) throws Exception
    {
        if (payload_size < 4+4+1)
            throw new Exception("Incomplete Create Channel Response");
        final int cid = buffer.getInt();
        final int sid = buffer.getInt();
        final PVAStatus status = PVAStatus.decode(buffer);

        final PVAChannel channel = client.getChannel(cid);
        if (channel == null)
        {
            logger.log(Level.WARNING, this + " received create channel response for unknown channel ID " + cid);
            return;
        }

        if (status.isSuccess())
            channel.completeConnection(sid);
        else
        {
            logger.log(Level.WARNING, "Failed to create channel " + channel + ": " + status);

            // Reset channel to init state and search again, after delay
            channel.setState(ClientChannelState.INIT);
            client.search.register(channel, false);
        }
    }

    private void handleChannelDestroyed(final ByteBuffer buffer, final int payload_size) throws Exception
    {
        if (payload_size < 4+4)
            throw new Exception("Incomplete Destroy Channel Response");
        final int cid = buffer.getInt();
        final int sid = buffer.getInt();

        final PVAChannel channel = client.getChannel(cid);
        if (channel == null)
        {
            logger.log(Level.WARNING, this + " received destroy channel response for unknown channel ID " + cid);
            return;
        }
        channel.channelDestroyed(sid);
    }

    private void handleGet(final ByteBuffer buffer, final int payload_size) throws Exception
    {
        // Dispatch to the initiating GetRequest
        if (payload_size < 4)
            throw new Exception("Incomplete Get Response");
        final int request_id = buffer.getInt(buffer.position());

        final ResponseHandler handler = response_handlers.remove(request_id);
        if (handler == null)
            throw new Exception("Received unsolicited Get Response for request " + request_id);
        handler.handleResponse(buffer, payload_size);
        markAlive();
    }

    private void handlePut(final ByteBuffer buffer, final int payload_size) throws Exception
    {
        // Dispatch to the initiating GetRequest
        if (payload_size < 4)
            throw new Exception("Incomplete Put Response");
        final int request_id = buffer.getInt(buffer.position());

        final ResponseHandler handler = response_handlers.remove(request_id);
        if (handler == null)
            throw new Exception("Received unsolicited Put Response for request " + request_id);
        handler.handleResponse(buffer, payload_size);
        markAlive();
    }

    private void handleMonitor(final ByteBuffer buffer, final int payload_size) throws Exception
    {
        // Dispatch to the initiating MonitorRequest
        if (payload_size < 4)
            throw new Exception("Incomplete Monitor Response");
        final int request_id = buffer.getInt(buffer.position());

        final ResponseHandler handler = response_handlers.get(request_id);
        if (handler == null)
            throw new Exception("Received unsolicited Monitor Response for request " + request_id);
        handler.handleResponse(buffer, payload_size);
        markAlive();
    }

    private void handleGetType(final ByteBuffer buffer, final int payload_size) throws Exception
    {
        // Dispatch to the initiating InfoRequest
        if (payload_size < 4)
            throw new Exception("Incomplete Get-Type Response");
        final int request_id = buffer.getInt(buffer.position());

        final ResponseHandler handler = response_handlers.remove(request_id);
        if (handler == null)
            throw new Exception("Received unsolicited Get-Type Response for request " + request_id);
        handler.handleResponse(buffer, payload_size);
        markAlive();
    }

    /** Close network socket and threads
     *  @param wait Wait for threads to end?
     */
    public void close(final boolean wait)
    {
        logger.log(Level.FINE, "Closing " + this);

        alive_check.cancel(false);

        // Wait until all requests are sent out
        submit(END_REQUEST);
        try
        {
            if (send_thread != null  &&  wait)
                send_thread.get(5, TimeUnit.SECONDS);
        }
        catch (Exception ex)
        {
            logger.log(Level.WARNING, "Cannot stop " + send_thread, ex);
        }

        try
        {
            closed = true;
            socket.close();
            if (wait)
                receive_thread.get(5, TimeUnit.SECONDS);
        }
        catch (Exception ex)
        {
            logger.log(Level.WARNING, "Cannot stop " + receive_thread, ex);
        }
        logger.log(Level.FINE, () -> this + " closed.");
    }

    @Override
    public String toString()
    {
        final StringBuilder buf = new StringBuilder();
        buf.append("TCPHandler");
        try
        {
            final SocketAddress server = socket.getRemoteAddress();
            buf.append(" ").append(server);
        }
        catch (Exception ex)
        {
            // Ignore
        }
        buf.append(" ").append(guid);
        return buf.toString();
    }
}
