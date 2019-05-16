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
public class PVAByteArray extends PVAData
{
    private final boolean unsigned;
    private volatile byte[] value;

    public PVAByteArray(final String name, final boolean unsigned)
    {
        super(name);
        this.unsigned = unsigned;
    }

    /** @return Current value */
    public byte[] get()
    {
        return value;
    }

    /** @param value Desired new value */
    public void set(final byte[] value)
    {
        this.value = value;
    }

    @Override
    public void setValue(final Object new_value) throws Exception
    {
        if (new_value instanceof byte[])
            set(((byte[]) new_value));
        else if (new_value instanceof List)
        {
            @SuppressWarnings("rawtypes")
            final List<?> list = (List)new_value;
            final byte[] new_items = new byte[list.size()];
            for (int i=0;  i<new_items.length;  ++i)
            {
                final Object item = list.get(i);
                if (item instanceof Number)
                    new_items[i] = ((Number)item).byteValue();
                else
                    throw new Exception("Cannot set " + formatType() + " to " + new_value);
            }
            value = new_items;
        }
        else
            throw new Exception("Cannot set " + formatType() + " to " + new_value);
    }

    @Override
    public PVAByteArray cloneType(final String name)
    {
        return new PVAByteArray(name, unsigned);
    }

    @Override
    public void encodeType(ByteBuffer buffer, BitSet described) throws Exception
    {
        if (unsigned)
            buffer.put((byte) 0b00101100);
        else
            buffer.put((byte) 0b00101000);
    }

    @Override
    public void decode(final PVATypeRegistry types, final ByteBuffer buffer) throws Exception
    {
        final int size = PVASize.decodeSize(buffer);
        byte[] new_value = value;
        if (new_value == null  ||  new_value.length != size)
            new_value = new byte[size];
        for (int i=0; i<size; ++i)
            new_value[i] = buffer.get();
        value = new_value;
    }

    @Override
    public void encode(final ByteBuffer buffer) throws Exception
    {
        final byte[] copy = value;
        PVASize.encodeSize(copy.length, buffer);
        for (int i=0; i<copy.length; ++i)
            buffer.put(copy[i]);
    }

    @Override
    protected void formatType(final int level, final StringBuilder buffer)
    {
        indent(level, buffer);
        if (unsigned)
            buffer.append('u');
        buffer.append("byte[] ").append(name);
    }

    @Override
    protected void format(final int level, final StringBuilder buffer)
    {
        formatType(level, buffer);
        buffer.append(" [");
        final byte[] safe = value;
        if (safe == null)
            buffer.append("null");
        else
        {
            for (int i=0; i<safe.length; ++i)
            {
                if (i > 0)
                    buffer.append(", ");
                if (unsigned)
                    buffer.append(Byte.toUnsignedInt(safe[i]));
                else
                    buffer.append(safe[i]);
            }
        }
        buffer.append("]");
    }
}
