/*******************************************************************************
 * Copyright (c) 2019 Oak Ridge National Laboratory.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 ******************************************************************************/
package org.epics.pva.data;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

/** 'Primitive' PV Access data type
 *   @author Kay Kasemir
 */
@SuppressWarnings("nls")
public class PVABoolArray extends PVAData implements PVAArray
{
    private volatile boolean[] value;

    public PVABoolArray(final String name, final boolean[] value)
    {
        super(name);
        this.value = value;
    }

    public PVABoolArray(final String name)
    {
        this(name, new boolean[0]);
    }

    /** @return Current value */
    public boolean[] get()
    {
        return value;
    }

    /** @param value Desired new value */
    public void set(final boolean[] value)
    {
        this.value = value;
    }

    @Override
    public void setValue(final Object new_value) throws Exception
    {
        if (new_value instanceof boolean[])
            set(((boolean[]) new_value));
        else if (new_value instanceof List)
        {
            @SuppressWarnings("rawtypes")
            final List<?> list = (List)new_value;
            final boolean[] new_items = new boolean[list.size()];
            for (int i=0;  i<new_items.length;  ++i)
            {
                final Object item = list.get(i);
                if (item instanceof Boolean)
                    new_items[i] = (Boolean)item;
                else
                    throw new Exception("Cannot set " + formatType() + " to " + new_value);
            }
            value = new_items;
        }
        else
            throw new Exception("Cannot set " + formatType() + " to " + new_value);
    }

    @Override
    public PVABoolArray cloneType(final String name)
    {
        return new PVABoolArray(name);
    }

    @Override
    public PVABoolArray cloneData()
    {
        return new PVABoolArray(name, value.clone());
    }

    @Override
    public void encodeType(ByteBuffer buffer, BitSet described) throws Exception
    {
        buffer.put((byte) (PVABool.FIELD_DESC_TYPE | PVAFieldDesc.Array.VARIABLE_SIZE.mask));
    }

    @Override
    public void decode(final PVATypeRegistry types, final ByteBuffer buffer) throws Exception
    {
        final int size = PVASize.decodeSize(buffer);
        boolean[] new_value = value;
        if (new_value == null  ||  new_value.length != size)
            new_value = new boolean[size];
        for (int i=0; i<size; ++i)
            new_value[i] = buffer.get() != 0;
        value = new_value;
    }

    @Override
    public void encode(final ByteBuffer buffer) throws Exception
    {
        final boolean[] copy = value;
        PVASize.encodeSize(copy.length, buffer);
        for (int i=0; i<copy.length; ++i)
            buffer.put(copy[i] ? (byte)1 : (byte) 0);
    }

    @Override
    protected int update(final int index, final PVAData new_value, final BitSet changes) throws Exception
    {
        if (new_value instanceof PVABoolArray)
        {
            final PVABoolArray other = (PVABoolArray) new_value;
            if (! Arrays.equals(other.value, value))
            {
                value = other.value.clone();
                changes.set(index);
            }
        }
        return index + 1;
    }

    @Override
    protected void formatType(final int level, final StringBuilder buffer)
    {
        indent(level, buffer);
        buffer.append("boolean[] ").append(name);
    }

    @Override
    protected void format(final int level, final StringBuilder buffer)
    {
        formatType(level, buffer);
        buffer.append(" [");
        final boolean[] safe = value;
        if (safe == null)
            buffer.append("null");
        else
        {
            for (int i=0; i<safe.length; ++i)
            {
                if (i > 0)
                    buffer.append(", ");
                buffer.append(safe[i]);
            }
        }
        buffer.append("]");
    }

    @Override
    public boolean equals(final Object obj)
    {
        if (! (obj instanceof PVABoolArray))
            return false;
        final PVABoolArray other = (PVABoolArray) obj;
        return Arrays.equals(other.value, value);
    }
}
