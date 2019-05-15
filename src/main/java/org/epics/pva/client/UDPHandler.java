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
import java.net.NetworkInterface;
import java.net.StandardProtocolFamily;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.DatagramChannel;
import java.util.logging.Level;

import org.epics.pva.PVAConstants;
import org.epics.pva.PVAHeader;
import org.epics.pva.PVASettings;
import org.epics.pva.data.Hexdump;
import org.epics.pva.data.PVAAddress;
import org.epics.pva.data.PVABool;
import org.epics.pva.data.PVAFieldDesc;
import org.epics.pva.data.PVAString;

/** Receives search replies and beacons
 *  @author Kay Kasemir
 */
@SuppressWarnings("nls")
class UDPHandler
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
    private final DatagramChannel udp;
    private final InetAddress response_address;
    private final int response_port;
    private final ByteBuffer receiveBuffer = ByteBuffer.allocate(PVAConstants.MAX_UDP_PACKET);
    private volatile boolean running = true;

    public UDPHandler(final BeaconHandler beacon_handler,
                      final SearchResponseHandler search_response) throws Exception
    {
        this.beacon_handler = beacon_handler;
        this.search_response = search_response;
        // Current use of multicast addresses works only with INET, not INET6
        udp = DatagramChannel.open(StandardProtocolFamily.INET);
        udp.configureBlocking(true);
        udp.socket().setBroadcast(true);
        udp.socket().setReuseAddress(true);
        udp.bind(new InetSocketAddress(PVASettings.EPICS_PVA_BROADCAST_PORT));

        final InetSocketAddress local_address = (InetSocketAddress) udp.getLocalAddress();
        response_address = local_address.getAddress();
        response_port = local_address.getPort();
    }

    /** Try to listen to multicast messages
     *  @return Found support for multicast?
     */
    public boolean configureMulticast()
    {
        try
        {
            final NetworkInterface loopback = Network.getLoopback();
            if (loopback != null)
            {
                final InetAddress group = InetAddress.getByName(PVASettings.EPICS_PVA_MULTICAST_GROUP);
                final InetSocketAddress local_broadcast = new InetSocketAddress(group, PVASettings.EPICS_PVA_BROADCAST_PORT);
                udp.join(group, loopback);

                logger.log(Level.CONFIG, "Multicast group " + local_broadcast + " using network interface " + loopback.getDisplayName());
                udp.setOption(StandardSocketOptions.IP_MULTICAST_LOOP, true);
                udp.setOption(StandardSocketOptions.IP_MULTICAST_IF, loopback);
            }
        }
        catch (Exception ex)
        {
            logger.log(Level.WARNING, "Cannot configure multicast support", ex);
            return false;
        }
        return true;
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
        udp.send(buffer, target);
    }

    public void start()
    {
        final Thread thread = new Thread(this::run, "UDP-receiver " + response_address + ":" + response_port);
        thread.setDaemon(true);
        thread.start();
    }

    private void run()
    {
        logger.log(Level.FINE, "Awaiting UDP replies on " + response_address +
                               " port " + response_port);
        while (running)
        {
            try
            {
                receiveBuffer.clear();

                // Wait for next UDP packet
                final InetSocketAddress from = (InetSocketAddress) udp.receive(receiveBuffer);

                // XXX Check against list of ignored addresses?

                receiveBuffer.flip();

                logger.log(Level.FINER, () -> "Received UDP from " + from + "\n" + Hexdump.toHexdump(receiveBuffer));
                handleMessages(from, receiveBuffer);
            }
            catch (Exception ex)
            {
                logger.log(Level.WARNING, "UDP receive error", ex);
            }
        }
        logger.log(Level.FINE, "Exiting UDP receive thread for " + response_address +
                               " port " + response_port);
    }


    /** Handle one or more reply messages
     *  @param from
     *  @param buffer
     *  @return Were all messages successfully handled?
     */
    private boolean handleMessages(final InetSocketAddress from, final ByteBuffer buffer)
    {
        while (buffer.remaining() >= PVAHeader.HEADER_SIZE)
        {
            byte b = buffer.get();
            if (b != PVAHeader.PVA_MAGIC)
            {
                logger.log(Level.WARNING, "PVA Server " + from + " sent UDP packet with invalid magic startbyte");
                return false;
            }

            final byte version = receiveBuffer.get();

            final byte flags = receiveBuffer.get();
            if ((flags & PVAHeader.FLAG_BIG_ENDIAN) != 0)
                buffer.order(ByteOrder.BIG_ENDIAN);
            else
                buffer.order(ByteOrder.LITTLE_ENDIAN);

            final byte command = receiveBuffer.get();
            final int payload = receiveBuffer.getInt();
            final int next = buffer.position() + payload;
            if (next > buffer.limit())
            {
                logger.log(Level.WARNING, "PVA Server " + from + " sent UDP packet with expected payload of " +
                        payload + " but only " + buffer.remaining() + " bytes of data");
                return false;
            }

            // Skip control messages
            if ((flags & PVAHeader.FLAG_CONTROL) == 0)
            {
                // If message cannot be decoded,
                // this might indicate overall message corruption
                if (! handleMessage(from, version, command, payload, buffer))
                    return false;
            }

            // Position on next message in case handleMessage read too much or too little
            buffer.position(next);
        }
        return true;
    }

    private boolean handleMessage(final InetSocketAddress from, final byte version,
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
            logger.log(Level.WARNING, "PVA Server " + from + " sent UDP packet with unknown command " + command);
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
}
