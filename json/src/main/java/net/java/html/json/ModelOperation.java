/**
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 1997-2010 Oracle and/or its affiliates. All rights reserved.
 *
 * Oracle and Java are registered trademarks of Oracle and/or its affiliates.
 * Other names may be trademarks of their respective owners.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common
 * Development and Distribution License("CDDL") (collectively, the
 * "License"). You may not use this file except in compliance with the
 * License. You can obtain a copy of the License at
 * http://www.netbeans.org/cddl-gplv2.html
 * or nbbuild/licenses/CDDL-GPL-2-CP. See the License for the
 * specific language governing permissions and limitations under the
 * License.  When distributing the software, include this License Header
 * Notice in each file and include the License file at
 * nbbuild/licenses/CDDL-GPL-2-CP.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the GPL Version 2 section of the License file that
 * accompanied this code. If applicable, add the following below the
 * License Header, with the fields enclosed by brackets [] replaced by
 * your own identifying information:
 * "Portions Copyrighted [year] [name of copyright owner]"
 *
 * Contributor(s):
 *
 * The Original Software is NetBeans. The Initial Developer of the Original
 * Software is Oracle. Portions Copyright 2013-2013 Oracle. All Rights Reserved.
 *
 * If you wish your version of this file to be governed by only the CDDL
 * or only the GPL Version 2, indicate your decision by adding
 * "[Contributor] elects to include this software in this distribution
 * under the [CDDL or GPL Version 2] license." If you do not indicate a
 * single choice of license, a recipient has the option to distribute
 * your version of this file under either the CDDL, the GPL Version 2 or
 * to extend the choice of license to its licensees as provided above.
 * However, if you add GPL Version 2 code and therefore, elected the GPL
 * Version 2 license, then the option applies only if the new code is
 * made subject to such option by the copyright holder.
 */
package net.java.html.json;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** The threading model of classes generated by {@link Model @Model} requires
 * that all operations are perform from the originating thread - unless they
 * are invoked as {@link ModelOperation @ModelOperation} methods.
 * <p>
 * A method in a class annotated by {@link Model @Model} annotation may be
 * annotated by {@link ModelOperation @ModelOperation}. The method has
 * to be static, non-private and return <code>void</code>. The first parameter
 * of the method must be the {@link Model#className() model class} followed
 * by any number of additional arguments.
 * <p>
 * As a result method of the same name and the same list of additional arguments
 * (e.g. without the first model class one) will be generated into the 
 * {@link Model#className() model class}. This method can be invoked on any
 * thread, any time and it is the responsibility of model manipulating
 * technology to ensure the model is available and only then call back to 
 * the original method annotated by {@link ModelOperation @ModelOperation}.
 * The call may happen synchronously (if possible), or be delayed and invoked
 * later (on appropriate dispatch thread), without blocking the caller.
 * <pre>
 * 
 * {@link Model @Model}(className="Names", properties={
 *   {@link Property @Property}(name = "names", type=String.class, array = true)
 * })
 * static class NamesModel {
 *   {@link ModelOperation @ModelOperation} static void <b>updateNames</b>(Names model, {@link java.util.List}<String> arr) {
 *     <em>// can safely access the model</em>
 *     model.getNames().addAll(arr);
 *   }
 * 
 *   static void initialize() {
 *     final Names pageModel = new Names();
 *     pageModel.applyBindings();
 * 
 *     <em>// spawn to different thread</em>
 *     {@link java.util.concurrent.Executors}.newSingleThreadExecutor().execute({
 *       List<String> arr = <em>// ... obtain the names somewhere from network</em>
 *       pageModel.<b>updateNames</b>(arr);
 *       // returns immediately, later it invokes the model operation
 *     });
 * 
 *   }
 * }
 * 
 * @author Jaroslav Tulach <jtulach@netbeans.org>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface ModelOperation {
}
