/*******************************************************************************
 * Copyright (c) 2019 Oak Ridge National Laboratory.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 ******************************************************************************/
package org.epics.pva.data;

import java.nio.ByteBuffer;

/** 'Primitive' PV Access data type
 *   @author Kay Kasemir
 */
@SuppressWarnings("nls")
public class PVADouble extends PVAData
{
    private volatile double value;

    public PVADouble(final String name)
    {
        this(name, Double.NaN);
    }

    public PVADouble(final String name, final double value)
    {
        super(name);
        this.value = value;
    }

    /** @return Current value */
    public double get()
    {
        return value;
    }

    /** @param value Desired new value */
    public void set(final double value)
    {
        this.value = value;
    }

    @Override
    public void setValue(final Object new_value) throws Exception
    {
        if (new_value instanceof Number)
            set(((Number) new_value).doubleValue());
        else
            throw new Exception("Cannot set " + formatType() + " to " + new_value);
    }

    @Override
    public PVADouble cloneType(final String name)
    {
        return new PVADouble(name);
    }

    @Override
    public void decode(final PVATypeRegistry types, final ByteBuffer buffer) throws Exception
    {
        value = buffer.getDouble();
    }

    @Override
    public void encode(final ByteBuffer buffer) throws Exception
    {
        buffer.putDouble(value);
    }
    @Override
    protected void formatType(final int level, final StringBuilder buffer)
    {
        indent(level, buffer);
        buffer.append("double ").append(name);
    }

    @Override
    protected void format(final int level, final StringBuilder buffer)
    {
        formatType(level, buffer);
        buffer.append(" ").append(value);
    }
}
