/**
 * HTML via Java(tm) Language Bindings
 * Copyright (C) 2013 Jaroslav Tulach <jaroslav.tulach@apidesign.org>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 2 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details. apidesign.org
 * designates this particular file as subject to the
 * "Classpath" exception as provided by apidesign.org
 * in the License file that accompanied this code.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. Look for COPYING file in the top folder.
 * If not, see http://wiki.apidesign.org/wiki/GPLwithClassPathException
 */
package org.apidesign.html.json.spi;

import net.java.html.json.Model;

/** An implementation of a binding between model classes (see {@link Model})
 * and particular technology like <a href="http://knockoutjs.com">knockout.js</a>
 * in a browser window, etc.
 *
 * @author Jaroslav Tulach <jtulach@netbeans.org>
 */
public interface Technology<Data> {
    /** Creates an object to wrap the provided model object. The model
     * has previously been generated by annotation processor associated 
     * with {@link Model} annotation.
     * 
     * @param model the model generated from {@link Model}
     * @return internal object representing the model
     */
    public Data wrapModel(Object model);
    
    /** Converts an element potentially representing a model into the model.
     * @param modelClass expected class to convert the data to
     * @param data the current data provided from the browser
     * @return the instance of modelClass somehow extracted from the data, may return <code>null</code>
     */
    public <M> M toModel(Class<M> modelClass, Object data);
    
    /** Binds a property between the model and the data as used by the technology.
     * 
     * @param b the description of the requested binding
     * @param model the original instance of the model
     * @param data the data to bind with the model
     */
    public void bind(PropertyBinding b, Object model, Data data);

    /** Model for given data has changed its value. The technology is
     * supposed to update its state (for example DOM nodes associated
     * with the model). The update usually happens asynchronously.
     * 
     * @param data technology's own representation of the model
     * @param propertyName name of the model property that changed
     */
    public void valueHasMutated(Data data, String propertyName);

    public void expose(FunctionBinding fb, Object model, Data d);
    
    /** Applies given data to current context (usually an HTML page).
     * @param data the data to apply
     */
    public void applyBindings(Data data);
    
    /**
     * Some technologies may require wrapping a Java array into a special
     * object. In such case they may return it from this method.
     *
     * @param arr original array
     * @return wrapped array
     */
    public Object wrapArray(Object[] arr);
}