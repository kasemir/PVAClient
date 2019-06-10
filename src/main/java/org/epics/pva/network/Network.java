/*******************************************************************************
 * Copyright (c) 2019 Oak Ridge National Laboratory.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 ******************************************************************************/
package org.epics.pva.network;

import static org.epics.pva.PVASettings.logger;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.StandardProtocolFamily;
import java.net.StandardSocketOptions;
import java.nio.channels.DatagramChannel;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.logging.Level;

import org.epics.pva.PVASettings;

@SuppressWarnings("nls")
public class Network
{
    /** Obtain broadcast addresses for all local network interfaces
     *  @param port UDP port on which to broadcast
     *  @return List of broadcast addresses
     */
    public static List<InetSocketAddress> getBroadcastAddresses(final int port)
    {
        final List<InetSocketAddress> addresses = new ArrayList<>();

        try
        {
            final Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements())
            {
                final NetworkInterface iface = ifs.nextElement();
                try
                {
                    // Only consider operational interfaces
                    if (!iface.isUp())
                        continue;
                    for (InterfaceAddress addr : iface.getInterfaceAddresses())
                        if (addr.getBroadcast() != null)
                        {
                            final InetSocketAddress bcast = new InetSocketAddress(addr.getBroadcast(), port);
                            if (! addresses.contains(bcast))
                                addresses.add(bcast);
                        }
                }
                catch (Throwable ex)
                {
                    logger.log(Level.WARNING, "Cannot inspect network interface " + iface, ex);
                }
            }
        }
        catch (Exception ex)
        {
            logger.log(Level.WARNING, "Cannot list network interfaces", ex);
        }

        if (addresses.isEmpty())
            addresses.add(new InetSocketAddress("255.255.255.255", port));

        return addresses;
    }


    public static List<InetSocketAddress> parseAddresses(final String... search_addresses)
    {
        final List<InetSocketAddress> addresses = new ArrayList<>();
        for (String search : search_addresses)
        {
            final int sep = search.lastIndexOf(':');
            if (sep > 0)
            {
                final String hostname = search.substring(0, sep);
                final int port = Integer.parseInt(search.substring(sep+1));
                addresses.add(new InetSocketAddress(hostname, port));
            }
            else
                addresses.add(new InetSocketAddress(search, PVASettings.EPICS_PVA_BROADCAST_PORT));
        }
        return addresses;
    }

    /** @return Loopback network interface or <code>null</code> */
    public static NetworkInterface getLoopback()
    {
        try
        {
            final Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
            while (nets.hasMoreElements())
            {
                final NetworkInterface iface = nets.nextElement();
                try
                {
                    if (iface.isUp() && iface.isLoopback())
                        return iface;
                }
                catch (Throwable ex)
                {
                    logger.log(Level.WARNING, "Cannot inspect network interface " + iface, ex);
                }
            }
        }
        catch (Exception ex)
        {
            logger.log(Level.WARNING, "Cannot list network interfaces", ex);
        }

        return null;
    }
    
    /** Create UDP channel
     * 
     *  @param broadcast Support broadcast?
     *  @param port Port to use or 0 to auto-assign
     *  @return
     *  @throws Exception
     */
    public static DatagramChannel createUDP(boolean broadcast, int port) throws Exception
    {
        // Current use of multicast addresses works only with INET, not INET6
        final DatagramChannel udp = DatagramChannel.open(StandardProtocolFamily.INET);
        udp.configureBlocking(true);
        if (broadcast)
            udp.socket().setBroadcast(true);
        udp.socket().setReuseAddress(true);
        udp.bind(new InetSocketAddress(port));
        return udp;
    }
    
    /** Try to listen to multicast messages
     *  @return Found support for multicast?
     */
    public static boolean configureMulticast(final DatagramChannel udp)
    {
        try
        {
            final NetworkInterface loopback = getLoopback();
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

}
