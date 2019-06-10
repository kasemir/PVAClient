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
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import org.epics.pva.Guid;
import org.epics.pva.PVAHeader;
import org.epics.pva.PVASettings;
import org.epics.pva.data.PVAString;
import org.epics.pva.data.PVATypeRegistry;
import org.epics.pva.network.CommandHandlers;
import org.epics.pva.network.RequestEncoder;
import org.epics.pva.network.TCPHandler;

/** Handle TCP connection to PVA server
 *
 *  <p>Maintains state of all the channels that
 *  we read/write on one PVA server.
 *
 *  @author Kay Kasemir
 */
@SuppressWarnings("nls")
class ClientTCPHandler extends TCPHandler
{
    private static final CommandHandlers<ClientTCPHandler> handlers =
        new CommandHandlers<>(new ValidationHandler(),
                              new ValidatedHandler(),
                              new EchoHandler(),
                              new CreateChannelHandler(),
                              new DestroyChannelHandler(),
                              new GetHandler(),
                              new PutHandler(),
                              new MonitorHandler());

    /** Client context */
    private final PVAClient client;

    /** Channels that use this connection */
    private final CopyOnWriteArrayList<PVAChannel> channels = new CopyOnWriteArrayList<>();

    /** Server's GUID */
    private final Guid guid;

    private final AtomicInteger server_changes = new AtomicInteger(-1);

    /** Description of data types used with this PVA server */
    private final PVATypeRegistry types = new PVATypeRegistry();

    /** Map of response handlers by request ID
     *
     *  <p>When response for request ID is received,
     *  handler is removed from map and invoked
     */
    private final ConcurrentHashMap<Integer, ResponseHandler> response_handlers = new ConcurrentHashMap<>();

    /** Timer used to check if connection is still alive */
    private static final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(run ->
    {
        final Thread thread = new Thread(run, "TCP Alive Timer");
        thread.setDaemon(true);
        return thread;
    });

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

    public ClientTCPHandler(final PVAClient client, final InetSocketAddress address, final Guid guid) throws Exception
    {
        super(createSocket(address), true);
        logger.log(Level.FINE, () -> "TCPHandler " + guid + " for " + address + " created ============================");
        this.client = client;
        this.guid = guid;

        // For default EPICS_CA_CONN_TMO: 30 sec, send echo at ~15 sec:
        // Check every ~3 seconds
        final long period = Math.max(1, PVASettings.EPICS_CA_CONN_TMO * 1000L / 30 * 3);
        alive_check = timer.scheduleWithFixedDelay(this::checkResponsiveness, period, period, TimeUnit.MILLISECONDS);
        // Don't start the send thread, yet.
        // To prevent sending messages before the server is ready,
        // it's started when server confirms the connection.
    }

    private static SocketChannel createSocket(InetSocketAddress address) throws Exception
    {
        final SocketChannel socket = SocketChannel.open(address);
        socket.configureBlocking(true);
        socket.socket().setTcpNoDelay(true);
        socket.socket().setKeepAlive(true);
        return socket;
    }


    /** @return Client context */
    PVAClient getClient()
    {
        return client;
    }

    /** @param channel Channel that uses this TCP connection */
    void addChannel(final PVAChannel channel)
    {
        channels.add(channel);
    }

    /** @param channel Channel that no longer uses this TCP connection */
    void removeChannel(final PVAChannel channel)
    {
        channels.remove(channel);
    }

    /** @return Channels that use this connection */
    public Collection<PVAChannel> getChannels()
    {
        return channels;
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

    /** Submit item to be sent to server and register handler for the response
     *
     *  <p>Handler will be invoked when the server replies to the request.
     *  @param item {@link RequestEncoder}
     *  @param handler {@link ResponseHandler}
     */
    public void submit(final RequestEncoder item, final ResponseHandler handler)
    {
        final int request_id = handler.getRequestID();
        response_handlers.put(request_id, handler);
        if (! submit(item))
            removeResponseHandler(request_id);
    }

    ResponseHandler getResponseHandler(final int request_id)
    {
        return response_handlers.get(request_id);
    }

    /** Unregister response handler
     *  @param request_id Request ID
     *  @return {@link ResponseHandler} that will no longer be called,
     *          <code>null</code> if none registered
     */
    ResponseHandler removeResponseHandler(final int request_id)
    {
        return response_handlers.remove(request_id);
    }

    /** Check responsiveness of this TCP connection */
    private void checkResponsiveness()
    {
        final long idle = System.currentTimeMillis() - last_life_sign;
        if (idle > PVASettings.EPICS_CA_CONN_TMO * 1000)
        {
            // If silent for full EPICS_CA_CONN_TMO, disconnect and start over
            logger.log(Level.FINE, () -> this + " silent for " + idle + "ms, closing");
            client.shutdownConnection(this);
        }
        else if (idle >= PVASettings.EPICS_CA_CONN_TMO * 1000 / 2)
        {
            if (channels.isEmpty())
            {   // Connection is idle because no channel uses it. Close!
                logger.log(Level.FINE, () -> this + " unused for " + idle + "ms, closing");
                client.shutdownConnection(this);
                return;
            }

            // With default EPICS_CA_CONN_TMO of 30 seconds,
            // Echo requested every 15 seconds.
            logger.log(Level.FINE, () -> this + " silent for " + idle + "ms, requesting echo");
            // Skip echo if the send queue already has items to avoid
            // filling queue which isn't emptied anyway.
            if (isSendQueueIdle())
                submit(echo_request);
            else
                logger.log(Level.FINE, () -> "Skipping echo, send queue already has items to send");
        }
    }

    /** Called whenever e.g. value is received and server is thus alive */
    void markAlive()
    {
        last_life_sign = System.currentTimeMillis();
    }

    @Override
    protected void onReceiverExited(final boolean running)
    {
        if (running)
            client.shutdownConnection(this);
    }

    @Override
    protected void handleControlMessage(final byte command, final ByteBuffer buffer) throws Exception
    {
        if (command == PVAHeader.CTRL_SET_BYTE_ORDER)
        {
            // First message received from server, remember its version
            final byte version = buffer.get(1);
            server_version  = version;
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
        else
            super.handleControlMessage(command, buffer);
    }

    @Override
    protected void handleApplicationMessage(final byte command, final ByteBuffer buffer) throws Exception
    {
        if (! handlers.handleCommand(command, this, buffer))
            logger.log(Level.WARNING, "Cannot handle reply for application command " + command);

    }

    void handleValidationRequest(final int server_receive_buffer_size,
                                 final short server_introspection_registry_max_size) throws Exception
    {
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

    void markValid() throws Exception
    {
        if (connection_validated.compareAndSet(false, true))
            startSender();
    }

    /** Close network socket and threads
     *  @param wait Wait for threads to end?
     */
    @Override
    public void close(final boolean wait)
    {
        alive_check.cancel(false);

        super.close(wait);
    }

    @Override
    public String toString()
    {
        return super.toString() + " " + guid;
    }
}
