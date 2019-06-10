/*******************************************************************************
 * Copyright (c) 2019 Oak Ridge National Laboratory.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 ******************************************************************************/
package org.epics.pva.client;

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
import org.epics.pva.data.PVAAddress;
import org.epics.pva.data.PVABool;
import org.epics.pva.data.PVAFieldDesc;
import org.epics.pva.data.PVAString;
import org.epics.pva.network.Network;
import org.epics.pva.network.UDPHandler;

/** Sends and receives search replies, monitors beacons
 *  @author Kay Kasemir
 */
@SuppressWarnings("nls")
class ClientUDPHandler extends UDPHandler
{
    @FunctionalInterface
    public interface BeaconHandler
    {
        /** @param server Server that sent a beacon
         *  @param guid  Globally unique ID of the server
         *  @param changes Change count, increments & rolls over as server has different channels
         */
        void handleBeacon(InetSocketAddress server, Guid guid, int changes);
    }

    @FunctionalInterface
    public interface SearchResponseHandler
    {
        /** @param channel_id Channel for which server replied
         *  @param server Server that replied to a search request
         *  @param guid  Globally unique ID of the server
         */
        void handleSearchResponse(int channel_id, InetSocketAddress server, Guid guid);
    }

    private final BeaconHandler beacon_handler;
    private final SearchResponseHandler search_response;

    // When multiple UDP sockets bind to the same port,
    // broadcast traffic reaches all of them.
    // Direct traffic is only received by the socket bound last.
    //
    // Create one UDP socket for the search send/response,
    // bound to a free port, so we can receive the search replies.
    private final DatagramChannel udp_search;
    private final InetAddress response_address;
    private final int response_port;
    private final ByteBuffer receive_buffer = ByteBuffer.allocate(PVAConstants.MAX_UDP_PACKET);

    // Listen for UDP beacons on a separate socket, bound to the EPICS_PVA_BROADCAST_PORT,
    // with the understanding that it will only receive broadcasts;
    // since they are often blocked by firewall, may receive nothing, ever.
    private final DatagramChannel udp_beacon;
    private final ByteBuffer beacon_buffer = ByteBuffer.allocate(PVAConstants.MAX_UDP_PACKET);

    private volatile Thread search_thread, beacon_thread;

    public ClientUDPHandler(final BeaconHandler beacon_handler,
                            final SearchResponseHandler search_response) throws Exception
    {
        this.beacon_handler = beacon_handler;
        this.search_response = search_response;

        // Search buffer may send broadcasts and gets re-used
        udp_search = Network.createUDP(true, 0);
        final InetSocketAddress local_address = (InetSocketAddress) udp_search.getLocalAddress();
        response_address = local_address.getAddress();
        response_port = local_address.getPort();

        // Beacon socket only receives, does not send broadcasts
        udp_beacon = Network.createUDP(false, PVASettings.EPICS_PVA_BROADCAST_PORT);

        logger.log(Level.FINE, "Awaiting search replies and beacons on UDP " + response_address +
                               " port " + response_port + " and " + PVASettings.EPICS_PVA_BROADCAST_PORT);
    }

    /** Try to listen to multicast messages
     *  @return Found support for multicast?
     */
    public boolean configureMulticast()
    {
        return Network.configureMulticast(udp_search);
    }

    public InetAddress getResponseAddress()
    {
        return response_address;
    }

    public int getResponsePort()
    {
        return response_port;
    }

    public void send(final ByteBuffer buffer, final InetSocketAddress target) throws Exception
    {
        udp_search.send(buffer, target);
    }

    public void start()
    {
        // Same code for messages from the 'search' and 'beacon' socket,
        // though each socket is likely to see only one type of message.
        search_thread = new Thread(() -> listen(udp_search, receive_buffer), "UDP-receiver " + response_address + ":" + response_port);
        search_thread.setDaemon(true);
        search_thread.start();

        beacon_thread = new Thread(() -> listen(udp_beacon, beacon_buffer), "UDP-receiver " + response_address + ":" + PVASettings.EPICS_PVA_BROADCAST_PORT);
        beacon_thread.setDaemon(true);
        beacon_thread.start();
    }

