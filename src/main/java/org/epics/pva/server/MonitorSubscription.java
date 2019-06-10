/*******************************************************************************
 * Copyright (c) 2019 Oak Ridge National Laboratory.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 ******************************************************************************/
package org.epics.pva.server;

import static org.epics.pva.PVASettings.logger;

import java.nio.ByteBuffer;
import java.util.BitSet;
import java.util.logging.Level;

import org.epics.pva.PVAHeader;
import org.epics.pva.data.PVABitSet;
import org.epics.pva.data.PVAData;
import org.epics.pva.data.PVAStructure;

/** One client's subscription to "monitor" a PV
 *
 *  <p>Maintains the most recent value sent to client,
 *  sends changes to that client as the value is updated.
 *
 *  @author Kay Kasemir
 */
@SuppressWarnings("nls")
class MonitorSubscription
{
    /** ID of monitor request sent by client */
    private final int req;

    /** The PV */
    private final ServerPV pv;

    /** TCP connection to client */
    private final ServerTCPHandler tcp;

    // Clients subscribe at different times,
    // and their TCP connection might be able to handle updates
    // at different rates, so each subscription maintains
    // the per-client state of the data, changes and overruns.

    /** Most recent value, to be sent to clients */
    private final PVAStructure data;

    /** Most recent changes, yet to be sent to clients */
    private volatile BitSet changes;

    /** Overruns, u.e. updates received between successful transmissions to client */
    private final BitSet overrun = new BitSet();

    MonitorSubscription(final int req, final ServerPV pv, final ServerTCPHandler tcp)
    {
        this.req = req;
        this.pv = pv;
        this.tcp = tcp;
        data = pv.getData();

        // Initial update: Send all the data
        changes = new BitSet();
        changes.set(0);
        tcp.submit(this::encodeMonitor);
    }

    void update(final PVAStructure new_data) throws Exception
    {
        // TODO Accumulate overrun

        changes = data.update(new_data);

        // TODO Only submit when there's not already one pending, waiting to be sent out
        tcp.submit(this::encodeMonitor);
    }

    private void encodeMonitor(final byte version, final ByteBuffer buffer) throws Exception
    {
        logger.log(Level.FINE, () -> "Sending MONITOR value for " + pv);

        PVAHeader.encodeMessageHeader(buffer, PVAHeader.FLAG_SERVER, PVAHeader.CMD_MONITOR, 0);
        final int payload_start = buffer.position();

        buffer.putInt(req);
        // Subcommand 0 = value update
        buffer.put((byte)0);

        // Encode what changed
        PVABitSet.encodeBitSet(changes, buffer);
        // Encode the changed data
        for (int index = changes.nextSetBit(0);
             index >= 0;
             index = changes.nextSetBit(index + 1))
        {
            // final version of index to allow use in logging lambdas
            final int i = index;
            final PVAData element = data.get(i);
            logger.log(Level.FINER, () -> "Encode data for indexed element " + i + ": " + element);
            element.encode(buffer);

            // Javadoc for nextSetBit() suggests checking for MAX_VALUE
            // to avoid index + 1 overflow and thus starting over with first bit
            if (i == Integer.MAX_VALUE)
                break;
        }
        changes.clear();

        PVABitSet.encodeBitSet(overrun, buffer);

        final int payload_end = buffer.position();
        buffer.putInt(PVAHeader.HEADER_OFFSET_PAYLOAD_SIZE, payload_end - payload_start);
    }
}
