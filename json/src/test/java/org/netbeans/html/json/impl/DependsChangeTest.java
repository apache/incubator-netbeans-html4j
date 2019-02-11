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

import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.Map;
import net.java.html.BrwsrCtx;
import net.java.html.json.ComputedProperty;
import net.java.html.json.Model;
import net.java.html.json.Models;
import net.java.html.json.Property;
import org.netbeans.html.context.spi.Contexts;
import org.netbeans.html.json.spi.FunctionBinding;
import org.netbeans.html.json.spi.JSONCall;
import org.netbeans.html.json.spi.PropertyBinding;
import org.netbeans.html.json.spi.Technology;
import org.netbeans.html.json.spi.Transfer;
import static org.testng.Assert.*;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 *
 * @author Jaroslav Tulach
 */
public class DependsChangeTest {
    private MapTechnology t;
    private BrwsrCtx c;

    @BeforeMethod public void initTechnology() {
        t = new MapTechnology();
        c = Contexts.newBuilder().register(Technology.class, t, 1).
            register(Transfer.class, t, 1).build();
    }

    @Model(className = "Depends", instance = true, properties = {
        @Property(name = "value", type = int.class),
        @Property(name = "next", type = Depends.class),
    })
    static class DependsCntrl {
        @ComputedProperty @Transitive(deep = true)
        static int sumPositive(Depends next, int value) {
            while (next != null && next.getValue() > 0) {
                value += next.getValue();
                next = next.getNext();
            }
            return value;
        }
    }

    @Test
    public void disappearModel() throws Exception {
        Depends p = Models.bind(
            new Depends(10, new Depends(20, new Depends(30, null))
        ), c);

        Depends refStrong = disappearModelOperations(p);

        Reference<Object> ref = new WeakReference<Object>(p);
        p = null;
        assertGC(ref, "MyOverall can now disappear");
        assertNotNull(refStrong, "Submodel still used");
    }

    private Depends disappearModelOperations(Depends p) throws InvocationTargetException, IllegalAccessException, IllegalArgumentException {
        Models.applyBindings(p);
        Map m = (Map)Models.toRaw(p);
        Object v = m.get("sumPositive");
        assertNotNull(v, "Value should be in the map");
        assertEquals(v.getClass(), One.class, "It is instance of One");
        One o = (One)v;
        assertEquals(o.changes, 0, "No changes so far");
        assertTrue(o.pb.isReadOnly(), "Derived property");
        assertEquals(o.get(), 60);
        Depends refStrong = p.getNext().getNext();
        p.getNext().setNext(null);
        assertEquals(o.changes, 1, "Change in sum");
        assertEquals(o.get(), 30);
        return refStrong;
    }

    static final class One {

        int changes;
        final PropertyBinding pb;
        final FunctionBinding fb;

        One(Object m, PropertyBinding pb) throws NoSuchMethodException {
            this.pb = pb;
            this.fb = null;
        }

        One(Object m, FunctionBinding fb) throws NoSuchMethodException {
            this.pb = null;
            this.fb = fb;
        }

        Object get() throws IllegalAccessException, IllegalArgumentException, InvocationTargetException {
            return pb.getValue();
        }

        void set(Object v) throws IllegalAccessException, IllegalArgumentException, InvocationTargetException {
            pb.setValue(v);
        }

        void assertNoChange(String msg) {
            assertEquals(changes, 0, msg);
        }

        void assertChange(String msg) {
            if (changes == 0) {
                fail(msg);
            }
            changes = 0;
        }
    }

    static final class MapTechnology
            implements Technology<Map<String, One>>, Transfer {

        @Override
        public Map<String, One> wrapModel(Object model) {
            return new HashMap<String, One>();
        }

        @Override
        public void valueHasMutated(Map<String, One> data, String propertyName) {
            One p = data.get(propertyName);
            if (p != null) {
                p.changes++;
            }
        }

        @Override
        public void bind(PropertyBinding b, Object model, Map<String, One> data) {
            try {
                One o = new One(model, b);
                data.put(b.getPropertyName(), o);
            } catch (NoSuchMethodException ex) {
                throw new IllegalStateException(ex);
            }
        }

        @Override
        public void expose(FunctionBinding fb, Object model, Map<String, One> data) {
            try {
                data.put(fb.getFunctionName(), new One(model, fb));
            } catch (NoSuchMethodException ex) {
                throw new IllegalStateException(ex);
            }
        }

        @Override
        public void applyBindings(Map<String, One> data) {
        }

        @Override
        public Object wrapArray(Object[] arr) {
            return arr;
        }

        @Override
        public void extract(Object obj, String[] props, Object[] values) {
            Map<?, ?> map = obj instanceof Map ? (Map<?, ?>) obj : null;
            for (int i = 0; i < Math.min(props.length, values.length); i++) {
                if (map == null) {
                    values[i] = null;
                } else {
                    values[i] = map.get(props[i]);
                    if (values[i] instanceof One) {
                        values[i] = ((One) values[i]).pb.getValue();
                    }
                }
            }
        }

        @Override
        public void loadJSON(JSONCall call) {
            call.notifyError(new UnsupportedOperationException());
        }

        @Override
        public <M> M toModel(Class<M> modelClass, Object data) {
            return modelClass.cast(data);
        }

        @Override
        public Object toJSON(InputStream is) throws IOException {
            throw new IOException();
        }

        @Override
        @Deprecated
        public void runSafe(Runnable r) {
            throw new UnsupportedOperationException("Not supported yet.");
        }
    }

    private static void assertGC(Reference<?> ref, String msg) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (isGone(ref)) {
                return;
            }
            try {
                System.gc();
                System.runFinalization();
            } catch (Error err) {
                err.printStackTrace();
            }
        }
        throw new InterruptedException(msg);
    }

    private static boolean isGone(Reference<?> ref) {
        return ref.get() == null;
    }

}
