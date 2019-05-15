/*******************************************************************************
 * Copyright (c) 2019 Oak Ridge National Laboratory.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 ******************************************************************************/
package org.epics.pva.client;

/** State of a {@link ClientChannel}
 *  @author Kay Kasemir
 */
public enum ClientChannelState
{
    /** Initial state, channel has just been created */
    INIT,

    /** Actively looking for PVA servers that host this channel */
    SEARCHING,

    /** Found a PVA server, establishing TCP connection */
    FOUND,

    /** Connected via TCP */
    CONNECTED,

    /** Channel was closed, cannot be used again */
    CLOSED
};
