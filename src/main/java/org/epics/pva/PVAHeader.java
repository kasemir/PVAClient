/*******************************************************************************
 * Copyright (c) 2019 Oak Ridge National Laboratory.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 ******************************************************************************/
package org.epics.pva;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** PVA Message Header Command Codes */
public class PVAHeader
{
    /** PVA protocol magic */
    public static final byte PVA_MAGIC = (byte)0xCA;

    /** PVA protocol revision (implemented by this library) */
    public static final byte PVA_PROTOCOL_REVISION = 2;

    /** Oldest PVA protocol revision handled by this library */
    public static final byte REQUIRED_PVA_PROTOCOL_REVISION = 1;

    /** Application message, single, by client, little endian */
    public static final byte FLAG_NONE       = 0;

    /** Control message? Else: Application */
    public static final byte FLAG_CONTROL    = 1;

    /** Segmented message? Else: Single */
    public static final byte FLAG_FIRST      = 1 << 4;
    public static final byte FLAG_LAST       = 2 << 4;
    public static final byte FLAG_MIDDLE     = 3 << 4;

    /** Server message? Else: Client */
    public static final byte FLAG_SERVER     = 1 << 6;

    /** Big endian encoding? Else: Little endian */
    public static final byte FLAG_BIG_ENDIAN = (byte) (1 << 7);

    /** Application command: Beacon */
    public static final byte CMD_BEACON = 0x00;

    /** Application command: Connection validation */
    public static final byte CMD_VALIDATION = 0x01;

    /** Application command: Echo */
    public static final byte CMD_ECHO = 0x02;

    /** Application command: Search */
    public static final byte CMD_SEARCH = 0x03;

    /** Application command: Reply to search */
    public static final byte CMD_SEARCH_REPLY = 0x04;

    /** Application command: Create Channel */
    public static final byte CMD_CREATE_CHANNEL = 0x07;

    /** Application command: Destroy Channel */
    public static final byte CMD_DESTROY_CHANNEL = 0x08;

    /** Application command: Connection was validated */
    public static final byte CMD_VALIDATED = 0x09;

    /** Application command: Get data */
    public static final byte CMD_GET = 0x0A;

    /** Application command: Get data */
    public static final byte CMD_PUT = 0x0B;

    /** Application command: Get data */
    public static final byte CMD_MONITOR = 0x0D;

    /** Application command: Get type info */
    public static final byte CMD_GET_TYPE = 0x11;


    /** Control message command to set byte order */
    public static final byte CTRL_SET_BYTE_ORDER = 2;

    /** Size of common PVA message header */
    public static final int HEADER_SIZE = 8;

    /** Offset from start of common PVA message header to int payload_size */
    public static final int HEADER_OFFSET_PAYLOAD_SIZE = 4;



    /** Encode common PVA message header
     *  @param buffer Buffer into which to encode
     *  @param flags  Combination of FLAG_
     *  @param command Command
     *  @param payload_size Size of payload that follows
     */
    public static void encodeMessageHeader(final ByteBuffer buffer, byte flags, final byte command, final int payload_size)
    {
        if (buffer.order() == ByteOrder.BIG_ENDIAN)
            flags |= FLAG_BIG_ENDIAN;
        else
            flags &= ~FLAG_BIG_ENDIAN;
        buffer.clear();
        buffer.put(PVA_MAGIC);
        buffer.put(PVA_PROTOCOL_REVISION);
        buffer.put(flags);
        buffer.put(command);
        buffer.putInt(payload_size);
    }
}
