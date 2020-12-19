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
package org.netbeans.html.presenters.spi;

import com.dukescript.api.strings.Texts;
import java.io.Flushable;
import java.io.IOException;
import java.io.Reader;
import java.lang.ref.PhantomReference;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.netbeans.html.boot.spi.Fn;

abstract class Generic implements Fn.Presenter, Fn.KeepAlive, Flushable {
    private StringBuilder msg;
    /** @GuardedBy("this") */
    private int callCounter;
    /** @GuardedBy("this") */
    private Item call;
    private final NavigableSet<Exported> exported;
    private final int key;
    private final boolean synchronous;
    private final boolean evalJS;
    private final String type;
    private final String app;
    private final CountDownLatch initialized = new CountDownLatch(1);
    
    Generic(
        boolean synchronous, boolean evalJS, String type, String app
    ) {
        this.exported = new TreeSet<Exported>();
        this.key = (int)(System.currentTimeMillis() / 777) % 1000;
        this.synchronous = synchronous;
        this.evalJS = evalJS;
        this.type = type;
        this.app = app;
    }
    
    final Object lock() {
        return initialized;
    }
    
    final void log(Level level, String msg, Object... args) {
        StringBuilder sb = this.msg;
        if (sb != null) {
            for (int i = 0; i < args.length; i++) {
                String txt = args[i] == null ? "null" : args[i].toString();
                msg = msg.replace("{" + i + "}", txt);
            }
            synchronized (lock()) {
                sb.append('[').append(level).append("] ");
                sb.append(msg);
                sb.append('\n');
            }
        }
        handleLog(level, msg, args);
    }
    abstract void handleLog(Level level, String msg, Object... args);
    
