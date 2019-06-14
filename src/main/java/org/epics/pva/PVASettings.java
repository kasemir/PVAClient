/*******************************************************************************
 * Copyright (c) 2019 Oak Ridge National Laboratory.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 ******************************************************************************/
package org.epics.pva;

import java.util.logging.Level;
import java.util.logging.Logger;

/** PVA Settings
 *
 *  <p>Settings are first read from Java properties.
 *  If not defined, environment variables are checked.
 *  Falling back to defaults defined in here.
 *
 *  @author Kay Kasemir
 */
@SuppressWarnings("nls")
public class PVASettings
{
    /** Common logger */
    public static final Logger logger = Logger.getLogger(PVASettings.class.getPackageName());

    /** Address list. When empty, local subnet is used */
    public static String EPICS_PVA_ADDR_LIST = "";

    /** First PVA port */
    public static int EPICS_PVA_SERVER_PORT = 5075;

    /** PVA port for name searches and beacons */
    public static int EPICS_PVA_BROADCAST_PORT = 5076;

    /** Multicast address */
    public static String EPICS_PVA_MULTICAST_GROUP = "224.0.0.128";

    /** Connection timeout [seconds]
     *
     * <p>When approaching this time without having received a new value,
     * an 'Echo' request is sent. If still no reply, the channel is disconnected.
     */
    public static int EPICS_CA_CONN_TMO = 30;

    static
    {
        EPICS_PVA_ADDR_LIST = set("EPICS_PVA_ADDR_LIST", EPICS_PVA_ADDR_LIST);
        EPICS_PVA_SERVER_PORT = set("EPICS_PVA_SERVER_PORT", EPICS_PVA_SERVER_PORT);
        EPICS_PVA_BROADCAST_PORT = set("EPICS_PVA_BROADCAST_PORT", EPICS_PVA_BROADCAST_PORT);
        EPICS_CA_CONN_TMO = set("EPICS_CA_CONN_TMO", EPICS_CA_CONN_TMO);
    }

    private static String set(final String name, final String default_value)
    {
        String value = System.getProperty(name);
        if (value != null)
        {
            logger.log(Level.CONFIG, name + " = " + value + " (from property)");
            return value;
        }
        value = System.getenv(name);
        if (value != null)
        {
            logger.log(Level.CONFIG, name + " = " + value + " (from environment)");
            return value;
        }
        logger.log(Level.CONFIG, name + " = " + default_value + " (default)");
        return default_value;
    }

    private static int set(final String name, final int default_value)
    {
        return Integer.parseInt(set(name, Integer.toString(default_value)));
    }
}
