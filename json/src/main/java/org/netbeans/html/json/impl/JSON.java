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

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import net.java.html.BrwsrCtx;
import org.netbeans.html.context.spi.Contexts;
import org.netbeans.html.json.spi.FunctionBinding;
import org.netbeans.html.json.spi.JSONCall;
import org.netbeans.html.json.spi.PropertyBinding;
import org.netbeans.html.json.spi.Proto;
import org.netbeans.html.json.spi.Technology;
import org.netbeans.html.json.spi.Transfer;
import org.netbeans.html.json.spi.WSTransfer;

/**
 *
 * @author Jaroslav Tulach
 */
public final class JSON {
    private JSON() {
    }

    static Technology<?> findTechnology(BrwsrCtx c) {
        Technology<?> t = Contexts.find(c, Technology.class);
        return t == null ? EmptyTech.EMPTY : t;
    }

    public static Transfer findTransfer(BrwsrCtx c) {
        Transfer t = Contexts.find(c, Transfer.class);
        return t == null ? EmptyTech.EMPTY : t;
    }

    public static WSTransfer<?> findWSTransfer(BrwsrCtx c) {
        WSTransfer<?> t = Contexts.find(c, WSTransfer.class);
        return t == null ? EmptyTech.EMPTY : t;
    }

    public static <M> void readBindings(BrwsrCtx c, M model, Object value) {
        Technology<?> tech = findTechnology(c);
        if (tech instanceof Technology.BatchCopy) {
            Proto p = findProto(model);
            PropertyBindingAccessor.getBindings(p, true, value);
        }
    }
    public static void extract(BrwsrCtx c, Object value, String[] props, Object[] values) {
        Transfer t = findTransfer(c);
        t.extract(value, props, values);
    }

    private static Object getProperty(BrwsrCtx c, Object obj, String prop) {
        if (prop == null) return obj;

        String[] arr = { prop };
        Object[] val = { null };
        extract(c, obj, arr, val);
        return val[0];
    }

