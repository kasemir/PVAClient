/*******************************************************************************
 * Copyright (c) 2019 Oak Ridge National Laboratory.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 ******************************************************************************/
package org.epics.pva.client;

import java.nio.ByteBuffer;

/** Encode request to be sent to server.
 *
 *  <p>Submitted to {@link TCPHandler}.
 *
 *  <p>Most requests include a unique RequestID
 *  which associates it with a response.
 *
 *  @author Kay Kasemir
 *  @see {@link ResponseHandler}
 */
interface RequestEncoder
{
    /** Encode item to send to server
     *
     *  <p>{@link TCPHandler} calls this when it's ready to send
     *  a message to the server.
     *
     *  <p>Implementation has ownership of the 'send'
     *  buffer while inside this method.
     *  When implementation returns, {@link TCPHandler} flips
     *  and sends the buffer content to server, then re-uses the buffer.
     *
     *  @param buffer Send buffer into which to encode item to send
     *  @throws Exception on error
     */
    public void encodeRequest(ByteBuffer buffer) throws Exception;
}
