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
package org.netbeans.html.json.spi;

import java.util.Map;
import java.util.TreeMap;
import net.java.html.BrwsrCtx;
import org.netbeans.html.context.spi.Contexts;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import org.testng.annotations.Test;


public class ProtoTest implements Technology.BatchInit<Object> {

    private Object applyBindings;
    private FunctionBinding[] functions;
    private PropertyBinding[] properties;
    private Object valueHasMutatedData;
    private String valueHasMutatedName;

    public ProtoTest() {
    }

    @Override
    public Object wrapModel(Object model, PropertyBinding[] propArr, FunctionBinding[] funcArr) {
        this.properties = propArr;
        this.functions = funcArr;
        return model;
    }

    @Override
    public Object wrapModel(Object model) {
        throw new UnsupportedOperationException("wrapModel");
    }

    @Override
    public <M> M toModel(Class<M> modelClass, Object data) {
        throw new UnsupportedOperationException("toModel");
    }

    @Override
    public void bind(PropertyBinding b, Object model, Object data) {
        throw new UnsupportedOperationException("bind");
    }

    @Override
    public void valueHasMutated(Object data, String propertyName) {
        this.valueHasMutatedData = data;
        this.valueHasMutatedName = propertyName;
    }

    @Override
    public void expose(FunctionBinding fb, Object model, Object d) {
        throw new UnsupportedOperationException("expose");
    }

    @Override
    public void applyBindings(Object data) {
        this.applyBindings = data;
    }

    @Override
    public Object wrapArray(Object[] arr) {
        throw new UnsupportedOperationException("wrapArray");
    }

    @Override
    @Deprecated
    public void runSafe(Runnable r) {
        throw new UnsupportedOperationException("runSafe");
    }

    static final class MyObj {
        final Proto proto;
        final Map<Integer, Object> values;

        MyObj(MyType type, BrwsrCtx ctx) {
            this.proto = type.createProto(this, ctx);
            this.values = new TreeMap<Integer, Object>();
        }
    }

    static final class MyType extends Proto.Type<MyObj> {

        MyType() {
            super(MyObj.class, MyObj.class, 0, 0);
        }



        @Override
        protected void setValue(MyObj model, int index, Object value) {
            model.values.put(index, value);
        }

        @Override
        protected Object getValue(MyObj model, int index) {
            return model.values.get(index);
        }

        @Override
        protected void call(MyObj model, int index, Object data, Object event) throws Exception {
            throw new UnsupportedOperationException("call");
        }

        @Override
        protected MyObj cloneTo(MyObj model, BrwsrCtx ctx) {
            throw new UnsupportedOperationException("cloneTo");
        }

        @Override
        protected MyObj read(BrwsrCtx c, Object json) {
            throw new UnsupportedOperationException("read");
        }

        @Override
        protected void onChange(MyObj model, int index) {
            throw new UnsupportedOperationException("onChange");
        }

        @Override
        protected Proto protoFor(Object object) {
            if (object instanceof MyObj) {
                return ((MyObj) object).proto;
            }
            return null;
        }
    }

    @Test
    public void registerPropertiesIncrementally() {
        BrwsrCtx ctx = Contexts.newBuilder().register(Technology.class, this, 100).build();
        MyType type = new MyType();
        MyObj obj = new MyObj(type, ctx);
        type.registerProperty("33", 1, false, false);
        type.registerProperty("44", 2, false, false);

        obj.proto.applyBindings();

        assertEquals(properties.length, 3, "Three properties");
        assertNull(properties[0], "First one is empty");
        assertNotNull(properties[1]);
        assertNotNull(properties[2]);

        type.setValue(obj, 1, "zero");
        type.setValue(obj, 2, "one");

        Object zero = type.getValue(obj, 1);
        Object one = type.getValue(obj, 2);

        assertEquals(zero, "zero");
        assertEquals(one, "one");

        assertNull(valueHasMutatedName);
        obj.values.put(1, "nil");
        obj.proto.valueHasMutated("33", "zero", "nil");
        assertEquals(valueHasMutatedName, "33");
        assertEquals(valueHasMutatedData, obj);

        Object nil = type.getValue(obj, 1);
        assertEquals(nil, "nil");
    }

    @Test
    public void registerFunctionsIncrementally() {
        BrwsrCtx ctx = Contexts.newBuilder().register(Technology.class, this, 100).build();
        MyType type = new MyType();
        MyObj obj = new MyObj(type, ctx);
        type.registerFunction("fifth", 5);

        obj.proto.applyBindings();

        assertEquals(6, functions.length);
        assertNull(functions[0]);
        assertNull(functions[1]);
        assertNull(functions[2]);
        assertNull(functions[3]);
        assertNull(functions[4]);
        assertNotNull(functions[5]);
    }
}
