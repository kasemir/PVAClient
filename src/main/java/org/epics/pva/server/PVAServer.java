/*******************************************************************************
 * Copyright (c) 2019 Oak Ridge National Laboratory.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 ******************************************************************************/
package org.epics.pva.server;

import static org.epics.pva.PVASettings.logger;

import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.logging.Level;

import org.epics.pva.Guid;
import org.epics.pva.data.PVAStructure;

/** PVA Server
 *  @author Kay Kasemir
 */
@SuppressWarnings("nls")
public class PVAServer
{
    private static ForkJoinPool POOL = ForkJoinPool.commonPool();
    
    private final Guid guid = new Guid();

    private final ConcurrentHashMap<String, ServerPV> pv_by_name = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, ServerPV> pv_by_sid = new ConcurrentHashMap<>();
    
    private final ServerTCPListener tcp;
    private final ServerUDPHandler udp;

    public PVAServer() throws Exception
    {
        tcp = new ServerTCPListener(this);
        udp = new ServerUDPHandler(this::handleSearchRequest);
    }

    /** Create a PV which will be served to clients
     * 
     *  @param name PV Name
     *  @param data Type definition and initial value
     *  @return {@link ServerPV}
     */
    public ServerPV createPV(final String name, final PVAStructure data)
    {
        final ServerPV pv = new ServerPV(name, data);
        pv_by_name.put(name, pv);
        pv_by_sid.put(pv.getSID(), pv);
        return pv;
    }
   
    /** Get existing PV 
     *  @param name PV name
     *  @return PV or <code>null</code> when unknown
     */
    ServerPV getPV(final String name)
    {
        return pv_by_name.get(name);
    }
   
    ServerPV getPV(final int sid)
    {
        return pv_by_sid.get(sid);
    }

    private void handleSearchRequest(final int seq, final int cid, final String name, final InetSocketAddress addr)
    {
        // Known channel?
        final ServerPV pv = getPV(name);
        if (pv != null)
        {
            // Reply with TCP connection info
            logger.log(Level.FINE, "Received Search for known PV " + pv);
            POOL.execute(() -> udp.sendSearchReply(guid, seq, cid, tcp, addr));
        }
    }
    
    public void close()
    {
        udp.close();
        tcp.close();
    }
}
