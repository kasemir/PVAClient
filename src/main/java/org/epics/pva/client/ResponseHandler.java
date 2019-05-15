/*******************************************************************************
 * Copyright (c) 2019 Oak Ridge National Laboratory.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 ******************************************************************************/
package org.epics.pva.client;

import java.nio.ByteBuffer;

/** Handler for response from server.
 *
 *  <p>Can be provided to {@link TCPHandler} when submitting a {@link RequestEncoder}.
 *
 *  @author Kay Kasemir
 */
interface ResponseHandler
{
    /** @return Unique ID that identifies the request and its response */
    public int getRequestID();

    /** Handle response received from server
     *
     *  <p>{@link TCPHandler} calls this when it
     *  receives a reply to the request ID.
     *
     *  <p>Implementation has ownership of the 'receive'
     *  buffer while inside this method.
     *  The buffer may contain more data than just the response
     *  for this handler, so implementation should check
     *  payload size.
     *
     *  @param buffer Buffer from which to decode response
     *  @param payload_size Size of payload for this handler
     *  @throws Exception on error
     */
    public void handleResponse(ByteBuffer buffer, int payload_size) throws Exception;
}
