/*******************************************************************************
 * Copyright (c) 2019 Oak Ridge National Laboratory.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 ******************************************************************************/
package org.epics.pva.server;

import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.logging.LogManager;

import org.epics.pva.PVASettings;
import org.epics.pva.data.PVADouble;
import org.epics.pva.data.PVAInt;
import org.epics.pva.data.PVAString;
import org.epics.pva.data.PVAStructure;
import org.epics.pva.data.nt.PVATimeStamp;

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

        final PVATimeStamp time = new PVATimeStamp();
        final PVAStructure data = new PVAStructure("demo", "demo_t",
                                                   new PVADouble("value", 3.13),
                                                   new PVAString("tag",   "Hello!"),
                                                   time);
        time.set(Instant.now());
        final ServerPV pv = server.createPV("demo", data);
        final ServerPV pv2 = server.createPV("demo2", data);
        for (int i=0; i<30000; ++i)
        {
            TimeUnit.SECONDS.sleep(1);

            // Update the data, tell server that it changed.
            // Server figures out what changed.
            //
            // This implies that the server keeps a thread-safe copy of the data,
            // and determines which elements of the data have changed.
            final PVADouble value = data.get("value");
            value.set(value.get() + 1);
            time.set(Instant.now());

            pv.update(data);
            pv2.update(data);

            // Alternative 1:
            // Client locks and unlocks the data,
            // and client informs server what has changed.
            //
            // Potentially more efficient, but complicates calling code.

            // pv.prepare_update();
            // final PVADouble value = data.get("value");
            // value.set(value.get() + 1);
            // final BitSet changes = new BitSet();
            // changes.set(data.getIndex(value));
            // pv.complete_update(changes);


            // Alternative 1:
            // Don't update the data directly, do that via the ServerPV
            //
            // Client code more concise, server library can handle
            // locking and quite easily determine changes,
            // but makes for an awkward API that uses the element indices
            // and has no type information

            // pv.update(1, 10.0*i, 2, "Hello #" + i);
        }

        // Throw exception if update doesn't match served data
        try
        {
            pv.update(new PVAStructure("xx", "xxx", new PVAInt("xx", 47)));
        }
        catch (Exception ex)
        {
            // Expected
            if (! ex.getMessage().toLowerCase().contains("incompatibl"))
                throw ex;
        }

        server.close();
    }
}
