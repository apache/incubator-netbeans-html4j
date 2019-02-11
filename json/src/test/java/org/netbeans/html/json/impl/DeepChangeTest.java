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
import java.util.List;
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
public class DeepChangeTest {
    private MapTechnology t;
    private BrwsrCtx c;

    @BeforeMethod public void initTechnology() {
        t = new MapTechnology();
        c = Contexts.newBuilder().register(Technology.class, t, 1).
            register(Transfer.class, t, 1).build();
    }
    
    @Model(className = "MyX", targetId = "anythingX", properties = {
        @Property(name = "one", type = MyY.class),
        @Property(name = "all", type = MyY.class, array = true)
    })
    static class X {
        @ComputedProperty @Transitive(deep = true)
        static MyY oneCopy(MyY one) {
            return Models.bind(one, BrwsrCtx.findDefault(X.class));
        }
        @ComputedProperty @Transitive(deep = true) 
        static String oneName(MyY one) {
            return one.getValue();
        }
        @ComputedProperty
        static String sndName(MyY one) {
            if (one == null || one.getValue() == null) {
                return null;
            } else {
                return one.getValue().toUpperCase();
            }
        }
        @ComputedProperty @Transitive(deep = false) 
        static String noName(MyY one) {
            if (one == null || one.getValue() == null) {
                return null;
            } else {
                return one.getValue().toUpperCase();
            }
        }
        @ComputedProperty @Transitive(deep = true) 
        static String thrdName(MyY one) {
            return "X" + one.getCount();
        }
        
        @ComputedProperty
        static String allNames(List<MyY> all) {
            StringBuilder sb = new StringBuilder();
            for (MyY y : all) {
                sb.append(y.getValue());
            }
            return sb.toString();
        }

        @ComputedProperty @Transitive(deep = true)
        static String firstFromNames(List<MyY> all) {
            for (MyY y : all) {
                if (y != null && y.getValue() != null) {
                    return y.getValue();
                }
            }
            return null;
        }
    }
    @Model(className = "MyY", properties = {
        @Property(name = "value", type = String.class),
        @Property(name = "count", type = int.class)
    })
    static class Y {
    }
    @Model(className = "MyOverall", properties = {
        @Property(name = "x", type = MyX.class)
    })
    static class Overall {
        @ComputedProperty @Transitive(deep = true) 
        static String valueAccross(MyX x) {
            return x.getFirstFromNames();
        }
    }
    
    @Test public void isTransitiveChangeNotifiedProperly() throws Exception {
        MyX p = Models.bind(
            new MyX(new MyY("Ahoj", 0), new MyY("Hi", 333), new MyY("Hello", 999)
        ), c).applyBindings();
        
        Map m = (Map)Models.toRaw(p);
        Object v = m.get("oneName");
        assertNotNull(v, "Value should be in the map");
        assertEquals(v.getClass(), One.class, "It is instance of One");
        One o = (One)v;
        assertEquals(o.changes, 0, "No changes so far");
        assertTrue(o.pb.isReadOnly(), "Derived property");
        assertEquals(o.get(), "Ahoj");

        p.getOne().setValue("Nazdar");
        
        assertEquals(o.get(), "Nazdar");
        assertEquals(o.changes, 1, "One change so far");
    }
    
    @Test public void isTransitiveChangeInArrayNotifiedProperly() throws Exception {
        MyX p = Models.bind(
            new MyX(new MyY("Ahoj", 0), new MyY("Hi", 333), new MyY("Hello", 999)
        ), c).applyBindings();
        
        Map m = (Map)Models.toRaw(p);
        Object v = m.get("allNames");
        assertNotNull(v, "Value should be in the map");
        assertEquals(v.getClass(), One.class, "It is instance of One");
        One o = (One)v;
        assertEquals(o.changes, 0, "No changes so far");
        assertTrue(o.pb.isReadOnly(), "Derived property");
        assertEquals(o.get(), "HiHello");

        p.getAll().get(0).setValue("Nazdar");
        
        assertEquals(o.get(), "NazdarHello");
        assertEquals(o.changes, 1, "One change so far");
    }

    @Test public void changingModelClass() throws Exception {
        final MyY myY = new MyY("Ahoj", 0);
        MyX p = Models.bind(
            new MyX(myY, new MyY("Hi", 333), new MyY("Hello", 999)),
            c)
        .applyBindings();
        MyY realY = p.getOne();

        Map m = (Map)Models.toRaw(p);
        Object v = m.get("one");
        assertNotNull(v, "Value should be in the map");
        assertEquals(v.getClass(), One.class, "It is instance of One");
        One o = (One)v;
        assertEquals(o.changes, 0, "No changes so far");
        assertFalse(o.pb.isReadOnly(), "Normal property");

        final MyY newY = new MyY("Hi", 1);
        p.setOne(newY);

        assertSame(p.getOne(), newY);
        assertEquals(o.changes, 1, "One change");
    }

