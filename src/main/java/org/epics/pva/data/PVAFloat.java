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
public class PVAFloat extends PVANumber
{
    public static final byte FIELD_DESC_TYPE = (byte)0b01000000;

    static PVAData decodeType(final String name, final byte field_desc, final ByteBuffer buffer) throws Exception
    {
        final PVAFieldDesc.Array array = PVAFieldDesc.Array.forFieldDesc(field_desc);

        final byte size = (byte) (field_desc & 0b111);
        if (size == 3)
        {
            if (array == PVAFieldDesc.Array.SCALAR)
                return new PVADouble(name);
            else if (array == PVAFieldDesc.Array.VARIABLE_SIZE)
                return new PVADoubleArray(name);
        }
        else if (size == 2)
        {
            if (array == PVAFieldDesc.Array.SCALAR)
                return new PVAFloat(name);
            else if (array == PVAFieldDesc.Array.VARIABLE_SIZE)
                return new PVAFloatArray(name);
        }

        throw new Exception("Cannot decode floating point encoding " + String.format("%02X ", field_desc) + ", " + array);
    }

    private volatile float value;

    public PVAFloat(final String name)
    {
        super(name);
        value = Float.NaN;
    }

    @Override
    public Number getNumber()
    {
        return value;
    }

    /** @return Current value */
    public float get()
    {
        return value;
    }

    /** @param value Desired new value */
    public void set(final float value)
    {
        this.value = value;
    }

    @Override
    public void setValue(final Object new_value) throws Exception
    {
        if (new_value instanceof Number)
            set(((Number) new_value).floatValue());
        else
            throw new Exception("Cannot set " + formatType() + " to " + new_value);
    }

    @Override
    public PVAFloat cloneType(final String name)
    {
        return new PVAFloat(name);
    }

    @Override
    public void encodeType(ByteBuffer buffer, BitSet described) throws Exception
    {
        buffer.put((byte) 0b01000010);
    }

    @Override
    public void decode(final PVATypeRegistry types, final ByteBuffer buffer) throws Exception
    {
        value = buffer.getFloat();
    }

    @Override
    public void encode(final ByteBuffer buffer) throws Exception
    {
        buffer.putFloat(value);
    }

    @Override
    protected void formatType(final int level, final StringBuilder buffer)
    {
        indent(level, buffer);
        buffer.append("float ").append(name);
    }

    @Override
    protected void format(final int level, final StringBuilder buffer)
    {
        formatType(level, buffer);
        buffer.append(" ").append(value);
    }
}
