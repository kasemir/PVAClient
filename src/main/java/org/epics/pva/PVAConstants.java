/*******************************************************************************
 * Copyright (c) 2019 Oak Ridge National Laboratory.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 ******************************************************************************/
package org.epics.pva;

/** Key buffer sizes
 *
 *  See
 *  https://github.com/epics-base/epicsCoreJava/blob/master/pvAccessJava/src/org/epics/pvaccess/PVAConstants.java
 */
public class PVAConstants
{
    /** UDP maximum send message size (for sending search requests).
     *
     *  <p>MAX_UDP: 1500 (max of ethernet and 802.{2,3} MTU) - 20/40(IPv4/IPv6) - 8(UDP) - some reserve (e.g. IPSEC)
     * (the MTU of Ethernet is currently independent of its speed variant)
     */
    public static final int MAX_UDP_UNFRAGMENTED_SEND = 1440;

    /** UDP maximum receive message size.
     *
     *  <p>MAX_UDP: 65535 (max UDP packet size) - 20/40(IPv4/IPv6) - 8(UDP)
     */
    public static final int MAX_UDP_PACKET = 65487;

    /** (Initial) TCP buffer size */
    public static final int TCP_BUFFER_SIZE = 1024 * 16;
}
