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
import java.util.List;
import java.util.Objects;

/** 'Primitive' PV Access data type
 *   @author Kay Kasemir
 */
@SuppressWarnings("nls")
public class PVAStringArray extends PVAData
{
    private volatile String[] value = new String[0];

    /** Construct variable-size string array */
    public PVAStringArray(final String name)
    {
        super(name);
    }

    /** @return Current value */
    public String[] get()
    {
        return value;
    }

    /** @param value Desired new value */
    public void set(final String[] value)
    {
        this.value = value;
    }

    @Override
    public void setValue(final Object new_value) throws Exception
    {
        if (new_value instanceof String[])
            value = (String[]) new_value;
        else if (new_value instanceof List)
        {
            @SuppressWarnings("rawtypes")
            final List<?> list = (List)new_value;
            value = new String[list.size()];
            for (int i=0; i<value.length; ++i)
                value[i] = Objects.toString(list.get(i));
        }
        else
            throw new Exception("Cannot set " + formatType() + " to " + new_value);
    }

    @Override
    public PVAStringArray cloneType(final String name)
    {
        return new PVAStringArray(name);
    }

    @Override
    public void decode(final PVATypeRegistry types, final ByteBuffer buffer) throws Exception
    {
        final int size = PVASize.decodeSize(buffer);
        String[] new_value = value;
        if (new_value == null  ||  new_value.length != size)
            new_value = new String[size];
        for (int i=0; i<size; ++i)
            new_value[i] = PVAString.decodeString(buffer);
        value = new_value;
    }

    @Override
    public void encode(final ByteBuffer buffer) throws Exception
    {
        final String[] copy = value;
        PVASize.encodeSize(copy.length, buffer);
        for (String s : copy)
            PVAString.encodeString(s, buffer);
    }

    @Override
    protected void formatType(final int level, final StringBuilder buffer)
    {
        indent(level, buffer);
        buffer.append("string[] ").append(name);
    }

    @Override
    protected void format(final int level, final StringBuilder buffer)
    {
        formatType(level, buffer);
        buffer.append(" ").append(Arrays.toString(value));
    }
}
