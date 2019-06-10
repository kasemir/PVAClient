/*******************************************************************************
 * Copyright (c) 2019 Oak Ridge National Laboratory.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 ******************************************************************************/
package org.epics.pva.server;

import static org.epics.pva.PVASettings.logger;

import java.nio.channels.SocketChannel;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import org.epics.pva.data.PVAData;
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
    private volatile PVAStructure data;
    
    ServerPV(final String name, final PVAStructure data)
    {
        this.name = name;
        this.sid = IDs.incrementAndGet();
        this.data = data;
    }
    
    public int getSID()
    {
        return sid;
    }
    
    // TODO Locking
    // Data is accessed when clients request it,
    // and when code updates it in the server
    public PVAStructure getData()
    {
        return data;
    }
    
    @Override
    public String toString()
    {
        return name + " [SID " + sid + "]";
    }
}