    @Texts({
        "begin=try {\n"
        + "  @1('r', -1, 'OK', 'Connected', null);\n"
        + "} catch (e) {\n"
        + "  console.warn(e);\n"
        + "}\n",
        
        "init=(function(global) {"
            + "\n  var fncns = new Array();"
            + "\n  var js2j = new Array();"
            + "\n  function jobject(id,value) {"
            + "\n    Object.defineProperty(this, 'id', { value : id });"
            + "\n    Object.defineProperty(this, 'v', { value : value });"
            + "\n    return this;"
            + "\n  };"
            + "\n  Object.defineProperty(jobject.prototype, 'native', { value : true });"
            + "\n  Object.defineProperty(jobject.prototype, 'valueOf', { value : function() { return this.v ? this.v : '[jobject ' + this.id + ']'; } });"
            + "\n  Object.defineProperty(jobject.prototype, 'toString', { value : jobject.prototype.valueOf });"
            + "\n  var toVMImpl = global['@2'];"
            + "\n  delete global['@2'];"
            + "\n  if (typeof toVMImpl !== 'function') {"
            + "\n    throw 'toVM should be a function: ' + toVMImpl;"
            + "\n  }"
            + "\n  function toVM(method, reqId, a2, a3, a4) {"
            + "\n    return toVMImpl(method, reqId, a2, a3, a4);"
            + "\n  }"
            + "\n  function toJava(method, reqId, r) {"
            + "\n      var t = typeof r;"
            + "\n      if (t === 'function') t = 'object';"
            + "\n      if (t === 'undefined' || r === null) {"
            + "\n        t = 'null';"
            + "\n        r = null;"
            + "\n      } else if (t === 'object') {"
            + "\n        if (r['native']) {"
            + "\n          t = 'java';"
            + "\n          r = r.id;"
            + "\n        } else if (Object.prototype.toString.call(r) === '[object Array]') {"
            + "\n        t = 'array';"
            + "\n        var l = r.length + ':';"
            + "\n        for (var i = 0; i < r.length; i++) {"
            + "\n            var toObj = toJava(null, reqId, r[i]);"
            + "\n            l += toObj.length + ':' + toObj;"
            + "\n          }"
            + "\n          r = l;"
            + "\n        } else {"
            + "\n          var size = js2j.length;"
            + "\n          js2j.push(r);"
            + "\n          r = size;"
            + "\n        }"
            + "\n      }"
            + "\n      if (method !== null) toVM(method, reqId, t, r);"
            + "\n      else return t + ':' + r;"
            + "\n  }"
            + "\n  var impl = {};"
            + "\n  impl.key = @1;"
            + "\n  global.ds = function(key) {"
            + "\n    if (key != impl.key) {"
            + "\n      impl = null;"
            + "\n      console.warn('Surprising access to Java with ' + key);"
            + "\n    }"
            + "\n    return impl;"
            + "\n  };"
            + "\n  impl.toJava = toJava;"
            + "\n  impl.rg = function(index, fn) {"
            + "\n    fncns[index] = fn;"
            + "\n  };"
            + "\n  impl.fn = function(index, n, reqId, self) {"
            + "\n    var args = Array.prototype.slice.call(arguments, 4);"
            + "\n    try {"
            + "\n      var fn = fncns[index];"
            + "\n      if (typeof fn !== 'function') throw 'Cannot find function at index: ' + index + ' in ' + fn + ' apply: ' + (fn ? fn.apply : undefined);"
            + "\n      var r = fn.apply(self, args);"
            + "\n      if (n) toJava('r', reqId, r, reqId);"
            + "\n    } catch (err) {"
            + "\n      if (typeof console !== 'undefined') console.warn('Error ' + err + ' at:\\n' + err.stack);"
            + "\n      if (n) toVM('r', reqId, 'error', '' + err + ' at:\\n' + err.stack);"
            + "\n    }"
            + "\n  };"
            + "\n  impl.o = function(i) {"
            + "\n    return js2j[i];"
            + "\n  };"
            + "\n  impl.j = function(n,v) {"
            + "\n   var r = new jobject(n,v);"
            + "\n   if (arguments.length > 2) {"
            + "\n     for (var i = 2; i < arguments.length; i++) {"
            + "\n       r[i - 2] = arguments[i];"
            + "\n     }"
            + "\n     r.length = arguments.length - 2;"
            + "\n   }"
            + "\n   return r;"
            + "\n  };"
            + "\n  impl.v = function(i) {"
            + "\n    return fncns[i];"
            + "\n  };"
            + "\n  impl.toVM = toVM;"
            + "\n  impl.toVM('r', -1, 'OK', 'Initialized');"
            + "\n})(this);",
        "initializationProtocol=--- Initialization protocol ---\n",
        "error=Cannot initialize DukeScript: @1",
        "version=$version"
    })
    final void init() {
        if (initialized.getCount() == 0) {
            return;
        }
        synchronized (lock()) {
            if (initialized.getCount() == 0) {
                return;
            }
            if (msg == null) {
                this.msg = new StringBuilder(Strings.initializationProtocol());
                callbackFn(new ProtoPresenterBuilder.OnPrepared() {
                    @Override
                    public void callbackIsPrepared(String clbk) {
                        log(Level.FINE, "callbackReady with {0}", clbk);
                        loadJS(Strings.begin(clbk).toString());
                        log(Level.FINE, "checking OK state");
                        loadJS(Strings.init(key, clbk).toString());
                    }
                });
            }
        }
        for (int counter = 0;; counter++) {
            try {
                handleLog(Level.FINE, "Awaiting as of {0}", counter);
                if (initialized.await(10, TimeUnit.SECONDS)) {
                    handleLog(Level.FINE, "Waiting is over");
                    return;
                }
                handleLog(Level.INFO, msg.toString());
            } catch (InterruptedException ex) {
                handleLog(Level.INFO, "Interrupt", ex);
            }
        }
    }

    /** @return the name of the callback function */
    abstract void callbackFn(ProtoPresenterBuilder.OnPrepared onReady);
    abstract void loadJS(String js);
    
    public final String js2java(String method,
        String a1, String a2, String a3, String a4
    ) throws Exception {
        if ("r".equals(method)) {
            result(a1, a2, a3);
            return null;
        } else if ("c".equals(method)) {
            return javacall(a1, a2, a3, a4);
        } else if ("jr".equals(method)) {
            int reqId = Integer.parseInt(a1);
            return javaresult(reqId);
        } else {
            throw new IllegalArgumentException(method);
        }
    }
    