    public static String toJSON(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Enum) {
            value = value.toString();
        }
        if (value instanceof Character) {
            value = Character.toString((Character)value);
        }
        if (value instanceof String) {
            String s = (String)value;
            int len = s.length();
            StringBuilder sb = new StringBuilder(len + 10);
            sb.append('"');
            for (int i = 0; i < len; i++) {
                char ch = s.charAt(i);
                switch (ch) {
                    case '\"': sb.append("\\\""); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    case '\\': sb.append("\\\\"); break;
                    default: sb.append(ch);
                }
            }
            sb.append('"');
            return sb.toString();
        }
        return value.toString();
    }

    public static String toString(BrwsrCtx c, Object obj, String prop) {
        obj = getProperty(c, obj, prop);
        return obj == null ? null : obj.toString();
    }
    public static Number toNumber(BrwsrCtx c, Object obj, String prop) {
        obj = getProperty(c, obj, prop);
        if (obj instanceof Character) {
            obj = (int)(Character)obj;
        }
        if (!(obj instanceof Number)) {
            obj = Double.NaN;
        }
        return (Number)obj;
    }
    public static <M> M toModel(BrwsrCtx c, Class<M> aClass, Object data, Object object) {
        Technology<?> t = findTechnology(c);
        Object o = t.toModel(aClass, data);
        return aClass.cast(o);
    }

    public static boolean isSame(int a, int b) {
        return a == b;
    }

    public static boolean isSame(double a, double b) {
        return a == b;
    }

    public static boolean isSame(Object a, Object b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return a.equals(b);
    }

    public static int hashPlus(Object o, int h) {
        return o == null ? h : h ^ o.hashCode();
    }

    public static <T> T extractValue(Class<T> type, Object val) {
        if (Number.class.isAssignableFrom(type)) {
            val = numberValue(val);
        }
        if (Boolean.class == type) {
            val = boolValue(val);
        }
        if (String.class == type) {
            val = stringValue(val);
        }
        if (Character.class == type) {
            val = charValue(val);
        }
        if (Integer.class == type) {
            val = val instanceof Number ? ((Number)val).intValue() : 0;
        }
        if (Long.class == type) {
            val = val instanceof Number  ? ((Number)val).longValue() : 0;
        }
        if (Short.class == type) {
            val = val instanceof Number ? ((Number)val).shortValue() : 0;
        }
        if (Byte.class == type) {
            val = val instanceof Number ? ((Number)val).byteValue() : 0;
        }
        if (Double.class == type) {
            val = val instanceof Number ? ((Number)val).doubleValue() : Double.NaN;
        }
        if (Float.class == type) {
            val = val instanceof Number ? ((Number)val).floatValue() : Float.NaN;
        }
        return type.cast(val);
    }

    static boolean isNumeric(Object val) {
        return ((val instanceof Integer) || (val instanceof Long) || (val instanceof Short) || (val instanceof Byte));
    }

    public static String stringValue(Object val) {
        if (val instanceof Boolean) {
            return ((Boolean)val ? "true" : "false");
        }
        if (isNumeric(val)) {
            return Long.toString(((Number)val).longValue());
        }
        if (val instanceof Float) {
            return Float.toString((Float)val);
        }
        if (val instanceof Double) {
            return Double.toString((Double)val);
        }
        return (String)val;
    }

    public static Number numberValue(Object val) {
        if (val instanceof String) {
            try {
                return Double.valueOf((String)val);
            } catch (NumberFormatException ex) {
                return Double.NaN;
            }
        }
        if (val instanceof Boolean) {
            return (Boolean)val ? 1 : 0;
        }
        return (Number)val;
    }

    public static Character charValue(Object val) {
        if (val instanceof Number) {
            return Character.toChars(numberValue(val).intValue())[0];
        }
        if (val instanceof Boolean) {
            return (Boolean)val ? (char)1 : (char)0;
        }
        if (val instanceof String) {
            String s = (String)val;
            return s.isEmpty() ? (char)0 : s.charAt(0);
        }
        return (Character)val;
    }

    public static Boolean boolValue(Object val) {
        if (val instanceof String) {
            return Boolean.parseBoolean((String)val);
        }
        if (val instanceof Number) {
            return numberValue(val).doubleValue() != 0.0;
        }

        return Boolean.TRUE.equals(val);
    }

    public static Object find(Object object, Bindings model) {
        if (object == null) {
            return null;
        }
        if (object instanceof JSONList) {
            return ((JSONList<?>) object).koData();
        }
        if (object instanceof Collection) {
            return JSONList.koData((Collection<?>) object, model);
        }
        if (
            object instanceof String ||
            object instanceof Boolean ||
            object instanceof Number ||
            object instanceof Character ||
            object instanceof Enum<?>
        ) {
            return object;
        }
        Proto proto = findProto(object);
        if (proto == null) {
            return null;
        }
        final Bindings b = PropertyBindingAccessor.getBindings(proto, true, null);
        return b == null ? null : b.jsObj();
    }

    private static Proto findProto(Object object) {
        Proto.Type<?> type = JSON.findType(object.getClass());
        if (type == null) {
            return null;
        }
        final Proto proto = PropertyBindingAccessor.protoFor(type, object);
        return proto;
    }

    public static Object find(Object object) {
        return find(object, null);
    }

    public static void applyBindings(Object object, String id) {
        final Proto proto = findProto(object);
        if (proto == null) {
            throw new IllegalArgumentException("Not a model: " + object.getClass());
        }
        proto.applyBindings(id);
    }

    public static abstract class WS {
        private WS() {
        }

        public abstract void send(BrwsrCtx ctx, String headers, String url, Object model);
        public static <Socket> WS create(WSTransfer<Socket> t, RcvrJSON r) {
            return new WSImpl<Socket>(t, r);
        }
    }

    private static final class WSImpl<Socket> extends WS {

        private final WSTransfer<Socket> trans;
        private final RcvrJSON rcvr;
        private Socket socket;
        private String prevURL;

        private WSImpl(WSTransfer<Socket> trans, RcvrJSON rcvr) {
            this.trans = trans;
            this.rcvr = rcvr;
        }

        @Override
        public void send(BrwsrCtx ctx, String headers, String url, Object data) {
            Socket s = socket;
            if (s == null) {
                if (data != null) {
                    throw new IllegalStateException("WebSocket is not opened yet. Call with null data, was: " + data);
                }
                JSONCall call = PropertyBindingAccessor.createCall(ctx, rcvr, headers, url, null, "WebSocket", null);
                socket = trans.open(url, call);
                prevURL = url;
                return;
            }
            if (data == null) {
                trans.close(s);
                socket = null;
                return;
            }
            if (!prevURL.equals(url)) {
                throw new IllegalStateException(
                    "Can't call to different URL " + url + " was: " + prevURL + "!"
                    + " Close the socket by calling it will null data first!"
                );
            }
            JSONCall call = PropertyBindingAccessor.createCall(ctx, rcvr, headers, prevURL, null, "WebSocket", data);
            trans.send(s, call);
        }

    }


    static ModelTypes initModelTypes(String preload, String implName) {
        ModelTypes types = null;
        try {
            Class.forName(preload);
            Class<?> clazz = Class.forName(implName);
            types = (ModelTypes) clazz.newInstance();
        } catch (ClassNotFoundException ex) {
            // OK, not supported
        } catch (NoClassDefFoundError ex) {
            // OK, not supported
        } catch (Throwable ex) {
            ex.printStackTrace();
        } finally {
            if (types == null) {
                types = new LinkedListTypes();
            }
        }
        return types;
    }

    public static void register(Class c, Proto.Type<?> type) {
        ModelTypes.MODELS.find(c)[0] = type;
    }

    public static boolean isModel(Class<?> clazz) {
        return findType(clazz) != null;
    }

    static Proto.Type<?> findType(Class<?> clazz) {
        for (int i = 0; i < 2; i++) {
            Proto.Type<?> from = ModelTypes.MODELS.find(clazz)[0];
            if (from == null) {
                initClass(clazz);
            } else {
                return from;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public static <Model> Model bindTo(Model model, BrwsrCtx c) {
        Proto.Type<Model> from = (Proto.Type<Model>) findType(model.getClass());
        if (from == null) {
            throw new IllegalArgumentException();
        }
        return PropertyBindingAccessor.clone(from, model, c);
    }

    public static <T> T readStream(BrwsrCtx c, Class<T> modelClazz, InputStream data, Collection<? super T> collectTo)
    throws IOException {
        Transfer tr = findTransfer(c);
        Object rawJSON = tr.toJSON((InputStream)data);
        if (rawJSON instanceof Object[]) {
            final Object[] arr = (Object[])rawJSON;
            if (collectTo != null) {
                for (int i = 0; i < arr.length; i++) {
                    collectTo.add(read(c, modelClazz, arr[i]));
                }
                return null;
            }
            if (arr.length == 0) {
                throw new EOFException("Recieved an empty array");
            }
            rawJSON = arr[0];
        }
        T res = read(c, modelClazz, rawJSON);
        if (collectTo != null) {
            collectTo.add(res);
        }
        return res;
    }
    public static <T> T read(BrwsrCtx c, Class<T> modelClazz, Object data) {
        if (data == null) {
            return null;
        }
        if (modelClazz == String.class) {
            return modelClazz.cast(data.toString());
        }
        for (int i = 0; i < 2; i++) {
            Proto.Type<?> from = ModelTypes.MODELS.find(modelClazz)[0];
            if (from == null) {
                initClass(modelClazz);
            } else {
                return modelClazz.cast(PropertyBindingAccessor.readFrom(from, c, data));
            }
        }
        throw new NullPointerException();
    }
    static void initClass(Class<?> modelClazz) {
        try {
            // try to resolve the class
            ClassLoader l;
            try {
                l = modelClazz.getClassLoader();
            } catch (SecurityException ex) {
                l = null;
            }
            if (l != null) {
                Class.forName(modelClazz.getName(), true, l);
            }
            modelClazz.newInstance();
        } catch (Exception ex) {
            // ignore and try again
        }
    }

    private static final class EmptyTech
    implements Technology<Object>, Transfer, WSTransfer<Void> {
        private static final EmptyTech EMPTY = new EmptyTech();

        @Override
        public Object wrapModel(Object model) {
            return model;
        }

        @Override
        public void valueHasMutated(Object data, String propertyName) {
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
        public void extract(Object obj, String[] props, Object[] values) {
            for (int i = 0; i < values.length; i++) {
                values[i] = null;
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
            throw new IOException("Not supported");
        }

        @Deprecated
        @Override
        public void runSafe(Runnable r) {
            r.run();
        }

        @Override
        public Void open(String url, JSONCall onReply) {
            onReply.notifyError(new UnsupportedOperationException("WebSockets not supported!"));
            return null;
        }

        @Override
        public void send(Void socket, JSONCall data) {
        }

        @Override
        public void close(Void socket) {
        }
    }

}
