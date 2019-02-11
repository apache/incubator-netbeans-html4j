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
package net.java.html.json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.concurrent.Executor;
import net.java.html.BrwsrCtx;
import org.netbeans.html.context.spi.Contexts;
import org.netbeans.html.json.spi.FunctionBinding;
import org.netbeans.html.json.spi.PropertyBinding;
import org.netbeans.html.json.spi.Technology;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 *
 * @author Jaroslav Tulach
 */
@Model(className = "Modelik", builder = "change", targetId = "", properties = {
    @Property(name = "value", type = int.class),
    @Property(name = "count", type = int.class),
    @Property(name = "unrelated", type = long.class),
    @Property(name = "names", type = String.class, array = true),
    @Property(name = "values", type = int.class, array = true),
    @Property(name = "people", type = Person.class, array = true),
    @Property(name = "changedProperty", type=String.class)
})
public class ModelTest {
    private MockTechnology my;
    private Modelik model;
    private static Modelik leakedModel;

    @BeforeMethod
    public void createModel() {
        my = new MockTechnology();
        final BrwsrCtx c = Contexts.newBuilder().register(Technology.class, my, 1).build();
        model = Models.bind(new Modelik(), c);
    }

    @Test public void classGeneratedWithSetterGetter() {
        model.setValue(10);
        assertEquals(10, model.getValue(), "Value changed");
    }

    @Test public void computedMethod() {
        model.setValue(4);
        assertEquals(16, model.getPowerValue());
    }

    @Test public void equalsAndHashCode() {
        Modelik m1 = new Modelik();
        m1.setValue(10);
        m1.setCount(20);
        m1.setUnrelated(30);
        m1.setChangedProperty("changed");
        m1.getNames().add("firstName");
        Modelik m2 = new Modelik().
            changeValue(10).
            changeCount(20).
            changeUnrelated(30).
            changeChangedProperty("changed").
            changeNames("firstName");

        assertTrue(m1.equals(m2), "They are the same");
        assertEquals(m1.hashCode(), m2.hashCode(), "Hashcode is the same");

        m1.setCount(33);

        assertFalse(m1.equals(m2), "No longer the same");
        assertFalse(m1.hashCode() == m2.hashCode(), "No longe is hashcode is the same");
    }

    @Test public void arrayIsMutable() {
        assertEquals(model.getNames().size(), 0, "Is empty");
        model.getNames().add("Jarda");
        assertEquals(model.getNames().size(), 1, "One element");
    }

    @Test public void arrayChangesNotNotifiedUntilInitied() {
        model.getNames().add("Hello");
        assertTrue(my.mutated.isEmpty(), "No change now " + my.mutated);
        model.getNames().remove("Hello");
        assertTrue(my.mutated.isEmpty(), "No change still " + my.mutated);
        assertTrue(model.getNames().isEmpty(), "No empty");
    }

    @Test public void arrayChangesNotified() {
        Models.applyBindings(model);
        model.getNames().add("Hello");

        assertFalse(my.mutated.isEmpty(), "There was a change" + my.mutated);
        assertTrue(my.mutated.contains("names"), "Change in names property: " + my.mutated);

        my.mutated.clear();

        Iterator<String> it = model.getNames().iterator();
        assertEquals(it.next(), "Hello");
        it.remove();

        assertFalse(my.mutated.isEmpty(), "There was a change" + my.mutated);
        assertTrue(my.mutated.contains("names"), "Change in names property: " + my.mutated);

        my.mutated.clear();

        ListIterator<String> lit = model.getNames().listIterator();
        lit.add("Jarda");

        assertFalse(my.mutated.isEmpty(), "There was a change" + my.mutated);
        assertTrue(my.mutated.contains("names"), "Change in names property: " + my.mutated);
    }

    @Test public void autoboxedArray() {
        model.getValues().add(10);

        assertEquals(model.getValues().get(0), Integer.valueOf(10), "Really ten");
    }

    @Test public void derivedArrayProp() {
        model.applyBindings();
        model.setCount(10);

        List<String> arr = model.getRepeat();
        assertEquals(arr.size(), 10, "Ten items: " + arr);

        my.mutated.clear();

        model.setCount(5);

        arr = model.getRepeat();
        assertEquals(arr.size(), 5, "Five items: " + arr);

        assertEquals(my.mutated.size(), 2, "Two properties changed: " + my.mutated);
        assertTrue(my.mutated.contains("repeat"), "Array is in there: " + my.mutated);
        assertTrue(my.mutated.contains("count"), "Count is in there: " + my.mutated);
    }

