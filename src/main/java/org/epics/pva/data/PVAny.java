/*******************************************************************************
 * Copyright (c) 2019 Oak Ridge National Laboratory.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 ******************************************************************************/
package org.epics.pva.data;

import java.nio.ByteBuffer;

/** PV Access 'any'
*
*  <p>Also called "Variant Union",
*  holds one or more {@link PVAData} elements
*  which may change for each value update.
*
*  @author Kay Kasemir
*/
@SuppressWarnings("nls")
public class PVAny extends PVAData
{
    private volatile PVAData value = null;

    public PVAny(final String name)
    {
        super(name);
    }

    @Override
    public void setValue(final Object new_value) throws Exception
    {
        if (new_value instanceof PVAData)
            value = (PVAData) new_value;
        else
            throw new Exception("Cannot set " + formatType() + " to " + new_value);
    }

    @Override
    public PVAData cloneType(final String name)
    {
        return new PVAny(name);
    }

    @Override
    public void decode(final PVATypeRegistry types, final ByteBuffer buffer) throws Exception
    {
        // Determine what the actual type is
        value = types.decodeType("any", buffer);
        // Unless it's the NULL_TYPE_CODE, decode value
        if (value != null)
            value.decode(types, buffer);
    }

    @Override
    public void encode(final ByteBuffer buffer) throws Exception
    {
        throw new Exception("TODO: Implement encoding 'any'");
    }

    @Override
    protected void formatType(final int level, final StringBuilder buffer)
    {
        indent(level, buffer);
        buffer.append("any ").append(name);
    }

    @Override
    protected void format(final int level, final StringBuilder buffer)
    {
        formatType(level, buffer);
        buffer.append("\n");
        if (value == null)
        {
            indent(level+1, buffer);
            buffer.append("(none)");
        }
        else
            value.format(level+1, buffer);
    }
}
