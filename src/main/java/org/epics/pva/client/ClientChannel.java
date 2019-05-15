/*******************************************************************************
 * Copyright (c) 2019 Oak Ridge National Laboratory.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 ******************************************************************************/
package org.epics.pva.client;

import static org.epics.pva.PVASettings.logger;

import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

import org.epics.pva.data.PVAStructure;

/** Client channel
 *
 *  <p>Obtained from {@link PVAClient#getChannel()}.
 *  Allows reading and writing a channel's data.
 *
 *  @author Kay Kasemir
 */
@SuppressWarnings("nls")
public class ClientChannel
{
    private static final AtomicInteger IDs = new AtomicInteger();

    private final PVAClient client;
    private final String name;
    private final ClientChannelListener listener;
    private final int id = IDs.incrementAndGet();
    int sid = -1;

    /** State, updated by other code in package */
    private AtomicReference<ClientChannelState> state = new AtomicReference<>(ClientChannelState.INIT);

    /** TCP Handler, set by PVAClient */
    private volatile TCPHandler tcp = null;

    ClientChannel(final PVAClient client, final String name, final ClientChannelListener listener)
    {
        this.client = client;
        this.name = name;
        this.listener = listener;
    }

    PVAClient getClient()
    {
        return client;
    }

    TCPHandler getTCP() throws Exception
    {
        final TCPHandler copy = tcp;
        if (copy == null)
            throw new Exception("Channel '" + name + "' is not connected");
        return tcp;
    }


    /** @return Client channel ID */
    int getId()
    {
        return id;
    }

    /** @return Channel name */
    public String getName()
    {
        return name;
    }

    /** @return {@link ClientChannelState} */
    public ClientChannelState getState()
    {
        return state.get();
    }

    void setState(final ClientChannelState new_state)
    {
        final ClientChannelState old = state.getAndSet(new_state);
        if (old != new_state)
            listener.channelStateChanged(this, new_state);
    }

    void createOnServer(final TCPHandler tcp)
    {
        this.tcp = tcp;
        // Enqueue request to create channel.
        // TCPHandler will perform it when connected,
        // or right away if it is already connected.
        tcp.submit(new CreateChannelRequest(this));
    }

    void completeConnection(final int sid)
    {
        if (state.compareAndSet(ClientChannelState.FOUND, ClientChannelState.CONNECTED))
        {
            this.sid = sid;
            logger.log(Level.FINE, () -> "Received create channel reply " + this + ", SID " + sid);
            listener.channelStateChanged(this, ClientChannelState.CONNECTED);

            // TODO resubscribeSubscriptions();
        }
        // Else: Channel was destroyed or closed, ignore the late connection
    }

    void resetConnection()
    {
        tcp = null;
        setState(ClientChannelState.INIT);
    }

    /** Read (get) channel's type info from server
     *
     *  <p>Returned {@link PVAStructure} only describes the type,
     *  the values are not set.
     *
     *  @param subfield Sub field to get, "" for complete data type
     *  @return {@link Future} for fetching the result
     */
    public Future<PVAStructure> info(final String subfield)
    {
        return new GetTypeRequest(this, subfield);
    }

    /** Read (get) channel's value from server
     *  @param request Request, "" for all fields, or "field_a, field_b.subfield"
     *  @return {@link Future} for fetching the result
     */
    public Future<PVAStructure> read(final String request)
    {
        return new GetRequest(this, request);
    }

    /** Write (put) an element of the channel's value on server
     *  @param request Request for element to write, e.g. "field(value)"
     *  @param new_value New value: Number, String
     *  @throws Exception on error
     *  @return {@link Future} for awaiting completion
     */
    public Future<Void> write(final String request, final Object new_value) throws Exception
    {
        return new PutRequest(this, request, new_value);
    }

    /** Start a subscription
     *
     *  @param request Request, "" for all fields, or "field_a, field_b.subfield"
     *  @param listener Will be invoked with channel and latest value
     *  @return Subscription ID, used to {@link #unsubscribe}
     *  @throws Exception on error
     */
    public int subscribe(final String request, final MonitorListener listener) throws Exception
    {
        // MonitorRequest submits itself to TCPHandler
        // and registers as response handler,
        // so we can later retrieve it via its requestID
        final MonitorRequest monitor = new MonitorRequest(this, request, listener);
        // Use that request ID as subscription ID
        return monitor.getRequestID();
    }

    /** Cancel a subscription
     *  @param subscription Subscription ID obtained by {@link #subscribe}
     *  @throws Exception on error
     */
    public void unsubscribe(final int subscription) throws Exception
    {
        // Locate MonitorRequest by ID
        final ResponseHandler monitor = getTCP().getResponseHandler(subscription);
        if (monitor != null  &&  monitor instanceof MonitorRequest)
            ((MonitorRequest) monitor).cancel();
        else
            throw new Exception("Invalid monitor request ID " + subscription);
    }

    void channelDestroyed(final int sid)
    {
        setState(ClientChannelState.CLOSED);

        if (sid == this.sid)
            logger.log(Level.FINE, () -> "Received destroy channel reply " + this);
        else
            logger.log(Level.WARNING, this + " destroyed with SID " + sid +" instead of expected " + this.sid);

        // TODO Anything?
        client.forgetChannel(this);
    }

    /** Close the channel */
    public void close()
    {
        final TCPHandler safe = tcp;
        if (safe != null)
            safe.submit(new DestroyChannelRequest(this));
    }

    @Override
    public String toString()
    {
        return "'" + name + "' [" + id + ", " + state.get() + "]";
    }
}
