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

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.annotation.AnnotationTypeMismatchException;
import java.lang.annotation.IncompleteAnnotationException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Completion;
import javax.annotation.processing.Completions;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import net.java.html.json.ComputedProperty;
import net.java.html.json.Function;
import net.java.html.json.Model;
import net.java.html.json.ModelOperation;
import net.java.html.json.OnPropertyChange;
import net.java.html.json.OnReceive;
import net.java.html.json.Property;
import org.openide.util.lookup.ServiceProvider;

/** Annotation processor to process {@link Model @Model} annotations and
 * generate appropriate model classes.
 *
 * @author Jaroslav Tulach
 */
@ServiceProvider(service=Processor.class)
@SupportedSourceVersion(SourceVersion.RELEASE_6)
@SupportedAnnotationTypes({
    "net.java.html.json.Model",
    "net.java.html.json.ModelOperation",
    "net.java.html.json.Function",
    "net.java.html.json.OnReceive",
    "net.java.html.json.OnPropertyChange",
    "net.java.html.json.ComputedProperty",
    "net.java.html.json.Property"
})
public final class ModelProcessor extends AbstractProcessor {
    private static final Logger LOG = Logger.getLogger(ModelProcessor.class.getName());
    private final Map<Element,String> models = new WeakHashMap<Element,String>();
    private final Map<Element,Prprt[]> verify = new WeakHashMap<Element,Prprt[]>();
    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        boolean ok = true;
        for (Element e : roundEnv.getElementsAnnotatedWith(Model.class)) {
            if (!processModel(e)) {
                ok = false;
            }
        }
        if (roundEnv.processingOver()) {
            models.clear();
            for (Map.Entry<Element, Prprt[]> entry : verify.entrySet()) {
                TypeElement te = (TypeElement)entry.getKey();
                String fqn = te.getQualifiedName().toString();
                Element finalElem = processingEnv.getElementUtils().getTypeElement(fqn);
                if (finalElem == null) {
                    continue;
                }
                Prprt[] props;
                Model m = finalElem.getAnnotation(Model.class);
                if (m == null) {
                    continue;
                }
                props = Prprt.wrap(processingEnv, finalElem, m.properties());
                for (Prprt p : props) {
                    boolean[] isModel = { false };
                    boolean[] isEnum = { false };
                    boolean[] isPrimitive = { false };
                    String t = checkType(p, isModel, isEnum, isPrimitive);
                    if (isEnum[0]) {
                        continue;
                    }
                    if (isPrimitive[0]) {
                        continue;
                    }
                    if (isModel[0]) {
                        continue;
                    }
                    if ("java.lang.String".equals(t)) {
                        continue;
                    }
                    error("The type " + t + " should be defined by @Model annotation", entry.getKey());
                }
            }
            verify.clear();
        }
        return ok;
    }

    private void error(String msg, Element e) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, msg, e);
    }

    private boolean processModel(Element e) {
        boolean ok = true;
        Model m = e.getAnnotation(Model.class);
        if (m == null) {
            return true;
        }
        String pkg = findPkgName(e);
        Writer w;
        String className = m.className();
        models.put(e, className);
        try {
            StringWriter body = new StringWriter();
            StringBuilder onReceiveType = new StringBuilder();
            List<GetSet> propsGetSet = new ArrayList<GetSet>();
            List<Object> functions = new ArrayList<Object>();
            Map<String, Collection<String[]>> propsDeps = new HashMap<String, Collection<String[]>>();
            Map<String, Collection<String>> functionDeps = new HashMap<String, Collection<String>>();
            Prprt[] props = createProps(e, m.properties());
            final String builderPrefix = findBuilderPrefix(e, m);

            if (!generateComputedProperties(className, body, props, e.getEnclosedElements(), propsGetSet, propsDeps)) {
                ok = false;
            }
            if (!generateOnChange(e, propsDeps, props, className, functionDeps)) {
                ok = false;
            }
            if (!generateProperties(e, builderPrefix, body, className, props, propsGetSet, propsDeps, functionDeps)) {
                ok = false;
            }
            if (!generateFunctions(e, body, className, e.getEnclosedElements(), functions)) {
                ok = false;
            }
            int functionsCount = functions.size() / 2;
            for (int i = 0; i < functions.size(); i += 2) {
                for (Prprt p : props) {
                    if (p.name().equals(functions.get(i))) {
                        error("Function cannot have the name of an existing property", e);
                        ok = false;
                    }
                }
            }
            if (!generateReceive(e, body, className, e.getEnclosedElements(), onReceiveType)) {
                ok = false;
            }
            if (!generateOperation(e, body, className, e.getEnclosedElements(), functions)) {
                ok = false;
            }
            FileObject java = processingEnv.getFiler().createSourceFile(pkg + '.' + className, e);
            w = new OutputStreamWriter(java.openOutputStream());
            try {
                w.append("package " + pkg + ";\n");
                w.append("import net.java.html.json.*;\n");
                final String inPckName = inPckName(e, false);
                w.append("/** Generated for {@link ").append(inPckName).append("}*/\n");
                w.append("public final class ").append(className).append(" implements Cloneable {\n");
                w.append("  private static Class<").append(inPckName).append("> modelFor() { return ").append(inPckName).append(".class; }\n");
                w.append("  private static final Html4JavaType TYPE = new Html4JavaType();\n");
                if (m.instance()) {
                    int cCnt = 0;
                    for (Element c : e.getEnclosedElements()) {
                        if (c.getKind() != ElementKind.CONSTRUCTOR) {
                            continue;
                        }
                        cCnt++;
                        ExecutableElement ec = (ExecutableElement) c;
                        if (ec.getParameters().size() > 0) {
                            continue;
                        }
                        if (ec.getModifiers().contains(Modifier.PRIVATE)) {
                            continue;
                        }
                        cCnt = 0;
                        break;
                    }
                    if (cCnt > 0) {
                        ok = false;
                        error("Needs non-private default constructor when instance=true", e);
                        w.append("  private final ").append(inPckName).append(" instance = null;\n");
                    } else {
                        w.append("  private final ").append(inPckName).append(" instance = new ").append(inPckName).append("();\n");
                    }
                }
                w.append("  private final org.netbeans.html.json.spi.Proto proto;\n");
                w.append(body.toString());
                w.append("  private ").append(className).append("(net.java.html.BrwsrCtx context) {\n");
                w.append("    this.proto = TYPE.createProto(this, context);\n");
                for (Prprt p : props) {
                    if (p.array()) {
                        final String tn = typeName(p);
                        String[] gs = toGetSet(p.name(), tn, p.array());
                        w.write("    this.prop_" + p.name() + " = proto.createList(\""
                            + p.name() + "\"");
                        if (p.mutable()) {
                            if (functionDeps.containsKey(p.name())) {
                                int index = Arrays.asList(functionDeps.keySet().toArray()).indexOf(p.name());
                                w.write(", " + index);
                            } else {
                                w.write(", -1");
                            }
                        } else {
                            w.write(", java.lang.Integer.MIN_VALUE");
                        }
                        Collection<String[]> dependants = propsDeps.get(p.name());
                        if (dependants != null) {
                            for (String[] depProp : dependants) {
                                w.write(", ");
                                w.write('\"');
                                w.write(depProp[0]);
                                w.write('\"');
                            }
                        }
                        w.write(")");
                        w.write(";\n");
                    }
                }
                w.append("  };\n");
                w.append("  public ").append(className).append("() {\n");
                w.append("    this(net.java.html.BrwsrCtx.findDefault(").append(className).append(".class));\n");
                for (Prprt p : props) {
                    if (!p.array()) {
                        boolean[] isModel = {false};
                        boolean[] isEnum = {false};
                        boolean isPrimitive[] = {false};
                        String tn = checkType(p, isModel, isEnum, isPrimitive);
                        if (isModel[0]) {
                            w.write("    prop_" + p.name() + " = TYPE; /* uninitialized */\n");
                        }
                    }
                }
                w.append("  };\n");
                if (props.length > 0 && builderPrefix == null) {
                    StringBuilder constructorWithArguments = new StringBuilder();
                    constructorWithArguments.append("  public ").append(className).append("(");
                    Prprt firstArray = null;
                    String sep = "";
                    int parameterCount = 0;
                    for (Prprt p : props) {
                        if (p.array()) {
                            if (firstArray == null) {
                                firstArray = p;
                            }
                            continue;
                        }
                        String tn = typeName(p);
                        constructorWithArguments.append(sep);
                        constructorWithArguments.append(tn);
                        String[] third = toGetSet(p.name(), tn, false);
                        constructorWithArguments.append(" ").append(third[2]);
                        sep = ", ";
                        parameterCount++;
                    }
                    if (firstArray != null) {
                        String tn;
                        boolean[] isModel = {false};
                        boolean[] isEnum = {false};
                        boolean isPrimitive[] = {false};
                        tn = checkType(firstArray, isModel, isEnum, isPrimitive);
                        constructorWithArguments.append(sep);
                        constructorWithArguments.append(tn);
                        String[] third = toGetSet(firstArray.name(), tn, true);
                        constructorWithArguments.append("... ").append(third[2]);
                        parameterCount++;
                    }
                    constructorWithArguments.append(") {\n");
                    constructorWithArguments.append("    this(net.java.html.BrwsrCtx.findDefault(").append(className).append(".class));\n");
                    for (Prprt p : props) {
                        if (p.array()) {
                            continue;
                        }
                        String[] third = toGetSet(p.name(), null, false);
                        constructorWithArguments.append("    this.prop_" + p.name() + " = " + third[2] + ";\n");
                    }
                    if (firstArray != null) {
                        String[] third = toGetSet(firstArray.name(), null, true);
                        constructorWithArguments.append("    proto.initTo(this.prop_" + firstArray.name() + ", " + third[2] + ");\n");
                    }
                    constructorWithArguments.append("  };\n");
                    if (parameterCount < 255) {
                        w.write(constructorWithArguments.toString());
                    }
                }
                w.append("  private static class Html4JavaType extends org.netbeans.html.json.spi.Proto.Type<").append(className).append("> {\n");
                w.append("    private Html4JavaType() {\n      super(").append(className).append(".class, ").
                    append(inPckName).append(".class, " + propsGetSet.size() + ", "
                    + functionsCount + ");\n");
                {
                    for (int i = 0; i < propsGetSet.size(); i++) {
                        w.append("      registerProperty(\"").append(propsGetSet.get(i).name).append("\", ");
                        w.append((i) + ", " + propsGetSet.get(i).readOnly + ", " + propsGetSet.get(i).constant + ");\n");
                    }
                }
                {
                    for (int i = 0; i < functionsCount; i++) {
                        w.append("      registerFunction(\"").append((String)functions.get(i * 2)).append("\", ");
                        w.append(i + ");\n");
                    }
                }
                w.append("    }\n");
                w.append("    @Override public void setValue(" + className + " data, int type, Object value) {\n");
                w.append("      switch (type) {\n");
                for (int i = 0; i < propsGetSet.size(); i++) {
                    final GetSet pgs = propsGetSet.get(i);
                    if (pgs.readOnly) {
                        continue;
                    }
                    final String set = pgs.setter;
                    String tn = pgs.type;
                    String btn = findBoxedType(tn);
                    if (btn != null) {
                        tn = btn;
                    }
                    w.append("        case " + i + ": ");
                    if (pgs.setter != null) {
                        w.append("data.").append(pgs.setter).append("(TYPE.extractValue(" + tn + ".class, value)); return;\n");
                    } else {
                        w.append("TYPE.replaceValue(data.").append(pgs.getter).append("(), " + tn + ".class, value); return;\n");
                    }
                }
                w.append("      }\n");
                w.append("      throw new UnsupportedOperationException();\n");
                w.append("    }\n");
                w.append("    @Override public Object getValue(" + className + " data, int type) {\n");
                w.append("      switch (type) {\n");
                for (int i = 0; i < propsGetSet.size(); i++) {
                    final String get = propsGetSet.get(i).getter;
                    if (get != null) {
                        w.append("        case " + i + ": return data." + get + "();\n");
                    }
                }
                w.append("      }\n");
                w.append("      throw new UnsupportedOperationException();\n");
                w.append("    }\n");
                w.append("    @Override public void call(" + className + " model, int type, Object data, Object ev) throws Exception {\n");
                w.append("      switch (type) {\n");
                for (int i = 0; i < functions.size(); i += 2) {
                    final String name = (String)functions.get(i);
                    final Object param = functions.get(i + 1);
                    if (param instanceof ExecutableElement) {
                        ExecutableElement ee = (ExecutableElement)param;
                        w.append("        case " + (i / 2) + ":\n");
                        w.append("          ");
                        if (m.instance()) {
                            w.append("model.instance");
                        } else {
                            w.append(((TypeElement)e).getQualifiedName());
                        }
                        w.append(".").append(name).append("(");
                        w.append(wrapParams(ee, null, className, "model", "ev", "data"));
                        w.append(");\n");
                        w.append("          return;\n");
                    } else {
                        String call = (String)param;
                        w.append("        case " + (i / 2) + ":\n"); // model." + name + "(data, ev); return;\n");
                        w.append("          ").append(call).append("\n");
                        w.append("          return;\n");

                    }
                }
                w.append("      }\n");
                w.append("      throw new UnsupportedOperationException();\n");
                w.append("    }\n");
                w.append("    @Override public org.netbeans.html.json.spi.Proto protoFor(Object obj) {\n");
                w.append("      return ((" + className + ")obj).proto;");
                w.append("    }\n");
                w.append("    @Override public void onChange(" + className + " model, int type) {\n");
                w.append("      switch (type) {\n");
                {
                    String[] arr = functionDeps.keySet().toArray(new String[0]);
                    for (int i = 0; i < arr.length; i++) {
                        Collection<String> onChange = functionDeps.get(arr[i]);
                        if (onChange != null) {
                            w.append("      case " + i + ":\n");
                            for (String call : onChange) {
                                w.append("      ").append(call).append("\n");
                            }
                            w.write("      return;\n");
                        }
                    }
                }
                w.append("    }\n");
                w.append("      throw new UnsupportedOperationException();\n");
                w.append("    }\n");
                w.append(onReceiveType);
                w.append("    @Override public " + className + " read(net.java.html.BrwsrCtx c, Object json) { return new " + className + "(c, json); }\n");
                w.append("    @Override public " + className + " cloneTo(" + className + " o, net.java.html.BrwsrCtx c) { return o.clone(c); }\n");
                w.append("  }\n");
                w.append("  private ").append(className).append("(net.java.html.BrwsrCtx c, Object json) {\n");
                w.append("    this(c);\n");
                int values = 0;
                for (int i = 0; i < propsGetSet.size(); i++) {
                    Prprt p = findPrprt(props, propsGetSet.get(i).name);
                    if (p == null) {
                        continue;
                    }
                    values++;
                }
                w.append("    Object[] ret = new Object[" + values + "];\n");
                w.append("    proto.extract(json, new String[] {\n");
                for (int i = 0; i < propsGetSet.size(); i++) {
                    Prprt p = findPrprt(props, propsGetSet.get(i).name);
                    if (p == null) {
                        continue;
                    }
                    w.append("      \"").append(propsGetSet.get(i).name).append("\",\n");
                }
                w.append("    }, ret);\n");
                for (int i = 0, cnt = 0, prop = 0; i < propsGetSet.size(); i++) {
                    final String pn = propsGetSet.get(i).name;
                    Prprt p = findPrprt(props, pn);
                    if (p == null || prop >= props.length) {
                        continue;
                    }
                    boolean[] isModel = { false };
                    boolean[] isEnum = { false };
                    boolean isPrimitive[] = { false };
                    String type = checkType(props[prop++], isModel, isEnum, isPrimitive);
                    if (p.array()) {
                        w.append("    for (Object e : useAsArray(ret[" + cnt + "])) {\n");
                        if (isModel[0]) {
                            w.append("      this.prop_").append(pn).append(".add(proto.read");
                            w.append("(" + type + ".class, e));\n");
                        } else if (isEnum[0]) {
                            w.append("        this.prop_").append(pn);
                            w.append(".add(e == null ? null : ");
                            w.append(type).append(".valueOf(TYPE.stringValue(e)));\n");
                        } else {
                            if (isPrimitive(type)) {
                                if (type.equals("char")) {
                                    w.append("        this.prop_").append(pn).append(".add(TYPE.charValue(e));\n");
                                } else if (type.equals("boolean")) {
                                    w.append("        this.prop_").append(pn).append(".add(TYPE.boolValue(e));\n");
                                } else {
                                    w.append("        this.prop_").append(pn).append(".add(TYPE.numberValue(e).");
                                    w.append(type).append("Value());\n");
                                }
                            } else {
                                w.append("        this.prop_").append(pn).append(".add((");
                                w.append(type).append(")e);\n");
                            }
                        }
                        w.append("    }\n");
                    } else {
                        if (isEnum[0]) {
                            w.append("    try {\n");
                            w.append("    this.prop_").append(pn);
                            w.append(" = ret[" + cnt + "] == null ? null : ");
                            w.append(type).append(".valueOf(TYPE.stringValue(ret[" + cnt + "]));\n");
                            w.append("    } catch (IllegalArgumentException ex) {\n");
                            w.append("      ex.printStackTrace();\n");
                            w.append("    }\n");
                        } else if (isPrimitive(type)) {
                            w.append("    this.prop_").append(pn);
                            w.append(" = ret[" + cnt + "] == null ? ");
                            if ("char".equals(type)) {
                                w.append("0 : (TYPE.charValue(");
                            } else if ("boolean".equals(type)) {
                                w.append("false : (TYPE.boolValue(");
                            } else {
                                w.append("0 : (TYPE.numberValue(");
                            }
                            w.append("ret[" + cnt + "])).");
                            w.append(type).append("Value();\n");
                        } else if (isModel[0]) {
                            w.append("    this.prop_").append(pn).append(" = proto.read");
                            w.append("(" + type + ".class, ");
                            w.append("ret[" + cnt + "]);\n");
                        }else {
                            w.append("    this.prop_").append(pn);
                            w.append(" = (").append(type).append(')');
                            w.append("ret[" + cnt + "];\n");
                        }
                    }
                    cnt++;
                }
                w.append("  }\n");
                w.append("  private static Object[] useAsArray(Object o) {\n");
                w.append("    return o instanceof Object[] ? ((Object[])o) : o == null ? new Object[0] : new Object[] { o };\n");
                w.append("  }\n");
                writeToString(props, w);
                writeClone(className, props, w);
                String targetId = findTargetId(e);
                if (targetId != null) {
                    w.write("  /** Activates this model instance in the current {@link \n"
                        + "net.java.html.json.Models#bind(java.lang.Object, net.java.html.BrwsrCtx) browser context}. \n"
                        + "In case of using Knockout technology, this means to \n"
                        + "bind JSON like data in this model instance with Knockout tags in \n"
                        + "the surrounding HTML page.\n"
                    );
                    if (targetId != null) {
                        w.write("This method binds to element '" + targetId + "' on the page\n");
                    }
                    w.write(""
                        + "@return <code>this</code> object\n"
                        + "*/\n"
                    );
                    w.write("  public " + className + " applyBindings() {\n");
                    w.write("    proto.applyBindings();\n");
    //                w.write("    proto.applyBindings(id);\n");
                    w.write("    return this;\n");
                    w.write("  }\n");
                } else {
                    w.write("  private " + className + " applyBindings() {\n");
                    w.write("    throw new IllegalStateException(\"Please specify targetId=\\\"\\\" in your @Model annotation\");\n");
                    w.write("  }\n");
                }
                w.write("  public boolean equals(Object o) {\n");
                w.write("    if (o == this) return true;\n");
                w.write("    if (!(o instanceof " + className + ")) return false;\n");
                w.write("    " + className + " p = (" + className + ")o;\n");
                boolean thisToNull = false;
                for (Prprt p : props) {
                    boolean[] isModel = {false};
                    boolean[] isEnum = {false};
                    boolean isPrimitive[] = {false};
                    checkType(p, isModel, isEnum, isPrimitive);
                    if (isModel[0]) {
                        w.write("    if (!TYPE.isSame(thisToNull(prop_" + p.name() + "), p.thisToNull(p.prop_" + p.name() + "))) return false;\n");
                        thisToNull = true;
                    } else {
                        w.write("    if (!TYPE.isSame(prop_" + p.name() + ", p.prop_" + p.name() + ")) return false;\n");
                    }
                }
                w.write("    return true;\n");
                w.write("  }\n");
                w.write("  public int hashCode() {\n");
                w.write("    int h = " + className + ".class.getName().hashCode();\n");
                for (Prprt p : props) {
                    boolean[] isModel = {false};
                    boolean[] isEnum = {false};
                    boolean isPrimitive[] = {false};
                    checkType(p, isModel, isEnum, isPrimitive);
                    if (isModel[0]) {
                        w.write("    h = TYPE.hashPlus(thisToNull(prop_" + p.name() + "), h);\n");
                    } else {
                        w.write("    h = TYPE.hashPlus(prop_" + p.name() + ", h);\n");
                    }
                }
                w.write("    return h;\n");
                w.write("  }\n");
                if (thisToNull) {
                    w.write("  private Object thisToNull(Object value) {\n");
                    w.write("    return value == this || value == TYPE ? null : value;\n");
                    w.write("  }\n");
                }
                w.write("}\n");
            } finally {
                w.close();
            }
        } catch (IOException ex) {
            error("Can't create " + className + ".java", e);
            return false;
        }
        return ok;
    }

    private static String findBuilderPrefix(Element e, Model m) {
        if (!m.builder().isEmpty()) {
            return m.builder();
        }
        for (AnnotationMirror am : e.getAnnotationMirrors()) {
            for (Map.Entry<? extends Object, ? extends Object> entry : am.getElementValues().entrySet()) {
                if ("builder()".equals(entry.getKey().toString())) {
                    return "";
                }
            }
        }
        return null;
    }

    private static String builderMethod(String builderPrefix, Prprt p) {
        if (builderPrefix.isEmpty()) {
            return p.name();
        }
        return builderPrefix + Character.toUpperCase(p.name().charAt(0)) + p.name().substring(1);
    }

    private boolean generateProperties(
        Element where, String builderPrefix,
        Writer w, String className, Prprt[] properties,
        List<GetSet> props,
        Map<String,Collection<String[]>> deps,
        Map<String,Collection<String>> functionDeps
    ) throws IOException {
        boolean ok = true;
        for (Prprt p : properties) {
            final String tn;
            tn = typeName(p);
            String[] gs = toGetSet(p.name(), tn, p.array());
            String castTo;

            if (p.array()) {
                w.write("  private final java.util.List<" + tn + "> prop_" + p.name() + ";\n");

                castTo = "java.util.List";
                w.write("  public java.util.List<" + tn + "> " + gs[0] + "() {\n");
                w.write("    proto.accessProperty(\"" + p.name() + "\");\n");
                w.write("    return prop_" + p.name() + ";\n");
                w.write("  }\n");
                if (builderPrefix != null) {
                    boolean[] isModel = {false};
                    boolean[] isEnum = {false};
                    boolean isPrimitive[] = {false};
                    String ret = checkType(p, isModel, isEnum, isPrimitive);
                    w.write("  public " + className + " " + builderMethod(builderPrefix, p) + "(" + ret + "... v) {\n");
                    w.write("    proto.accessProperty(\"" + p.name() + "\");\n");
                    w.append("   TYPE.replaceValue(prop_").append(p.name()).append(", " + tn + ".class, v);\n");
                    w.write("    return this;\n");
                    w.write("  }\n");
                }
            } else {
                castTo = tn;
                boolean isModel[] = { false };
                boolean isEnum[] = { false };
                boolean isPrimitive[] = { false };
                checkType(p, isModel, isEnum, isPrimitive);
                if (isModel[0]) {
                    w.write("  private /*" + tn + "*/Object prop_" + p.name() + ";\n");

                } else {
                    w.write("  private " + tn + " prop_" + p.name() + ";\n");
                }
                w.write("  public " + tn + " " + gs[0] + "() {\n");
                w.write("    proto.accessProperty(\"" + p.name() + "\");\n");
                if (isModel[0]) {
                    w.write("    if (prop_" + p.name() + " == TYPE) prop_" + p.name() + " = net.java.html.json.Models.bind(new " + tn +"(), proto.getContext());\n");
                }
                w.write("    return (" + tn + ")prop_" + p.name() + ";\n");
                w.write("  }\n");
                w.write("  public void " + gs[1] + "(" + tn + " v) {\n");
                if (!p.mutable()) {
                    w.write("    proto.initTo(null, null);\n");
                }
                w.write("    proto.verifyUnlocked();\n");
                w.write("    Object o = prop_" + p.name() + ";\n");
                if (isModel[0]) {
                    w.write("    if (o == v) return;\n");
                    w.write("    prop_" + p.name() + " = v;\n");
                } else {
                    w.write("    if (TYPE.isSame(o , v)) return;\n");
                    w.write("    prop_" + p.name() + " = v;\n");
                }
                w.write("    proto.valueHasMutated(\"" + p.name() + "\", o, v);\n");
                {
                    Collection<String[]> dependants = deps.get(p.name());
                    if (dependants != null) {
                        for (String[] pair : dependants) {
                            w.write("    proto.valueHasMutated(\"" + pair[0] + "\", null, " + pair[1] + "());\n");
                        }
                    }
                }
                {
                    Collection<String> dependants = functionDeps.get(p.name());
                    if (dependants != null) {
                        w.append("    ");
                        w.append(className).append(" model = ").append(className).append(".this;\n");
                        for (String call : dependants) {
                            w.append("  ").append(call);
                        }
                    }
                }
                w.write("  }\n");
                if (builderPrefix != null) {
                    w.write("  public " + className + " " + builderMethod(builderPrefix, p) + "(" + tn + " v) {\n");
                    w.write("    " + gs[1] + "(v);\n");
                    w.write("    return this;\n");
                    w.write("  }\n");
                }
            }

            for (int i = 0; i < props.size(); i++) {
                if (props.get(i).name.equals(p.name())) {
                    error("Cannot have the property " + p.name() + " defined twice", where);
                    ok = false;
                }
            }

            props.add(new GetSet(
                p.name(),
                gs[0],
                gs[1],
                tn,
                gs[3] == null && !p.array(),
                !p.mutable()
            ));
        }
        return ok;
    }

    private boolean generateComputedProperties(
        String className,
        Writer w, Prprt[] fixedProps,
        Collection<? extends Element> arr, Collection<GetSet> props,
        Map<String,Collection<String[]>> deps
    ) throws IOException {
        boolean ok = true;
        NEXT_ANNOTATION: for (Element e : arr) {
            if (e.getKind() != ElementKind.METHOD) {
                continue;
            }
            final ComputedProperty cp = e.getAnnotation(ComputedProperty.class);
            final Transitive tp = e.getAnnotation(Transitive.class);
            if (cp == null) {
                continue;
            }
            if (!e.getModifiers().contains(Modifier.STATIC)) {
                error("Method " + e.getSimpleName() + " has to be static when annotated by @ComputedProperty", e);
                ok = false;
                continue;
            }
            ExecutableElement ee = (ExecutableElement)e;
            ExecutableElement write = null;
            if (!cp.write().isEmpty()) {
                write = findWrite(ee, (TypeElement)e.getEnclosingElement(), cp.write(), className);
                ok = write != null;
            }
            final TypeMirror rt = ee.getReturnType();
            final Types tu = processingEnv.getTypeUtils();
            TypeMirror ert = tu.erasure(rt);
            String tn = fqn(ert, ee);
            boolean array = false;
            final TypeMirror toCheck;
            if (tn.equals("java.util.List")) {
                array = true;
                toCheck = ((DeclaredType)rt).getTypeArguments().get(0);
            } else {
                toCheck = rt;
            }

            final String sn = ee.getSimpleName().toString();

            if (toCheck.getKind().isPrimitive()) {
                // OK
            } else {
                TypeMirror stringType = processingEnv.getElementUtils().getTypeElement("java.lang.String").asType();
                TypeMirror enumType = processingEnv.getElementUtils().getTypeElement("java.lang.Enum").asType();

                if (tu.isSubtype(toCheck, stringType)) {
                    // OK
                } else if (tu.isSubtype(tu.erasure(toCheck), tu.erasure(enumType))) {
                    // OK
                } else if (isModel(toCheck)) {
                    // OK
                } else {
                    try {
                        tu.unboxedType(toCheck);
                        // boxed types are OK
                    } catch (IllegalArgumentException ex) {
                        ok = false;
                        error(sn + " cannot return " + toCheck, e);
                    }
                }
            }

            final String propertyName = e.getSimpleName().toString();
            for (GetSet prop : props) {
                if (propertyName.equals(prop.name)) {
                    error("Cannot have the property " + propertyName + " defined twice", e);
                    ok = false;
                    continue NEXT_ANNOTATION;
                }
            }

            String[] gs = toGetSet(sn, tn, array);

            w.write("  public " + tn);
            if (array) {
                w.write("<" + toCheck + ">");
            }
            w.write(" " + gs[0] + "() {\n");
            int arg = 0;
            boolean deep = false;

            final List<? extends VariableElement> methodParameters = ee.getParameters();

            String unknownSingleProperty = methodParameters.size() != 1 ? null :
                verifyPropName(methodParameters.get(0), fixedProps);

            if (unknownSingleProperty == null) {
                for (VariableElement pe : methodParameters) {
                    final String dn = pe.getSimpleName().toString();

                    String unknownPropertyError = verifyPropName(pe, fixedProps);
                    if (unknownPropertyError != null) {
                        error(unknownPropertyError, e);
                        ok = false;
                    }
                    final TypeMirror pt = pe.asType();
                    if (isModel(pt)) {
                        deep = true;
                    }
                    final String dt = fqn(pt, ee);
                    if (dt.startsWith("java.util.List") && pt instanceof DeclaredType) {
                        final List<? extends TypeMirror> ptArgs = ((DeclaredType)pt).getTypeArguments();
                        if (ptArgs.size() == 1 && isModel(ptArgs.get(0))) {
                            deep = true;
                        }
                    }
                    String[] call = toGetSet(dn, dt, false);
                    w.write("    " + dt + " arg" + (++arg) + " = ");
                    w.write(call[0] + "();\n");

                    Collection<String[]> depends = deps.get(dn);
                    if (depends == null) {
                        depends = new LinkedHashSet<String[]>();
                        deps.put(dn, depends);
                    }
                    depends.add(new String[] { sn, gs[0] });
                }
            } else {
                VariableElement firstProp = methodParameters.get(0);
                TypeMirror type = firstProp.asType();
                CharSequence simpleName;
                if (type.getKind() == TypeKind.DECLARED) {
                    simpleName = ((DeclaredType) type).asElement().getSimpleName();
                } else {
                    simpleName = type.toString();
                }
                if (simpleName.toString().equals(className)) {
                } else {
                    error("Single parameter needs to be of type " + className + " or " + unknownSingleProperty, e);
                    ok = false;
                    continue NEXT_ANNOTATION;
                }
                w.write("    " + simpleName + " arg" + (++arg) + " = this;\n");
            }
            w.write("    try {\n");
            if (tp != null) {
                deep = tp.deep();
            }
            if (deep) {
                w.write("      proto.acquireLock(\"" + sn + "\");\n");
            } else {
                w.write("      proto.acquireLock();\n");
            }
            w.write("      return " + fqn(ee.getEnclosingElement().asType(), ee) + '.' + e.getSimpleName() + "(");
            String sep = "";
            for (int i = 1; i <= arg; i++) {
                w.write(sep);
                w.write("arg" + i);
                sep = ", ";
            }
            w.write(");\n");
            w.write("    } finally {\n");
            w.write("      proto.releaseLock();\n");
            w.write("    }\n");
            w.write("  }\n");

            if (write == null) {
                props.add(new GetSet(
                    propertyName,
                    gs[0],
                    null,
                    tn,
                    true,
                    false
                ));
            } else {
                w.write("  public void " + gs[4] + "(" + write.getParameters().get(1).asType());
                w.write(" value) {\n");
                w.write("    " + fqn(ee.getEnclosingElement().asType(), ee) + '.' + write.getSimpleName() + "(this, value);\n");
                w.write("  }\n");

                props.add(new GetSet(
                    propertyName,
                    gs[0],
                    gs[4],
                    tn,
                    false,
                    false
                ));
            }
        }

        return ok;
    }

    private static String[] toGetSet(String name, String type, boolean array) {
        String n = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        boolean clazz = "class".equals(name);
        String pref = clazz ? "access" : "get";
        if ("boolean".equals(type) && !array) {
            pref = "is";
        }
        if (array) {
            return new String[] {
                pref + n,
                null,
                "a" + n,
                null,
                "set" + n
            };
        }
        return new String[]{
            pref + n,
            "set" + n,
            "a" + n,
            "",
            "set" + n
        };
    }

    private String typeName(Prprt p) {
        String ret;
        boolean[] isModel = { false };
        boolean[] isEnum = { false };
        boolean isPrimitive[] = { false };
        ret = checkType(p, isModel, isEnum, isPrimitive);
        if (p.array()) {
            String bt = findBoxedType(ret);
            if (bt != null) {
                return bt;
            }
        }
        return ret;
    }

    private static String findBoxedType(String ret) {
        if (ret.equals("boolean")) {
            return Boolean.class.getName();
        }
        if (ret.equals("byte")) {
            return Byte.class.getName();
        }
        if (ret.equals("short")) {
            return Short.class.getName();
        }
        if (ret.equals("char")) {
            return Character.class.getName();
        }
        if (ret.equals("int")) {
            return Integer.class.getName();
        }
        if (ret.equals("long")) {
            return Long.class.getName();
        }
        if (ret.equals("float")) {
            return Float.class.getName();
        }
        if (ret.equals("double")) {
            return Double.class.getName();
        }
        return null;
    }

    private String verifyPropName(Element e, Prprt[] existingProps) {
        String propName = e.getSimpleName().toString();
        StringBuilder sb = new StringBuilder();
        String sep = "";
        for (Prprt Prprt : existingProps) {
            if (Prprt.name().equals(propName)) {
                return null;
            }
            sb.append(sep);
            sb.append('"');
            sb.append(Prprt.name());
            sb.append('"');
            sep = ", ";
        }
        return propName + " has to be one of known properties: " + sb;
    }

    private static String findPkgName(Element e) {
        for (;;) {
            if (e.getKind() == ElementKind.PACKAGE) {
                return ((PackageElement)e).getQualifiedName().toString();
            }
            e = e.getEnclosingElement();
        }
    }

    private boolean generateFunctions(
        Element clazz, StringWriter body, String className,
        List<? extends Element> enclosedElements, List<Object> functions
    ) {
        boolean instance = clazz.getAnnotation(Model.class).instance();
        for (Element m : enclosedElements) {
            if (m.getKind() != ElementKind.METHOD) {
                continue;
            }
            ExecutableElement e = (ExecutableElement)m;
            Function onF = e.getAnnotation(Function.class);
            if (onF == null) {
                continue;
            }
            if (!instance && !e.getModifiers().contains(Modifier.STATIC)) {
                error("@OnFunction method needs to be static", e);
                return false;
            }
            if (e.getModifiers().contains(Modifier.PRIVATE)) {
                error("@OnFunction method cannot be private", e);
                return false;
            }
            if (e.getReturnType().getKind() != TypeKind.VOID) {
                error("@OnFunction method should return void", e);
                return false;
            }
            String n = e.getSimpleName().toString();
            functions.add(n);
            functions.add(e);
        }
        return true;
    }

    private boolean generateOnChange(Element clazz, Map<String,Collection<String[]>> propDeps,
        Prprt[] properties, String className,
        Map<String, Collection<String>> functionDeps
    ) {
        boolean instance = clazz.getAnnotation(Model.class).instance();
        for (Element m : clazz.getEnclosedElements()) {
            if (m.getKind() != ElementKind.METHOD) {
                continue;
            }
            ExecutableElement e = (ExecutableElement) m;
            OnPropertyChange onPC = e.getAnnotation(OnPropertyChange.class);
            if (onPC == null) {
                continue;
            }
            for (String pn : onPC.value()) {
                if (findPrprt(properties, pn) == null && findDerivedFrom(propDeps, pn).isEmpty()) {
                    error("No Prprt named '" + pn + "' in the model", clazz);
                    return false;
                }
            }
            if (!instance && !e.getModifiers().contains(Modifier.STATIC)) {
                error("@OnPrprtChange method needs to be static", e);
                return false;
            }
            if (e.getModifiers().contains(Modifier.PRIVATE)) {
                error("@OnPrprtChange method cannot be private", e);
                return false;
            }
            if (e.getReturnType().getKind() != TypeKind.VOID) {
                error("@OnPrprtChange method should return void", e);
                return false;
            }
            String n = e.getSimpleName().toString();


            for (String pn : onPC.value()) {
                StringBuilder call = new StringBuilder();
                call.append("  ").append(inPckName(clazz, instance)).append(".").append(n).append("(");
                call.append(wrapPropName(e, className, "name", pn));
                call.append(");\n");

                Collection<String> change = functionDeps.get(pn);
                if (change == null) {
                    change = new ArrayList<String>();
                    functionDeps.put(pn, change);
                }
                change.add(call.toString());
                for (String dpn : findDerivedFrom(propDeps, pn)) {
                    change = functionDeps.get(dpn);
                    if (change == null) {
                        change = new ArrayList<String>();
                        functionDeps.put(dpn, change);
                    }
                    change.add(call.toString());
                }
            }
        }
        return true;
    }

    private boolean generateOperation(Element clazz,
        StringWriter body, String className,
        List<? extends Element> enclosedElements,
        List<Object> functions
    ) {
        final Types tu = processingEnv.getTypeUtils();
        boolean instance = clazz.getAnnotation(Model.class).instance();
        for (Element m : enclosedElements) {
            if (m.getKind() != ElementKind.METHOD) {
                continue;
            }
            ExecutableElement e = (ExecutableElement)m;
            ModelOperation mO = e.getAnnotation(ModelOperation.class);
            if (mO == null) {
                continue;
            }
            if (!instance && !e.getModifiers().contains(Modifier.STATIC)) {
                error("@ModelOperation method needs to be static", e);
                return false;
            }
            if (e.getModifiers().contains(Modifier.PRIVATE)) {
                error("@ModelOperation method cannot be private", e);
                return false;
            }
            if (e.getReturnType().getKind() != TypeKind.VOID) {
                error("@ModelOperation method should return void", e);
                return false;
            }
            List<String> args = new ArrayList<String>();
            {
                body.append("  /** @see " + clazz.getSimpleName() + "#" + m.getSimpleName() + " */\n");
                body.append("  public void ").append(m.getSimpleName()).append("(");
                String sep = "";
                boolean checkFirst = true;
                for (VariableElement ve : e.getParameters()) {
                    final TypeMirror type = ve.asType();
                    CharSequence simpleName;
                    if (type.getKind() == TypeKind.DECLARED) {
                        simpleName = ((DeclaredType)type).asElement().getSimpleName();
                    } else {
                        simpleName = type.toString();
                    }
                    if (checkFirst && simpleName.toString().equals(className)) {
                        checkFirst = false;
                    } else {
                        if (checkFirst) {
                            error("First parameter of @ModelOperation method must be " + className, m);
                            return false;
                        }
                        if (ve.asType().getKind() == TypeKind.ARRAY) {
                            args.add("(Object) " + ve.getSimpleName().toString());
                        } else {
                            args.add(ve.getSimpleName().toString());
                        }
                        body.append(sep).append("final ");
                        body.append(ve.asType().toString()).append(" ");
                        body.append(ve.toString());
                        sep = ", ";
                    }
                }
                body.append(") {\n");
                int idx = functions.size() / 2;
                functions.add(m.getSimpleName().toString());
                body.append("    proto.runInBrowser(" + idx);
                for (String s : args) {
                    body.append(", ").append(s);
                }
                body.append(");\n");
                body.append("  }\n");

                StringBuilder call = new StringBuilder();
                call.append("{ Object[] arr = (Object[])data; ");
                call.append(inPckName(clazz, true)).append(".").append(m.getSimpleName()).append("(");
                int i = 0;
                for (VariableElement ve : e.getParameters()) {
                    if (i++ == 0) {
                        call.append("model");
                        continue;
                    }
                    String type = ve.asType().toString();
                    String boxedType = findBoxedType(type);
                    if (boxedType != null) {
                        type = boxedType;
                    }
                    call.append(", ").append("(").append(type).append(")arr[").append(i - 2).append("]");
                }
                call.append("); }");
                functions.add(call.toString());
            }

        }
        return true;
    }


    private boolean generateReceive(
        Element clazz, StringWriter body, String className,
        List<? extends Element> enclosedElements, StringBuilder inType
    ) {
        boolean ret = generateReceiveImpl(clazz, body, className, enclosedElements, inType);
        if (!ret) {
            inType.setLength(0);
        }
        return ret;
    }
    private boolean generateReceiveImpl(
        Element clazz, StringWriter body, String className,
        List<? extends Element> enclosedElements, StringBuilder inType
    ) {
        inType.append("  @Override public void onMessage(").append(className).append(" model, int index, int type, Object data, Object[] params) {\n");
        inType.append("    switch (index) {\n");
        int index = 0;
        boolean ok = true;
        boolean instance = clazz.getAnnotation(Model.class).instance();
        for (Element m : enclosedElements) {
            if (m.getKind() != ElementKind.METHOD) {
                continue;
            }
            ExecutableElement e = (ExecutableElement)m;
            OnReceive onR = e.getAnnotation(OnReceive.class);
            if (onR == null) {
                continue;
            }
            if (!instance && !e.getModifiers().contains(Modifier.STATIC)) {
                error("@OnReceive method needs to be static", e);
                return false;
            }
            if (e.getModifiers().contains(Modifier.PRIVATE)) {
                error("@OnReceive method cannot be private", e);
                return false;
            }
            if (e.getReturnType().getKind() != TypeKind.VOID) {
                error("@OnReceive method should return void", e);
                return false;
            }
            if (!onR.jsonp().isEmpty() && !"GET".equals(onR.method())) {
                error("JSONP works only with GET transport method", e);
            }
            String dataMirror = findDataSpecified(e, onR);
            if ("PUT".equals(onR.method()) && dataMirror == null) {
                error("PUT method needs to specify a data() class", e);
                return false;
            }
            if ("POST".equals(onR.method()) && dataMirror == null) {
                error("POST method needs to specify a data() class", e);
                return false;
            }
            if (e.getParameters().size() < 2) {
                error("@OnReceive method needs at least two parameters", e);
            }
            final boolean isWebSocket = "WebSocket".equals(onR.method());
            if (isWebSocket && dataMirror == null) {
                error("WebSocket method needs to specify a data() class", e);
            }
            int expectsList = 0;
            List<String> args = new ArrayList<String>();
            List<String> params = new ArrayList<String>();
            // first argument is model class
            {
                TypeMirror type = e.getParameters().get(0).asType();
                CharSequence simpleName;
                if (type.getKind() == TypeKind.DECLARED) {
                    simpleName = ((DeclaredType) type).asElement().getSimpleName();
                } else {
                    simpleName = type.toString();
                }
                if (simpleName.toString().equals(className)) {
                    args.add("model");
                } else {
                    error("First parameter needs to be " + className, e);
                    return false;
                }
            }

            String modelClass;
            {
                final Types tu = processingEnv.getTypeUtils();
                TypeMirror type = e.getParameters().get(1).asType();
                TypeMirror modelType = null;
                TypeMirror ert = tu.erasure(type);

                if (isModel(type)) {
                    modelType = type;
                } else if (type.getKind() == TypeKind.ARRAY) {
                    modelType = ((ArrayType)type).getComponentType();
                    expectsList = 1;
                } else if ("java.util.List".equals(fqn(ert, e))) {
                    List<? extends TypeMirror> typeArgs = ((DeclaredType)type).getTypeArguments();
                    if (typeArgs.size() == 1) {
                        modelType = typeArgs.get(0);
                        expectsList = 2;
                    }
                } else if (type.toString().equals("java.lang.String")) {
                    modelType = type;
                }
                if (modelType == null) {
                    error("Second arguments needs to be a model, String or array or List of models", e);
                    return false;
                }
                modelClass = modelType.toString();
                if (expectsList == 1) {
                    args.add("arr");
                } else if (expectsList == 2) {
                    args.add("net.java.html.json.Models.asList(arr)");
                } else {
                    args.add("arr[0]");
                }
            }
            String n = e.getSimpleName().toString();
            String c = inPckName(clazz, false);
            if (isWebSocket) {
                body.append("  /** Performs WebSocket communication and then calls {@link ");
                body.append(c).append("#").append(n).append("}.\n");
                body.append("  * Call with <code>null</code> data parameter\n");
                body.append("  * to open the connection (even if not required). Call with non-null data to\n");
                body.append("  * send messages to server. Call again with <code>null</code> data to close the socket.\n");
                body.append("  */\n");
                if (onR.headers().length > 0) {
                    error("WebSocket spec does not support headers", e);
                }
            } else {
                body.append("  /** Performs network communication and then calls {@link ");
                body.append(c).append("#").append(n).append("}.\n");
                body.append("  */\n");
            }
            body.append("  public void ").append(n).append("(");
            StringBuilder urlBefore = new StringBuilder();
            StringBuilder urlAfter = new StringBuilder();
            StringBuilder headers = new StringBuilder();
            String jsonpVarName = null;
            {
                String sep = "";
                boolean skipJSONP = onR.jsonp().isEmpty();
                Set<String> receiveParams = new LinkedHashSet<String>();
                findParamNames(receiveParams, e, onR.url(), onR.jsonp(), urlBefore, urlAfter);
                for (String headerLine : onR.headers()) {
                    if (headerLine.contains("\r") || headerLine.contains("\n")) {
                        error("Header line cannot contain line separator", e);
                    }
                    findParamNames(receiveParams, e, headerLine, null, headers);
                    headers.append("+ \"\\r\\n\" +\n");
                }
                if (headers.length() > 0) {
                    headers.append("\"\"");
                }
                for (String p : receiveParams) {
                    if (!skipJSONP && p.equals(onR.jsonp())) {
                        skipJSONP = true;
                        jsonpVarName = p;
                        continue;
                    }
                    body.append(sep);
                    body.append("String ").append(p);
                    sep = ", ";
                }
                if (!skipJSONP) {
                    error(
                        "Name of jsonp attribute ('" + onR.jsonp() +
                        "') is not used in url attribute '" + onR.url() + "'", e
                    );
                }
                if (dataMirror != null) {
                    body.append(sep).append(dataMirror.toString()).append(" data");
                }
                for (int i = 2; i < e.getParameters().size(); i++) {
                    if (isWebSocket) {
                        error("@OnReceive(method=\"WebSocket\") can only have two arguments", e);
                        ok = false;
                    }

                    VariableElement ve = e.getParameters().get(i);
                    body.append(sep).append(ve.asType().toString()).append(" ").append(ve.getSimpleName());
                    final String tp = ve.asType().toString();
                    String btn = findBoxedType(tp);
                    if (btn == null) {
                        btn = tp;
                    }
                    args.add("(" + btn + ")params[" + (i - 2) + "]");
                    params.add(ve.getSimpleName().toString());
                    sep = ", ";
                }
            }
            body.append(") {\n");
            boolean webSocket = onR.method().equals("WebSocket");
            if (webSocket) {
                if (generateWSReceiveBody(index++, body, inType, onR, e, clazz, className, expectsList != 0, modelClass, n, args, params, urlBefore, jsonpVarName, urlAfter, dataMirror, headers)) {
                    return false;
                }
                body.append("  }\n");
                body.append("  private Object ws_" + e.getSimpleName() + ";\n");
            } else {
                if (generateJSONReceiveBody(index++, body, inType, onR, e, clazz, className, expectsList != 0, modelClass, n, args, params, urlBefore, jsonpVarName, urlAfter, dataMirror, headers)) {
                    ok = false;
                }
                body.append("  }\n");
            }
        }
        inType.append("    }\n");
        inType.append("    throw new UnsupportedOperationException(\"index: \" + index + \" type: \" + type);\n");
        inType.append("  }\n");
        return ok;
    }

    private boolean generateJSONReceiveBody(int index, StringWriter method, StringBuilder body, OnReceive onR, ExecutableElement e, Element clazz, String className, boolean expectsList, String modelClass, String n, List<String> args, List<String> params, StringBuilder urlBefore, String jsonpVarName, StringBuilder urlAfter, String dataMirror, StringBuilder headers) {
        boolean error = false;
        body.append(
            "    case " + index + ": {\n" +
            "      if (type == 2) { /* on error */\n" +
            "        Exception ex = (Exception)data;\n"
            );
        if (onR.onError().isEmpty()) {
            body.append(
                "        ex.printStackTrace();\n"
                );
        } else {
            int errorParamsLength = findOnError(e, ((TypeElement)clazz), onR.onError(), className);
            error = errorParamsLength < 0;
            body.append("        ").append(clazz.getSimpleName()).append(".").append(onR.onError()).append("(");
            body.append("model, ex");
            for (int i = 2; i < errorParamsLength; i++) {
                String arg = args.get(i);
                body.append(", ");
                if (arg.startsWith("arr") || arg.startsWith("java.util.Array")) {
                    body.append("null");
                } else {
                    body.append(arg);
                }
            }
            body.append(");\n");
        }
        body.append(
            "        return;\n" +
            "      } else if (type == 1) {\n" +
            "        Object[] ev = (Object[])data;\n"
            );
        if (expectsList) {
            body.append(
                "        " + modelClass + "[] arr = new " + modelClass + "[ev.length];\n"
                );
        } else {
            body.append(
                "        " + modelClass + "[] arr = { null };\n"
                );
        }
        body.append(
            "        TYPE.copyJSON(model.proto.getContext(), ev, " + modelClass + ".class, arr);\n"
        );
        {
            body.append("        ").append(clazz.getSimpleName()).append(".").append(n).append("(");
            String sep = "";
            for (String arg : args) {
                body.append(sep);
                body.append(arg);
                sep = ", ";
            }
            body.append(");\n");
        }
        body.append(
            "        return;\n" +
            "      }\n" +
            "    }\n"
            );
        method.append("    proto.loadJSONWithHeaders(" + index + ",\n        ");
        method.append(headers.length() == 0 ? "null" : headers).append(",\n        ");
        method.append(urlBefore).append(", ");
        if (jsonpVarName != null) {
            method.append(urlAfter);
        } else {
            method.append("null");
        }
        if (!"GET".equals(onR.method()) || dataMirror != null) {
            method.append(", \"").append(onR.method()).append('"');
            if (dataMirror != null) {
                method.append(", data");
            } else {
                method.append(", null");
            }
        } else {
            method.append(", null, null");
        }
        for (String a : params) {
            method.append(", ").append(a);
        }
        method.append(");\n");
        return error;
    }

    private boolean generateWSReceiveBody(int index, StringWriter method, StringBuilder body, OnReceive onR, ExecutableElement e, Element clazz, String className, boolean expectsList, String modelClass, String n, List<String> args, List<String> params, StringBuilder urlBefore, String jsonpVarName, StringBuilder urlAfter, String dataMirror, StringBuilder headers) {
        body.append(
            "    case " + index + ": {\n" +
            "      if (type == 0) { /* on open */\n" +
            "        ").append(inPckName(clazz, true)).append(".").append(n).append("(");
        {
            String sep = "";
            for (String arg : args) {
                body.append(sep);
                if (arg.startsWith("arr") || arg.startsWith("net.java.html.json.Models.asList")) {
                    body.append("null");
                } else {
                    body.append(arg);
                }
                sep = ", ";
            }
        }
        body.append(");\n");
        body.append(
            "        return;\n" +
            "      } else if (type == 2) { /* on error */\n" +
            "        Exception value = (Exception)data;\n"
            );
        if (onR.onError().isEmpty()) {
            body.append(
                "        value.printStackTrace();\n"
                );
        } else {
            int errorParamsLength = findOnError(e, ((TypeElement)clazz), onR.onError(), className);
            if (errorParamsLength < 0) {
                return true;
            }
            body.append("        ").append(inPckName(clazz, true)).append(".").append(onR.onError()).append("(");
            body.append("model, value");
            for (int i = 2; i < errorParamsLength; i++) {
                String arg = args.get(i);
                body.append(", ");
                if (arg.startsWith("arr") || arg.startsWith("java.util.Array")) {
                    body.append("null");
                } else {
                    body.append(arg);
                }
            }
            body.append(");\n");
        }
        body.append(
            "        return;\n" +
            "      } else if (type == 1) {\n" +
            "        Object[] ev = (Object[])data;\n"
        );
        if (expectsList) {
            body.append(
                "        " + modelClass + "[] arr = new " + modelClass + "[ev.length];\n"
                );
        } else {
            body.append(
                "        " + modelClass + "[] arr = { null };\n"
                );
        }
        body.append(
            "        TYPE.copyJSON(model.proto.getContext(), ev, " + modelClass + ".class, arr);\n"
        );
        {
            body.append("        ").append(inPckName(clazz, true)).append(".").append(n).append("(");
            String sep = "";
            for (String arg : args) {
                body.append(sep);
                body.append(arg);
                sep = ", ";
            }
            body.append(");\n");
        }
        body.append(
            "        return;\n" +
            "      }"
        );
        if (!onR.onError().isEmpty()) {
            body.append(" else if (type == 3) { /* on close */\n");
            body.append("        ").append(inPckName(clazz, true)).append(".").append(onR.onError()).append("(");
            body.append("model, null);\n");
            body.append(
                "        return;" +
                "      }"
            );
        }
        body.append("\n");
        body.append("    }\n");
        method.append("    if (this.ws_").append(e.getSimpleName()).append(" == null) {\n");
        method.append("      this.ws_").append(e.getSimpleName());
        method.append("= proto.wsOpen(" + index + ", ");
        method.append(urlBefore).append(", data);\n");
        method.append("    } else {\n");
        method.append("      proto.wsSend(this.ws_").append(e.getSimpleName()).append(", ").append(urlBefore).append(", data");
        for (String a : params) {
            method.append(", ").append(a);
        }
        method.append(");\n");
        method.append("    }\n");
        return false;
    }

    private CharSequence wrapParams(
        ExecutableElement ee, String id, String className, String classRef, String evName, String dataName
    ) {
        TypeMirror stringType = processingEnv.getElementUtils().getTypeElement("java.lang.String").asType();
        StringBuilder params = new StringBuilder();
        boolean first = true;
        for (VariableElement ve : ee.getParameters()) {
            if (!first) {
                params.append(", ");
            }
            first = false;
            String toCall = null;
            String toFinish = null;
            boolean addNull = true;
            if (ve.asType() == stringType) {
                if (ve.getSimpleName().contentEquals("id")) {
                    params.append('"').append(id).append('"');
                    continue;
                }
                toCall = classRef + ".proto.toString(";
            }
            if (ve.asType().getKind() == TypeKind.DOUBLE) {
                toCall = classRef + ".proto.toNumber(";
                toFinish = ".doubleValue()";
            }
            if (ve.asType().getKind() == TypeKind.FLOAT) {
                toCall = classRef + ".proto.toNumber(";
                toFinish = ".floatValue()";
            }
            if (ve.asType().getKind() == TypeKind.INT) {
                toCall = classRef + ".proto.toNumber(";
                toFinish = ".intValue()";
            }
            if (ve.asType().getKind() == TypeKind.BYTE) {
                toCall = classRef + ".proto.toNumber(";
                toFinish = ".byteValue()";
            }
            if (ve.asType().getKind() == TypeKind.SHORT) {
                toCall = classRef + ".proto.toNumber(";
                toFinish = ".shortValue()";
            }
            if (ve.asType().getKind() == TypeKind.LONG) {
                toCall = classRef + ".proto.toNumber(";
                toFinish = ".longValue()";
            }
            if (ve.asType().getKind() == TypeKind.BOOLEAN) {
                toCall = "\"true\".equals(" + classRef + ".proto.toString(";
                toFinish = ")";
            }
            if (ve.asType().getKind() == TypeKind.CHAR) {
                toCall = "(char)" + classRef + ".proto.toNumber(";
                toFinish = ".intValue()";
            }
            if (dataName != null && ve.getSimpleName().contentEquals(dataName) && isModel(ve.asType())) {
                toCall = classRef + ".proto.toModel(" + ve.asType() + ".class, ";
                addNull = false;
            }

            if (toCall != null) {
                params.append(toCall);
                if (dataName != null && ve.getSimpleName().contentEquals(dataName)) {
                    params.append(dataName);
                    if (addNull) {
                        params.append(", null");
                    }
                } else {
                    if (evName == null) {
                        final StringBuilder sb = new StringBuilder();
                        sb.append("Unexpected string parameter name.");
                        if (dataName != null) {
                            sb.append(" Try \"").append(dataName).append("\"");
                        }
                        error(sb.toString(), ee);
                    }
                    params.append(evName);
                    params.append(", \"");
                    params.append(ve.getSimpleName().toString());
                    params.append("\"");
                }
                params.append(")");
                if (toFinish != null) {
                    params.append(toFinish);
                }
                continue;
            }
            String rn = fqn(ve.asType(), ee);
            int last = rn.lastIndexOf('.');
            if (last >= 0) {
                rn = rn.substring(last + 1);
            }
            if (rn.equals(className)) {
                params.append(classRef);
                continue;
            }
            StringBuilder err = new StringBuilder();
            err.append("Argument ").
                append(ve.getSimpleName()).
                append(" is not valid. The annotated method can only accept ").
                append(className).
                append(" argument");
            if (dataName != null) {
                err.append(" or argument named '").append(dataName).append("'");
            }
            err.append(".");
            error(err.toString(), ee);
        }
        return params;
    }


    private CharSequence wrapPropName(
        ExecutableElement ee, String className, String propName, String propValue
    ) {
        TypeMirror stringType = processingEnv.getElementUtils().getTypeElement("java.lang.String").asType();
        StringBuilder params = new StringBuilder();
        boolean first = true;
        for (VariableElement ve : ee.getParameters()) {
            if (!first) {
                params.append(", ");
            }
            first = false;
            if (ve.asType() == stringType) {
                if (propName != null && ve.getSimpleName().contentEquals(propName)) {
                    params.append('"').append(propValue).append('"');
                } else {
                    error("Unexpected string parameter name. Try \"" + propName + "\".", ee);
                }
                continue;
            }
            String rn = fqn(ve.asType(), ee);
            int last = rn.lastIndexOf('.');
            if (last >= 0) {
                rn = rn.substring(last + 1);
            }
            if (rn.equals(className)) {
                params.append("model");
                continue;
            }
            error(
                "@OnPrprtChange method can only accept String or " + className + " arguments",
                ee);
        }
        return params;
    }

    private boolean isModel(TypeMirror tm) {
        if (tm.getKind() == TypeKind.ERROR) {
            return true;
        }
        final Element e = processingEnv.getTypeUtils().asElement(tm);
        if (e == null) {
            return false;
        }
        for (Element ch : e.getEnclosedElements()) {
            if (ch.getKind() == ElementKind.METHOD) {
                ExecutableElement ee = (ExecutableElement)ch;
                if (ee.getParameters().isEmpty() && ee.getSimpleName().contentEquals("modelFor")) {
                    return true;
                }
            }
        }
        return models.values().contains(e.getSimpleName().toString());
    }

    private void writeToString(Prprt[] props, Writer w) throws IOException {
        w.write("  public String toString() {\n");
        w.write("    StringBuilder sb = new StringBuilder();\n");
        w.write("    sb.append('{');\n");
        String sep = "";
        for (Prprt p : props) {
            w.write(sep);
            w.append("    sb.append('\"').append(\"" + p.name() + "\")");
                w.append(".append('\"').append(\":\");\n");
            String tn = typeName(p);
            String[] gs = toGetSet(p.name(), tn, p.array());
            boolean isModel[] = { false };
            boolean isEnum[] = { false };
            boolean isPrimitive[] = { false };
            checkType(p, isModel, isEnum, isPrimitive);
            if (isModel[0]) {
                w.append("    sb.append(TYPE.toJSON(thisToNull(this.prop_");
                w.append(p.name()).append(")));\n");
            } else {
                w.append("    sb.append(TYPE.toJSON(");
                w.append(gs[0]).append("()));\n");
            }
            sep =    "    sb.append(',');\n";
        }
        w.write("    sb.append('}');\n");
        w.write("    return sb.toString();\n");
        w.write("  }\n");
    }
    private void writeClone(String className, Prprt[] props, Writer w) throws IOException {
        w.write("  public " + className + " clone() {\n");
        w.write("    return clone(proto.getContext());\n");
        w.write("  }\n");
        w.write("  private " + className + " clone(net.java.html.BrwsrCtx ctx) {\n");
        w.write("    " + className + " ret = new " + className + "(ctx);\n");
        for (Prprt p : props) {
            String tn = typeName(p);
            String[] gs = toGetSet(p.name(), tn, p.array());
            if (!p.array()) {
                boolean isModel[] = { false };
                boolean isEnum[] = { false };
                boolean isPrimitive[] = { false };
                checkType(p, isModel, isEnum, isPrimitive);
                if (!isModel[0]) {
                    w.write("    ret.prop_" + p.name() + " = " + gs[0] + "();\n");
                    continue;
                }
                w.write("    ret.prop_" + p.name() + " =  prop_" + p.name() + " == null ? null : prop_" + p.name() + " == TYPE ? TYPE : net.java.html.json.Models.bind(" + gs[0] + "(), ctx);\n");
            } else {
                w.write("    proto.cloneList(ret." + gs[0] + "(), ctx, prop_" + p.name() + ");\n");
            }
        }

        w.write("    return ret;\n");
        w.write("  }\n");
    }

    private String inPckName(Element e, boolean preferInstance) {
        if (preferInstance && e.getAnnotation(Model.class).instance()) {
            return "model.instance";
        }
        StringBuilder sb = new StringBuilder();
        while (e.getKind() != ElementKind.PACKAGE) {
            if (sb.length() == 0) {
                sb.append(e.getSimpleName());
            } else {
                sb.insert(0, '.');
                sb.insert(0, e.getSimpleName());
            }
            e = e.getEnclosingElement();
        }
        return sb.toString();
    }

    private String fqn(TypeMirror pt, Element relative) {
        if (pt.getKind() == TypeKind.ERROR) {
            final Elements eu = processingEnv.getElementUtils();
            PackageElement pckg = eu.getPackageOf(relative);
            return pckg.getQualifiedName() + "." + pt.toString();
        }
        return pt.toString();
    }

    private String checkType(Prprt p, boolean[] isModel, boolean[] isEnum, boolean[] isPrimitive) {
        TypeMirror tm;
        try {
            String ret = p.typeName(processingEnv);
            TypeElement e = processingEnv.getElementUtils().getTypeElement(ret);
            if (e == null) {
                isModel[0] = true;
                isEnum[0] = false;
                isPrimitive[0] = false;
                return ret;
            }
            tm = e.asType();
        } catch (MirroredTypeException ex) {
            tm = ex.getTypeMirror();
        }
        tm = processingEnv.getTypeUtils().erasure(tm);
        if (isPrimitive[0] = tm.getKind().isPrimitive()) {
            isEnum[0] = false;
            isModel[0] = false;
            return tm.toString();
        }
        final Element e = processingEnv.getTypeUtils().asElement(tm);
        if (e.getKind() == ElementKind.CLASS && tm.getKind() == TypeKind.ERROR) {
            isModel[0] = true;
            isEnum[0] = false;
            return e.getSimpleName().toString();
        }

        TypeMirror enm = processingEnv.getElementUtils().getTypeElement("java.lang.Enum").asType();
        enm = processingEnv.getTypeUtils().erasure(enm);
        isEnum[0] = processingEnv.getTypeUtils().isSubtype(tm, enm);

        String ret;
        if (!isEnum[0]) {
            final Model m = e == null ? null : e.getAnnotation(Model.class);
            if (m != null) {
                ret = findPkgName(e) + '.' + m.className();
                isModel[0] = true;
                models.put(e, m.className());
            } else if (findModelForMthd(e)) {
                ret = ((TypeElement)e).getQualifiedName().toString();
                isModel[0] = true;
            } else {
                ret = tm.toString();
            }
        } else {
            ret = tm.toString();
        }
        return ret;
    }

    private static boolean findModelForMthd(Element clazz) {
        if (clazz == null) {
            return false;
        }
        for (Element e : clazz.getEnclosedElements()) {
            if (e.getKind() == ElementKind.METHOD) {
                ExecutableElement ee = (ExecutableElement)e;
                if (
                    ee.getSimpleName().contentEquals("modelFor") &&
                    ee.getParameters().isEmpty()
                ) {
                    return true;
                }
            }
        }
        return false;
    }

    private void findParamNames(
        Set<String> params, Element e, String url, String jsonParam, StringBuilder... both
    ) {
        int wasJSON = 0;

        for (int pos = 0; ;) {
            int next = url.indexOf('{', pos);
            if (next == -1) {
                both[wasJSON].append('"')
                    .append(url.substring(pos).replace("\"", "\\\""))
                    .append('"');
                return;
            }
            int close = url.indexOf('}', next);
            if (close == -1) {
                error("Unbalanced '{' and '}' in " + url, e);
                return;
            }
            final String paramName = url.substring(next + 1, close);
            params.add(paramName);
            if (paramName.equals(jsonParam) && !jsonParam.isEmpty()) {
                both[wasJSON].append('"')
                    .append(url.substring(pos, next).replace("\"", "\\\""))
                    .append('"');
                wasJSON = 1;
            } else {
                both[wasJSON].append('"')
                    .append(url.substring(pos, next).replace("\"", "\\\""))
                    .append("\" + ").append(paramName).append(" + ");
            }
            pos = close + 1;
        }
    }

    private static Prprt findPrprt(Prprt[] properties, String propName) {
        for (Prprt p : properties) {
            if (propName.equals(p.name())) {
                return p;
            }
        }
        return null;
    }

    private boolean isPrimitive(String type) {
        return
            "int".equals(type) ||
            "double".equals(type) ||
            "long".equals(type) ||
            "short".equals(type) ||
            "byte".equals(type) ||
            "char".equals(type) ||
            "boolean".equals(type) ||
            "float".equals(type);
    }

    private static Collection<String> findDerivedFrom(Map<String, Collection<String[]>> propsDeps, String derivedProp) {
        Set<String> names = new HashSet<String>();
        for (Map.Entry<String, Collection<String[]>> e : propsDeps.entrySet()) {
            for (String[] pair : e.getValue()) {
                if (pair[0].equals(derivedProp)) {
                    names.add(e.getKey());
                    break;
                }
            }
        }
        return names;
    }

    private Prprt[] createProps(Element e, Property[] arr) {
        Prprt[] ret = Prprt.wrap(processingEnv, e, arr);
        Prprt[] prev = verify.put(e, ret);
        if (prev != null) {
            error("Two sets of properties for ", e);
        }
        return ret;
    }

    private String findDataSpecified(ExecutableElement e, OnReceive onR) {
        try {
            return onR.data().getName();
        } catch (MirroredTypeException ex) {
            final TypeMirror tm = ex.getTypeMirror();
            String name;
            final Element te = processingEnv.getTypeUtils().asElement(tm);
            if (te.getKind() == ElementKind.CLASS && tm.getKind() == TypeKind.ERROR) {
                name = te.getSimpleName().toString();
            } else {
                name = tm.toString();
            }
            return "java.lang.Object".equals(name) ? null : name;
        } catch (Exception ex) {
            // fallback
        }

        AnnotationMirror found = null;
        for (AnnotationMirror am : e.getAnnotationMirrors()) {
            if (am.getAnnotationType().toString().equals(OnReceive.class.getName())) {
                found = am;
            }
        }
        if (found == null) {
            return null;
        }

        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : found.getElementValues().entrySet()) {
            ExecutableElement ee = entry.getKey();
            AnnotationValue av = entry.getValue();
            if (ee.getSimpleName().contentEquals("data")) {
                List<? extends Object> values = getAnnoValues(processingEnv, e, found);
                for (Object v : values) {
                    String sv = v.toString();
                    if (sv.startsWith("data = ") && sv.endsWith(".class")) {
                        return sv.substring(7, sv.length() - 6);
                    }
                }
                return "error";
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    static List<? extends Object> getAnnoValues(ProcessingEnvironment pe, Element e, AnnotationMirror am) {
        try {
            Class<?> trees = Class.forName("com.sun.tools.javac.api.JavacTrees");
            Method m = trees.getMethod("instance", ProcessingEnvironment.class);
            Object instance = m.invoke(null, pe);
            m = instance.getClass().getMethod("getPath", Element.class, AnnotationMirror.class);
            Object path = m.invoke(instance, e, am);
            m = path.getClass().getMethod("getLeaf");
            Object leaf = m.invoke(path);
            m = leaf.getClass().getMethod("getArguments");
            return (List) m.invoke(leaf);
        } catch (Exception ex) {
            return Collections.emptyList();
        }
    }

    private static String findTargetId(Element e) {
        for (AnnotationMirror m : e.getAnnotationMirrors()) {
            if (m.getAnnotationType().toString().equals(Model.class.getName())) {
                for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entrySet : m.getElementValues().entrySet()) {
                    ExecutableElement key = entrySet.getKey();
                    AnnotationValue value = entrySet.getValue();
                    if ("targetId()".equals(key.toString())) {
                        return value.toString();
                    }
                }
            }
        }
        return null;
    }

    private static class Prprt {
        private final Element e;
        private final AnnotationMirror tm;
        private final Property p;

        public Prprt(Element e, AnnotationMirror tm, Property p) {
            this.e = e;
            this.tm = tm;
            this.p = p;
        }

        String name() {
            return p.name();
        }

        boolean array() {
            return p.array();
        }

        boolean mutable() {
            return p.mutable();
        }

        String typeName(ProcessingEnvironment env) {
            RuntimeException ex;
            try {
                return p.type().getName();
            } catch (IncompleteAnnotationException e) {
                ex = e;
            } catch (AnnotationTypeMismatchException e) {
                ex = e;
            }
            for (Object v : getAnnoValues(env, e, tm)) {
                String s = v.toString().replace(" ", "");
                if (s.startsWith("type=") && s.endsWith(".class")) {
                    return s.substring(5, s.length() - 6);
                }
            }
            throw ex;
        }


        @SuppressWarnings("unchecked")
        static Prprt[] wrap(ProcessingEnvironment pe, Element e, Property[] arr) {
            if (arr.length == 0) {
                return new Prprt[0];
            }

            if (!e.getKind().isClass()) {
                throw new IllegalStateException("" + e.getKind());
            }
            TypeElement te = (TypeElement)e;
            List<? extends AnnotationValue> val = null;
            for (AnnotationMirror an : te.getAnnotationMirrors()) {
                for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : an.getElementValues().entrySet()) {
                    if (entry.getKey().getSimpleName().contentEquals("properties")) {
                        val = (List)entry.getValue().getValue();
                        break;
                    }
                }
            }
            if (val == null || val.size() != arr.length) {
                pe.getMessager().printMessage(Diagnostic.Kind.ERROR, "" + val, e);
                return new Prprt[0];
            }
            Prprt[] ret = new Prprt[arr.length];
            BIG: for (int i = 0; i < ret.length; i++) {
                AnnotationMirror am = (AnnotationMirror)val.get(i).getValue();
                ret[i] = new Prprt(e, am, arr[i]);

            }
            return ret;
        }
    } // end of Prprt

    private static final class GetSet {
        final String name;
        final String getter;
        final String setter;
        final String type;
        final boolean readOnly;
        final boolean constant;
        
        GetSet(String name, String getter, String setter, String type, boolean readOnly, boolean constant) {
            this.name = name;
            this.getter = getter;
            this.setter = setter;
            this.type = type;
            this.readOnly = readOnly;
            this.constant = constant;
        }
    }

    @Override
    public Iterable<? extends Completion> getCompletions(Element element, AnnotationMirror annotation, ExecutableElement member, String userText) {
        final Level l = Level.FINE;
        LOG.log(l, " element: {0}", element);
        LOG.log(l, " annotation: {0}", annotation);
        LOG.log(l, " member: {0}", member);
        LOG.log(l, " userText: {0}", userText);
        LOG.log(l, "str: {0}", annotation.getAnnotationType().toString());
        if (annotation.getAnnotationType().toString().equals(OnReceive.class.getName())) {
            if (member.getSimpleName().contentEquals("method")) {
                return Arrays.asList(
                    methodOf("GET"),
                    methodOf("POST"),
                    methodOf("PUT"),
                    methodOf("DELETE"),
                    methodOf("HEAD"),
                    methodOf("WebSocket")
                );
            }
        }

        return super.getCompletions(element, annotation, member, userText);
    }

    private static final Completion methodOf(String method) {
        ResourceBundle rb = ResourceBundle.getBundle("org.netbeans.html.json.impl.Bundle");
        return Completions.of('"' + method + '"', rb.getString("MSG_Completion_" + method));
    }

    private int findOnError(ExecutableElement errElem, TypeElement te, String name, String className) {
        String err = null;
        METHODS:
        for (Element e : te.getEnclosedElements()) {
            if (e.getKind() != ElementKind.METHOD) {
                continue;
            }
            if (!e.getSimpleName().contentEquals(name)) {
                continue;
            }
            if (!e.getModifiers().contains(Modifier.STATIC)) {
                errElem = (ExecutableElement) e;
                err = "Would have to be static";
                continue;
            }
            ExecutableElement ee = (ExecutableElement) e;
            TypeMirror excType = processingEnv.getElementUtils().getTypeElement(Exception.class.getName()).asType();
            final List<? extends VariableElement> params = ee.getParameters();
            boolean error = false;
            if (params.size() < 2 || params.size() > errElem.getParameters().size()) {
                error = true;
            } else {
                String firstType = params.get(0).asType().toString();
                int lastDot = firstType.lastIndexOf('.');
                if (lastDot != -1) {
                    firstType = firstType.substring(lastDot + 1);
                }
                if (!firstType.equals(className)) {
                    error = true;
                }
                if (!processingEnv.getTypeUtils().isAssignable(excType, params.get(1).asType())) {
                    error = true;
                }
                for (int i = 2; i < params.size(); i++) {
                    final VariableElement expectedParam = errElem.getParameters().get(i);
                    if (!processingEnv.getTypeUtils().isSameType(params.get(i).asType(), errElem.getParameters().get(i).asType())) {
                        error = true;
                        err = "Parameter #" + (i + 1) + " should be of type " + expectedParam;
                    }
                }
            }
            if (error) {
                errElem = (ExecutableElement) e;
                if (err == null) {
                    err = "Error method first argument needs to be " + className + " and second Exception";
                }
                continue;
            }
            return params.size();
        }
        if (err == null) {
            err = "Cannot find " + name + "(" + className + ", Exception) method in this class";
        }
        error(err, errElem);
        return -1;
    }

    private ExecutableElement findWrite(ExecutableElement computedPropElem, TypeElement te, String name, String className) {
        String err = null;
        METHODS:
        for (Element e : te.getEnclosedElements()) {
            if (e.getKind() != ElementKind.METHOD) {
                continue;
            }
            if (!e.getSimpleName().contentEquals(name)) {
                continue;
            }
            if (e.equals(computedPropElem)) {
                continue;
            }
            if (!e.getModifiers().contains(Modifier.STATIC)) {
                computedPropElem = (ExecutableElement) e;
                err = "Would have to be static";
                continue;
            }
            ExecutableElement ee = (ExecutableElement) e;
            if (ee.getReturnType().getKind() != TypeKind.VOID) {
                computedPropElem = (ExecutableElement) e;
                err = "Write method has to return void";
                continue;
            }
            TypeMirror retType = computedPropElem.getReturnType();
            final List<? extends VariableElement> params = ee.getParameters();
            boolean error = false;
            if (params.size() != 2) {
                error = true;
            } else {
                String firstType = params.get(0).asType().toString();
                int lastDot = firstType.lastIndexOf('.');
                if (lastDot != -1) {
                    firstType = firstType.substring(lastDot + 1);
                }
                if (!firstType.equals(className)) {
                    error = true;
                }
                if (!processingEnv.getTypeUtils().isAssignable(retType, params.get(1).asType())) {
                    error = true;
                }
            }
            if (error) {
                computedPropElem = (ExecutableElement) e;
                err = "Write method first argument needs to be " + className + " and second " + retType + " or Object";
                continue;
            }
            return ee;
        }
        if (err == null) {
            err = "Cannot find " + name + "(" + className + ", value) method in this class";
        }
        error(err, computedPropElem);
        return null;
    }

}
