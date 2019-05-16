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
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;

/** PV Access structure
 *
 *  <p>Holds one or more {@link PVAData} elements.
 *  Often named as for example a normative type.
 *
 *  @author Kay Kasemir
 */
@SuppressWarnings("nls")
public class PVAStructure extends PVAData
{
    static PVAStructure decodeType(final PVATypeRegistry types, final String name, final ByteBuffer buffer) throws Exception
    {
        final String struct_name = PVAString.decodeString(buffer);

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

        return new PVAStructure(name, struct_name, values);
    }

    /** Structure type name */
    private final String struct_name;

    private volatile short type_id = 0;

    /** Unmodifiable list of elements.
     *
     *  <p>The value of each element may be updated, but
     *  no elements can be added, removed, replaced.
     */
    private final List<PVAData> elements;

    public PVAStructure(final String name, final String struct_name, final PVAData... elements)
    {
        this(name, struct_name, List.of(elements));
    }

    public PVAStructure(final String name, final String struct_name, final List<PVAData> elements)
    {
        super(name);
        this.struct_name = struct_name;
        this.elements = Collections.unmodifiableList(elements);
    }

    @Override
    public void setValue(final Object new_value) throws Exception
    {
        // Cannot set structure, only individual elements
        throw new Exception("Cannot set " + getStructureName() + " " + name + " to " + new_value);
    }

    /** @return Structure type name */
    public String getStructureName()
    {
        return struct_name;
    }

    /** @return Structure type ID, 0 when not set */
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
    public PVAStructure cloneType(final String name)
    {
        final List<PVAData> copy = new ArrayList<>(elements.size());
        for (PVAData element : elements)
            copy.add(element.cloneType(element.getName()));
        final PVAStructure clone = new PVAStructure(name, struct_name, copy);
        clone.type_id = type_id;
        return clone;
    }

    @Override
    public void decode(final PVATypeRegistry types, final ByteBuffer buffer) throws Exception
    {
        decodeElements(types, buffer);
    }

    /** Decode selected structure elements
     *
     *  <p>The bitset indicates which elements have changed,
     *  i.e. what element data is available in the buffer.
     *  Note that the bitset may address the same elements multiple times.
     *  For example, a 'get' or 'monitor' reply may contain a bitset
     *  {0, 1, 2, 3} that indicates that the whole structure changed (bit 0),
     *  plus then stressing that elements 1, 2, 3 in that structure changed.
     *  The buffer, however, does not repeat data for elements already read.
     *
     *  @param changes Bits to decode (will not be modified)
     *  @param types Type registry
     *  @param buffer Buffer from which to read structure elements
     *  @throws Exception on error
     */
    public void decodeElements(final BitSet changes, final PVATypeRegistry types, final ByteBuffer buffer) throws Exception
    {
        logger.log(Level.FINER, () -> "Structure elements to decode: " + changes);
        // Preserve 'changes', operate with copy
        final BitSet to_decode = (BitSet) changes.clone();
        for (int index = to_decode.nextSetBit(0);
             index >= 0;
             index = to_decode.nextSetBit(index + 1))
        {
            // final version of index to allow use in logging lambdas
            final int i = index;
            final PVAData element = get(i);
            if (element instanceof PVAStructure)
            {
                final PVAStructure se = (PVAStructure) element;
                logger.log(Level.FINER, () -> "Getting data for indexed element " + i + ": " + se.getStructureName());
                final int count = se.decodeElements(types, buffer);
                logger.log(Level.FINER, () -> "Decoded elements " + i + ".." + (i + count));
                to_decode.clear(index, index + count);
            }
            else
            {
                logger.log(Level.FINER, () -> "Getting data for indexed element " + i + ": " + element.formatType());
                element.decode(types, buffer);
                to_decode.clear(index);
            }
            logger.log(Level.FINER, () -> "Remaining elements to read: " + to_decode);

            // Javadoc for nextSetBit() suggests checking for MAX_VALUE
            // to avoid index + 1 overflow and thus starting over with first bit
            if (i == Integer.MAX_VALUE)
                break;
        }
    }

    /** Decode value for this {@link PVAData}
     *
     *  <p>Returns the number of elements decoded,
     *  including elements in sub-structures.
     *
     *  @param types Type registry
     *  @param buffer Buffer, positioned on value
     *  @return Deep count of decoded elements
     *  @throws Exception on error
     */
    private int decodeElements(final PVATypeRegistry types, final ByteBuffer buffer) throws Exception
    {
        logger.log(Level.FINEST, () -> "Decoding structure " + getStructureName() + " " + getName());
        int count = 0;
        for (PVAData element : elements)
        {
            // Count the element itself, be it the whole sub-struct
            // or a basic element
            ++count;
            if (element instanceof PVAStructure)
            {
                final PVAStructure se = (PVAStructure) element;
                logger.log(Level.FINEST, () -> "Decode sub-structure " + se.getStructureName() + " " + se.getName());
                count += se.decodeElements(types, buffer);
            }
            else
            {
                logger.log(Level.FINEST, () -> "Decode " + element.formatType());
                element.decode(types, buffer);
            }
        }
        logger.log(Level.FINEST, "Decoded " + count + " elements");
        return count;
    }

    @Override
    public void encode(final ByteBuffer buffer) throws Exception
    {
        for (PVAData element : elements)
            element.encode(buffer);
    }

