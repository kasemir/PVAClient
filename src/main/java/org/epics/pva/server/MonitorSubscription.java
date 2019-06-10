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
import org.epics.pva.data.PVAStructure;

/** One client's subscription to a PV
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

    private final ServerPV pv;

    private final ServerTCPHandler tcp;

    private final PVAStructure data;

    private volatile BitSet changes;

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

        // TODO Encode just what changed
        final BitSet all = new BitSet();
        all.set(0);
        PVABitSet.encodeBitSet(all, buffer);

        pv.getData().encode(buffer);


        PVABitSet.encodeBitSet(overrun, buffer);

        final int payload_end = buffer.position();
        buffer.putInt(PVAHeader.HEADER_OFFSET_PAYLOAD_SIZE, payload_end - payload_start);
    }
}
