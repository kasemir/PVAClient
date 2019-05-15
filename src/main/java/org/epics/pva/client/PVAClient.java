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
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import org.epics.pva.PVASettings;

/** PVA Client
 *
 *  <p>Maintains pool of PVs, coordinates search requests etc.
 *
 *  @author Kay Kasemir
 */
@SuppressWarnings("nls")
public class PVAClient
{
    private final UDPHandler udp;

    final ChannelSearch search;

    /** Channels by client ID */
    private final ConcurrentHashMap<Integer, ClientChannel> channels_by_id = new ConcurrentHashMap<>();

    /** TCP handlers by server address */
    private final ConcurrentHashMap<InetSocketAddress, TCPHandler> tcp_handlers = new ConcurrentHashMap<>();

    private final AtomicInteger request_ids = new AtomicInteger();

    public PVAClient() throws Exception
    {
        List<InetSocketAddress> search_addresses = Network.parseAddresses(PVASettings.EPICS_PVA_ADDR_LIST.split("\\s+"));
        if (search_addresses.isEmpty())
            search_addresses = Network.getBroadcastAddresses(PVASettings.EPICS_PVA_BROADCAST_PORT);

        udp = new UDPHandler(this::handleBeacon, this::handleSearchResponse);
        if (udp.configureMulticast())
            search_addresses.add(new InetSocketAddress(PVASettings.EPICS_PVA_MULTICAST_GROUP, PVASettings.EPICS_PVA_BROADCAST_PORT));

        search = new ChannelSearch(udp, search_addresses);

        udp.start();
        search.start();
    }

    /** @return New request ID unique to this client and all its connections */
    int allocateRequestID()
    {
        return request_ids.incrementAndGet();
    }

    /** Get or create channel by name
     *
     *  <p>Starts search.
     *
     *  @param channel_name
     *  @param listener {@link ClientChannelListener}
     *  @return {@link ClientChannel}
     */
    public ClientChannel getChannel(final String channel_name, final ClientChannelListener listener)
    {
        final ClientChannel channel = new ClientChannel(this, channel_name, listener);
        channels_by_id.putIfAbsent(channel.getId(), channel);
        search.register(channel, true);
        return channel;
    }

    /** Get channel by client ID
     *  @param cid Channel ID, using client's ID
     *  @return {@link ClientChannel}, may be <code>null</code>
     */
    ClientChannel getChannel(final int cid)
    {
        return channels_by_id.get(cid);
    }

    void forgetChannel(final ClientChannel channel)
    {
        channels_by_id.remove(channel.getId());
    }

    private void handleBeacon(final InetSocketAddress server, final Guid guid, final int changes)
    {
        final TCPHandler tcp = tcp_handlers.get(server);
        if (tcp == null)
            logger.log(Level.FINER, () -> "Beacon from new server " + server);
        else
        {
            if (tcp.checkBeaconChanges(changes))
                logger.log(Level.FINER, () -> "Beacon from " + server + " indicates changes");
            else if (! tcp.getGuid().equals(guid))
                logger.log(Level.FINER, () -> "Beacon from " + server +
                                              " has new GUID " + guid +
                                              " (was " + tcp.getGuid() + ")");
            else
                return;
        }

        search.boost();
    }

    private void handleSearchResponse(final int channel_id, final InetSocketAddress server, final Guid guid)
    {
        final ClientChannel channel = search.unregister(channel_id);
        // Late reply, we already deleted that channel
        if (channel == null)
            return;
        channel.setState(ClientChannelState.FOUND);
        logger.log(Level.FINE, () -> "Reply for " + channel + " from " + server);

        final TCPHandler tcp = tcp_handlers.computeIfAbsent(server, addr ->
        {
            try
            {
                // XXX Try connecting 3 times with 100ms pause?
                return new TCPHandler(this, addr, guid);
            }
            catch (Exception ex)
            {
                logger.log(Level.WARNING, "Cannot connect to TCP " + addr);
            }
            return null;
        });

        channel.createOnServer(tcp);
    }

    /** Close all channels and network connections
     *
     *  <p>Waits a little for all channels to be closed.
     */
    public void close()
    {
        int wait = 50;
        while (! channels_by_id.isEmpty())
        {
            if (--wait > 0)
                try
                {
                    Thread.sleep(100);
                }
                catch (InterruptedException e)
                {
                    // Ignore
                }
            else
            {
                logger.log(Level.WARNING, "PVA Client closed with remaining channels: " + channels_by_id.values());
                break;
            }
        }

        for (TCPHandler handler : tcp_handlers.values())
            handler.close();
    }
}