    @Override
    public void encodeType(final ByteBuffer buffer, final BitSet described) throws Exception
    {
        final short type_id = getTypeID();
        if (type_id != 0)
        {
            final int u_type_id = Short.toUnsignedInt(type_id);
            if (described.get(u_type_id))
            {   // Refer to existing definition
                buffer.put(PVAFieldDesc.ONLY_ID_TYPE_CODE);
                buffer.putShort(type_id);
                // Done!
                return;
            }
            else
            {   // (Re-)define this type
                buffer.put(PVAFieldDesc.FULL_WITH_ID_TYPE_CODE);
                buffer.putShort(type_id);
                described.set(u_type_id);
            }
        }

        // Encode 'structure' type, name
        buffer.put((byte) (PVAComplex.FIELD_DESC_TYPE | PVAComplex.STRUCTURE));
        PVAString.encodeString(struct_name, buffer);

        // Encode elements
        PVASize.encodeSize(elements.size(), buffer);
        for (PVAData element : elements)
        {
            PVAString.encodeString(element.getName(), buffer);
            element.encodeType(buffer, described);
        }
    }

    /** Get structure element by name
     *
     *  <p>Performs shallow search of this structure,
     *  not searching sub-structures
     *
     *  @param element_name Name of structure element
     *  @return Located element or <code>null</code>
     */
    @SuppressWarnings("unchecked")
    public <PVA extends PVAData> PVA get(final String element_name)
    {
        for (PVAData element : elements)
            if (element.getName().equals(element_name))
                return (PVA) element;
        return null;
    }

    /** Get structure element by index
     *
     *  <p>Starting at a top-level structure, index 0
     *  refers to the complete structure.
     *  Index 1 refers to the first element.
     *  If that element is again a structure,
     *  index 2 refers to the first element inside that structure.
     *
     *  <p>In other words, the indices decent into sub-structures:
     *  <pre>
     *  0  struct TOP
     *  1     double element1
     *  2     double element2
     *  3     struct SUB1
     *  4         double element1_1
     *  5         double element1_2
     *  6     struct SUB2
     *  7         double element2_1
     *  8         double element2_2
     *  9     double element3
     *  </pre>
     *
     *  @param index Element index
     *  @return Located element or <code>null</code>
     */
    public PVAData get(final int index)
    {
        final Object result = getElementOrNextIndex(0, index);
        if (result instanceof PVAData)
            return (PVAData) result;
        return null;
    }

    /** Locate element by index
     *
     *  @param i Incremented element index as we recurse structures
     *  @param index Desired element index
     *  @return Located {@link PVAData} or {@link Integer} for next i
     */
    private Object getElementOrNextIndex(int i, final int index)
    {
        // Does index refer to complete structure?
        if (i == index)
            return this;
        // Check elements
        for (PVAData element : elements)
        {
            ++i;
            if (i == index)
                return element;
            // Descend into sub struct,
            // get the incremented index upon return
            if (element instanceof PVAStructure)
            {
                final Object next = ((PVAStructure) element).getElementOrNextIndex(i, index);
                if (next instanceof Integer)
                    i = (Integer) next;
                else
                    return next;
            }
        }
        return i;
    }

    /** Get index of element within structure
     *
     *  <p>Starting at a top-level structure, index 0
     *  refers to the complete structure.
     *  Index 1 refers to the first element.
     *  If that element is again a structure,
     *  index 2 refers to the first element inside that structure
     *  and so on.
     *
     *  <p>Element to be located must be an actual element
     *  of the structure. A value that happens to have
     *  the same name and type as some structure element
     *  is not sufficient because a structure may contain
     *  more than one "int value" somewhere within its
     *  sub-element.
     *
     *  @param element Structure element to locate
     *  @return Index of that element
     *  @throws Exception on error, including element not found
     *  @see #get(int)
     */
    public int getIndex(final PVAData element) throws Exception
    {
        final int index = getIndexOrNext(0, element);
        if (index >= 0)
            return index;
        throw new Exception("Cannot locate " + element.formatType() +
                            " in " + getStructureName() + " " + getName());
    }

    /** Locate element, recursing into sub-structures
     *  @param i Index of this structure
     *  @param element Element to locate
     *  @return Zero or larger: Index of the element. Negative: Next index to check
     */
    private int getIndexOrNext(int i, final PVAData element)
    {
        if (element == this)
            return i;
        // Check elements
        for (PVAData sub : elements)
        {
            ++i;
            if (sub == element)
                return i;
            // Descend into sub struct,
            // get the incremented index upon return
            if (sub instanceof PVAStructure)
            {
                final int next = ((PVAStructure) sub).getIndexOrNext(i, element);
                if (next >= 0)
                    return next;
                else
                    i = -next;
            }
        }
        return -i;
    }

    @Override
    public void formatType(int level, StringBuilder buffer)
    {
        indent(level, buffer);
        if (getStructureName().isEmpty())
            buffer.append("structure ");
        else
            buffer.append(getStructureName()).append(" ");
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
        if (getStructureName().isEmpty())
            buffer.append("structure ");
        else
            buffer.append(getStructureName()).append(" ");
        buffer.append(name);
        for (PVAData element : elements)
        {
            buffer.append("\n");
            element.format(level+1, buffer);
        }
    }
}
