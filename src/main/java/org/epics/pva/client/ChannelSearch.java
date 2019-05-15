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
import java.nio.ByteOrder;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import org.epics.pva.PVAConstants;
import org.epics.pva.PVAHeader;
import org.epics.pva.data.Hexdump;
import org.epics.pva.data.PVAAddress;
import org.epics.pva.data.PVAString;

/** Handler for search requests
 *
 *  <p>Maintains thread that periodically issues search requests
 *  for registered channels.
 *
 *  <p>Details of search timing are based on
 *  https://github.com/epics-base/epicsCoreJava/blob/master/pvAccessJava/src/org/epics/pvaccess/client/impl/remote/search/SimpleChannelSearchManagerImpl.java
 *
 *  @author Kay Kasemir
 */
@SuppressWarnings("nls")
public class ChannelSearch
{
    /** Channel that's being searched */
    private class SearchedChannel
    {
        final AtomicInteger search_counter;
        final ClientChannel channel;

        SearchedChannel(final ClientChannel channel, final boolean now)
        {
            // Counter of 0 means the next regular search will increment
            // to 1 (no search), then 2 (power of two -> search).
            // So it'll "soon" perform a regular search.
            this.search_counter = new AtomicInteger(now ? 0 : MAX_SEARCH_RESET);
            this.channel = channel;
            // Not starting an _immediate_ search in here because
            // this needs to be added to searched_channels first.
            // Otherwise run risk of getting reply without being able
            // to handle it
        }
    }

    /** Search request sequence number */
    private static final AtomicInteger search_sequence = new AtomicInteger();

    private final UDPHandler udp;

    /** Basic search period */
    private static final int SEARCH_PERIOD_MS = 225;

    /** Search period jitter to avoid multiple clients all searching at the same period */
    private static final int SEARCH_JITTER_MS = 25;

    /** Exponential search intervals
     *
     *  <p>Search counter for a channel is incremented each SEARCH_PERIOD_MS.
     *  When counter is a power of 2, search request is sent.
     *  Counter starts at 1, and first search period increments to 2:
     *     0 ms increments to 2 -> Search!
     *   225 ms increments to 3 -> No search
     *   450 ms increments to 4 -> Search (~0.5 sec after last)
     *   675 ms increments to 5 -> No search
     *   900 ms increments to 6 -> No search
     *  1125 ms increments to 7 -> No search
     *  1350 ms increments to 8 -> Search (~ 1 sec after last)
     *  ...
     *
     *  <p>So the time between searches is roughly 0.5 seconds,
     *  1 second, 2, 4, 8, 15, 30 seconds.
     *
     *  <p>Once the search count reaches 256, it's reset to 129.
     *  This means it then takes 128 periods to again reach 256
     *  for the next search, so searches end up being issued
     *  roughly every 128*0.225 = 30 seconds.
     */
    private static final int BOOST_SEARCH_COUNT = 1,
                             MAX_SEARCH_COUNT = 256,
                             MAX_SEARCH_RESET = 129;

    /** Map of searched channels by channel ID */
    private ConcurrentHashMap<Integer, SearchedChannel> searched_channels = new ConcurrentHashMap<>();

    /** Timer used to periodically check channels and issue search requests */
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(run ->
    {
        final Thread thread = new Thread(run, "ChannelSearch");
        thread.setDaemon(true);
        return thread;
    });

    /** Buffer for assembling search messages */
    private final ByteBuffer send_buffer = ByteBuffer.allocate(PVAConstants.MAX_UDP_UNFRAGMENTED_SEND);

    /** Address list to which search requests are sent */
    private final List<InetSocketAddress> search_addresses;

    public ChannelSearch(final UDPHandler udp, final List<InetSocketAddress> search_addresses) throws Exception
    {
        this.udp = udp;
        this.search_addresses = search_addresses;
        for (InetSocketAddress addr : search_addresses)
            logger.log(Level.CONFIG, "Sending searches to " + addr.getAddress() + " port " + addr.getPort());
    }

    public void start()
    {
        // +-jitter to prevent multiple clients from sending concurrent search requests
        final long period = SEARCH_PERIOD_MS + (new Random().nextInt(2*SEARCH_JITTER_MS+1) - SEARCH_JITTER_MS);

        logger.log(Level.FINE,
                   String.format("Search intervals: %.2f s, %.2f s, %.2f s, ..., %.2f s",
                                 2*period/1000.0,
                                 4*period/1000.0,
                                 8*period/1000.0,
                                 128*period/1000.0));
        timer.scheduleAtFixedRate(this::runSearches, period, period, TimeUnit.MILLISECONDS);
    }

