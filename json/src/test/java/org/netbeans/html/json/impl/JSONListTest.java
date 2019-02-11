/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.netbeans.html.json.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.java.html.BrwsrCtx;
import net.java.html.json.Model;
import net.java.html.json.Models;
import net.java.html.json.People;
import net.java.html.json.Person;
import net.java.html.json.Property;
import net.java.html.json.Sex;
import org.netbeans.html.context.spi.Contexts;
import org.netbeans.html.json.spi.FunctionBinding;
import org.netbeans.html.json.spi.PropertyBinding;
import org.netbeans.html.json.spi.Technology;
import static org.testng.Assert.*;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 *
 * @author Jaroslav Tulach
 */
@Model(className = "JSNLst", properties = {
    @Property(name = "names", type = String.class, array = true)
})
public class JSONListTest implements Technology<Object> {
    private boolean replaceArray;
    private final Map<String,PropertyBinding> bindings = new HashMap<String,PropertyBinding>();
    private final List<String> changed = new ArrayList<String>();
    
    public JSONListTest() {
    }
    
    @BeforeMethod public void clear() {
        replaceArray = false;
        changed.clear();
    }

    @Test public void testConvertorOnAnObject() {
        BrwsrCtx c = Contexts.newBuilder().register(Technology.class, this, 1).build();
        
        Person p = Models.bind(new Person(), c);
        p.setFirstName("1");
        p.setLastName("2");
        p.setSex(Sex.MALE);

        Object real = Models.toRaw(p);
        assertEquals(this, real, "I am the right model");
    }
    
    @Test public void testConvertorOnAnArray() {
        BrwsrCtx c = Contexts.newBuilder().register(Technology.class, this, 1).build();
        
        Person p = Models.bind(new Person(), c);
        p.setFirstName("1");
        p.setLastName("2");
        p.setSex(Sex.MALE);
        
        People people = Models.bind(new People(p), c).applyBindings();
        assertEquals(people.getInfo().toString(), "[{\"firstName\":\"1\",\"lastName\":\"2\",\"sex\":\"MALE\"}]", "Converted to real JSON");
        
        PropertyBinding pb = bindings.get("info");
        assertNotNull(pb, "Binding for info found");
        
        Object real = pb.getValue();
        assertTrue(real instanceof Object[], "It is an array: " + real);
        Object[] arr = (Object[])real;
        assertEquals(arr.length, 1, "Size is one");
        assertEquals(this, arr[0], "I am the right model");
    }
    
    @Test public void testNicknames() {
        BrwsrCtx c = Contexts.newBuilder().register(Technology.class, this, 1).build();
        
        People people = Models.bind(new People(), c).applyBindings();
        people.getNicknames().add("One");
        people.getNicknames().add("Two");
        
        PropertyBinding pb = bindings.get("nicknames");
        assertNotNull(pb, "Binding for info found");
        
        Object real = pb.getValue();
        assertTrue(real instanceof Object[], "It is an array: " + real);
        Object[] arr = (Object[])real;
        assertEquals(arr.length, 2, "Length two");
        assertEquals(arr[0], "One", "Text should be in the model");
        assertEquals(arr[1], "Two", "2nd text in the model");
    }
    
    @Test public void testConvertorOnAnArrayWithWrapper() {
        this.replaceArray = true;
        BrwsrCtx c = Contexts.newBuilder().register(Technology.class, this, 1).build();
        
        Person p = Models.bind(new Person(), c);
        p.setFirstName("1");
        p.setLastName("2");
        p.setSex(Sex.MALE);
        
        People people = Models.bind(new People(), c).applyBindings();
        people.getInfo().add(p);
        
        Object real = JSON.find(people.getInfo());
        assertEquals(real, this, "I am the model of the array");
    }

    @Test public void bindingsOnArray() {
        this.replaceArray = true;
        BrwsrCtx c = Contexts.newBuilder().register(Technology.class, this, 1).build();
        
        People p = Models.bind(new People(), c).applyBindings();
        p.getAge().add(30);
        
        PropertyBinding pb = bindings.get("age");
        assertNotNull(pb, "There is a binding for age list");
        
        assertEquals(pb.getValue(), this, "I am the model of the array");
    }
    
    @Test public void toStringOnArrayOfStrings() {
        JSNLst l = new JSNLst("Jarda", "Jirka", "Parda");
        assertEquals(l.toString(), "{\"names\":[\"Jarda\",\"Jirka\",\"Parda\"]}", "Properly quoted");
    }

    @Test public void testChangeOnProps() {
        BrwsrCtx c = Contexts.newBuilder().register(Technology.class, this, 1).build();

        assertTrue(changed.isEmpty());
        
        People p = Models.bind(new People(), c).applyBindings();
        p.getAge().add(42);

        assertEquals(sum(p.getAge()), 42);
        assertFalse(changed.isEmpty());
        changed.clear();

        List<Integer> vals = new ArrayList<Integer>();
        vals.add(12);
        vals.add(30);
        ((JSONList<Integer>)p.getAge()).fastReplace(vals);

        assertEquals(changed.size(), 1, "One change");
        assertEquals(changed.get(0), "age", "One change");
        assertEquals(sum(p.getAge()), 42);
    }

    private static int sum(List<Integer> arr) {
        int sum = 0;
        for (Integer i : arr) {
            sum += i;
        }
        return sum;
    }

    @Override
    public Object wrapModel(Object model) {
        return this;
    }

    @Override
    public void bind(PropertyBinding b, Object model, Object data) {
        bindings.put(b.getPropertyName(), b);
    }

    @Override
    public void valueHasMutated(Object data, String propertyName) {
        changed.add(propertyName);
    }

    @Override
    public void expose(FunctionBinding fb, Object model, Object d) {
    }

    @Override
    public void applyBindings(Object data) {
    }

    @Override
    public Object wrapArray(Object[] arr) {
        return replaceArray ? this : arr;
    }

    @Override
    public <M> M toModel(Class<M> modelClass, Object data) {
        return modelClass.cast(data);
    }

    @Override
    @Deprecated
    public void runSafe(Runnable r) {
        throw new UnsupportedOperationException("Not supported yet.");
    }
}
