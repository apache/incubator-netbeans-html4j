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

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import org.netbeans.html.json.spi.Proto;

/**
 *
 * @author Jaroslav Tulach
 */
public final class JSONList<T> extends SimpleList<T> {
    private final Proto proto;
    private final String name;
    private final String[] deps;
    private final int index;

    public JSONList(Proto proto, String name, int changeIndex, String... deps) {
        this.proto = proto;
        this.name = name;
        this.deps = deps;
        this.index = changeIndex;
    }
    
    @SuppressWarnings("unchecked")
    public void init(Object values) {
        int len;
        if (values == null || (len = Array.getLength(values)) == 0) {
            return;
        }
        for (int i = 0; i < len; i++) {
            Object data = Array.get(values, i);
            super.add((T)data);
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> void init(Collection<T> to, Object values) {
        int len;
        if (values == null || (len = Array.getLength(values)) == 0) {
            return;
        }
        for (int i = 0; i < len; i++) {
            Object data = Array.get(values, i);
            to.add((T)data);
        }
    }
    
    @Override
    public boolean add(T e) {
        prepareChange();
        boolean ret = super.add(e);
        notifyChange();
        return ret;
    }

    @Override
    public boolean addAll(Collection<? extends T> c) {
        prepareChange();
        boolean ret = super.addAll(c);
        notifyChange();
        return ret;
    }

    @Override
    public boolean addAll(int index, Collection<? extends T> c) {
        prepareChange();
        boolean ret = super.addAll(index, c);
        notifyChange();
        return ret;
    }

    public void fastReplace(Collection<? extends T> c) {
        prepareChange();
        super.clear();
        super.addAll(c);
        notifyChange();
    }

    @Override
    public boolean remove(Object o) {
        prepareChange();
        boolean ret = super.remove(o);
        notifyChange();
        return ret;
    }

    @Override
    public void clear() {
        prepareChange();
        super.clear();
        notifyChange();
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        prepareChange();
        boolean ret = super.removeAll(c);
        notifyChange();
        return ret;
    }

    public void sort(Comparator<? super T> c) {
        super.sort(c);
        notifyChange();
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        prepareChange();
        boolean ret = super.retainAll(c);
        notifyChange();
        return ret;
    }

    @Override
    public T set(int index, T element) {
        prepareChange();
        T ret = super.set(index, element);
        notifyChange();
        return ret;
    }

    @Override
    public void add(int index, T element) {
        prepareChange();
        super.add(index, element);
        notifyChange();
    }

    @Override
    public T remove(int index) {
        prepareChange();
        T ret = super.remove(index);
        notifyChange();
        return ret;
    }

    @Override
    void clearImpl(int from, int to) {
        super.clearImpl(from, to);
        notifyChange();
    }

    @Override
    public String toString() {
        Iterator<T> it = iterator();
        if (!it.hasNext()) {
            return "[]";
        }
        String sep = "";
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        while (it.hasNext()) {
            T t = it.next();
            sb.append(sep);
            sb.append(JSON.toJSON(t));
            sep = ",";
        }
        sb.append(']');
        return sb.toString();
    }

    private void prepareChange() {
        if (index == Integer.MIN_VALUE) {
            try {
                proto.initTo(null, null);
            } catch (IllegalStateException ex) {
                throw new UnsupportedOperationException();
            }
        }
    }

    private void notifyChange() {
        proto.getContext().execute(new Runnable() {
            @Override
            public void run() {
                proto.valueHasMutated(name);
                Bindings m = PropertyBindingAccessor.getBindings(proto, false, null);
                if (m != null) {
                    for (String dependant : deps) {
                        m.valueHasMutated(dependant, null, null);
                    }
                    if (index >= 0) {
                        PropertyBindingAccessor.notifyProtoChange(proto, index);
                    }
                }
            }
        });
    }

    @Override
    public JSONList clone() {
        throw new UnsupportedOperationException();
    }

    static final Object koData(Collection<?> c, Bindings m) {
        Object[] arr = c.toArray(new Object[c.size()]);
        for (int i = 0; i < arr.length; i++) {
            Object r = JSON.find(arr[i], m);
            if (r != null) {
                arr[i] = r;
            }
        }
        return m.wrapArray(arr);
    }

    final Object koData() {
        return koData(this, PropertyBindingAccessor.getBindings(proto, true, null));
    }
}
