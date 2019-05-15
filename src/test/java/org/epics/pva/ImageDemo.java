/*******************************************************************************
 * Copyright (c) 2019 Oak Ridge National Laboratory.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 ******************************************************************************/
package org.epics.pva;

import java.util.concurrent.TimeUnit;
import java.util.logging.LogManager;

import org.epics.pva.client.ClientChannel;
import org.epics.pva.client.ClientChannelListener;
import org.epics.pva.client.ClientChannelState;
import org.epics.pva.client.MonitorListener;
import org.epics.pva.client.PVAClient;
import org.epics.pva.data.PVAShortArray;
import org.epics.pva.data.PVAUnion;
import org.junit.Test;

/** Demo using 'IMAGE' PV from
 *  https://github.com/kasemir/EPICSV4Sandbox/tree/master/ntndarrayServer
 *
 *  Run ./ntndarrayServerMain IMAGE
 *
 *  @author Kay Kasemir
 */
@SuppressWarnings("nls")
public class ImageDemo
{
    static
    {
        try
        {
            LogManager.getLogManager().readConfiguration(PVASettings.class.getResourceAsStream("/logging.properties"));
        }
        catch (Exception ex)
        {
            ex.printStackTrace();
        }
    }

    @Test
    public void testImage() throws Exception
    {
        // Create a client
        final PVAClient pva = new PVAClient();

        // Connect
        final ClientChannelListener channel_listener = (channel, state) -> System.out.println(channel);
        final ClientChannel ch = pva.getChannel("IMAGE", channel_listener);
        while (ch.getState() != ClientChannelState.CONNECTED)
            TimeUnit.MILLISECONDS.sleep(100);

        System.out.println(ch.read("").get());

        // Monitor updates
        final MonitorListener monitor_listener = (channel, changed, data) ->
        {
            final PVAUnion value = data.get("value");
            final PVAShortArray array = value.get();
            System.out.println("value: " + array.get().length + " elements");
        };
        final int subscription = ch.subscribe("value, dimension, timeStamp", monitor_listener);
        TimeUnit.SECONDS.sleep(3000);
        ch.unsubscribe(subscription);

        // Close channels
        ch.close();

        // Close the client
        pva.close();
    }
}
