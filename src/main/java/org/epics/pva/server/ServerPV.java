/*******************************************************************************
 * Copyright (c) 2019 Oak Ridge National Laboratory.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 ******************************************************************************/
package org.epics.pva.server;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentHashMap.KeySetView;
import java.util.concurrent.atomic.AtomicInteger;

import org.epics.pva.data.PVAStructure;

/** Maintains all the server's PVs
 *  @author Kay Kasemir
 */
@SuppressWarnings("nls")
public class ServerPV
{
    private static final AtomicInteger IDs = new AtomicInteger();

    private final String name;
    private final int sid;

    /** Current value
     *
     *  <p>Updates need to SYNC on data
     */
    private final PVAStructure data;

    /** All the 'monitor' subscriptions to this PV */
    private final KeySetView<MonitorSubscription, Boolean> subscriptions = ConcurrentHashMap.newKeySet();

    ServerPV(final String name, final PVAStructure data)
    {
        this.name = name;
        this.sid = IDs.incrementAndGet();
        this.data = data.cloneData();
    }

    public int getSID()
    {
        return sid;
    }

    /** @param subscription Subscription that needs to receive value updates */
    void register(final MonitorSubscription subscription)
    {
        subscriptions.add(subscription);
    }

    void unregister(int req, ServerTCPHandler tcp)
    {
        for (MonitorSubscription subscription : subscriptions)
            if (subscription.isFor(tcp, req))
            {
                subscriptions.remove(subscription);
                break;
            }
    }

    /** Update the PV's data
     *
     *  <p>The new data is used to update the current
     *  value of the PV.
     *  Its type must match the initial value used when
     *  creating the PV on the server.
     *
     *  @param new_data New data to serve
     *  @throws Exception on error
     */
    public void update(final PVAStructure new_data) throws Exception
    {
        // Update data
        synchronized (data)
        {
            data.update(new_data);
        }
        // Update subscriptions
        for (MonitorSubscription subscription : subscriptions)
            subscription.update(new_data);
    }

    /** Get current value
     *  @return PV's current data
     */
    PVAStructure getData()
    {
        synchronized (data)
        {
            return data.cloneData();
        }
    }

    @Override
    public String toString()
    {
        return name + " [SID " + sid + "]";
    }
}
