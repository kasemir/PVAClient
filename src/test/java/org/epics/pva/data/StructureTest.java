/*******************************************************************************
 * Copyright (c) 2019 Oak Ridge National Laboratory.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 ******************************************************************************/
package org.epics.pva.data;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.junit.Assert.assertThat;

import org.junit.Test;

@SuppressWarnings("nls")
public class StructureTest
{
    @Test
    public void testStructure() throws Exception
    {
        // Create some structure, a few fields already set
        final PVAStructure time = new PVAStructure("timeStamp", "time_t",
                                                   new PVALong("secondsPastEpoch"),
                                                   new PVAInt("nanoseconds", 42),
                                                   new PVAInt("userTag"));
        // Set value of other field in struct 
        final PVALong sec = time.get("secondsPastEpoch");
        sec.set(41);
        
        // Create some other struct
        final PVAStructure alarm = new PVAStructure("alarm", "alarm_t",
                                                    new PVAInt("severity"),
                                                    new PVAInt("status"),
                                                    new PVAString("message", "NO_ALARM"));

        // Create top-level data
        final PVAStructure data = new PVAStructure("demo", "NTBogus",
                                                   new PVADouble("value", 3.14),
                                                   time,
                                                   alarm,
                                                   new PVAInt("extra", 42));

        System.out.println("Type Description:");
        System.out.println(data.formatType());

        System.out.println("\nValue:");
        System.out.println(data);

        System.out.println("\nElements:");
        final PVADouble value = data.get("value");
        System.out.println(value);
        assertThat(value, not(nullValue()));
        assertThat(value.get(), equalTo(3.14));

        int idx = data.getIndex(value);
        System.out.println("Index: " + idx);
        assertThat(idx, equalTo(1));

        final PVAInt extra = data.get("extra");
        System.out.println(extra);
        assertThat(extra.get(), equalTo(42));
        idx = data.getIndex(extra);
        System.out.println("Index: " + idx);
        assertThat(idx, equalTo(10));

        System.out.println("\nIndexed access:");
        int i = 0;
        PVAData sub = data.get(i);
        System.out.println("Index " + i + ": " + sub.getName());
        assertThat(sub, equalTo(data));
        idx = data.getIndex(sub);
        System.out.println("Index lookup: " + idx);
        assertThat(idx, equalTo(i));

        i = 1;
        sub = data.get(i);
        System.out.println("Index " + i + ": " + sub.getName());
        assertThat(sub.getName(), equalTo("value"));
        idx = data.getIndex(sub);
        System.out.println("Index lookup: " + idx);
        assertThat(idx, equalTo(i));

        i = 2;
        sub = data.get(i);
        System.out.println("Index " + i + ": " + sub.getName());
        assertThat(sub.getName(), equalTo("timeStamp"));
        idx = data.getIndex(sub);
        System.out.println("Index lookup: " + idx);
        assertThat(idx, equalTo(i));

        i = 3;
        sub = data.get(i);
        System.out.println("Index " + i + ": " + sub.getName());
        assertThat(sub.getName(), equalTo("secondsPastEpoch"));
        idx = data.getIndex(sub);
        System.out.println("Index lookup: " + idx);
        assertThat(idx, equalTo(i));

        i = 10;
        sub = data.get(i);
        System.out.println("Index " + i + ": " + sub.getName());
        assertThat(sub.getName(), equalTo("extra"));
        idx = data.getIndex(sub);
        System.out.println("Index lookup: " + idx);
        assertThat(idx, equalTo(i));

        i = 11;
        sub = data.get(i);
        System.out.println("Index " + i + ": " + sub);
        assertThat(sub, nullValue());
    }
}