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
package org.netbeans.html.presenters.spi.test;

import java.util.logging.Level;
import org.netbeans.html.presenters.spi.test.Testing.Synchronized;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.fail;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Factory;

public class CallbackTest {
    @Factory public static Object[] deadlockTests() throws Exception {
        return GenericTest.createTests(new CBP());
    }
    
    @AfterClass public static void countCallbacks() {
        assertEquals(Counter.callbacks, Counter.calls, "Every call to loadJS is prefixed with a callback");
    }
    
    
    private static final class CBP extends Synchronized {
        ThreadLocal<Boolean> initializing = new ThreadLocal<>();
        int exp = 0;

        @Override
        protected void loadJS(String js) {
            if (!initializing.get()) {
                Throwable[] arr = { null };
                dispatch(() -> {
                    try {
                        Object res = eng.eval("counter()");
                        LOG.log(Level.FINE, "counter res: {0}", res);
                        if (res instanceof Number) {
                            assertEquals(((Number) res).intValue(), ++exp, "Counter has been incremented");
                        } else {
                            fail("Expecting number: " + res);
                        }
                    } catch (Throwable ex) {
                        arr[0] = ex;
                    }
                });
                if (arr[0] != null) {
                    if (arr[0] instanceof Error) {
                        throw (Error)arr[0];
                    }
                    if (arr[0] instanceof RuntimeException) {
                        throw (RuntimeException)arr[0];
                    }
                    throw new AssertionError(arr[0]);
                }
            }
            super.loadJS(js);
        }

        @Override void beforeTest(Class<?> testClass) throws Exception {
            try {
                initializing.set(true);
                Counter.registerCounter(exp);
            } finally {
                initializing.set(false);
            }
        }
        
    }
}
