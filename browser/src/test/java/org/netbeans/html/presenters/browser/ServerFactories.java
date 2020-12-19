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
package org.netbeans.html.presenters.browser;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import net.java.html.boot.BrowserBuilder;
import org.netbeans.html.boot.spi.Fn;
import org.testng.ITest;
import org.testng.annotations.DataProvider;

public final class ServerFactories {
    private ServerFactories() {
    }

    @DataProvider(name = "serverFactories")
    public static Object[][] serverFactories() {
        Supplier<HttpServer<?,?,?,?>> grizzly = GrizzlyServer::new;
        List<Object[]> arr = new ArrayList<>();
        if (!System.getProperty("os.name").contains("Window")) {
            arr.add(new Object[] { "Grizzly", grizzly});
        }
        arr.add(new Object[] {"Default", null});
        return arr.toArray(new Object[0][]);
    }

    static Fn.Presenter[] collect(
        String browserName, Collection<? super ITest> res,
        Class<? extends Annotation> test, Supplier<Class[]> tests
    ) throws Exception {
        final Object[][] factories = serverFactories();
        Fn.Presenter[] arr = new Fn.Presenter[factories.length];
        for (int i = 0; i < factories.length; i++) {
            Object[] pair = factories[i];
            arr[i] = collect(browserName, (String) pair[0], (Supplier<HttpServer<?,?,?,?>>) pair[1], res, test, tests);
        }
        return arr;
    }

    static Fn.Presenter collect(
        String browserName, String prefix, Supplier<HttpServer<?,?,?,?>> serverProvider,
        Collection<? super ITest> res,
        Class<? extends Annotation> test, Supplier<Class[]> tests
    ) throws Exception {
        Fn.Presenter[] browserPresenter = { null };
        Fn[] updateName = { null };
        CountDownLatch cdl = new CountDownLatch(1);
        final Browser.Config cfg = new Browser.Config().debug(true);
        final BrowserBuilder bb = BrowserBuilder.newBrowser(new Browser(browserName, cfg, serverProvider)).
            loadPage("empty.html").
            loadFinished(() -> {
                browserPresenter[0] = Fn.activePresenter();
                updateName[0] = Fn.define(KOScript.class,
                    "document.getElementsByTagName('h1')[0].innerHTML='" + browserName + "@' + t;",
                    "t"
                );
                cdl.countDown();
            });
        Executors.newSingleThreadExecutor().submit(bb::showAndWait);
        cdl.await();
        Class[] arr = tests.get();
        for (Class c : arr) {
            for (Method m : c.getMethods()) {
                if (m.getAnnotation(test) != null) {
                    res.add(new KOScript(updateName[0], prefix, browserPresenter[0], m));
                }
            }
        }
        return browserPresenter[0];
    }

}