    @Test public void addingIntoArray() throws Exception {
        MyX p = Models.bind(
            new MyX(new MyY("Ahoj", 0), new MyY("Hi", 333), new MyY("Hello", 999)
        ), c).applyBindings();
        
        Map m = (Map)Models.toRaw(p);
        Object v = m.get("allNames");
        assertNotNull(v, "Value should be in the map");
        assertEquals(v.getClass(), One.class, "It is instance of One");
        One o = (One)v;
        assertEquals(o.changes, 0, "No changes so far");
        assertTrue(o.pb.isReadOnly(), "Derived property");
        assertEquals(o.get(), "HiHello");

        MyY y = new MyY("Cus", 1);
        p.getAll().add(y);
        
        assertEquals(o.changes, 1, "One change so far");
        assertEquals(o.get(), "HiHelloCus");
        
        y.setValue("Nazdar");
        
        assertEquals(o.changes, 2, "2nd change so far");
        assertEquals(o.get(), "HiHelloNazdar");
        
        y.setValue("Zdravim");

        assertEquals(o.changes, 3, "3rd change so far");
        assertEquals(o.get(), "HiHelloZdravim");
    }
    
    @Test public void firstChangeInArrayNotifiedProperly() throws Exception {
        MyX p = Models.bind(
            new MyX(new MyY("Ahoj", 0), new MyY("Hi", 333), new MyY("Hello", 999)
        ), c).applyBindings();
        
        Map m = (Map)Models.toRaw(p);
        Object v = m.get("firstFromNames");
        assertNotNull(v, "Value should be in the map");
        assertEquals(v.getClass(), One.class, "It is instance of One");
        One o = (One)v;
        assertEquals(o.changes, 0, "No changes so far");
        assertTrue(o.pb.isReadOnly(), "Derived property");
        assertEquals(o.get(), "Hi");

        p.getAll().get(0).setValue("Nazdar");
        
        assertEquals(o.get(), "Nazdar");
        assertEquals(o.changes, 1, "One change so far");
    }
    
    @Test public void firstChangeInArrayToNull() throws Exception {
        MyX p = Models.bind(
            new MyX(new MyY("Ahoj", 0), new MyY("Hi", 333), new MyY("Hello", 999)
        ), c).applyBindings();
        
        Map m = (Map)Models.toRaw(p);
        Object v = m.get("firstFromNames");
        assertNotNull(v, "Value should be in the map");
        assertEquals(v.getClass(), One.class, "It is instance of One");
        One o = (One)v;
        assertEquals(o.changes, 0, "No changes so far");
        assertTrue(o.pb.isReadOnly(), "Derived property");
        assertEquals(o.get(), "Hi");

        p.getAll().get(0).setValue(null);
        
        assertEquals(o.get(), "Hello");
        assertEquals(o.changes, 1, "One change so far");
        
        p.getAll().get(0).setValue("Nazdar");

        assertEquals(o.get(), "Nazdar");
        assertEquals(o.changes, 2, "2nd change so far");
    }
    
    @Test public void firstChangeInArrayNotifiedTransitively() throws Exception {
        MyOverall p = Models.bind(
            new MyOverall(new MyX(new MyY("Ahoj", 0), new MyY("Hi", 333), new MyY("Hello", 999))
        ), c);
        Models.applyBindings(p);
        
        Map m = (Map)Models.toRaw(p);
        Object v = m.get("valueAccross");
        assertNotNull(v, "Value should be in the map");
        assertEquals(v.getClass(), One.class, "It is instance of One");
        One o = (One)v;
        assertEquals(o.changes, 0, "No changes so far");
        assertTrue(o.pb.isReadOnly(), "Derived property");
        assertEquals(o.get(), "Hi");

        final List<MyY> all = p.getX().getAll();
        MyY refStrong = all.get(0);
        Reference<MyY> ref = new WeakReference<MyY>(refStrong);
        refStrong.setValue("Nazdar");
        
        assertEquals(o.get(), "Nazdar");
        assertEquals(o.changes, 1, "One change so far");

        final MyY hi = Models.bind(new MyY("Ciao", 33), c);
        all.set(0, hi);

        assertEquals(o.changes, 2, "Second change");
        assertEquals(o.get(), "Ciao");

        refStrong.setValue("Ignore");
        assertEquals(o.changes, 2, "Still two changes");

        refStrong = null;
        assertGC(ref, "Original MyY can now disappear");
    }

