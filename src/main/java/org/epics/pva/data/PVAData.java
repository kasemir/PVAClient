/*******************************************************************************
 * Copyright (c) 2019 Oak Ridge National Laboratory.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 ******************************************************************************/
package org.epics.pva.data;

import java.nio.ByteBuffer;

/** PV Access Data
 *
 *  <p>A named piece of data that's read from the PVA server
 *  or written to the PVA server.
 *  Derived classes implement specific data types.
 *
 *  @author Kay Kasemir
 */
@SuppressWarnings("nls")
public abstract class PVAData
{
    protected final String name;

    protected PVAData(final String name)
    {
        this.name = name;
    }

    /** @return Data item name, e.g. "value" */
    public String getName()
    {
        return name;
    }

    /** Set value
     *
     *  <p>Derived classes implement type-safe get/set
     *  for the specific data type.
     *  This method allows setting the value from
     *  generic {@link Object}.
     *
     *  @param new_value
     *  @throws Exception on error, including incompatible data type
     */
    public abstract void setValue(Object new_value) throws Exception;

    /** @param name Name for the cloned item
     *  @return A clone of this type, with requested name, not cloning the data
     */
    public abstract PVAData cloneType(String name);

    /** Decode value for this {@link PVAData}
     *  @param types Type registry
     *  @param buffer Buffer, positioned on value
     *  @throws Exception on error
     */
    public abstract void decode(PVATypeRegistry types, ByteBuffer buffer) throws Exception;

    /** Encode value of this {@link PVAData}
     *  @param buffer Buffer, positioned where value should be written
     *  @throws Exception on error
     */
    public abstract void encode(ByteBuffer buffer) throws Exception;

    /** Format the type with indentation
     *  @param level Indentation level
     *  @param buffer Buffer to which to add this type
     */
    protected abstract void formatType(int level, StringBuilder buffer);

    /** Format the type and data with indentation
     *  @param level Indentation level
     *  @param buffer Buffer to which to add this value
     */
    protected abstract void format(int level, StringBuilder buffer);

    /** Add indentation
     *  @param level Indentation level
     *  @param buffer Buffer to which indentation is added
     */
    protected static void indent(int level, StringBuilder buffer)
    {
        for (int i=0; i<level; ++i)
            buffer.append("    ");
    }

    /** Get data type and name
     *
     *  <p>Recurses into sub-structures
     *  @return Data type and name, e.g. "double value"
     */
    public String formatType()
    {
        final StringBuilder buf = new StringBuilder();
        formatType(0, buf);
        return buf.toString();
    }

    /** Get data type, name and value
     *
     *  <p>Recurses into sub-structures
     *  @return Data type, name and value, e.g. "double value 3.14"
     */
    public String format()
    {
        final StringBuilder buf = new StringBuilder();
        format(0, buf);
        return buf.toString();
    }

    @Override
    public String toString()
    {
        return format();
    }
}