    /** @param channel Channel that should be searched
     *  @param now Start searching as soon as possible, or delay?
     */
    public void register(final ClientChannel channel, final boolean now)
    {
        logger.log(Level.FINE, () -> "Register search for " + channel.getName() + " " + channel.getId());
        channel.setState(ClientChannelState.SEARCHING);
        searched_channels.computeIfAbsent(channel.getId(), id -> new SearchedChannel(channel, now));
        // Issue immediate search request?
        if (now)
            search(channel);
    }

    public ClientChannel unregister(final int channel_id)
    {
        final SearchedChannel searched = searched_channels.remove(channel_id);
        if (searched != null)
        {
            logger.log(Level.FINE, () -> "Unregister search for " + searched.channel.getName() + " " + channel_id);
            return searched.channel;
        }
        logger.log(Level.FINER, "Unknown search channel ID " + channel_id);
        return null;
    }

    /** Boost search for missing channels
     *
     *  <p>Resets their search counter so they're searched "real soon".
     */
    public void boost()
    {
        for (SearchedChannel searched : searched_channels.values())
        {
            logger.log(Level.WARNING, () -> "Restart search for " + searched.channel.getName());
            searched.search_counter.set(BOOST_SEARCH_COUNT);
            // Not sending search right now:
            //   search(channel);
            // Instead, scheduling it to be searched again real soon for a few times.
            // We tend to receive multiple copies of the same beacon via various network
            // interfaces, so by scheduling a search real soon it happens once,
            // not for every duplicate of the same beacon
        }
    }

    private static boolean isPowerOfTwo(final int x)
    {
        return x > 0  &&  (x & (x - 1)) == 0;
    }

    /** Invoked by timer: Check searched channels for the next one to handle */
    private void runSearches()
    {
        for (SearchedChannel searched : searched_channels.values())
        {
            final int counter = searched.search_counter.updateAndGet(val -> val >= MAX_SEARCH_COUNT ? MAX_SEARCH_RESET : val+1);
            if (isPowerOfTwo(counter))
            {
                logger.log(Level.FINE, () -> "Searching... " + searched.channel);
                search(searched.channel);
            }
        }
    }

    private void search(final ClientChannel channel)
    {
        // Search is invoked for new SearchedChannel(channel, now)
        // as well as by regular, timed search.
        // Lock the send buffer to avoid concurrent use.
        synchronized (send_buffer)
        {
            // Create with zero payload size, to be patched later
            final byte flags = send_buffer.order() == ByteOrder.BIG_ENDIAN ? PVAHeader.FLAG_BIG_ENDIAN : PVAHeader.FLAG_NONE;
            PVAHeader.encodeMessageHeader(send_buffer, flags, PVAHeader.CMD_SEARCH, 0);

            final int payload_start = send_buffer.position();

            // SEARCH message sequence
            final int seq = search_sequence.incrementAndGet();
            send_buffer.putInt(seq);

            // 0-bit for replyRequired, 7-th bit for "sent as unicast" (1)/"sent as broadcast/multicast" (0)
            send_buffer.put((byte) 0);

            // reserved
            send_buffer.put((byte) 0);
            send_buffer.put((byte) 0);
            send_buffer.put((byte) 0);

            // responseAddress, IPv6 address in case of IP based transport, UDP
            PVAAddress.encode(udp.getResponseAddress(), send_buffer);

            // responsePort
            send_buffer.putShort((short)udp.getResponsePort());

            // string[] protocols with count as byte since < 254
            send_buffer.put((byte)1);
            PVAString.encodeString("tcp", send_buffer);

            // struct { int searchInstanceID, string channelName } channels[] with count as short?!
            send_buffer.putShort((short)1);
            send_buffer.putInt(channel.getId());
            PVAString.encodeString(channel.getName(), send_buffer);

            // Update payload size
            final int payload_size = send_buffer.position() - payload_start;
            send_buffer.putInt(PVAHeader.HEADER_OFFSET_PAYLOAD_SIZE, payload_size);

            send_buffer.flip();
            logger.log(Level.FINE, "Search Request #" + seq + " for " + channel);
            for (InetSocketAddress addr : search_addresses)
            {
                try
                {
                    logger.log(Level.FINER, () -> "Sending search To " + addr + "\n" + Hexdump.toHexdump(send_buffer));
                    udp.send(send_buffer, addr);
                }
                catch (Exception ex)
                {
                    logger.log(Level.WARNING, "Failed to send search request to " + addr, ex);
                }
                send_buffer.rewind();
            }
        }
    }
}