    @Test public void derivedArrayPropChange() {
        model.applyBindings();
        model.setCount(5);

        List<String> arr = model.getRepeat();
        assertEquals(arr.size(), 5, "Five items: " + arr);

        model.setRepeat(10);
        assertEquals(model.getCount(), 10, "Changing repeat changes count");
    }

    @Test public void derivedPropertiesAreNotified() {
        model.applyBindings();

        model.setValue(33);

        // not interested in change of this property
        my.mutated.remove("changedProperty");

        assertEquals(my.mutated.size(), 2, "Two properties changed: " + my.mutated);
        assertTrue(my.mutated.contains("powerValue"), "Power value is in there: " + my.mutated);
        assertTrue(my.mutated.contains("value"), "Simple value is in there: " + my.mutated);

        my.mutated.clear();

        model.setUnrelated(44);


        // not interested in change of this property
        my.mutated.remove("changedProperty");
        assertEquals(my.mutated.size(), 1, "One property changed: " + my.mutated);
        assertTrue(my.mutated.contains("unrelated"), "Its name is unrelated");
    }

    @Test public void computedPropertyCannotWriteToModel() {
        leakedModel = model;
        try {
            String res = model.getNotAllowedWrite();
            fail("We should not be allowed to write to the model: " + res);
        } catch (IllegalStateException ex) {
            // OK, we can't read
        }
    }

    @Test public void computedPropertyReadToModel() {
        leakedModel = model;
        String res = model.getAllowedRead();
        assertEquals("Allowed: " + model.getUnrelated(), res);
    }

    @OnReceive(url = "{protocol}://{host}?query={query}", data = Person.class, onError = "errorState")
    static void loadPeople(Modelik thiz, People p) {
        Modelik m = null;
        m.applyBindings();
        m.loadPeople("http", "apidesign.org", "query", new Person());
    }

    static void errorState(Modelik thiz, Exception ex) {

    }

    @OnReceive(url="{url}", headers={
        "Easy: {easy}",
        "H-a+r!d?e.r: {harder}",
        "H-a+r!d?e's\"t: {harder}",
        "Repeat-ed: {rep}",
        "Repeat+ed: {rep}",
        "Same-URL: {url}"
    })
    static void fetchPeopleWithHeaders(Modelik model, People p) {
        model.fetchPeopleWithHeaders("url", "easy", "harder", "rep");
    }

    @OnReceive(url = "{protocol}://{host}?callback={back}&query={query}", jsonp = "back")
    static void loadPeopleViaJSONP(Modelik thiz, People p) {
        Modelik m = null;
        m.applyBindings();
        m.loadPeopleViaJSONP("http", "apidesign.org", "query");
    }

    @OnReceive(url = "{rep}://{rep}")
    static void repeatedTest(Modelik thiz, People p) {
        thiz.repeatedTest("justOneParameterRep");
    }

    @Function
    static void doSomething() {
    }

    @ComputedProperty(write = "setPowerValue")
    static int powerValue(int value) {
        return value * value;
    }

    static void setPowerValue(Modelik m, int value) {
        m.setValue((int)Math.sqrt(value));
    }

    @OnPropertyChange({ "powerValue", "unrelated" })
    static void aPropertyChanged(Modelik m, String name) {
        m.setChangedProperty(name);
    }

    @OnPropertyChange({ "values" })
    static void anArrayPropertyChanged(String name, Modelik m) {
        m.setChangedProperty(name);
    }

    @Test public void changeAnything() {
        model.setCount(44);
        assertNull(model.getChangedProperty(), "No observed value change");
    }
    @Test public void changeValue() {
        model.setValue(33);
        assertEquals(model.getChangedProperty(), "powerValue", "power property changed");
    }
    @Test public void changePowerValue() {
        model.setValue(3);
        assertEquals(model.getPowerValue(), 9, "Square");
        model.setPowerValue(16);
        assertEquals(model.getValue(), 4, "Square root");
        assertEquals(model.getPowerValue(), 16, "Square changed");
    }
    @Test public void changeUnrelated() {
        model.setUnrelated(333);
        assertEquals(model.getChangedProperty(), "unrelated", "unrelated changed");
    }

    @Test public void changeInArray() {
        model.getValues().add(10);
        assertNull(model.getChangedProperty(), "No change before applyBindings");
        model.applyBindings();
        model.getValues().add(10);
        assertEquals(model.getChangedProperty(), "values", "Something added into the array");
    }

