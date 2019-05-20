/*******************************************************************************
 * Copyright (c) 2019 Oak Ridge National Laboratory.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 ******************************************************************************/
package org.epics.pva.data;

/** 'Primitive' PV Access data type based on {@link Number}
 *   @author Kay Kasemir
 */
abstract public class PVANumber extends PVAData
{
    protected PVANumber(final String name)
    {
        super(name);
    }

    /** @return Current value */
    abstract public Number getNumber();
}
