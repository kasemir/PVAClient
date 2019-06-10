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
import java.nio.channels.SocketChannel;
import java.util.logging.Level;

import org.epics.pva.PVAHeader;
import org.epics.pva.data.PVASize;
import org.epics.pva.data.PVATypeRegistry;
import org.epics.pva.network.CommandHandlers;
import org.epics.pva.network.TCPHandler;

/** Handler for one TCP-connected client
 *  @author Kay Kasemir
 */
@SuppressWarnings("nls")
class ServerTCPHandler extends TCPHandler
{
    /** Handlers for various commands, re-used whenever a command is received */
    private static final CommandHandlers<ServerTCPHandler> handlers =
        new CommandHandlers<>(new ValidationHandler(),
                              new CreateChannelHandler(),
                              new GetHandler(),
                              new MonitorHandler(),
                              new DestroyChannelHandler(),
                              new DestroyRequestHandler(),
                              new CancelHandler());

    /** Server that holds all the PVs */
    private final PVAServer server;

    /** Types declared by client at other end of this TCP connection */
    private final PVATypeRegistry client_types = new PVATypeRegistry();

    public ServerTCPHandler(final PVAServer server, final SocketChannel client) throws Exception
    {
        super(client, false);
        this.server = server;
        server.register(this);
        startSender();

        // Initialize TCP connection by setting byte order..
        submit((version, buffer) ->
        {
            logger.log(Level.FINE, () -> "Set byte order " + buffer.order());
            PVAHeader.encodeMessageHeader(buffer,
                    (byte) (PVAHeader.FLAG_CONTROL | PVAHeader.FLAG_SERVER),
                    PVAHeader.CTRL_SET_BYTE_ORDER, 0);
        });
        // .. and requesting connection validation
        submit((version, buffer) ->
        {
            logger.log(Level.FINE, () -> "Sending Validation Request");
            PVAHeader.encodeMessageHeader(buffer,
                    PVAHeader.FLAG_SERVER,
                    PVAHeader.CMD_VALIDATION, 4+2+1);

            // int serverReceiveBufferSize;
            buffer.putInt(receive_buffer.capacity());

            // short serverIntrospectionRegistryMaxSize;
            buffer.putShort(Short.MAX_VALUE);

            // string[] authNZ;
            PVASize.encodeSize(0, buffer);
            // No authNZ
        });
    }

    PVAServer getServer()
    {
        return server;
    }

    PVATypeRegistry getClientTypes()
    {
        return client_types;
    }

    @Override
    protected void onReceiverExited(final boolean running)
    {
        if (running)
            server.shutdownConnection(this);
    }

    @Override
    protected void handleApplicationMessage(final byte command, final ByteBuffer buffer) throws Exception
    {
        if (!handlers.handleCommand(command, this, buffer))
            super.handleApplicationMessage(command, buffer);
    }
}
