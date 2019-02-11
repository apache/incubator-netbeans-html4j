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
import java.util.List;
import java.util.ListIterator;
import java.util.NoSuchElementException;

public class SimpleList<E> implements List<E> {
    private Object[] arr;
    private int size;

    public SimpleList() {
    }

    private SimpleList(Object[] data) {
        arr = data.clone();
        size = data.length;
    }

    @SafeVarargs
    @SuppressWarnings("varargs")
    public static <T> List<T> asList(T... arr) {
        return new SimpleList<T>(arr);
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public boolean isEmpty() {
        return size == 0;
    }

    @Override
    public boolean contains(Object o) {
        return containsImpl(o, 0, size);
    }

    final boolean containsImpl(Object o, int from, int to) {
        for (int i = from; i < to; i++) {
            if (equals(o, arr[i])) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Iterator<E> iterator() {
        return new LI(0, size);
    }

    @Override
    public Object[] toArray() {
        return toArrayImpl(0, size);
    }

    final Object[] toArrayImpl(int from, int to) {
        Object[] ret = new Object[to - from];
        for (int i = 0; i < ret.length; i++) {
            ret[i] = arr[i + from];
        }
        return ret;
    }

    @Override
    public <T> T[] toArray(T[] a) {
        return toArrayImpl(a, 0, size);
    }

    @SuppressWarnings("unchecked")
    final <T> T[] toArrayImpl(T[] a, int from, int to) {
        if (a.length < to - from) {
            a = newArr(a, to - from);
        }
        for (int i = from; i < to; i++) {
            a[i - from] = (T) arr[i];
        }
        return a;
    }

    @Override
    public boolean add(E e) {
        return addImpl(e);
    }

    private boolean addImpl(E e) {
        ensureAccess(size + 1);
        arr[size++] = e;
        return true;
    }

    @Override
    public boolean remove(Object o) {
        return removeImpl(o, 0, size);
    }

    private boolean removeImpl(Object o, int from, int to) {
        boolean found = false;
        for (int i = from; i < to; i++) {
            if (found) {
                arr[i - 1] = arr[i];
            } else {
                if (equals(o, arr[i])) {
                    found = true;
                }
            }
        }
        if (found) {
            arr[--size] = null;
        }
        return found;
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        for (Object o : c) {
            if (!contains(o)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean addAll(Collection<? extends E> c) {
        ensureAccess(size + c.size());
        for (E o : c) {
            addImpl(o);
        }
        return !c.isEmpty();
    }

    @Override
    public boolean addAll(int index, Collection<? extends E> c) {
        return addImpl(index, c);
    }

    private boolean addImpl(int index, Collection<? extends E> c) {
        final int toAdd = c.size();
        if (toAdd == 0) {
            return false;
        }
        int nowSize = size;
        ensureAccess(nowSize + toAdd);
        for (int i = nowSize - 1; i >= index; i--) {
            arr[i + toAdd] = arr[i];
        }
        for (Object o : c) {
            arr[index++] = o;
        }
        size += toAdd;
        return true;
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        int prev = size;
        for (Object o : c) {
            remove(o);
        }
        return prev != size;
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        return retainImpl(this, c);
    }

    public void sort(Comparator<? super E> c) {
        sortImpl(c, 0, size);
    }

    @SuppressWarnings("unchecked")
    final void sortImpl(Comparator<? super E> c, int from, int to) {
        for (int i = from; i < to; i++) {
            Object min = arr[i];
            int minIndex = i;
            for (int j = i + 1; j < to; j++) {
                final int compare;
                if (c == null) {
                    compare = ((Comparable<Object>)min).compareTo(arr[j]);
                } else {
                    compare = c.compare((E)min, (E)arr[j]);
                }
                if (compare > 0) {
                    min = arr[j];
                    minIndex = j;
                }
            }
            if (i != minIndex) {
                arr[minIndex] = arr[i];
                arr[i] = min;
            }
        }
    }

    @Override
    public void clear() {
        size = 0;
    }

    void  clearImpl(int from, int to) {
        for (int i = 0; i + from < size; i++) {
            arr[from + i] = arr[to + i];
        }
        size += from;
        size -= to;
    }

    @SuppressWarnings("unchecked")
    @Override
    public E get(int index) {
        checkAccess(index);
        return (E) arr[index];
    }

    private void checkAccess(int index) throws ArrayIndexOutOfBoundsException {
        if (index < 0 || index >= size) {
            throw new ArrayIndexOutOfBoundsException();
        }
    }

    private int ensureAccess(int reqSize) {
        if (reqSize < size) {
            return size;
        }

        int newSize = arr == null ? 0 : arr.length;
        if (newSize < 4) {
            newSize = 4;
        }
        while (newSize < reqSize) {
            newSize *= 2;
        }
        Object[] newArr = new Object[newSize];
        for (int i = 0; i < size; i++) {
            newArr[i] = arr[i];
        }

        arr = newArr;
        return reqSize;
    }

    private void ensureSize(int newSize) {
        this.size = ensureAccess(newSize);
    }

    @SuppressWarnings("unchecked")
    @Override
    public E set(int index, E element) {
        checkAccess(index);
        E prev = (E) arr[index];
        arr[index] = element;
        return prev;
    }

    @SuppressWarnings("unchecked")
    @Override
    public void add(int index, E element) {
        addImpl(index, asList(element));
    }

    @SuppressWarnings("unchecked")
    @Override
    public E remove(int index) {
        checkAccess(index);
        E prev = (E) arr[index];
        for (int i = index + 1; i < size; i++) {
            arr[i - 1] = arr[i];
        }
        arr[--size] = null;
        return prev;
    }

    @Override
    public int indexOf(Object o) {
        return indexOfImpl(o, 0, size);
    }

    final int indexOfImpl(Object o, int from, int to) {
        for (int i = from; i < to; i++) {
            if (equals(o, arr[i])) {
                return i - from;
            }
        }
        return -1;
    }

    @Override
    public int lastIndexOf(Object o) {
        return lastIndexOfImpl(o, 0, size);
    }

    public int lastIndexOfImpl(Object o, int from, int to) {
        for (int i = to - 1; i >= from; i--) {
            if (equals(o, arr[i])) {
                return i - from;
            }
        }
        return -1;
    }

    @Override
    public ListIterator<E> listIterator() {
        return new LI(0, size);
    }

    @Override
    public ListIterator<E> listIterator(int index) {
        return new LI(index, 0, size);
    }

    @Override
    public List<E> subList(int fromIndex, int toIndex) {
        return new Sub(fromIndex, toIndex);
    }

    private static boolean equals(Object o1, Object o2) {
        if (o1 == null) {
            return o2 == null;
        } else {
            return o1.equals(o2);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T[] newArr(T[] a, int size) {
        return (T[]) Array.newInstance(a.getClass().getComponentType(), size);
    }

    @Override
    public boolean equals(Object obj) {
        return equalsList(this, obj);
    }

    @Override
    public int hashCode() {
        return hashList(this);
    }

    @Override
    public String toString() {
        return toStringList(this);
    }

    boolean retainImpl(Collection<?> thiz, Collection<?> c) {
        boolean changed = false;
        Iterator<?> it = thiz.iterator();
        while (it.hasNext()) {
            Object obj = it.next();
            if (!c.contains(obj)) {
                it.remove();
                changed = true;
            }
        }
        return changed;
    }

    static boolean equalsList(List<?> thiz, Object obj) {
        if (obj == thiz) return true;
        if (obj instanceof List) {
            List<?> list = (List<?>) obj;
            if (thiz.size() != list.size()) {
                return false;
            }
            for (int i = 0; i < thiz.size(); i++) {
                if (!equals(thiz.get(i), list.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    static int hashList(List<?> thiz) {
        int hashCode = 1;
        for (Object e : thiz) {
            hashCode = 31 * hashCode + (e == null ? 0 : e.hashCode());
        }
        return hashCode;
    }

    static String toStringList(List<?> thiz) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        String sep = "";
        for (Object e : thiz) {
            sb.append(sep);
            sb.append(e);
            sep = ", ";
        }
        sb.append(']');
        return sb.toString();
    }

    public static void ensureSize(List<?> list, int size) {
        if (list instanceof SimpleList) {
            ((SimpleList<?>) list).ensureSize(size);
            return;
        }
        while (list.size() < size) {
            list.add(null);
        }
    }

    private final class Sub implements List<E> {
        private final int from;
        private int to;

        Sub(int from, int to) {
            this.from = from;
            this.to = to;
        }

        @Override
        public int size() {
            return to - from;
        }

        @Override
        public boolean isEmpty() {
            return to <= from;
        }

        @Override
        public boolean contains(Object o) {
            return containsImpl(o, from, to);
        }

        @Override
        public Object[] toArray() {
            return toArrayImpl(from, to);
        }

        @Override
        public <T> T[] toArray(T[] a) {
            return toArrayImpl(a, from, to);
        }

        @Override
        public boolean add(E e) {
            SimpleList.this.add(to++, e);
            return true;
        }

        @Override
        public boolean remove(Object o) {
            if (removeImpl(o, from, to)) {
                to--;
                return true;
            }
            return false;
        }

        @Override
        public boolean containsAll(Collection<?> c) {
            for (Object o : c) {
                if (!contains(o)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public boolean addAll(Collection<? extends E> c) {
            SimpleList.this.addAll(to, c);
            to += c.size();
            return true;
        }

        @Override
        public boolean addAll(int index, Collection<? extends E> c) {
            SimpleList.this.addAll(from + index, c);
            to += c.size();
            return true;
        }

        @Override
        public boolean removeAll(Collection<?> c) {
            int prev = size();
            for (Object o : c) {
                remove(o);
            }
            return prev != size();
        }

        @Override
        public boolean retainAll(Collection<?> c) {
            return retainImpl(this, c);
        }

        public void sort(Comparator<? super E> c) {
            sortImpl(c, from, to);
        }

        @Override
        public void clear() {
            clearImpl(from, to);
            to = from;
        }

        @Override
        public E get(int index) {
            return SimpleList.this.get(from + index);
        }

        @Override
        public E set(int index, E element) {
            return SimpleList.this.set(from + index, element);
        }

        @Override
        public void add(int index, E element) {
            SimpleList.this.add(index + from, element);
            to++;
        }

        @Override
        public E remove(int index) {
            E ret = SimpleList.this.remove(index + from);
            to--;
            return ret;
        }

        @Override
        public int indexOf(Object o) {
            return indexOfImpl(o, from, to);
        }

        @Override
        public int lastIndexOf(Object o) {
            return lastIndexOfImpl(o, from, to);
        }

        @Override
        public Iterator<E> iterator() {
            return listIterator(0);
        }

        @Override
        public ListIterator<E> listIterator() {
            return listIterator(0);
        }

        @Override
        public ListIterator<E> listIterator(int index) {
            return new LI(from + index, from, to) {
                @Override
                public void remove() {
                    super.remove();
                    to--;
                }
            };
        }

        @Override
        public List<E> subList(int fromIndex, int toIndex) {
            return new Sub(from + fromIndex, from + toIndex);
        }

        @Override
        public boolean equals(Object obj) {
            return equalsList(this, obj);
        }

        @Override
        public int hashCode() {
            return hashList(this);
        }

        @Override
        public String toString() {
            return toStringList(this);
        }
    }

    private class LI implements ListIterator<E> {
        private int prev = -1;
        private int at;
        private final int min;
        private final int max;
        private int add;

        LI(int at, int min, int max) {
            this.at = at;
            this.min = min;
            this.max = max;
        }

        LI(int min, int max) {
            this(min, min, max);
        }

        @Override
        public boolean hasNext() {
            return at < max + add;
        }

        @SuppressWarnings("unchecked")
        @Override
        public E next() {
            if (at == max + add) {
                throw new NoSuchElementException();
            }
            prev = at;
            return (E) arr[at++];
        }

        @Override
        public boolean hasPrevious() {
            return at > min;
        }

        @SuppressWarnings("unchecked")
        @Override
        public E previous() {
            if (at == min) {
                throw new NoSuchElementException();
            }
            prev = --at;
            return (E) arr[prev];
        }

        @Override
        public int nextIndex() {
            return at - min;
        }

        @Override
        public int previousIndex() {
            return at - 1 - min;
        }

        @Override
        public void remove() {
            if (prev == -1) {
                throw new IllegalStateException();
            }
            SimpleList.this.remove(prev);
            at = prev;
            prev = -1;
            add--;
        }

        @Override
        public void set(E e) {
            SimpleList.this.set(min + prev, e);
        }

        @Override
        public void add(E e) {
            SimpleList.this.add(min + at, e);
            add++;
        }

    }
}
