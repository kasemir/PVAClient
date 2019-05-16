/*******************************************************************************
 * Copyright (c) 2019 Oak Ridge National Laboratory.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 ******************************************************************************/
package org.epics.pva.client;

/** Listener to a {@link ClientChannel}
 *
 *  @author Kay Kasemir
 */
public interface ClientChannelListener
{
    /** Invoked when the channel state changes
     *
     *  <p>Will be called as soon as possible, i.e. within
     *  the thread that handles the network communication.
     *
     *  <p>Client code may invoke {@link ClientChannel#read()}
     *  or {@link ClientChannel#subscribe()} to initiate
     *  reading data or to start a subscription, but
     *  client code <b>must not</b> block,
     *  i.e. awaiting the result of {@link ClientChannel#read()}
     *  is not permitted within this call.
     *
     *  @param channel
     *  @param state
     */
    public void channelStateChanged(ClientChannel channel, ClientChannelState state);
}