    @ComputedProperty
    static String allowedRead() {
        return "Allowed: " + leakedModel.getUnrelated();
    }

    @ComputedProperty
    static String notAllowedWrite() {
        leakedModel.setUnrelated(11);
        return "Not allowed callback!";
    }

    @ComputedProperty(write="parseRepeat")
    static List<String> repeat(int count) {
        return Collections.nCopies(count, "Hello");
    }
    static void parseRepeat(Modelik m, Object v) {
        m.setCount((Integer)v);
    }

    public @Test void hasPersonPropertyAndComputedFullName() {
        List<Person> arr = model.getPeople();
        assertEquals(arr.size(), 0, "By default empty");
        Person p = null;
        if (p != null) {
            String fullNameGenerated = p.getFullName();
            assertNotNull(fullNameGenerated);
        }
    }

    public @Test void computedListIsOfTypeString() {
        Person p = new Person("1st", "2nd", Sex.MALE);
        String first = p.getBothNames().get(0);
        String last = p.getBothNames().get(1);
        assertEquals(first, "1st");
        assertEquals(last, "2nd");
    }
    
    @Model(className = "Inner", instance = true, properties = {
        @Property(name = "x", type = int.class),
        @Property(name = "y", type = int.class)
    })
    static final class InnerCntrl {
        private BrwsrCtx ctx;

        @ModelOperation
        void init(Inner model, BrwsrCtx ctx) {
            this.ctx = ctx;
        }

        @Function
        void setYToTen(Inner model) {
            assertCtx();
            model.setY(10);
        }

        @ModelOperation
        void modelYToTen(Inner model) {
            assertCtx();
            model.setY(10);
        }

        @OnPropertyChange("y")
        void increment(Inner model) {
            model.setX(model.getX() + 1);
            assertCtx();
        }

        private void assertCtx() {
            BrwsrCtx realCtx = BrwsrCtx.findDefault(InnerCntrl.class);
            assertSame(realCtx, ctx, "Proper Ctx is provided");
        }
    }

//   directly using setters doesn't set the context currently
//    @Test
//    public void incrementXOnChangeOfY() {
//        doIncreementXOnChangeOfY(0);
//    }

    @Test
    public void incrementXOnChangeOfYViaFunction() {
        doIncreementXOnChangeOfY(1);
    }

    @Test
    public void incrementXOnChangeOfYViaModel() {
        doIncreementXOnChangeOfY(2);
    }

    private void doIncreementXOnChangeOfY(int modificationType) {
        class Exec implements Executor {
            int cnt;
            @Override
            public void execute(Runnable command) {
                cnt++;
                command.run();
            }

            final void assertCount(int expected, String msg) {
                assertEquals(cnt, expected, msg);
                cnt = 0;
            }
        }
        MapModelTest.MapTechnology tech = new MapModelTest.MapTechnology();
        Exec exec = new Exec();
        final BrwsrCtx c = Contexts.newBuilder().
            register(Technology.class, tech, 1).
            register(Executor.class, exec, 5).
            build();

        Inner model = Models.bind(new Inner(), c);
        exec.assertCount(0, "Executor not used for anything yet");
        model.init(c);
        exec.assertCount(1, "Executor used for initialization");
        
        Models.applyBindings(model);

        assertEquals(model.getX(), 0, "Zero");
        assertEquals(model.getY(), 0, "Zero too");
        int execUse;
        if (modificationType != 1) {
            model.modelYToTen();
            execUse = 3;
        } else {
            Object raw = Models.toRaw(model);
            assertTrue(raw instanceof Map);
            Map<?,?> map = (Map<?,?>) raw;
            MapModelTest.One one = (MapModelTest.One) map.get("setYToTen");
            assertNotNull(one);
            one.fb.call(model, null);
            execUse = 3;
        }
        assertEquals(model.getX(), 1, "One");
        assertEquals(model.getY(), 10, "Ten");
        exec.assertCount(execUse, "Executor used");
    }

    private static class MockTechnology implements Technology<Object> {
        private final List<String> mutated = new ArrayList<String>();

        @Override
        public Object wrapModel(Object model) {
            return this;
        }

        @Override
        public void valueHasMutated(Object data, String propertyName) {
            mutated.add(propertyName);
        }

        @Override
        public void bind(PropertyBinding b, Object model, Object data) {
        }

        @Override
        public void expose(FunctionBinding fb, Object model, Object d) {
        }

        @Override
        public void applyBindings(Object data) {
        }

        @Override
        public Object wrapArray(Object[] arr) {
            return arr;
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
}