    @Test
    public void disappearModel() throws Exception {
        MyOverall p = Models.bind(
            new MyOverall(new MyX(new MyY("Ahoj", 0), new MyY("Hi", 333), new MyY("Hello", 999))
        ), c);

        MyY refStrong = disappearModelOperations(p);

        Reference<MyOverall> ref = new WeakReference<MyOverall>(p);
        p = null;
        assertGC(ref, "MyOverall can now disappear");
        assertNotNull(refStrong, "Submodel still used");
    }

    private MyY disappearModelOperations(MyOverall p) throws InvocationTargetException, IllegalAccessException, IllegalArgumentException {
        Models.applyBindings(p);
        Map m = (Map)Models.toRaw(p);
        Object v = m.get("valueAccross");
        assertNotNull(v, "Value should be in the map");
        assertEquals(v.getClass(), One.class, "It is instance of One");
        One o = (One)v;
        assertEquals(o.changes, 0, "No changes so far");
        assertTrue(o.pb.isReadOnly(), "Derived property");
        assertEquals(o.get(), "Hi");
        final List<MyY> all = p.getX().getAll();
        MyY refStrong = all.get(0);
        refStrong.setValue("Nazdar");
        assertEquals(o.get(), "Nazdar");
        assertEquals(o.changes, 1, "One change so far");
        all.clear();
        assertEquals(o.changes, 2, "Second change");
        assertNull(o.get(), "MyY array is empty now");
        refStrong.setValue("Ignore");
        assertEquals(o.changes, 2, "Still two changes");
        return refStrong;
    }
    
    @Test public void secondChangeInArrayIgnored() throws Exception {
        MyX p = Models.bind(
            new MyX(new MyY("Ahoj", 0), new MyY("Hi", 333), new MyY("Hello", 999)
        ), c).applyBindings();
        
        Map m = (Map)Models.toRaw(p);
        Object v = m.get("firstFromNames");
        assertNotNull(v, "Value should be in the map");
        assertEquals(v.getClass(), One.class, "It is instance of One");
        One o = (One)v;
        assertEquals(o.changes, 0, "No changes so far");
        assertTrue(o.pb.isReadOnly(), "Derived property");
        assertEquals(o.get(), "Hi");

        p.getAll().get(1).setValue("Nazdar");
        
        assertEquals(o.get(), "Hi");
        assertEquals(o.changes, 0, "No change so far");
    }
    
    @Test public void changeInArraySizeNeedsToBeRecomputed() throws Exception {
        MyX p = Models.bind(
            new MyX(new MyY("Ahoj", 0), new MyY("Hi", 333), new MyY("Hello", 999)
        ), c).applyBindings();
        
        Map m = (Map)Models.toRaw(p);
        Object v = m.get("firstFromNames");
        assertNotNull(v, "Value should be in the map");
        assertEquals(v.getClass(), One.class, "It is instance of One");
        One o = (One)v;
        assertEquals(o.changes, 0, "No changes so far");
        assertTrue(o.pb.isReadOnly(), "Derived property");
        assertEquals(o.get(), "Hi");

        p.getAll().remove(1);
        
        assertEquals(o.get(), "Hi");
        assertEquals(o.changes, 1, "This required a change");
    }
    
    @Test public void doublePropertyChangeNotified() throws Exception {
        MyX p = Models.bind(
            new MyX(new MyY("Ahoj", 0), new MyY("Hi", 333), new MyY("Hello", 999)
        ), c).applyBindings();
        
        Map m = (Map)Models.toRaw(p);
        Object v = m.get("oneName");
        assertNotNull(v, "Value should be in the map");
        Object v2 = m.get("sndName");
        assertNotNull(v2, "Value2 should be in the map");
        One o = (One)v;
        One o2 = (One)v2;
        assertEquals(o.changes, 0, "No changes so far");
        assertEquals(o2.changes, 0, "No changes so far");
        assertTrue(o.pb.isReadOnly(), "Derived property");
        assertEquals(o.get(), "Ahoj");
        assertEquals(o2.get(), "AHOJ");

        p.getOne().setValue("Nazdar");
        
        assertEquals(o.get(), "Nazdar");
        assertEquals(o.changes, 1, "One change so far");
        assertEquals(o2.changes, 1, "One change so far");
        assertEquals(o2.get(), "NAZDAR");
    }
    
