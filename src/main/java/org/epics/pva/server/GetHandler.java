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
import org.epics.pva.data.PVAStatus;
import org.epics.pva.data.PVAStructure;
import org.epics.pva.network.CommandHandler;

/** Handle client's GET command
 *  @author Kay Kasemir
 */
@SuppressWarnings("nls")
class GetHandler implements CommandHandler<ServerTCPHandler>
{
    @Override
    public byte getCommand()
    {
        return PVAHeader.CMD_GET;
    }

    @Override
    public void handleCommand(final ServerTCPHandler tcp, final ByteBuffer buffer) throws Exception
    {
        if (buffer.remaining() < 4+4+1)
            throw new Exception("Incomplete GET, only " + buffer.remaining());
        
        // int serverChannelID;
        final int sid = buffer.getInt();
        final ServerPV pv = tcp.getServer().getPV(sid);
        if (pv == null)
            throw new Exception("GET request for unknown PV sid " + sid);
        
        // int requestID
        final int req = buffer.getInt();
        
        // byte sub command = 0x08 for INIT
        final byte subcmd = buffer.get();
        
        if (subcmd == PVAHeader.CMD_SUB_INIT)
        {
            // FieldDesc pvRequestIF
            // PVField pvRequest
            final PVAData requested_type = tcp.getClientTypes().decodeType("", buffer);
            logger.log(Level.FINE, () -> "Recieved GET INIT request for " + pv + " as\n" + requested_type.formatType());
            sendDataInitReply(tcp, PVAHeader.CMD_GET, req, pv, requested_type);
        }
        else
        {
            logger.log(Level.FINE, () -> "Received GET for " + pv);
            sendGetReply(tcp, req, pv);
        }
    }
    
    static void sendDataInitReply(final ServerTCPHandler tcp, final byte command, final int req, final ServerPV pv, final PVAData requested_type)
    {
        // Like QSRV, ignore the requested type and send pv.data
        tcp.submit((version, buffer) ->
        {
            final PVAStructure type = pv.getData();
            logger.log(Level.FINE, () -> "Sending data INIT reply for " + pv + " as\n" + type.formatType());

            PVAHeader.encodeMessageHeader(buffer, PVAHeader.FLAG_SERVER, command, 0);
            final int payload_start = buffer.position();
            // int requestID
            buffer.putInt(req);
            // byte subcommand
            buffer.put(PVAHeader.CMD_SUB_INIT);
            // Status status
            PVAStatus.StatusOK.encode(buffer);
            // FieldDesc pvStructureIF
            final BitSet described = new BitSet();
            type.encodeType(buffer, described);
            final int payload_end = buffer.position();
            buffer.putInt(PVAHeader.HEADER_OFFSET_PAYLOAD_SIZE, payload_end - payload_start);
        });   
    }
    
    private void sendGetReply(final ServerTCPHandler tcp, final int req, final ServerPV pv)
    {
        // Like QSRV, ignore the requested type and send pv.data
        tcp.submit((version, buffer) ->
        {
            final PVAStructure data = pv.getData();
            logger.log(Level.FINE, () -> "Sending GET data for " + pv + ":\n" + data.format());

            PVAHeader.encodeMessageHeader(buffer, PVAHeader.FLAG_SERVER, PVAHeader.CMD_GET, 0);
            final int payload_start = buffer.position();
            // int requestID
            buffer.putInt(req);
            // byte subcommand
            buffer.put((byte)0);
            // Status status
            PVAStatus.StatusOK.encode(buffer);
            // changed: Top-level structure, i.e. all
            final BitSet changed = new BitSet();
            changed.set(0);
            PVABitSet.encodeBitSet(changed, buffer);
            // Data
            data.encode(buffer);
            final int payload_end = buffer.position();
            buffer.putInt(PVAHeader.HEADER_OFFSET_PAYLOAD_SIZE, payload_end - payload_start);
        });   
    }
}