/*******************************************************************************
 * Copyright (c) 2019 Oak Ridge National Laboratory.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 ******************************************************************************/
package org.epics.pva.server;

import static org.epics.pva.PVASettings.logger;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.logging.Level;

import org.epics.pva.Guid;
import org.epics.pva.PVAConstants;
import org.epics.pva.PVAHeader;
import org.epics.pva.PVASettings;
import org.epics.pva.client.SearchRequest;
import org.epics.pva.data.Hexdump;
import org.epics.pva.data.PVAAddress;
import org.epics.pva.data.PVABool;
import org.epics.pva.data.PVAString;
import org.epics.pva.network.Network;
import org.epics.pva.network.UDPHandler;

/** Listen to search requests, send beacons
 *  @author Kay Kasemir
 */
@SuppressWarnings("nls")
class ServerUDPHandler extends UDPHandler
{
    /** Invoked when client sends a generic 'list servers'
     *  as well as a specific PV name search
     */
    @FunctionalInterface
    public interface SearchHandler
    {
        /** @param seq Client's search sequence
         *  @param cid Client channel ID or -1
         *  @param name Channel name or <code>null</code>
         *  @param addr Client's address and TCP port
         */
        public void handleSearchRequest(int seq, int cid, String name, InetSocketAddress addr);
    }

    private final SearchHandler search_handler;

    /** UDP channel on which we listen to name search
     *  and on which we send beacons
     */
    private final DatagramChannel udp;

    private final InetSocketAddress local_address;
    private final InetSocketAddress local_multicast;

    private final ByteBuffer receive_buffer = ByteBuffer.allocate(PVAConstants.MAX_UDP_PACKET);
    private final ByteBuffer send_buffer = ByteBuffer.allocate(PVAConstants.MAX_UDP_PACKET);

    private volatile Thread listen_thread = null;


    /** Start handling UDP search requests
     *  @param search_handler Callback for received name searches
     *  @throws Exception on error
     */
    public ServerUDPHandler(final SearchHandler search_handler) throws Exception
    {
        this.search_handler = search_handler;
        udp = Network.createUDP(false, PVASettings.EPICS_PVA_BROADCAST_PORT);
        local_multicast = Network.configureMulticast(udp);
        local_address = (InetSocketAddress) udp.getLocalAddress();
        logger.log(Level.FINE, "Awaiting searches and sending beacons on UDP " + local_address);

        listen_thread = new Thread(() -> listen(udp, receive_buffer), "UDP-receiver " + local_address);
        listen_thread.setDaemon(true);
        listen_thread.start();
    }

    @Override
    protected boolean handleMessage(final InetSocketAddress from, final byte version,
                                    final byte command, final int payload, final ByteBuffer buffer)
    {
        switch (command)
        {
        case PVAHeader.CMD_ORIGIN_TAG:
            return handleOriginTag(from, version, payload, buffer);
        case PVAHeader.CMD_SEARCH:
            return handleSearch(from, version, payload, buffer);
        default:
            logger.log(Level.WARNING, "PVA Client " + from + " sent UDP packet with unknown command 0x" + Integer.toHexString(command));
        }
        return true;
    }

    private boolean handleOriginTag(final InetSocketAddress from, final byte version,
                                    final int payload, final ByteBuffer buffer)
    {
        final InetAddress addr;
        try
        {
            addr = PVAAddress.decode(buffer);
        }
        catch (Exception ex)
        {
            logger.log(Level.WARNING, "PVA Client " + from + " sent origin tag with invalid address");
            return false;
        }
        logger.log(Level.FINER, () -> "PVA Client " + from + " sent origin tag " + addr);

        return true;
    }