    abstract void dispatch(Runnable r);

    /** Makes sure all pending calls into JavaScript are immediately 
     * performed. 
     * 
     * @throws IOException if something goes wrong
     */
    @Override
    public void flush() throws IOException {
        if (initialized.getCount() == 0) {
            flushImpl();
        }
    }
    
    @Override
    public Fn defineFn(String code, String[] names, boolean[] keepAlive) {
        init();
        return new GFn(code, names, keepAlive);
    }    
    
    @Override
    public Fn defineFn(String code, String... names) {
        init();
        return new GFn(code, names, null);
    }
    
    private static final class Key extends WeakReference<Object> {
        private int hash;
        
        public Key(Object obj) {
            super(obj);
            this.hash = System.identityHashCode(obj);
        }

        @Override
        public int hashCode() {
            int hash = 7;
            hash = 47 * hash + this.hash;
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof Key) {
                Key other = (Key)obj;
                if (hash != other.hash) {
                    return false;
                }
                return true;
            }
            return false;
        }
    }
    
    private Map<Key,Integer> ids = new HashMap<Key, Integer>();
    int identityHashCode(Object o) {
        Key k = new Key(o);
        Integer val = ids.get(k);
        if (val == null) {
            int s = ids.size();
            ids.put(k, s);
            return s;
        }
        return val;
    }
    
    final int registerObject(Object o, boolean weak, boolean[] justAdded, String[] valueOf) {
        if (o instanceof Enum && valueOf != null) {
            valueOf[0] = o.toString();
        }
        int id = identityHashCode(o);
        for (;;) {
            Object exp = findObject(id);
            if (o == exp) {
                return id;
            }
            if (exp == null) {
                if (justAdded != null) {
                    justAdded[0] = true;
                }
                exported.add(new Exported(id, weak, o));
                return id;
            }
            throw new IllegalStateException("Collision!");
        }
    }
    
    final Object findObject(int id) {
        Exported obj = exported.floor(new Exported(id, false, null));
        return obj == null || obj.id != id ? null : obj.get();
    }
    
    @Texts({
        "fnHead=var jsvm = {};\n",
        "fnName=jsvm.@1 = function(",
        "fnThiz=thiz",
        "fnNoThiz=  var thiz = null;\n",
        "fnSep=,",
        "fnParam=p@1",
        "fnClose=) {\n",
        "fnBegin=  var encParams = ds(@1).toJava(null, -1, [",
        "fnPPar=@2 p@1",
        "fnBody=]);\n" +
            "  var v = ds(@3).toVM('c', '@1', '@2', thiz ? thiz.id : null, encParams);\n" +
            "  while (v !== null && v.indexOf && v.indexOf('javascript:') === 0) {\n" +
            "    var idAndScript = v.substring(11);\n" +
            "    var slash = idAndScript.indexOf('/');\n" +
            "    var id = idAndScript.substring(0, slash);\n" +
            "    var script = idAndScript.substring(slash + 1);\n" +
            "    try {\n" +
            "      var r = eval.call(null, script);\n" +
            "    } catch (e) { \n" +
            "      console.warn('error: ' + e + ' executing: ' + script + ' at:\\n' + e.stack); \n" +
            "    }\n" +
            "    v = ds(@3).toVM('jr', id);\n" +
            "  }\n" +
            "  return @4 ? eval('(' + v + ')') : v;\n" +
            "};\n",
        
        "fnFoot=ds(@2).rg(@1, jsvm);\n"
    })
    final Integer exportVm(Object vm) {
        int jNumber = registerObject(vm, false, null, null);
        int vmNumber = COUNTER.getAndIncrement();
        StringBuilder sb = new StringBuilder();
        sb.append(Strings.fnHead());
        for (Method m : vm.getClass().getMethods()) {
            if (m.getDeclaringClass() == Object.class) {
                continue;
            }
            final Class<?>[] types = m.getParameterTypes();
            boolean instanceMethod = 
                types.length > 0 && 
                m.getName().startsWith(types[0].getName().replace('.', '_') + "$");
            int params = instanceMethod ? types.length - 1 : types.length;
            sb.append(Strings.fnName(m.getName()));
            String sep;
            if (instanceMethod) {
                sb.append(Strings.fnThiz());
                sep = Strings.fnSep();
            } else {
                sep = "";
            }
            for (int i = 0; i < params; i++) {
                sb.append(sep);
                sb.append(Strings.fnParam(i));
                sep = Strings.fnSep();
            }
            sb.append(Strings.fnClose());
            if (!instanceMethod) {
                sb.append(Strings.fnNoThiz());
            }
            sb.append(Strings.fnBegin(key));
            for (int i = 0; i < params; i++) {
                sb.append(Strings.fnPPar(i, i == 0 ? "" : ","));
            }
            sb.append(Strings.fnBody(jNumber, m.getName(), key, evalJS));
        }
        sb.append(Strings.fnFoot(vmNumber, key));
        deferExec(sb);
        return vmNumber;
    }

    @Texts({
        "v_null=null",
        "v_number=number",
        "v_java=java",
        "v_object=object",
        "v_array=array",
        "v_boolean=boolean",
        "v_error=error"
    })
    final Object valueOf(String typeof, String res) {
        if (Strings.v_null().equals(typeof)) { // NOI18N
            return null;
        }
        if (Strings.v_number().equals(typeof)) { // NOI18N
            return Double.valueOf(res);
        }
        if (Strings.v_java().equals(typeof)) { // NOI18N
            return findObject(Integer.parseInt(res));
        }
        if (Strings.v_object().equals(typeof)) { // NOI18N
            return new JSObject(Integer.parseInt(res));
        }
        if (Strings.v_array().equals(typeof)) { // NOI18N
            int at = res.indexOf(':');
            int size = Integer.parseInt(res.substring(0, at));
            Object[] arr = new Object[size];
            at++;
            for (int i = 0; i < size; i++) {
                int next = res.indexOf(':', at);
                int length = Integer.parseInt(res.substring(at, next));
                at = next + 1 + length;
                arr[i] = valueOf(res.substring(next + 1, at));
            }
            return arr;
        }
        if (Strings.v_boolean().equals(typeof)) { // NOI18N
            return Boolean.valueOf(res);
        }
        if (Strings.v_error().equals(typeof)) { // NOI18N
            throw new IllegalStateException(res);
        }
        return res;
    }
    
    final Object valueOf(String typeAndValue) {
        int colon = typeAndValue.indexOf(':');
        return valueOf(typeAndValue.substring(0, colon), typeAndValue.substring(colon + 1));
    }

    final void encodeObject(Object a, boolean weak, StringBuilder sb, int[] vmId) {
        if (a == null) {
            sb.append(Strings.v_null());
        } else if (a.getClass().isArray()) {
            int len = Array.getLength(a);
            sb.append('[');
            String sep = "";
            for (int i = 0; i < len; i++) {
                Object o = Array.get(a, i);
                sb.append(sep);
                encodeObject(o, weak, sb, null);
                sep = ",";
            }
            sb.append(']');
        } else if (a instanceof Number) {
            sb.append(a.toString());
        } else if (a instanceof String) {
            sb.append('"');
            String s = (String)a;
            int len = s.length();
            for (int i = 0; i < len; i++) {
                char ch = s.charAt(i);
                switch (ch) {
                    case '\\': sb.append("\\\\"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\"': sb.append("\\\""); break;
                    default:
                        sb.append(ch);
                        break;
                }
            }
            sb.append('"');
        } else if (a instanceof Boolean) {
            sb.append(a.toString());
        } else if (a instanceof Character) {
            sb.append((int)(Character)a);
        } else if (a instanceof JSObject) {
            sb.append("ds(").append(key).append(").o(").append(((JSObject) a).index).append(")");
        } else {
            if (vmId != null) {
                sb.append("ds(").append(key).append(").v(").append(vmId[0]).append(")");
            } else {
                String[] valueOf = { null };
                sb.append("ds(").append(key).append(").j(").append(registerObject(a, weak, null, valueOf));
                sb.append(",");
                encodeObject(valueOf[0], weak, sb, null);
                if (a instanceof Object[]) {
                    for (Object n : ((Object[])a)) {
                        sb.append(",");
                        encodeObject(n, weak, sb, null);
                    }
                }
                sb.append(")");
            }
        }
    }

    interface OnReady {
        void callbackReady(String name);
    }
    
    private abstract class Item {
        final int id;
        final Item prev;

        Item(int id, Item prev) {
            this.id = id;
            this.prev = prev;
        }

        abstract void result(String typeof, String res);
        abstract void noLongerNeeded();
        abstract void inJava();
        abstract String inJavaScript(int[] finishedId);
        abstract void header(StringBuilder sb, String sep);
        abstract boolean done();

        final void toString(StringBuilder sb, String sep) {
            header(sb, sep);
            if (prev != null) {
                sb.append(", prev=\n");
                prev.toString(sb, "  " + sep);
                sb.append("\n").append(sep);
            }
            sb.append("]");
        }

        @Override
        public final String toString() {
            synchronized (lock()) {
                StringBuilder sb = new StringBuilder();
                toString(sb, "");
                return sb.toString();
            }
        }
    }

    private class JavaItem extends Item implements Runnable {
        Boolean done;
        boolean resultObtained;

        final Method method;
        final Object thiz;
        final Object[] params;
        Object result;
        
        JavaItem(int id, Item prev, Method method, Object thiz, Object[] params) {
            super(id, prev);
            this.method = method;
            this.thiz = thiz;
            this.params = adaptParams(method, Arrays.asList(params));
        }

        @Override
        final void header(StringBuilder sb, String sep) {
            sb.append(sep).append("JavaItem[id=").append(id).append(", ");
            sb.append("method=").append(method.getName());
        }

        @Override
        protected final String inJavaScript(int[] finished) {
            assert Thread.holdsLock(lock());
            if (Boolean.TRUE.equals(done)) {
                StringBuilder sb = new StringBuilder();
                encodeObject(result, false, sb, null);
                finished[0] = -1;
                return sb.toString();
            }
            return null;
        }

        @Override
        protected final void inJava() {
            assert Thread.holdsLock(lock());
            if (this.method == null) {
                return;
            }
            if (done == null) {
                done = false;
                try {
                    log(Level.FINE, "Calling {0}", method);
                    result = method.invoke(thiz, params);
                } catch (Exception ex) {
                    log(Level.SEVERE, "Cannot invoke " + method + " on " + thiz + " with " + Arrays.toString(params), ex);
                } finally {
                    done = true;
                    log(Level.FINE, "Result: {0}", result);
                }
            }
        }
        
        @Override
        public void run() {
            synchronized (lock()) {
                log(Level.FINE, "run: {0}", this);
                inJava();
                lock().notifyAll();
            }
        }

        @Override
        void result(String typeof, String res) {
        }

        @Override
        void noLongerNeeded() {
            resultObtained = true;
        }

        @Override
        boolean done() {
            return Boolean.TRUE.equals(done);
        }
    }

    private final class JsItem extends Item {
        private final String toExec;
        private String typeof;
        private Boolean done;
        private String result;
        
        JsItem(int id, Item prev, String toExec) {
            super(id, prev);
            this.toExec = toExec;
        }

        @Override
        protected final void result(String newType, String newResult) {
            assert Thread.holdsLock(lock());
            this.typeof = newType;
            this.result = newResult;
            log(Level.FINE, "result ({0}): {1} for {2}", typeof, result, toExec);
        }

        @Override
        protected final void noLongerNeeded() {
        }

        @Override
        protected final String inJavaScript(int[] finished) {
            assert Thread.holdsLock(lock());
            assert Thread.holdsLock(lock());
            if (Boolean.TRUE.equals(done)) {
                return null;
            }
            done = true;
            return "javascript:" + finished[0] + "/" + toExec;
        }

        @Override
        protected final void inJava() {
        }

        @Override
        final void header(StringBuilder sb, String sep) {
            sb.append(sep).append("JsItem[id=").append(id).append(", ");
            sb.append("javascript");
        }

        @Override
        boolean done() {
            return Boolean.TRUE.equals(done);
        }
    } // end of Item
    
    final void result(String counterId, String typeof, String res) {
        log(Level.FINE, "result#{2}@{0}: {1}", typeof, res, counterId);
        synchronized (lock()) {
            if ("OK".equals(typeof)) {
                log(Level.FINE, "init: {0}", res);
                lock().notifyAll();
                if ("Initialized".equals(res)) {
                    log(Level.FINE, "callbackReady: countingDown");
                    handleLog(Level.FINE, msg.toString());
                    msg = null;
                    initialized.countDown();
                }
                return;
            }
            final int id = Integer.parseInt(counterId);
            final Item top = topMostCall(null);
            Item my = findCall(id);
            if (top == my) {
                top.result(typeof, res);
                finishCall(top);
            } else {
                my.result(typeof, res);
            }
        }
    }

    final String javacall(
        String vmNumber, String fnName, String thizId, String encParams
    ) throws Exception {
        synchronized (lock()) {
            Object vm = findObject(Integer.parseInt(vmNumber));
            assert vm != null;
            final Object obj = thizId == null || "null".equals(thizId)
                    ? null : valueOf("java", thizId);
            Method method = null;
            for (Method m : vm.getClass().getMethods()) {
                if (m.getName().equals(fnName)) {
                    method = m;
                    break;
                }
            }
            assert method != null;
            List<Object> params = new ArrayList<Object>();
            if (obj != null) {
                params.add(obj);
            }
            final Object args = valueOf(encParams);
            if (!(args instanceof Object[])) {
                throw new IllegalStateException("Expecting array: " + args);
            }
            params.addAll(Arrays.asList((Object[]) args));
            Object[] converted = adaptParams(method, params);
            Item top = topMostCall(null);
            boolean first = top == null || top.done();
            log(Level.FINE, "jc: {0}@{1}args: {2} is first: {3}, now: {4}", new Object[]{method.getName(), vm, params, first, topMostCall(null)});
            JavaItem newItem = registerCall(new JavaItem(nextCallId(), top, method, vm, converted));
            try {
                if (first || synchronous) {
                    dispatch(newItem);
                }
                return javaresult(newItem.id);
            } finally {
                newItem.noLongerNeeded();
            }
        }
    }

    final String javaresult(int reqId) throws IllegalStateException, InterruptedException {
        synchronized (lock()) {
            int[] finishedId = { reqId };
            for (;;) {
                if (deferred != null) {
                    deferred.insert(0, "javascript:" + reqId + "/");
                    String ret = deferred.toString();
                    deferred = null;
                    return ret;
                }
                final Item top = topMostCall(null);
                String jsToExec = top.inJavaScript(finishedId);
                log(Level.FINE, "jr: {0} jsToExec: {1} finished: {2}", new Object[]{top, jsToExec, finishedId[0]});
                if (jsToExec != null) {
                    if (finishedId[0] == -1) {
                        finishCall(top);
                    }
                    return jsToExec;
                }
                lock().wait();
            }
        }
    }

    private StringBuilder deferred;
    private Collection<Object> arguments = new LinkedList<Object>();

    @Override
    public final void loadScript(final Reader reader) throws Exception {
        StringBuilder sb = new StringBuilder();
        char[] arr = new char[4092];
        for (;;) {
            int len = reader.read(arr);
            if (len == -1) {
                break;
            }
            sb.append(arr, 0, len);
        }
        deferExec(sb);
    }


    final void deferExec(StringBuilder sb) {
        synchronized (lock()) {
            log(Level.FINE, "deferExec: {0} empty: {1}, call: {2}", new Object[]{sb, deferred == null, topMostCall(null)});
            if (deferred == null) {
                deferred = sb;
            } else {
                deferred.append(sb);
            }
        }
    }

    @Texts({
        "flushBegin=try {\n",
        "flushExec=\n\n} finally {\n  ds(@1).toJava('r', '@2', null);\n}\n"
    })
    void flushImpl() {
        synchronized (lock()) {
            if (deferred != null) {
                final int id = nextCallId();
                deferred.insert(0, Strings.flushBegin());
                log(Level.FINE, "flush#{1}: {0}", deferred, id);
                exec(id, Strings.flushExec(key, id).toString());
            }
        }
    }

    final Object exec(int id, String fn) {
        Object ret;
        boolean first;
        synchronized (lock()) {
            if (deferred != null) {
                deferred.append(fn);
                fn = deferred.toString();
                deferred = null;
                log(Level.FINE, "Flushing {0}", fn);
            }

            JsItem myCall;
            boolean load;
            final Item top = topMostCall(null);
            if (top != null) {
                myCall = registerCall(new JsItem(id, top, fn));
                load = synchronous;
                first = false;
            } else {
                myCall = registerCall(new JsItem(id, null, null));
                load = true;
                first = true;
            }
            try {
                if (load) {
                    loadJS(fn);
                }
                for (;;) {
                    if (myCall.typeof != null) {
                        break;
                    }
                    try {
                        lock().wait();
                    } catch (InterruptedException ex) {
                        log(Level.SEVERE, null, ex);
                    }
                    Item c = topMostCall(null);
                    if (c != null) {
                        c.inJava();
                    }
                    lock().notifyAll();
                }
                ret = valueOf(myCall.typeof, (String) myCall.result);
            } finally {
                myCall.noLongerNeeded();
            }
        }
        if (first) {
            arguments.clear();
        }
        return ret;
    }
    
    private static Object[] adaptParams(Method toCall, List<Object> args) {
        final Object[] arr = new Object[args.size()];
        final Class<?>[] types = toCall.getParameterTypes();
        for (int i = 0; i < arr.length; i++) {
            arr[i] = adaptType(types[i], args.get(i));
        }
        return arr;
    }
    
    private static Object adaptType(Class<?> type, Object value) {
        if (type.isPrimitive() && value instanceof Number) {
            final Number n = (Number)value;
            if (type == Byte.TYPE) return n.byteValue();
            if (type == Short.TYPE) return n.shortValue();
            if (type == Integer.TYPE) return n.intValue();
            if (type == Long.TYPE) return n.longValue();
            if (type == Float.TYPE) return n.floatValue();
            if (type == Double.TYPE) return n.doubleValue();
            if (type == Character.TYPE) return (char)n.intValue();
        }
        return value;
    }
    
    private static final class JSObject {
        private final int index;

        public JSObject(int index) {
            this.index = index;
        }

        @Override
        public int hashCode() {
            return 37 * this.index;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            final JSObject other = (JSObject) obj;
            return this.index == other.index;
        }

        @Texts({
            "jsObject=[jsobject-@1]"
        })
        @Override
        public String toString() {
            return Strings.jsObject(index).toString();
        }
        
    } // end of JSObject
    
    static final AtomicInteger COUNTER = new AtomicInteger(0);
    @Texts({
        "registerFn=ds(@2).rg(@1, function(",
        "registerCode=) {\n@1\n});",
        "v_vm=vm"
    })
    private final class GFn extends Fn {
        private final int id;
        private final int[] vmId;
        private final boolean[] keepAlive;
        
        public GFn(String code, String[] names, boolean[] ka) {
            super(Generic.this);
            this.id = COUNTER.getAndIncrement();
            this.keepAlive = ka;
            
            StringBuilder sb = new StringBuilder(1024);
            sb.append(Strings.registerFn(id, key));
            String sep = "";
            boolean isVm = false;
            for (String n : names) {
                sb.append(sep).append(n);
                sep = ",";
                isVm = false;
                if (Strings.v_vm().equals(n)) {
                    isVm = true;
                }
            }
            sb.append(Strings.registerCode(code));
            this.vmId = isVm ? new int[] { -1 } : null;
            deferExec(sb);
        }

        @Override
        public Object invoke(Object thiz, Object... args) throws Exception {
            return invokeImpl(true, thiz, args);
        }

        @Override
        public void invokeLater(Object thiz, Object... args) throws Exception {
            invokeImpl(false, thiz, args);
        }

        @Texts({
            "invokeImplFn=ds(@3).fn(@1, @2, @4, "
        })
        private Object invokeImpl(boolean wait4js, Object thiz, Object... args) throws Exception {
            if (vmId != null && vmId[0] < 0) {
                vmId[0] = exportVm(args[args.length - 1]);
            }

            StringBuilder sb = new StringBuilder(256);
            encodeObject(thiz, false, sb, null);
            for (int i = 0; i < args.length; i++) {
                sb.append(", ");
                boolean weak = keepAlive != null && !keepAlive[i];
                encodeObject(args[i], weak, sb, i == args.length - 1 ? vmId : null);
            }
            sb.append(");");
            
            arguments.add(thiz);
            arguments.add(args);

            synchronized (lock()) {
                int callId = nextCallId();
                sb.insert(0, Strings.invokeImplFn(id, wait4js, key, callId));
                if (wait4js) {
                    return exec(callId, sb.toString());
                } else {
                    deferExec(sb);
                    return null;
                }
            }
        }
    }
    
    private static final class Exported implements Comparable<Exported> {
        private final int id;
        private final Object obj;
        private final boolean ref;

        Exported(int id, boolean ref, Object obj) {
            this.id = id;
            this.obj = ref ? createReferenceFor(obj) : obj;
            this.ref = ref;
            WeakHolder.clean();
        }
        
        protected Object get() {
            if (ref) {
                return ((Reference<?>)obj).get();
            } else {
                return obj;
            }
        }
        
        @Override
        public int compareTo(Exported o) {
            return id - o.id;
        }

        private static Object createReferenceFor(Object obj) {
            Reference<Object> ref = new WeakReference<Object>(obj);
            if (obj instanceof Reference) {
                Reference<?> myRef = (Reference<?>) obj;
                if (obj.getClass().getName().equals("org.netbeans.html.ko4j.Knockout")) {
                    // workaround for #255677
                    WeakHolder h = new WeakHolder(myRef.get(), obj);
                    h.register();
                }
            }
            return ref;
        }
    }

    private static final class WeakHolder extends PhantomReference<Object> {
        private static final ReferenceQueue QUEUE = new ReferenceQueue();
        private static final Set<WeakHolder> active = new HashSet<WeakHolder>();
        private final Object knockout;

        public WeakHolder(Object referent, Object knockout) {
            super(referent, QUEUE);
            this.knockout = knockout;
        }

        static void clean() {
            for (;;) {
                WeakHolder h = (WeakHolder) QUEUE.poll();
                if (h == null) {
                    break;
                }
                active.remove(h);
            }
        }

        void register() {
            active.add(this);
        }
    }

    private Item topMostCall(Class<? extends Item> type) {
        assert Thread.holdsLock(lock());
        for (Item it = call; it != null; it = it.prev) {
            if (type == null || type.isInstance(it)) {
                return it;
            }
        }
        return null;
    }

    private Item findCall(int reqId) {
        for (Item it = call;; it = it.prev) {
            if (it == null) {
                throw new NullPointerException();
            }
            if (it.id == reqId) {
                return it;
            }
        }
    }

    private <T extends Item> T registerCall(T call) {
        assert Thread.holdsLock(lock());
        this.call = call;
        lock().notifyAll();
        return call;
    }

    private void finishCall(Item call) {
        assert Thread.holdsLock(lock());
        assert this.call == call;
        this.call = call.prev;
        lock().notifyAll();
    }

    private int nextCallId() {
        assert Thread.holdsLock(lock());
        return ++callCounter;
    }
}
