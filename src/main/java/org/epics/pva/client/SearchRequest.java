/*******************************************************************************
 * Copyright (c) 2019 Oak Ridge National Laboratory.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 ******************************************************************************/
package org.epics.pva.client;

import java.net.InetAddress;
import java.nio.ByteBuffer;

import org.epics.pva.PVAHeader;
import org.epics.pva.data.PVAAddress;
import org.epics.pva.data.PVAString;

/** Helper for search requests
 *  @author Kay Kasemir
 */
@SuppressWarnings("nls")
public class SearchRequest
{
    public static void encode(final boolean unicast, final int seq, final int cid, final String name, final InetAddress address, final short port, final ByteBuffer buffer)
    {
        // Create with zero payload size, to be patched later
        PVAHeader.encodeMessageHeader(buffer, PVAHeader.FLAG_NONE, PVAHeader.CMD_SEARCH, 0);

        final int payload_start = buffer.position();

        // SEARCH message sequence
        buffer.putInt(seq);

        // If a host has multiple listeners on the UDP search port,
        // only the one started last will see the unicast.
        // Mark search message as unicast so that receiver will forward
        // it via local broadcast to other local listeners.
        // 0-bit for replyRequired, 7-th bit for "sent as unicast" (1)/"sent as broadcast/multicast" (0)
        buffer.put((byte) ((unicast ? 0x80 : 0x00) | (cid < 0 ? 0x01 : 0x00)));

        // reserved
        buffer.put((byte) 0);
        buffer.put((byte) 0);
        buffer.put((byte) 0);

        // responseAddress, IPv6 address in case of IP based transport, UDP
        PVAAddress.encode(address, buffer);

        // responsePort
        buffer.putShort(port);

        // string[] protocols with count as byte since < 254
        // struct { int searchInstanceID, string channelName } channels[] with count as short?!
        if (cid < 0)
        {
            buffer.put((byte)0);
            buffer.putShort((short)0);
        }
        else
        {
            buffer.put((byte)1);
            PVAString.encodeString("tcp", buffer);

            buffer.putShort((short)1);
            buffer.putInt(cid);
            PVAString.encodeString(name, buffer);
        }

        // Update payload size
        buffer.putInt(PVAHeader.HEADER_OFFSET_PAYLOAD_SIZE, buffer.position() - payload_start);
    }
}
