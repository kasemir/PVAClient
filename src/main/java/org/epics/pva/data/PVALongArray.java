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
import java.util.List;

/** 'Primitive' PV Access data type
 *   @author Kay Kasemir
 */
@SuppressWarnings("nls")
public class PVALongArray extends PVAData implements PVAArray
{
    private final boolean unsigned;
    private volatile long[] value;

    public PVALongArray(final String name, final boolean unsigned)
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
    public long[] get()
    {
        return value;
    }

    /** @param value Desired new value */
    public void set(final long[] value)
    {
        this.value = value;
    }

    @Override
    public void setValue(final Object new_value) throws Exception
    {
        if (new_value instanceof long[])
            set(((long[]) new_value));
        else if (new_value instanceof List)
        {
            @SuppressWarnings("rawtypes")
            final List<?> list = (List)new_value;
            final long[] new_items = new long[list.size()];
            for (int i=0;  i<new_items.length;  ++i)
            {
                final Object item = list.get(i);
                if (item instanceof Number)
                    new_items[i] = ((Number)item).longValue();
                else
                    throw new Exception("Cannot set " + formatType() + " to " + new_value);
            }
            value = new_items;
        }
        else
            throw new Exception("Cannot set " + formatType() + " to " + new_value);
    }

    @Override
    public PVALongArray cloneType(final String name)
    {
        return new PVALongArray(name, unsigned);
    }

    @Override
    public void encodeType(ByteBuffer buffer, BitSet described) throws Exception
    {
        if (unsigned)
            buffer.put((byte) 0b00101111);
        else
            buffer.put((byte) 0b00101011);
    }

    @Override
    public void decode(final PVATypeRegistry types, final ByteBuffer buffer) throws Exception
    {
        final int size = PVASize.decodeSize(buffer);
        long[] new_value = value;
        if (new_value == null  ||  new_value.length != size)
            new_value = new long[size];
        for (int i=0; i<size; ++i)
            new_value[i] = buffer.getLong();
        value = new_value;
    }

    @Override
    public void encode(final ByteBuffer buffer) throws Exception
    {
        final long[] copy = value;
        PVASize.encodeSize(copy.length, buffer);
        for (int i=0; i<copy.length; ++i)
            buffer.putLong(copy[i]);
    }

    @Override
    protected void formatType(final int level, final StringBuilder buffer)
    {
        indent(level, buffer);
        if (unsigned)
            buffer.append('u');
        buffer.append("long[] ").append(name);
    }

    @Override
    protected void format(final int level, final StringBuilder buffer)
    {
        formatType(level, buffer);
        buffer.append(" [");
        final long[] safe = value;
        if (safe == null)
            buffer.append("null");
        else
        {
            for (int i=0; i<safe.length; ++i)
            {
                if (i > 0)
                    buffer.append(", ");
                if (unsigned)
                    buffer.append(Long.toUnsignedString(safe[i]));
                else
                    buffer.append(safe[i]);
            }
        }
        buffer.append("]");
    }
}