    @Override
    protected boolean handleMessage(final InetSocketAddress from, final byte version,
                                    final byte command, final int payload, final ByteBuffer buffer)
    {
        switch (command)
        {
        case PVAHeader.CMD_BEACON:
            return handleBeacon(from, version, payload, buffer);
        case PVAHeader.CMD_SEARCH:
            // Ignore search requests, which includes our own
            return true;
        case PVAHeader.CMD_SEARCH_REPLY:
            return handleSearchReply(from, version, payload, buffer);
        default:
            logger.log(Level.WARNING, "PVA Server " + from + " sent UDP packet with unknown command 0x" + Integer.toHexString(command));
        }
        return false;
    }

    private boolean handleBeacon(final InetSocketAddress from, final byte version,
            final int payload, final ByteBuffer buffer)
    {
        if (payload < 12 + 1 + 1 + 2 + 16 + 2 + 4 + 1)
        {
            logger.log(Level.WARNING, "PVA Server " + from + " sent only " + payload + " bytes for beacon");
            return false;
        }

        // Server GUID
        final Guid guid = new Guid(buffer);

        // Flags
        buffer.get();

        final int sequence = Byte.toUnsignedInt(buffer.get());
        final short changes = buffer.getShort();

        // Server's address and port
        final InetAddress addr;
        try
        {
            addr = PVAAddress.decode(buffer);
        }
        catch (Exception ex)
        {
            logger.log(Level.WARNING, "PVA Server " + from + " sent beacon with invalid address");
            return false;
        }
        final int port = Short.toUnsignedInt(buffer.getShort());

        // Use address from reply unless it's a generic local address
        final InetSocketAddress server;
        if (addr.isAnyLocalAddress())
            server = new InetSocketAddress(from.getAddress(), port);
        else
            server = new InetSocketAddress(addr, port);

        final String protocol = PVAString.decodeString(buffer);
        if (! "tcp".equals(protocol))
        {
            logger.log(Level.WARNING, "PVA Server " + from + " sent beacon for protocol '" + protocol + "'");
            return false;
        }

        final byte server_status_desc = buffer.get();
        if (server_status_desc != PVAFieldDesc.NULL_TYPE_CODE)
            logger.log(Level.WARNING, "PVA Server " + from + " sent beacon with server status field description");

        logger.log(Level.FINE, () -> "Received Beacon #" + sequence + " from " + server + " " + guid + ", " + changes + " changes");
        beacon_handler.handleBeacon(server, guid, changes);

        return true;
    }

    private boolean handleSearchReply(final InetSocketAddress from, final byte version,
                                      final int payload, final ByteBuffer buffer)
    {
        // Expect GUID + ID + UP + port + "tcp" + found + count
        if (payload < 12 + 4 + 16 + 2 + 4 + 1 + 2)
        {
            logger.log(Level.WARNING, "PVA Server " + from + " sent only " + payload + " bytes for search reply");
            return false;
        }

        // Server GUID
        final Guid guid = new Guid(buffer);

        // Search Sequence ID
        final int seq = buffer.getInt();

        // Server's address and port
        final InetAddress addr;
        try
        {
            addr = PVAAddress.decode(buffer);
        }
        catch (Exception ex)
        {
            logger.log(Level.WARNING, "PVA Server " + from + " sent search reply with invalid address");
            return false;
        }
        final int port = Short.toUnsignedInt(buffer.getShort());

        // Use address from reply unless it's a generic local address
        final InetSocketAddress server;
        if (addr.isAnyLocalAddress())
            server = new InetSocketAddress(from.getAddress(), port);
        else
            server = new InetSocketAddress(addr, port);

        final String protocol = PVAString.decodeString(buffer);
        if (! "tcp".equals(protocol))
        {
            logger.log(Level.WARNING, "PVA Server " + from + " sent search reply #" + seq + " for protocol '" + protocol + "'");
            return false;
        }

        // Server may reply with list of PVs that it does _not_ have...
        final boolean found = PVABool.decodeBoolean(buffer);
        if (! found)
            return true;

        final int count = Short.toUnsignedInt(buffer.getShort());
        for (int i=0; i<count; ++i)
        {
            final int cid = buffer.getInt();
            search_response.handleSearchResponse(cid, server, guid);
        }

        return true;
    }

    public void close()
    {
        super.close();
        // Close sockets, wait a little for threads to exit
        try
        {
            udp_search.close();
            udp_beacon.close();

            if (search_thread != null)
                search_thread.join(5000);
            if (beacon_thread != null)
                beacon_thread.join(5000);
        }
        catch (Exception ex)
        {
            // Ignore
        }
    }
}
