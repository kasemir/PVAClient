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
import java.util.logging.Level;

import org.epics.pva.common.PVAHeader;
import org.epics.pva.common.RequestEncoder;

@SuppressWarnings("nls")
class EchoRequest implements RequestEncoder
{
    static final byte[] CHECK = new byte[] { 'e', 'c', 'h', 'o' };

    @Override
    public void encodeRequest(final byte version, final ByteBuffer buffer) throws Exception
    {
        logger.log(Level.FINE, "Sending ECHO request");
        // Protocol always required server to echo the payload, but version 1 servers didn't
        if (version < 2)
            PVAHeader.encodeMessageHeader(buffer, PVAHeader.FLAG_NONE, PVAHeader.CMD_ECHO, 0);
        else
        {
            PVAHeader.encodeMessageHeader(buffer, PVAHeader.FLAG_NONE, PVAHeader.CMD_ECHO, CHECK.length);
            buffer.put(CHECK);
        }
    }
}
