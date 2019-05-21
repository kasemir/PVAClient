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
 *  <p>Maintain PVs, coordinates search requests etc.
 *
 *  <p>Does not pool PVs by name. A caller requesting
 *  channels for the same name more than once will receive
 *  separate channels, with different internal channel IDs,
 *  which will result in separate channels on the PVA server.
 *
 *  <p>This is sufficient for simple clients,
 *  and higher-level client libraries tend to already
 *  pool PVs by name.
 *
 *  @author Kay Kasemir
 */
@SuppressWarnings("nls")
public class PVAClient
{
    /** Default channel listener logs state changes */
    private static final ClientChannelListener DEFAULT_CHANNEL_LISTENER = (ch, state) ->  logger.log(Level.INFO, ch.toString());

    private final UDPHandler udp;

    final ChannelSearch search;

    /** Channels by client ID */
    // For now this is the only list of channels.
    // Usage:
    // * Get channel by client ID: Hashed, fast
    // * Loop over all values, find TCPHandler for channel: Linear search, yuck
    private final ConcurrentHashMap<Integer, PVAChannel> channels_by_id = new ConcurrentHashMap<>();

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

    /** Create channel by name
    *
    *  <p>Starts search.
    *
    *  @param channel_name
    *  @return {@link PVAChannel}
    */
    public PVAChannel getChannel(final String channel_name)
    {
        return getChannel(channel_name, DEFAULT_CHANNEL_LISTENER);
    }

    /** Create channel by name
     *
     *  <p>Starts search.
     *
     *  @param channel_name
     *  @param listener {@link ClientChannelListener}
     *  @return {@link PVAChannel}
     */
    public PVAChannel getChannel(final String channel_name, final ClientChannelListener listener)
    {
        final PVAChannel channel = new PVAChannel(this, channel_name, listener);
        channels_by_id.putIfAbsent(channel.getId(), channel);
        search.register(channel, true);
        return channel;
    }

    /** Get channel by client ID
     *  @param cid Channel ID, using client's ID
     *  @return {@link PVAChannel}, may be <code>null</code>
     */
    PVAChannel getChannel(final int cid)
    {
        return channels_by_id.get(cid);
    }

    /** Forget about a channel
     *
     *  <p>Called when server confirmed that channel has been destroyed.
     *  Removes channel from ID map, so it will no longer be
     *  recognized.
     *
     *  <p>If this was the last channel on a TCP connection,
     *  the {@link TCPHandler} is closed.
     *
     *  @param channel Channel to forget
     */
    void forgetChannel(final PVAChannel channel)
    {
        channels_by_id.remove(channel.getId());

        // Did channel have a connection?
        final TCPHandler tcp = channel.tcp.get();
        if (tcp == null)
            return;

        // Is any other channel using that connection?
        for (PVAChannel other : channels_by_id.values())
            if (other.tcp.get() == tcp)
                return;

        // Close the connection
        tcp_handlers.remove(tcp.getAddress());
        tcp.close(false);
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
        final PVAChannel channel = search.unregister(channel_id);
        // Late reply, we already deleted that channel
        if (channel == null)
            return;
        channel.setState(ClientChannelState.FOUND);
        logger.log(Level.FINE, () -> "Reply for " + channel + " from " + server);

        final TCPHandler tcp = tcp_handlers.computeIfAbsent(server, addr ->
        {
            try
            {
                return new TCPHandler(this, addr, guid);
            }
            catch (Exception ex)
            {
                logger.log(Level.WARNING, "Cannot connect to TCP " + addr);
            }
            return null;
        });

        channel.registerWithServer(tcp);
    }

    /** Called by {@link TCPHandler} when connection is lost
     *  @param tcp TCP handler that just lost connection and needs to be closed
     */
    void handleConnectionLost(final TCPHandler tcp)
    {
        // Forget this connection
        final TCPHandler removed = tcp_handlers.remove(tcp.getAddress());
        if (removed != tcp)
            logger.log(Level.WARNING, "Closed unknown " + tcp, new Exception("Call stack"));

        // Reset all channels that used the connection
        for (PVAChannel channel : channels_by_id.values())
        {
            try
            {
                if (channel.getTCP() == tcp)
                {
                    channel.resetConnection();
                    // Search again soon
                    search.register(channel, false);
                }
            }
            catch (Exception ex)
            {
                logger.log(Level.WARNING, "Error resetting channel " + channel, ex);
            }
        }

        tcp.close(false);
    }

    /** Close all channels and network connections
     *
     *  <p>Waits a little for all channels to be closed.
     */
    public void close()
    {
        // Stop searching for missing channels
        search.close();

        // Assume caller has closed channels, wait for that
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
                // Warn and move on
                logger.log(Level.WARNING, "PVA Client closed with remaining channels: " + channels_by_id.values());
                break;
            }
        }

        // Stop TCP and UDP threads
        for (TCPHandler handler : tcp_handlers.values())
            handler.close(true);

        udp.close();
    }
}
