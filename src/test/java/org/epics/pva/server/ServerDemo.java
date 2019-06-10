/*******************************************************************************
 * Copyright (c) 2019 Oak Ridge National Laboratory.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 ******************************************************************************/
package org.epics.pva.server;

import java.util.BitSet;
import java.util.concurrent.TimeUnit;
import java.util.logging.LogManager;

import org.epics.pva.PVASettings;
import org.epics.pva.data.PVADouble;
import org.epics.pva.data.PVAString;
import org.epics.pva.data.PVAStructure;

/** PVA Server Demo
 *  @author Kay Kasemir
 */
@SuppressWarnings("nls")
public class ServerDemo
{
    public static void main(String[] args) throws Exception
    {
        LogManager.getLogManager().readConfiguration(PVASettings.class.getResourceAsStream("/logging.properties"));

        final PVAServer server = new PVAServer();

        final PVAStructure data = new PVAStructure("demo", "demo_t",
                                                   new PVADouble("value", 3.13),
                                                   new PVAString("tag",   "Hello!"));

        final ServerPV pv = server.createPV("demo", data);

        for (int i=0; i<60; ++i)
        {
            TimeUnit.SECONDS.sleep(1);

            // Option 1: Update the data, tell server that it changed.
            // Server figures out what changed.
            // ==> Server must keep a copy of the data.
            //     No locking necessary, but determining what
            //     changed is expensive

            // final PVADouble value = data.get("value");
            // value.set(value.get() + 1);
            // pv.update(data);

            // Throw exception if update doesn't match served data
            // pv.update(new PVAInt("xx", 47));



            // Option 2: Update the data, tell server what changed
            // ==> Both server and this code need to lock the data.
            //     If you forget to lock/unlock, you're stuffed.
            //     If 'changes' bitset is wrong, it's your fault.

            // pv.prepare_update();
            final PVADouble value = data.get("value");
            value.set(value.get() + 1);
            final BitSet changes = new BitSet();
            changes.set(data.getIndex(value));
            // pv.complete_update(changes);


            // Option 3: Don't update the data directly,
            // do that via the ServerPV
            // ==> ServerPV can track the changes and
            //     internally lock as needed.
            //     But awkward API compared to the usual PVA* data calls.
            //     How to for example get the current value if you want to increment?

            // pv.update(1, 10.0*i, 2, "Hello #" + i);
        }

        server.close();
    }
}
