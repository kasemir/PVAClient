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
    @FunctionalInterface
    public interface SearchHandler
    {
        /** @param seq Client's search sequence
         *  @param cid Client channel ID
         *  @param name Channel name
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
    private final ByteBuffer receiveBuffer = ByteBuffer.allocate(PVAConstants.MAX_UDP_PACKET);
    private final ByteBuffer sendBuffer = ByteBuffer.allocate(PVAConstants.MAX_UDP_PACKET);

    private volatile Thread listen_thread = null;


    /** Start handling UDP search requests
     *  @param search_handler Callback for received name searches
     *  @throws Exception on error
     */
    public ServerUDPHandler(final SearchHandler search_handler) throws Exception
    {
        this.search_handler = search_handler;
        udp = Network.createUDP(false, PVASettings.EPICS_PVA_BROADCAST_PORT);
        Network.configureMulticast(udp);
        local_address = (InetSocketAddress) udp.getLocalAddress();
        logger.log(Level.FINE, "Awaiting searches and sending beacons on UDP " + local_address);

        listen_thread = new Thread(() -> listen(udp, receiveBuffer), "UDP-receiver " + local_address);
        listen_thread.setDaemon(true);
        listen_thread.start();
    }
    
    protected boolean handleMessage(final InetSocketAddress from, final byte version,
                                    final byte command, final int payload, final ByteBuffer buffer)
    {
        switch (command)
        {
        case PVAHeader.CMD_SEARCH:
            return handleSearch(from, version, payload, buffer);
        default:
            logger.log(Level.WARNING, "PVA Client " + from + " sent UDP packet with unknown command 0x" + Integer.toHexString(command));
        }
        return true;
    }

    private boolean handleSearch(final InetSocketAddress from, final byte version,
                                 final int payload, final ByteBuffer buffer)
    {
        // Search Sequence ID
        final int seq = buffer.getInt();

        // 0-bit for replyRequired, 7-th bit for "sent as unicast" (1)/"sent as broadcast/multicast" (0)
        /* final byte req = */ buffer.get();

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
        
        // Use address from reply unless it's a generic local address
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
        if (! tcp)
        {
            logger.log(Level.WARNING, "PVA Client " + from + " sent search #" + seq + " for protocol '" + protocol + "', need 'tcp'");
            return false;
        }
        
        // Loop over searched channels
        count = Short.toUnsignedInt(buffer.getShort());
        for (int i=0; i<count; ++i)
        {
            final int cid = buffer.getInt();
            final String name = PVAString.decodeString(buffer);
            logger.log(Level.FINER, () -> "PVA Client " + from + " sent search #" + seq + " for " + name + " [" + cid + "]");
            search_handler.handleSearchRequest(seq, cid, name, client);
        }
        return true;
    }
    
    /** Send a "channel found" reply to a client's search
     *  @param guid This server's GUID
     *  @param seq Client search request sequence number
     *  @param cid Client's channel ID
     *  @param tcp TCP connection where client can connect to this server
     *  @param client Address of client's UDP port
     */
    public void sendSearchReply(final Guid guid, final int seq, final int cid, final ServerTCPListener tcp, final InetSocketAddress client)
    {
        synchronized (sendBuffer)
        {
            sendBuffer.clear();
            PVAHeader.encodeMessageHeader(sendBuffer, PVAHeader.FLAG_SERVER, PVAHeader.CMD_SEARCH_REPLY, 12+4+16+2+4+1+2+4);
            // Server GUID
            guid.encode(sendBuffer);
            
            // Search Sequence ID
            sendBuffer.putInt(seq);
            
            // Server's address and port
            PVAAddress.encode(tcp.response_address, sendBuffer);
            sendBuffer.putShort((short)tcp.response_port);
            
            // Protocol
            PVAString.encodeString("tcp", sendBuffer);
            
            // Found
            PVABool.encodeBoolean(true, sendBuffer);
            
            // int[] cid;
            sendBuffer.putShort((short)1);
            sendBuffer.putInt(cid);
            
            sendBuffer.flip();
            logger.log(Level.FINER, () -> "Sending search reply to " + client + "\n" + Hexdump.toHexdump(sendBuffer));
            try
            {
                udp.send(sendBuffer, client);
            }
            catch (Exception ex)
            {
                logger.log(Level.WARNING, "Cannot send search reply", ex);
            }
        }
    }
    
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