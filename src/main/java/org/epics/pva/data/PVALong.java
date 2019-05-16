/*******************************************************************************
 * Copyright (c) 2019 Oak Ridge National Laboratory.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 ******************************************************************************/
package org.epics.pva.data;

import java.nio.ByteBuffer;
import java.util.BitSet;

/** 'Primitive' PV Access data type
 *   @author Kay Kasemir
 */
@SuppressWarnings("nls")
public class PVALong extends PVAData
{
    private final boolean unsigned;
    private volatile long value;

    public PVALong(final String name)
    {
        this(name, false);
    }

    public PVALong(final String name, final boolean unsigned)
    {
        super(name);
        this.unsigned = unsigned;
    }

    /** @return Is value unsigned? */
    public boolean isUnsigned()
    {
        return unsigned;
    }

    /** @return Current value */
    public long get()
    {
        return value;
    }

    /** @param value Desired new value */
    public void set(final long value)
    {
        this.value = value;
    }

    @Override
    public void setValue(final Object new_value) throws Exception
    {
        if (new_value instanceof Number)
            set(((Number) new_value).longValue());
        else
            throw new Exception("Cannot set " + formatType() + " to " + new_value);
    }

    @Override
    public PVALong cloneType(final String name)
    {
        return new PVALong(name, unsigned);
    }

    @Override
    public void encodeType(ByteBuffer buffer, BitSet described) throws Exception
    {
        if (unsigned)
            buffer.put((byte) 0b00100111);
        else
            buffer.put((byte) 0b00100011);
    }

    @Override
    public void decode(final PVATypeRegistry types, final ByteBuffer buffer) throws Exception
    {
        value = buffer.getLong();
    }

    @Override
    public void encode(final ByteBuffer buffer) throws Exception
    {
        buffer.putLong(value);
    }

    @Override
    protected void formatType(final int level, final StringBuilder buffer)
    {
        indent(level, buffer);
        if (unsigned)
            buffer.append('u');
        buffer.append("long ").append(name);
    }

    @Override
    protected void format(final int level, final StringBuilder buffer)
    {
        formatType(level, buffer);
        buffer.append(" ");
        if (unsigned)
            buffer.append(Long.toUnsignedString(value));
        else
            buffer.append(value);
    }
}
