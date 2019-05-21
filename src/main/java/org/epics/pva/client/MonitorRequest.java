/*******************************************************************************
 * Copyright (c) 2019 Oak Ridge National Laboratory.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 ******************************************************************************/
package org.epics.pva.client;

import static org.epics.pva.PVASettings.logger;

import java.nio.ByteBuffer;
import java.util.BitSet;
import java.util.logging.Level;

import org.epics.pva.PVAHeader;
import org.epics.pva.data.PVABitSet;
import org.epics.pva.data.PVAData;
import org.epics.pva.data.PVAStatus;
import org.epics.pva.data.PVAStructure;

@SuppressWarnings("nls")
class MonitorRequest implements RequestEncoder, ResponseHandler
{
    /** Sub command to (re)start getting values */
    private static final byte START = 0x44;

    /** Sub command to stop/pause */
    private static final byte STOP = 0x04;

    private final PVAChannel channel;

    private final String request;

    private final MonitorListener listener;

    private final int request_id;

    /** Next request to send, cycling from INIT to START.
     *  Cancel() then sets it to DESTROY.
     */
    private volatile byte state = GetRequest.INIT;

    private volatile PVAStructure data;

    public MonitorRequest(final PVAChannel channel, final String request, final MonitorListener listener) throws Exception
    {
        this.channel = channel;
        this.request = request;
        this.listener = listener;
        this.request_id = channel.getClient().allocateRequestID();
        channel.getTCP().submit(this, this);
    }

    @Override
    public int getRequestID()
    {
        return request_id;
    }

    @Override
    public void encodeRequest(final byte version, final ByteBuffer buffer) throws Exception
    {
        if (state == GetRequest.INIT)
        {
            logger.log(Level.FINE, () -> "Sending monitor INIT request #" + request_id + " for " + channel + " '" + request + "'");

            // Guess size based on empty field request (6)
            final int size_offset = buffer.position() + PVAHeader.HEADER_OFFSET_PAYLOAD_SIZE;
            PVAHeader.encodeMessageHeader(buffer, PVAHeader.FLAG_NONE, PVAHeader.CMD_MONITOR, 4+4+1+6);
            buffer.putInt(channel.sid);
            buffer.putInt(request_id);
            buffer.put(GetRequest.INIT);

            final FieldRequest field_request = new FieldRequest(request);
            final int request_size = field_request.encodeType(buffer);
            buffer.putInt(size_offset, 4+4+1+request_size);
        }
        else
        {
            if (state == START)
                logger.log(Level.FINE, () -> "Sending monitor START request #" + request_id + " for " + channel);
            else if (state == STOP)
                logger.log(Level.FINE, () -> "Sending monitor STOP request #" + request_id + " for " + channel);
            else if (state == GetRequest.DESTROY)
                logger.log(Level.FINE, () -> "Sending monitor DESTROY request #" + request_id + " for " + channel);
            else
                throw new Exception("Cannot handle monitor state " + state);
            PVAHeader.encodeMessageHeader(buffer, PVAHeader.FLAG_NONE, PVAHeader.CMD_MONITOR, 4+4+1);
            buffer.putInt(channel.sid);
            buffer.putInt(request_id);
            buffer.put(state);
        }
    }

    @Override
    public void handleResponse(final ByteBuffer buffer, final int payload_size) throws Exception
    {
        if (payload_size < 4+1+1)
            throw new Exception("Incomplete Monitor Response");
        final int request_id = buffer.getInt();
        final byte subcmd = buffer.get();

        // Response to specific command, or value update?
        if (subcmd != 0)
        {
            PVAStatus status = PVAStatus.decode(buffer);
            if (! status.isSuccess())
            {
                logger.log(Level.WARNING, channel + " Monitor Response for " + request + ": " + status);
                return;
            }

            if (subcmd == GetRequest.INIT)
            {
                logger.log(Level.FINE,
                           () -> "Received monitor INIT reply #" + request_id +
                                 " for " + channel + ": " + status);

                // Decode type description from INIT response
                final PVAData type = channel.getTCP().getTypeRegistry().decodeType("", buffer);
                if (type instanceof PVAStructure)
                {
                    data = (PVAStructure)type;
                    logger.log(Level.FINER, () -> "Introspection Info: " + data.formatType());
                }
                else
                {
                    data = null;
                    throw new Exception("Expected PVAStructure, got " + type);
                }

                // Submit request again, this time to START getting data
                state = START;
                channel.getTCP().submit(this, this);
            }
            else
                throw new Exception("Unexpected Monitor response to subcmd " + subcmd);
        }
        else
        {   // Value update
            if (channel.getState() != ClientChannelState.CONNECTED)
            {
                logger.log(Level.WARNING,
                           () -> "Received unexpected monitor #" + request_id +
                                 " update for " + channel);
                return;
            }
            logger.log(Level.FINE,
                       () -> "Received monitor #" + request_id +
                             " update for " + channel);

            // Decode data from GET reply
            // 1) Bitset that indicates which elements of struct have changed
            final BitSet changes = PVABitSet.getBitSet(buffer);

            // 2) Decode those elements
            data.decodeElements(changes, channel.getTCP().getTypeRegistry(), buffer);

            final BitSet overrun = PVABitSet.getBitSet(buffer);
            logger.log(Level.FINER, () -> "Overruns: " + overrun);

            // Notify listener of latest value
            listener.handleMonitor(channel, changes, data);
        }
    }

    public void cancel() throws Exception
    {
        // Submit request again, this time to START getting data
        state = GetRequest.DESTROY;
        final TCPHandler tcp = channel.getTCP();
        tcp.submit(this, this);
        // Not expecting more replies
        tcp.removeHandler(this);
    }
}
