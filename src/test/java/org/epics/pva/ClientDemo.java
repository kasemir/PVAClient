/*******************************************************************************
 * Copyright (c) 2019 Oak Ridge National Laboratory.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 ******************************************************************************/
package org.epics.pva;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.LogManager;

import org.epics.pva.client.ClientChannelListener;
import org.epics.pva.client.ClientChannelState;
import org.epics.pva.client.MonitorListener;
import org.epics.pva.client.PVAChannel;
import org.epics.pva.client.PVAClient;
import org.epics.pva.data.PVAData;
import org.epics.pva.data.PVAStructure;
import org.junit.Test;

/** Demo using demo.db from test resources
 *  @author Kay Kasemir
 */
@SuppressWarnings("nls")
public class ClientDemo
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
    public void testConnection() throws Exception
    {
        // Create a client
        final PVAClient pva = new PVAClient();

        final PVAChannel ch1 = pva.getChannel("ramp");
        final PVAChannel ch2 = pva.getChannel("saw");
        CompletableFuture.allOf(ch1.connect(), ch2.connect()).get(5, TimeUnit.SECONDS);
        assertTrue(ch1.isConnected());
        assertTrue(ch2.isConnected());

        // Close channels
        ch2.close();
        ch1.close();

        // Close the client
        pva.close();
    }

    @Test
    public void testFailedConnection() throws Exception
    {
        final PVAClient pva = new PVAClient();
        final PVAChannel ch = pva.getChannel("bogus");
        final long start = System.currentTimeMillis();
        try
        {
            ch.connect().get(3, TimeUnit.SECONDS);
            fail("Connected?!");
        }
        catch (TimeoutException ex)
        {
            // Expected
        }
        final long timeout = System.currentTimeMillis();
        assertTrue(timeout - start < 3500);

        System.out.println("Gave up connecting to " + ch);
        ch.close();

        pva.close();
        final long done = System.currentTimeMillis();
        assertTrue(done - timeout < 1000);
    }

    @Test
    public void testSimplestGet() throws Exception
    {
        // Create a client
        final PVAClient pva = new PVAClient();

        // Connect
        final PVAChannel ch = pva.getChannel("ramp");
        ch.connect().get(5, TimeUnit.SECONDS);
        // Get data
        Future<PVAStructure> data = ch.read("");
        System.out.println(ch.getName() + " = " + data.get());

        // Close
        ch.close();
        pva.close();
    }

    @Test
    public void testGet() throws Exception
    {
        // Create a client
        final PVAClient pva = new PVAClient();

        // Connect to one or more channels
        final ClientChannelListener listener = (channel, state) ->
            System.out.println(channel);
        final PVAChannel ch1 = pva.getChannel("ramp", listener);
        final PVAChannel ch2 = pva.getChannel("saw", listener);
        CompletableFuture.allOf(ch1.connect(), ch2.connect()).get(5, TimeUnit.SECONDS);

        // Get data
        Future<PVAStructure> data = ch1.read("");
        System.out.println(ch1.getName() + " = " + data.get());

        data = ch2.read("");
        System.out.println(ch2.getName() + " = " + data.get());

        // Close channels
        ch2.close();
        ch1.close();

        // Close the client
        pva.close();
    }

    @Test
    public void testPut() throws Exception
    {
        // Create a client
        final PVAClient pva = new PVAClient();

        // Connect to one or more channels
        final PVAChannel channel = pva.getChannel("ramp");
        channel.connect().get(5, TimeUnit.SECONDS);

        // Write data
        channel.write("value", 2.0).get(2, TimeUnit.SECONDS);

        // Close channels
        channel.close();

        // Close the client
        pva.close();
    }

    @Test
    public void testPutEnum() throws Exception
    {
        final PVAClient pva = new PVAClient();
        final PVAChannel channel = pva.getChannel("ramp.SCAN");
        channel.connect().get(5, TimeUnit.SECONDS);

        // Set SCAN to ".1 second" and back to "1 second"
        channel.write("value", 9).get(2, TimeUnit.SECONDS);
        TimeUnit.SECONDS.sleep(3);
        channel.write("value", 6).get(2, TimeUnit.SECONDS);

        channel.close();
        pva.close();
    }

    @Test
    public void testAll() throws Exception
    {
        // Create a client
        final PVAClient pva = new PVAClient();

        // Connect to one or more channels
        final ClientChannelListener channel_listener = (ch, state) ->
        {
            System.out.println(ch.getName() + ": " + state);
        };
        final PVAChannel ch1 = pva.getChannel("ramp", channel_listener);
        final PVAChannel ch2 = pva.getChannel("saw", channel_listener);

        // Wait until channels connect by polling state
        while (ch1.getState() != ClientChannelState.CONNECTED  &&
               ch2.getState() != ClientChannelState.CONNECTED)
            Thread.sleep(100);

        // Get data
        PVAStructure data = ch1.read("").get(2, TimeUnit.SECONDS);
        System.out.println(ch1.getName() + " = " + data);
        System.out.println(data.get("value"));

        data = ch2.read("").get(2, TimeUnit.SECONDS);
        System.out.println(ch2.getName() + " = " + data);
        System.out.println(data.get("value"));

        // Subscribe
        final MonitorListener monitor_listener = (channel, changes, update) ->
        {
            System.out.println("Update for " + channel.getName() + ":");
            if (changes.get(0)  ||  ! (update instanceof PVAStructure))
                System.out.println(update);
            else
            {
                final PVAStructure struct = update;
                for (int index=changes.nextSetBit(0); index >= 0; index = changes.nextSetBit(index+1))
                {
                    final PVAData element = struct.get(index);
                    System.out.println("    " + element);
                }
            }
        };
        int monitor = ch1.subscribe("", monitor_listener);
        Thread.sleep(5000);

        // Cancel subscription, subscribe to other channel
        ch1.unsubscribe(monitor);
        monitor = ch2.subscribe("", monitor_listener);
        Thread.sleep(5000);
        ch2.unsubscribe(monitor);

        // write
        ch1.write("value", -5).get();

        // Close channels
        ch1.close();
        ch2.close();

        // Close client
        pva.close();

        // Check if anything else happens after channels were closed
        Thread.sleep(10000);
    }
}
