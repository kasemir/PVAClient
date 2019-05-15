/*******************************************************************************
 * Copyright (c) 2019 Oak Ridge National Laboratory.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 ******************************************************************************/
package org.epics.pva.data;

import java.nio.ByteBuffer;
import java.util.Objects;

/** 'Primitive' PV Access data type
 *   @author Kay Kasemir
 */
@SuppressWarnings("nls")
public class PVAString extends PVAData
{
    public static final byte FIELD_DESC_TYPE = (byte)0b01100000;

    /** @return Encoded size of string in bytes */
    public static int getEncodedSize(final String string)
    {
        final int len = string.length();
        return PVASize.size(len) + len;
    }

    /** @param string String to encode
     *  @param buffer Buffer into which to encode string
     */
    public static void encodeString(final String string, final ByteBuffer buffer)
    {
        if (string == null)
            PVASize.encodeSize(-1, buffer);
        else
        {
            PVASize.encodeSize(string.length(), buffer);
            buffer.put(string.getBytes());
        }
    }

    /** @param buffer Buffer from which to decode string
     *  @return Decoded string
     */
    public static String decodeString(final ByteBuffer buffer)
    {
        final int size = PVASize.decodeSize(buffer);
        if (size >= 0)
        {
            byte[] bytes = new byte[size];
            buffer.get(bytes);
            return new String(bytes);
        }
        return null;
    }

    static PVAData decodeType(final String name, final byte field_desc, final ByteBuffer buffer) throws Exception
    {
        final PVAFieldDesc.Array array = PVAFieldDesc.Array.forFieldDesc(field_desc);
        if (array == PVAFieldDesc.Array.SCALAR)
            return new PVAString(name);
        else if (array == PVAFieldDesc.Array.VARIABLE_SIZE)
            return new PVAStringArray(name);
        else
            throw new Exception("Cannot handle " + array);
    }

    private volatile String value;

    public PVAString(final String name)
    {
        this(name, null);
    }

    public PVAString(final String name, final String value)
    {
        super(name);
        this.value = value;
    }

    /** @return Current value */
    public String get()
    {
        return value;
    }

    /** @param value Desired new value */
    public void set(final String value)
    {
        this.value = value;
    }

    @Override
    public void setValue(final Object new_value) throws Exception
    {
        set(Objects.toString(new_value));
    }

    @Override
    public PVAString cloneType(final String name)
    {
        return new PVAString(name);
    }

    @Override
    public void decode(final PVATypeRegistry types, final ByteBuffer buffer) throws Exception
    {
        value = decodeString(buffer);
    }

    @Override
    public void encode(final ByteBuffer buffer) throws Exception
    {
        encodeString(value, buffer);
    }

    @Override
    protected void formatType(final int level, final StringBuilder buffer)
    {
        indent(level, buffer);
        buffer.append("string ").append(name);
    }

    @Override
    protected void format(final int level, final StringBuilder buffer)
    {
        formatType(level, buffer);
        buffer.append(" ").append(value);
    }
}