    @Test public void onlyAffectedPropertyChangeNotified() throws Exception {
        MyX p = Models.bind(
            new MyX(new MyY("Ahoj", 0), new MyY("Hi", 333), new MyY("Hello", 999)
        ), c).applyBindings();
        
        Map m = (Map)Models.toRaw(p);
        Object v = m.get("oneName");
        assertNotNull(v, "Value should be in the map");
        Object v2 = m.get("thrdName");
        assertNotNull(v2, "Value2 should be in the map");
        One o = (One)v;
        One o2 = (One)v2;
        assertEquals(o.changes, 0, "No changes so far");
        assertEquals(o2.changes, 0, "No changes so far");
        assertTrue(o.pb.isReadOnly(), "Derived property");
        assertEquals(o.get(), "Ahoj");
        assertEquals(o2.get(), "X0");

        p.getOne().setCount(10);
        
        assertEquals(o.get(), "Ahoj");
        assertEquals(o.changes, 0, "Still no change");
        assertEquals(o2.changes, 1, "One change so far");
        assertEquals(o2.get(), "X10");
    }
    
    @Test public void onlyDeepPropsAreNotified() throws Exception {
        MyX p = Models.bind(
            new MyX(new MyY("Ahoj", 0), new MyY("Hi", 333), new MyY("Hello", 999)
        ), c).applyBindings();
        
        Map m = (Map)Models.toRaw(p);
        Object v = m.get("oneName");
        assertNotNull(v, "Value should be in the map");
        Object v2 = m.get("noName");
        assertNotNull(v2, "Value2 should be in the map");
        One o = (One)v;
        One o2 = (One)v2;
        assertEquals(o.changes, 0, "No changes so far");
        assertEquals(o2.changes, 0, "No changes so far");
        assertTrue(o.pb.isReadOnly(), "Derived property");
        assertEquals(o.get(), "Ahoj");
        assertEquals(o2.get(), "AHOJ");

        p.getOne().setValue("Nazdar");
        
        assertEquals(o.get(), "Nazdar");
        assertEquals(o.changes, 1, "One change so far");
        assertEquals(o2.changes, 0, "This change is not noticed");
        assertEquals(o2.get(), "NAZDAR", "but property value changes when computed");
    }

    @Test
    public void mixingContextsIsOK() throws Exception {
        BrwsrCtx ctx = Contexts.newBuilder().build();
        final MyY one = Models.bind(new MyY("Ahoj", 0), ctx);
        MyX p = Models.bind(
            new MyX(one, new MyY("Hi", 333), new MyY("Hello", 999)), c
        ).applyBindings();

        Map m = (Map) Models.toRaw(p);
        Object v = m.get("oneName");
        assertNotNull(v, "Value should be in the map");
        One o = (One) v;
        assertEquals(o.get(), "Ahoj");

        p.getOne().setValue("Nazdar");

        assertEquals(o.get(), "Nazdar");
        assertEquals(o.changes, 1, "One change so far");
    }
    
    @Test
    public void rebindReplacesTheInstance() throws Exception {
        BrwsrCtx ctx = Contexts.newBuilder().build();
        MyX x = new MyX();

        MyY y = Models.bind(new MyY(), ctx);
        x.setOne(y);

        assertSame(x.getOne(), y);
    }

    @Test
    public void rebindReplacesTheInstanceAndNotifies() throws Exception {
        BrwsrCtx ctx = Contexts.newBuilder().build();
        final MyY one = Models.bind(new MyY(), ctx);
        MyX p = Models.bind(
            new MyX(one, new MyY("Hi", 333), new MyY("Hello", 999)), c
        ).applyBindings();

        Map m = (Map) Models.toRaw(p);
        
        Object v = m.get("one");
        assertNotNull(v, "Value should be in the map");
        One o = (One) v;
        assertEquals(o.changes, 0, "No changes yet");

        MyY y = Models.bind(new MyY(), ctx);
        p.setOne(y);

        assertSame(p.getOne(), y);
        assertSame(o.changes, 1, "One change now");
    }

    @Test
    public void mixingWithCloneIsOK() throws Exception {
        BrwsrCtx ctx = Contexts.newBuilder().build();
        final MyY one = Models.bind(new MyY("Ahoj", 0), ctx);
        MyX p = Models.bind(new MyX(one, new MyY("Hi", 333), new MyY("Hello", 999)
        ), c).applyBindings();

        Map m = (Map) Models.toRaw(p);
        Object v = m.get("oneCopy");
        assertNotNull(v, "Value should be in the map");
        One o = (One) v;
        assertEquals(((MyY)o.get()).getValue(), "Ahoj");

        p.getOne().setValue("Nazdar");

        assertEquals(((MyY)o.get()).getValue(), "Nazdar");
        assertEquals(o.changes, 1, "One change so far");
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
