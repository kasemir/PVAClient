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
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

import org.epics.pva.PVAHeader;
import org.epics.pva.data.PVABitSet;
import org.epics.pva.data.PVAData;
import org.epics.pva.data.PVAStatus;
import org.epics.pva.data.PVAStructure;

@SuppressWarnings("nls")
class PutRequest extends CompletableFuture<Void> implements RequestEncoder, ResponseHandler
{
    /** Sub command to write value */
    private static final byte PUT = 0;

    private final ClientChannel channel;

    private final String request;

    private final int request_id;

    private volatile PVAStructure data;

    private final Object new_value;

    /** INIT or PUT? */
    private volatile boolean init = true;

    /** Request to write channel's value
     *  @param channel {@link ClientChannel}
     *  @param request Request for element to write, e.g. "field(value)"
     */
    public PutRequest(final ClientChannel channel, final String request, final Object new_value)
    {
        this.channel = channel;
        this.request = request;
        this.request_id = channel.getClient().allocateRequestID();
        this.new_value = new_value;
        try
        {
            channel.getTCP().submit(this, this);
        }
        catch (Exception ex)
        {
            completeExceptionally(ex);
        }
    }

    @Override
    public int getRequestID()
    {
        return request_id;
    }

    @Override
    public void encodeRequest(final ByteBuffer buffer) throws Exception
    {
        if (init)
        {
            logger.log(Level.FINE, () -> "Sending put INIT request #" + request_id + " for " + channel + " '" + request + "'");

            // Guess, assumes empty FieldRequest (6)
            final int size_offset = buffer.position() + PVAHeader.HEADER_OFFSET_PAYLOAD_SIZE;
            PVAHeader.encodeMessageHeader(buffer, PVAHeader.FLAG_NONE, PVAHeader.CMD_PUT, 4+4+1+6);
            buffer.putInt(channel.sid);
            buffer.putInt(request_id);
            buffer.put(GetRequest.INIT);

            final FieldRequest field_request = new FieldRequest(request);
            final int request_size = field_request.encodeType(buffer);
            buffer.putInt(size_offset, 4+4+1+request_size);

            init = false;
        }
        else
        {
            logger.log(Level.FINE, () -> "Sending put PUT request #" + request_id + " for " + channel + " = " + new_value);

            // Guess, empty bitset (1)
            final int size_offset = buffer.position() + PVAHeader.HEADER_OFFSET_PAYLOAD_SIZE;
            PVAHeader.encodeMessageHeader(buffer, PVAHeader.FLAG_NONE, PVAHeader.CMD_PUT, 4+4+1+1);
            final int pos = buffer.position();
            buffer.putInt(channel.sid);
            buffer.putInt(request_id);
            buffer.put((byte)(PUT | GetRequest.DESTROY));

            // Locate the 'value' field
            PVAData field = data.get("value");
            if (field == null)
                fail(new Exception("Cannot locate 'value' to write in " + data));

            if (field instanceof PVAStructure)
            {
                final PVAStructure struct = (PVAStructure) field;
                if ("enum_t".equals(struct.getStructureName()) ||
                    data.getStructureName().toLowerCase().indexOf("ntenum") > 0)
                    field = struct.get("index");
            }

            // Bitset to describe which field we're about to write
            final BitSet changed = new BitSet();
            changed.set(data.getIndex(field));
            logger.log(Level.FINE, () -> "Updated structure elements: " + changed);
            PVABitSet.putBitSet(changed, buffer);

            // Write the updated field
            field.setValue(new_value);
            field.encode(buffer);

            // Fix message size
            final int request_size = buffer.position() - pos;
            buffer.putInt(size_offset, request_size);
        }
    }

    @Override
    public void handleResponse(final ByteBuffer buffer, final int payload_size) throws Exception
    {
        if (payload_size < 4+1+1)
            fail(new Exception("Incomplete Put Response"));
        final int request_id = buffer.getInt();
        final byte subcmd = buffer.get();
        PVAStatus status = PVAStatus.decode(buffer);
        if (! status.isSuccess())
            fail(new Exception("Put Response: " + status));

        if (subcmd == GetRequest.INIT)
        {
            logger.log(Level.FINE,
                       () -> "Received put INIT reply #" + request_id +
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
                fail(new Exception("Expected PVAStructure, got " + type));
            }

            // Submit request again, this time to PUT data
            channel.getTCP().submit(this, this);
        }
        else if (subcmd == ((byte)(PUT | GetRequest.DESTROY)))
        {
            logger.log(Level.FINE,
                    () -> "Received put PUT reply #" + request_id +
                          " for " + channel + ": " + status);
            // Indicate completion now that server confirmed PUT
            complete(null);
        }
        else
            throw new Exception("Cannot decode Put " + subcmd + " Reply #" + request_id);
    }

    private void fail(final Exception ex) throws Exception
    {
        completeExceptionally(ex);
        throw ex;
    }
}
