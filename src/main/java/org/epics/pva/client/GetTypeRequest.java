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
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

import org.epics.pva.PVAHeader;
import org.epics.pva.data.PVAData;
import org.epics.pva.data.PVAStatus;
import org.epics.pva.data.PVAString;
import org.epics.pva.data.PVAStructure;

@SuppressWarnings("nls")
class GetTypeRequest extends CompletableFuture<PVAStructure> implements RequestEncoder, ResponseHandler
{
    private final ClientChannel channel;

    private final String subfield;

    private final int request_id;

    private volatile PVAStructure data;

    /** Request to fetch a channel's data type
     *  @param channel {@link ClientChannel}
     *  @param subfield Field name, "" for all fields
     */
    public GetTypeRequest(final ClientChannel channel, final String subfield)
    {
        this.channel = channel;
        this.subfield = subfield;
        this.request_id = channel.getClient().allocateRequestID();
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
    public void encodeRequest(final byte version, final ByteBuffer buffer) throws Exception
    {
        logger.log(Level.FINE, () -> "Sending Get-Type request #" + request_id + " for " + channel + ", sub field '" + subfield + "'");

        PVAHeader.encodeMessageHeader(buffer, PVAHeader.FLAG_NONE, PVAHeader.CMD_GET_TYPE, 4+4+PVAString.getEncodedSize(subfield));
        buffer.putInt(channel.sid);
        buffer.putInt(request_id);
        PVAString.encodeString(subfield, buffer);
    }

    @Override
    public void handleResponse(final ByteBuffer buffer, final int payload_size) throws Exception
    {
        if (payload_size < 4+1+1)
            fail(new Exception("Incomplete Get-Type Response"));
        final int request_id = buffer.getInt();
        PVAStatus status = PVAStatus.decode(buffer);
        if (! status.isSuccess())
            throw new Exception("Get-Type Response: " + status);

        logger.log(Level.FINE,
                   () -> "Received Get-Type reply #" + request_id +
                         " for " + channel + ": " + status);

        // Decode type description
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

        // Indicate completion now that we have data
        complete(data);
    }

    private void fail(final Exception ex) throws Exception
    {
        completeExceptionally(ex);
        throw ex;
    }
}
