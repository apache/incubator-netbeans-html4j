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

import java.net.URL;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import org.netbeans.html.presenters.spi.ProtoPresenter;
import org.netbeans.html.presenters.spi.ProtoPresenterBuilder;

class Testing {
    static final Logger LOG = Logger.getLogger(Testing.class.getName());
    final Executor QUEUE;
    final ScriptEngine eng;
    final boolean sync;
    final ProtoPresenter presenter;

    public Testing() {
        this(false);
    }

    protected Testing(boolean sync) {
        this(sync, Executors.newSingleThreadExecutor());
    }
    protected Testing(boolean sync, Executor queue) {
        this.sync = sync;
        this.QUEUE = queue;
        this.presenter = ProtoPresenterBuilder.newBuilder()
            .app("Testing")
            .type("test")
            .dispatcher(QUEUE, false)
            .loadJavaScript(this::loadJS, sync)
            .displayer(this::displayPage)
            .preparator(this::callbackFn, true)
            .logger(this::log)
            .register(new Holder())
            .build();

        ScriptEngineManager sem = new ScriptEngineManager();
        eng = sem.getEngineByMimeType("text/javascript");
        eng.getBindings(ScriptContext.ENGINE_SCOPE).put("polyglot.js.allowAllAccess", true);
        try {
            eng.eval("function alert(m) { Packages.java.lang.System.out.println(m); };");
        } catch (ScriptException ex) {
            throw new IllegalStateException(ex);
        }        
    }

    protected void log(int priority, String msg, Object... args) {
        Level level = findLevel(priority);
        if (args.length == 1 && args[0] instanceof Throwable) {
            LOG.log(level, msg, (Throwable)args[0]);
        } else {
            LOG.log(level, msg, args);
        }
    }

    private static Level findLevel(int priority) {
        if (priority >= Level.SEVERE.intValue()) {
            return Level.SEVERE;
        }
        if (priority >= Level.WARNING.intValue()) {
            return Level.WARNING;
        }
        if (priority >= Level.INFO.intValue()) {
            return Level.INFO;
        }
        return Level.FINE;
    }

    public final class Clbk {
        private Clbk() {
        }

        private String ts(Object o) {
            return o == null ? null : o.toString();
        }

        public String pass(String method, Object a1, Object a2, Object a3, Object a4) throws Exception {
            return presenter.js2java(method, ts(a1), ts(a2), ts(a3), ts(a4));
        }
    }
    private final Clbk clbk = new Clbk();

    protected void callbackFn(ProtoPresenterBuilder.OnPrepared ready) {
        eng.getBindings(ScriptContext.ENGINE_SCOPE).put("jvm", clbk);
        try {
            eng.eval("(function(global) {\n"
                + "  var jvm = global.jvm;\n"
                + "  global.testingCB = function(m,a1,a2,a3,a4) {\n"
                + "    return jvm.pass(m,a1,a2,a3,a4);\n"
                + "  }\n"
                + "})(this);\n"
            );
        } catch (ScriptException ex) {
            throw new IllegalStateException(ex);
        }
        eng.getBindings(ScriptContext.ENGINE_SCOPE).put("jvm", "");
        ready.callbackIsPrepared("testingCB");
    }

    protected void loadJS(final String js) {
        QUEUE.execute(new Runnable() {
            public void run() {
                try {
                    Object res = eng.eval(js);
                    LOG.log(Level.FINE, "Result: {0}", res);
                } catch (Throwable ex) {
                    LOG.log(Level.SEVERE, "Can't process " + js, ex);
                }
            }
        });
    }

    public void displayPage(URL url, Runnable r) {
        r.run();
    }

    public void dispatch(Runnable r) {
        QUEUE.execute(r);
    }

    void beforeTest(Class<?> declaringClass) throws Exception {
    }
    
    static class Synchronized extends Testing {
        public Synchronized() {
            super(true, (r) -> r.run());
        }

        @Override
        protected void loadJS(final String js) {
            try {
                Object res = eng.eval(js);
                LOG.log(Level.FINE, "Result: {0}", res);
            } catch (Throwable ex) {
                LOG.log(Level.SEVERE, "Can't process " + js, ex);
            }
        }

        @Override
        public void displayPage(URL url, Runnable r) {
            r.run();
        }

        @Override
        public void dispatch(Runnable r) {
            r.run();
        }

        @Override
        void beforeTest(Class<?> declaringClass) throws Exception {
        }
    } // end of Synchronized

    final class Holder {
        Testing get() {
            return Testing.this;
        }
    }
}
