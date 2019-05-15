/*******************************************************************************
 * Copyright (c) 2019 Oak Ridge National Laboratory.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 ******************************************************************************/
package org.epics.pva.data;

import static org.epics.pva.PVASettings.logger;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;

/** PV Access Union
 *
 *  <p>Holds one or more {@link PVAData} elements.
 *  Often named as for example a normative type.
 *
 *  @author Kay Kasemir
 */
@SuppressWarnings("nls")
public class PVAUnion extends PVAData
{
    static PVAUnion decodeType(final PVATypeRegistry types, final String name, final ByteBuffer buffer) throws Exception
    {
        final String union_name = PVAString.decodeString(buffer);

        // number of elements
        final int size = PVASize.decodeSize(buffer);

        // (name, FieldDesc)[]
        final List<PVAData> values = new ArrayList<>(size);
        for (int i=0; i<size; ++i)
        {
            final String element = PVAString.decodeString(buffer);
            final PVAData value = types.decodeType(element, buffer);
            values.add(value);
        }

        return new PVAUnion(name, union_name, values);
    }

    /** Union type name */
    private final String union_name;

    private volatile short type_id = 0;

    /** Unmodifiable list of elements.
     *
     *  <p>The value of each element may be updated, but
     *  no elements can be added, removed, replaced.
     */
    private final List<PVAData> elements;

    private volatile int selected = -1;

    public PVAUnion(final String name, final String struct_name, final PVAData... elements)
    {
        this(name, struct_name, List.of(elements));
    }

    public PVAUnion(final String name, final String struct_name, final List<PVAData> elements)
    {
        super(name);
        this.union_name = struct_name;
        this.elements = Collections.unmodifiableList(elements);
    }

    /** @return Selected element of the union */
    @SuppressWarnings("unchecked")
    public <PVA extends PVAData> PVA get()
    {
        final int safe_sel = selected;
        if (safe_sel < 0)
            return null;
        return (PVA) elements.get(safe_sel);
    }

    /** @return Index of selection option, -1 if none */
    public int getSelector()
    {
        return selected;
    }

    /** @return Options of the union */
    public List<PVAData> getOptions()
    {
        return elements;
    }

    /** @param option Option of the union to set, -1 for none
     *  @param new_value Value for that option
     *  @throws Exception on error
     */
    public void set(final int option, final Object new_value) throws Exception
    {
        if (option >= 0)
            elements.get(option).setValue(new_value);
        selected = option;
    }

    @Override
    public void setValue(final Object new_value) throws Exception
    {
        elements.get(selected).setValue(new_value);
    }

    /** @return Union type name */
    public String getUnionName()
    {
        return union_name;
    }

    /** @return Union type ID, 0 when not set */
    public short getTypeID()
    {
        return type_id;
    }

    /** @param id Type ID, 0 to clear */
    public void setTypeID(final short id)
    {
        this.type_id = id;
    }

    @Override
    public PVAUnion cloneType(final String name)
    {
        final List<PVAData> copy = new ArrayList<>(elements.size());
        for (PVAData element : elements)
            copy.add(element.cloneType(element.getName()));
        final PVAUnion clone = new PVAUnion(name, union_name, copy);
        clone.type_id = type_id;
        return clone;
    }

    @Override
    public void decode(final PVATypeRegistry types, final ByteBuffer buffer) throws Exception
    {
        selected = PVASize.decodeSize(buffer);
        if (selected < 0  ||  selected >= elements.size())
            throw new Exception("Invalid union selector " + selected + " for " + formatType());
        final PVAData element = elements.get(selected);
        logger.log(Level.FINER, () -> "Getting data for union element " + selected + ": " + element.formatType());
        element.decode(types, buffer);
    }

    @Override
    public void encode(final ByteBuffer buffer) throws Exception
    {
        PVASize.encodeSize(selected, buffer);
        elements.get(selected).encode(buffer);
    }

    @Override
    public void formatType(int level, StringBuilder buffer)
    {
        indent(level, buffer);
        if (union_name.isEmpty())
            buffer.append("union ");
        else
            buffer.append(union_name).append(" ");
        buffer.append(name);
        if (type_id > 0)
            buffer.append(" [#").append(type_id).append("]");
        for (PVAData element : elements)
        {
            buffer.append("\n");
            element.formatType(level+1, buffer);
        }
    }

    @Override
    protected void format(final int level, final StringBuilder buffer)
    {
        indent(level, buffer);
        if (union_name.isEmpty())
            buffer.append("union ");
        else
            buffer.append(union_name).append(" ");
        buffer.append(name);
        if (selected < 0)
            buffer.append("\n - nothing selected -\n");
        else
        {
            buffer.append("\n");
            elements.get(selected).format(level+1, buffer);
        }
    }
}
