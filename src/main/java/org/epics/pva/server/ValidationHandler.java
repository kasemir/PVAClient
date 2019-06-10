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
import java.util.logging.Level;

import org.epics.pva.PVAHeader;
import org.epics.pva.data.PVAStatus;
import org.epics.pva.data.PVAString;
import org.epics.pva.network.CommandHandler;

/** Handle response clients's VALIDATION reply
 *  @author Kay Kasemir
 */
@SuppressWarnings("nls")
class ValidationHandler implements CommandHandler<ServerTCPHandler>
{
    @Override
    public byte getCommand()
    {
        return PVAHeader.CMD_VALIDATION;
    }

    @SuppressWarnings("unused")
    @Override
    public void handleCommand(final ServerTCPHandler tcp, final ByteBuffer buffer) throws Exception
    {
        if (buffer.remaining() < 4+2+2+1)
            throw new Exception("Incomplete validation response");
        
        final int client_buffer_size = buffer.getInt();
        final int client_registry_size = Short.toUnsignedInt(buffer.getShort());
        final short quos = buffer.getShort();
        final String auth = PVAString.decodeString(buffer);
        logger.log(Level.FINE, "Connection validated, auth '" + auth + "'");
        sendConnectionValidated(tcp);
    }
        
    private void sendConnectionValidated(final ServerTCPHandler tcp)
    {
        tcp.submit((version, buf) ->
        {
            logger.log(Level.FINE, () -> "Confirm validation");
            PVAHeader.encodeMessageHeader(buf,
                    PVAHeader.FLAG_SERVER,
                    PVAHeader.CMD_VALIDATED, 1);
            PVAStatus.StatusOK.encode(buf);
        });
    }
}