    /** @param from Origin of search request
     *  @param version Client's version
     *  @param payload Size of payload
     *  @param buffer Buffer with search request
     *  @return Valid request?
     */
    private boolean handleSearch(final InetSocketAddress from, final byte version,
                                 final int payload, final ByteBuffer buffer)
    {
        // Search Sequence ID
        final int seq = buffer.getInt();

        // 0-bit for replyRequired, 7-th bit for "sent as unicast" (1)/"sent as broadcast/multicast" (0)
        final byte flags = buffer.get();
        final boolean unicast = (flags & 0x80) == 0x80;
        final boolean reply_required = (flags & 0x01) == 0x01;

        // reserved
        buffer.get();
        buffer.get();
        buffer.get();

        // responseAddress, IPv6 address in case of IP based transport, UDP
        final InetAddress addr;
        try
        {
            addr = PVAAddress.decode(buffer);
        }
        catch (Exception ex)
        {
            logger.log(Level.WARNING, "PVA Client " + from + " sent search #" + seq + " with invalid address");
            return false;
        }
        final int port = Short.toUnsignedInt(buffer.getShort());

        // Use address from message unless it's a generic local address
        final InetSocketAddress client;
        if (addr.isAnyLocalAddress())
            client = new InetSocketAddress(from.getAddress(), port);
        else
            client = new InetSocketAddress(addr, port);

        // Assert that client supports "tcp", ignore rest
        boolean tcp = false;
        int count = Byte.toUnsignedInt(buffer.get());
        String protocol = "<none>";
        for (int i=0; i<count; ++i)
        {
            protocol = PVAString.decodeString(buffer);
            if ("tcp".equals(protocol))
            {
                tcp = true;
                break;
            }
        }

        // Loop over searched channels
        count = Short.toUnsignedInt(buffer.getShort());

        if (count == 0  &&  reply_required)
        {   // pvlist request
            logger.log(Level.FINER, () -> "PVA Client " + from + " sent search #" + seq + " to list servers");
            search_handler.handleSearchRequest(0, -1, null, client);
            if (unicast)
                PVAServer.POOL.submit(() -> forwardSearchRequest(0, -1, null, client.getAddress(), (short)client.getPort()));
        }
        else
        {   // Channel search request
            if (! tcp)
            {
                logger.log(Level.WARNING, "PVA Client " + from + " sent search #" + seq + " for protocol '" + protocol + "', need 'tcp'");
                return false;
            }
            for (int i=0; i<count; ++i)
            {
                final int cid = buffer.getInt();
                final String name = PVAString.decodeString(buffer);
                logger.log(Level.FINER, () -> "PVA Client " + from + " sent search #" + seq + " for " + name + " [" + cid + "]");
                search_handler.handleSearchRequest(seq, cid, name, client);
                if (unicast)
                    PVAServer.POOL.submit(() -> forwardSearchRequest(seq, cid, name, client.getAddress(), (short)client.getPort()));
            }
        }

        return true;
    }

    /** Forward a search request that we received as unicast to the local multicast group
     *
     *  <p>This allows other local UDP listeners to see the message,
     *  which we received because we're the last program to attach to the UDP port,
     *  shielding unicast messages to already running listeners.
     *
     *  @param seq Search sequence or 0
     *  @param cid Channel ID or -1
     *  @param name Name or <code>null</code>
     *  @param address Client's address ..
     *  @param port    .. and port
     */
    private void forwardSearchRequest(final int seq, final int cid, final String name, final InetAddress address, final short port)
    {
        if (local_multicast == null)
            return;
        synchronized (send_buffer)
        {
            send_buffer.clear();
            SearchRequest.encode(false, seq, cid, name, address, port, send_buffer);
            send_buffer.flip();
            logger.log(Level.FINER, () -> "Forward search to " + local_multicast + "\n" + Hexdump.toHexdump(send_buffer));
            try
            {
                udp.send(send_buffer, local_multicast);
            }
            catch (Exception ex)
            {
                logger.log(Level.WARNING, "Cannot forward search", ex);
            }
        }
    }

    /** Send a "channel found" reply to a client's search
     *  @param guid This server's GUID
     *  @param seq Client search request sequence number
     *  @param cid Client's channel ID or -1
     *  @param tcp TCP connection where client can connect to this server
     *  @param client Address of client's UDP port
     */
    public void sendSearchReply(final Guid guid, final int seq, final int cid, final ServerTCPListener tcp, final InetSocketAddress client)
    {
        synchronized (send_buffer)
        {
            send_buffer.clear();
            PVAHeader.encodeMessageHeader(send_buffer, PVAHeader.FLAG_SERVER, PVAHeader.CMD_SEARCH_REPLY, 12+4+16+2+4+1+2+ (cid < 0 ? 0 : 4));

            // Server GUID
            guid.encode(send_buffer);

            // Search Sequence ID
            send_buffer.putInt(seq);

            // Server's address and port
            PVAAddress.encode(tcp.response_address, send_buffer);
            send_buffer.putShort((short)tcp.response_port);

            // Protocol
            PVAString.encodeString("tcp", send_buffer);

            // Found
            PVABool.encodeBoolean(cid >= 0, send_buffer);

            // int[] cid;
            if (cid < 0)
                send_buffer.putShort((short)0);
            else
            {
                send_buffer.putShort((short)1);
                send_buffer.putInt(cid);
            }

            send_buffer.flip();
            logger.log(Level.FINER, () -> "Sending search reply to " + client + "\n" + Hexdump.toHexdump(send_buffer));
            try
            {
                udp.send(send_buffer, client);
            }
            catch (Exception ex)
            {
                logger.log(Level.WARNING, "Cannot send search reply", ex);
            }
        }
    }

    @Override
    public void close()
    {
        super.close();
        // Close sockets, wait a little for threads to exit
        try
        {
            udp.close();

            if (listen_thread != null)
                listen_thread.join(5000);
        }
        catch (Exception ex)
        {
            // Ignore
        }
    }
}