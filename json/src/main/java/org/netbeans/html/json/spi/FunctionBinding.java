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
package org.netbeans.html.json.spi;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import net.java.html.BrwsrCtx;
import net.java.html.json.Function;
import net.java.html.json.Model;
import static org.netbeans.html.json.spi.PropertyBinding.weakSupported;

/** Describes a function provided by the {@link Model} and 
 * annotated by {@link Function} annotation.
 *
 * @author Jaroslav Tulach
 */
public abstract class FunctionBinding {
    FunctionBinding() {
    }
    
    /** Returns name of the function.
     * @return function name
     */
    public abstract String getFunctionName();
    
    /**
     * Calls the function provided data associated with current element, as well
     * as information about the event that triggered the event.
     *
     * @param data data associated with selected element
     * @param ev event (with additional properties) that triggered the event
     */
    public abstract void call(Object data, Object ev);
    
    /** Returns identical version of the binding, but one that holds on the
     * original model object via weak reference.
     * 
     * @return binding that uses weak reference
     * @since 1.1
     */
    public abstract FunctionBinding weak();

    static <M> FunctionBinding registerFunction(String name, int index, M model, Proto.Type<M> access) {
        return new Impl<M>(model, name, index, access);
    }
    
    private static abstract class AImpl<M> extends FunctionBinding {
        final String name;
        final Proto.Type<M> access;
        final int index;

        public AImpl(String name, int index, Proto.Type<M> access) {
            this.name = name;
            this.index = index;
            this.access = access;
        }
        
        protected abstract M model();

        @Override
        public String getFunctionName() {
            return name;
        }

        @Override
        public void call(final Object data, final Object ev) {
            final M model = model();
            if (model == null) {
                return;
            }
            BrwsrCtx ctx = access.protoFor(model).getContext();
            class Dispatch implements Runnable {
                @Override
                public void run() {
                    try {
                        access.call(model, index, data, ev);
                    } catch (Throwable ex) {
                        ex.printStackTrace();
                    }
                }
            }
            ctx.execute(new Dispatch());
        }
    }
    
    private static final class Impl<M> extends AImpl<M> {
        private final M model;

        public Impl(M model, String name, int index, Proto.Type<M> access) {
            super(name, index, access);
            this.model = model;
        }

        @Override
        protected M model() {
            return model;
        }

        @Override
        public FunctionBinding weak() {
            if (weakSupported) {
                return new Weak<M>(model, name, index, access);
            } else {
                return this;
            }
        }
    }
    
    private static final class Weak<M> extends AImpl<M> {
        private final Reference<M> ref;
        
        public Weak(M model, String name, int index, Proto.Type<M> access) {
            super(name, index, access);
            this.ref = new WeakReference<M>(model);
        }

        @Override
        protected M model() {
            return ref.get();
        }

        @Override
        public FunctionBinding weak() {
            return this;
        }
    }
}
