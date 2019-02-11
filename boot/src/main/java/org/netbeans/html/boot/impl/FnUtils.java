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
package org.netbeans.html.boot.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import net.java.html.js.JavaScriptBody;
import net.java.html.js.JavaScriptResource;
import org.netbeans.html.boot.spi.Fn;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;
import org.objectweb.asm.signature.SignatureWriter;

/** Utilities related to bytecode transformations. Depend on asm.jar which
 * needs to be added to be provided to classpath to make methods in this 
 * class useful.
 *
 * @author Jaroslav Tulach
 */
public final class FnUtils {
    private FnUtils() {
    }
    
    /** Seeks for {@link JavaScriptBody} and {@link JavaScriptResource} annotations
     * in the bytecode and converts them into real code. Used by Maven plugin
     * postprocessing classes.
     * 
     * @param bytecode the original bytecode with javascript specific annotations
     * @param loader the loader to load resources (scripts and classes) when needed
     * @return the transformed bytecode
     * @since 0.7
     */
    public static byte[] transform(byte[] bytecode, ClassLoader loader) {
        ClassReader cr = new ClassReader(bytecode) {
            // to allow us to compile with -profile compact1 on 
            // JDK8 while processing the class as JDK7, the highest
            // class format asm 4.1 understands to
            @Override
            public short readShort(int index) {
                short s = super.readShort(index);
                if (index == 6 && s > Opcodes.V1_7) {
                    return Opcodes.V1_7;
                }
                return s;
            }
        };
        FindInClass tst = new FindInClass(loader, null);
        cr.accept(tst, 0);
        if (tst.found > 0) {
            ClassWriter w = new ClassWriterEx(loader, cr, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
            FindInClass fic = new FindInClass(loader, w);
            cr.accept(fic, 0);
            bytecode = w.toByteArray();
        }
        return bytecode;
    }
    
    public static ClassLoader newLoader(final FindResources f, final Fn.Presenter d, ClassLoader parent) {
        return new JsClassLoaderImpl(parent, f, d);
    }

    static String callback(final String body) {
        return new JsCallback() {
            @Override
            protected CharSequence callMethod(
                String ident, String fqn, String method, String params
            ) {
                StringBuilder sb = new StringBuilder();
                if (ident != null) {
                    sb.append("vm.raw$");
                } else {
                    sb.append("vm.");
                }
                sb.append(mangle(fqn, method, params));
                sb.append("(");
                if (ident != null) {
                    sb.append(ident);
                }
                return sb;
            }

        }.parse(body);
    }

    private static final class FindInClass extends ClassVisitor {
        private String name;
        private int found;
        private int resourcesCnt = 0;
        private final String[] resources = new String[256];

        public FindInClass(ClassLoader l, ClassVisitor cv) {
            super(Opcodes.ASM5, cv);
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            this.name = name;
            super.visit(version, access, name, signature, superName, interfaces);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
            final AnnotationVisitor del = super.visitAnnotation(desc, visible);
            if ("Lnet/java/html/js/JavaScriptResource;".equals(desc)) {
                return new LoadResource(del);
            }
            if ("Lnet/java/html/js/JavaScriptResource$Group;".equals(desc)) {
                return new LoadResource(del);
            }
            return del;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
            return new FindInMethod(access, name, desc,
                    super.visitMethod(access & (~Opcodes.ACC_NATIVE), name, desc, signature, exceptions)
            );
        }

        @Override
        public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
            if (name.startsWith("$$fn$$")) {
                return null;
            }
            return superField(access, name, desc, signature, value);
        }

        final FieldVisitor superField(int access, String name, String desc, String signature, Object value) {
            return super.visitField(access, name, desc, signature, value);
        }

        private final class FindInMethod extends MethodVisitor {

            private final String name;
            private final String desc;
            private final int access;
            private FindInAnno fia;
            private boolean bodyGenerated;

            public FindInMethod(int access, String name, String desc, MethodVisitor mv) {
                super(Opcodes.ASM5, mv);
                this.access = access;
                this.name = name;
                this.desc = desc;
            }

            @Override
            public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                if ("Lnet/java/html/js/JavaScriptBody;".equals(desc)) { // NOI18N
                    found++;
                    return new FindInAnno();
                }
                return super.visitAnnotation(desc, visible);
            }

            private void generateJSBody(FindInAnno fia) {
                this.fia = fia;
            }

            @Override
            public void visitCode() {
                if (fia == null) {
                    return;
                }
                generateBody(true);
            }

            private boolean generateBody(boolean hasCode) {
                if (bodyGenerated) {
                    return false;
                }
                bodyGenerated = true;
                if (mv != null) {
                    AnnotationVisitor va = super.visitAnnotation("Lnet/java/html/js/JavaScriptBody;", false);
                    AnnotationVisitor varr = va.visitArray("args");
                    for (String argName : fia.args) {
                        varr.visit(null, argName);
                    }
                    varr.visitEnd();
                    va.visit("javacall", fia.javacall);
                    va.visit("body", fia.body);
                    va.visitEnd();
                }
                
                String body;
                List<String> args;
                if (fia.javacall) {
                    body = callback(fia.body);
                    args = new ArrayList<String>(fia.args);
                    args.add("vm");
                } else {
                    body = fia.body;
                    args = fia.args;
                }

                super.visitFieldInsn(
                        Opcodes.GETSTATIC, FindInClass.this.name,
                        "$$fn$$" + name + "_" + found,
                        "Lorg/netbeans/html/boot/spi/Fn;"
                );
                super.visitInsn(Opcodes.DUP);
                super.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        "org/netbeans/html/boot/spi/Fn", "isValid",
                        "(Lorg/netbeans/html/boot/spi/Fn;)Z", false
                );
                Label ifNotNull = new Label();
                super.visitJumpInsn(Opcodes.IFNE, ifNotNull);

                // init Fn
                super.visitInsn(Opcodes.POP);
                super.visitLdcInsn(Type.getObjectType(FindInClass.this.name));
                super.visitInsn(fia.keepAlive ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
                super.visitLdcInsn(body);
                super.visitIntInsn(Opcodes.SIPUSH, args.size());
                super.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String");
                boolean needsVM = false;
                for (int i = 0; i < args.size(); i++) {
                    assert !needsVM;
                    String argName = args.get(i);
                    needsVM = "vm".equals(argName);
                    super.visitInsn(Opcodes.DUP);
                    super.visitIntInsn(Opcodes.BIPUSH, i);
                    super.visitLdcInsn(argName);
                    super.visitInsn(Opcodes.AASTORE);
                }
                super.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "org/netbeans/html/boot/spi/Fn", "define",
                        "(Ljava/lang/Class;ZLjava/lang/String;[Ljava/lang/String;)Lorg/netbeans/html/boot/spi/Fn;",
                        false
                );
                Label noPresenter = new Label();
                super.visitInsn(Opcodes.DUP);
                super.visitJumpInsn(Opcodes.IFNULL, noPresenter);
                int cnt = resourcesCnt;
                while (cnt > 0) {
                    String resource = resources[--cnt];
                    if (resource == null) {
                        continue;
                    }
                    super.visitLdcInsn(Type.getObjectType(FindInClass.this.name));
                    super.visitLdcInsn(resource);
                    super.visitMethodInsn(Opcodes.INVOKESTATIC,
                            "org/netbeans/html/boot/spi/Fn", "preload",
                            "(Lorg/netbeans/html/boot/spi/Fn;Ljava/lang/Class;Ljava/lang/String;)Lorg/netbeans/html/boot/spi/Fn;",
                            false
                    );
                }
                super.visitInsn(Opcodes.DUP);
                super.visitFieldInsn(
                        Opcodes.PUTSTATIC, FindInClass.this.name,
                        "$$fn$$" + name + "_" + found,
                        "Lorg/netbeans/html/boot/spi/Fn;"
                );
                // end of Fn init

                super.visitLabel(ifNotNull);

                final int offset;
                if ((access & Opcodes.ACC_STATIC) == 0) {
                    offset = 1;
                    super.visitIntInsn(Opcodes.ALOAD, 0);
                } else {
                    offset = 0;
                    super.visitInsn(Opcodes.ACONST_NULL);
                }

                super.visitIntInsn(Opcodes.SIPUSH, args.size());
                super.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");

                class SV extends SignatureVisitor {

                    private boolean nowReturn;
                    private Type returnType;
                    private int index;
                    private int loadIndex = offset;

                    public SV() {
                        super(Opcodes.ASM5);
                    }

                    @Override
                    public void visitBaseType(char descriptor) {
                        final Type t = Type.getType("" + descriptor);
                        if (nowReturn) {
                            returnType = t;
                            return;
                        }
                        FindInMethod.super.visitInsn(Opcodes.DUP);
                        FindInMethod.super.visitIntInsn(Opcodes.SIPUSH, index++);
                        FindInMethod.super.visitVarInsn(t.getOpcode(Opcodes.ILOAD), loadIndex++);
                        String factory;
                        switch (descriptor) {
                            case 'I':
                                factory = "java/lang/Integer";
                                break;
                            case 'J':
                                factory = "java/lang/Long";
                                loadIndex++;
                                break;
                            case 'S':
                                factory = "java/lang/Short";
                                break;
                            case 'F':
                                factory = "java/lang/Float";
                                break;
                            case 'D':
                                factory = "java/lang/Double";
                                loadIndex++;
                                break;
                            case 'Z':
                                factory = "java/lang/Boolean";
                                break;
                            case 'C':
                                factory = "java/lang/Character";
                                break;
                            case 'B':
                                factory = "java/lang/Byte";
                                break;
                            default:
                                throw new IllegalStateException(t.toString());
                        }
                        FindInMethod.super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                factory, "valueOf", "(" + descriptor + ")L" + factory + ";", false
                        );
                        FindInMethod.super.visitInsn(Opcodes.AASTORE);
                    }

                    @Override
                    public SignatureVisitor visitArrayType() {
                        if (nowReturn) {
                            return new SignatureVisitor(Opcodes.ASM5) {
                                @Override
                                public void visitClassType(String name) {
                                    returnType = Type.getType("[" + Type.getObjectType(name).getDescriptor());
                                }

                                @Override
                                public void visitBaseType(char descriptor) {
                                    returnType = Type.getType("[" + descriptor);
                                }
                            };
                        }
                        loadObject();
                        return new SignatureWriter();
                    }

                    @Override
                    public void visitClassType(String name) {
                        if (nowReturn) {
                            returnType = Type.getObjectType(name);
                            return;
                        }
                        loadObject();
                    }

                    @Override
                    public SignatureVisitor visitReturnType() {
                        nowReturn = true;
                        return this;
                    }

                    private void loadObject() {
                        FindInMethod.super.visitInsn(Opcodes.DUP);
                        FindInMethod.super.visitIntInsn(Opcodes.SIPUSH, index++);
                        FindInMethod.super.visitVarInsn(Opcodes.ALOAD, loadIndex++);
                        FindInMethod.super.visitInsn(Opcodes.AASTORE);
                    }

                }
                SV sv = new SV();
                SignatureReader sr = new SignatureReader(desc);
                sr.accept(sv);

                if (needsVM) {
                    FindInMethod.super.visitInsn(Opcodes.DUP);
                    FindInMethod.super.visitIntInsn(Opcodes.SIPUSH, sv.index);
                    int lastSlash = FindInClass.this.name.lastIndexOf('/');
                    String jsCallbacks = FindInClass.this.name.substring(0, lastSlash + 1) + "$JsCallbacks$";
                    FindInMethod.super.visitFieldInsn(Opcodes.GETSTATIC, jsCallbacks, "VM", "L" + jsCallbacks + ";");
                    FindInMethod.super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, jsCallbacks, "current", "()L" + jsCallbacks + ";", false);
                    FindInMethod.super.visitInsn(Opcodes.AASTORE);
                }

                if (fia.wait4js) {
                    super.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                            "org/netbeans/html/boot/spi/Fn", "invoke", "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", false
                    );
                    switch (sv.returnType.getSort()) {
                        case Type.VOID:
                            super.visitInsn(Opcodes.RETURN);
                            break;
                        case Type.ARRAY:
                        case Type.OBJECT:
                            super.visitTypeInsn(Opcodes.CHECKCAST, sv.returnType.getInternalName());
                            super.visitInsn(Opcodes.ARETURN);
                            break;
                        case Type.BOOLEAN: {
                            Label handleNullValue = new Label();
                            super.visitInsn(Opcodes.DUP);
                            super.visitJumpInsn(Opcodes.IFNULL, handleNullValue);
                            super.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Boolean");
                            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                    "java/lang/Boolean", "booleanValue", "()Z", false
                            );
                            super.visitInsn(Opcodes.IRETURN);
                            super.visitLabel(handleNullValue);
                            super.visitInsn(Opcodes.ICONST_0);
                            super.visitInsn(Opcodes.IRETURN);
                            break;
                        }
                        default:
                            super.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Number");
                            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                    "java/lang/Number", sv.returnType.getClassName() + "Value", "()" + sv.returnType.getDescriptor(), false
                            );
                            super.visitInsn(sv.returnType.getOpcode(Opcodes.IRETURN));
                    }
                } else {
                    super.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                            "org/netbeans/html/boot/spi/Fn", "invokeLater", "(Ljava/lang/Object;[Ljava/lang/Object;)V", false
                    );
                    super.visitInsn(Opcodes.RETURN);
                }
                super.visitLabel(noPresenter);
                if (hasCode) {
                    super.visitCode();
                } else {
                    super.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException");
                    super.visitInsn(Opcodes.DUP);
                    super.visitLdcInsn("No presenter active. Use BrwsrCtx.execute!");
                    super.visitMethodInsn(Opcodes.INVOKESPECIAL, 
                        "java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;)V", false
                    );
                    this.visitInsn(Opcodes.ATHROW);
                }
                return true;
            }
            
            @Override
            public void visitEnd() {
                super.visitEnd();
                if (fia != null) {
                    if (generateBody(false)) {
                        // native method
                        super.visitMaxs(1, 0);
                    }
                    FindInClass.this.superField(
                            Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                            "$$fn$$" + name + "_" + found,
                            "Lorg/netbeans/html/boot/spi/Fn;",
                            null, null
                    );
                }
            }

            private final class FindInAnno extends AnnotationVisitor {

                List<String> args = new ArrayList<String>();
                String body;
                boolean javacall = false;
                boolean wait4js = true;
                boolean keepAlive = true;

                public FindInAnno() {
                    super(Opcodes.ASM5);
                }

                @Override
                public void visit(String name, Object value) {
                    if (name == null) {
                        args.add((String) value);
                        return;
                    }
                    if (name.equals("javacall")) { // NOI18N
                        javacall = (Boolean) value;
                        return;
                    }
                    if (name.equals("wait4js")) { // NOI18N
                        wait4js = (Boolean) value;
                        return;
                    }
                    if (name.equals("keepAlive")) { // NOI18N
                        keepAlive = (Boolean) value;
                        return;
                    }
                    assert name.equals("body"); // NOI18N
                    body = (String) value;
                }

                @Override
                public AnnotationVisitor visitArray(String name) {
                    return this;
                }

                @Override
                public void visitEnd() {
                    if (body != null) {
                        generateJSBody(this);
                    }
                }
            }
        }

        private final class LoadResource extends AnnotationVisitor {
            public LoadResource(AnnotationVisitor av) {
                super(Opcodes.ASM5, av);
            }

            @Override
            public void visit(String attrName, Object value) {
                super.visit(attrName, value);
                String relPath = (String) value;
                if (relPath.startsWith("/")) {
                    resources[resourcesCnt++] = relPath;
                } else {
                    int last = name.lastIndexOf('/');
                    String fullPath = name.substring(0, last + 1) + relPath;
                    resources[resourcesCnt++] = fullPath;
                }
            }

            @Override
            public AnnotationVisitor visitArray(String name) {
                return new LoadResource(super.visitArray(name));
            }

            @Override
            public AnnotationVisitor visitAnnotation(String name, String desc) {
                return new LoadResource(super.visitAnnotation(name, desc));
            }
        }
    }

    private static class ClassWriterEx extends ClassWriter {

        private final ClassLoader loader;

        public ClassWriterEx(ClassLoader l, ClassReader classReader, int flags) {
            super(classReader, flags);
            this.loader = l;
        }

        @Override
        protected String getCommonSuperClass(final String type1, final String type2) {
            Class<?> c, d;
            try {
                c = Class.forName(type1.replace('/', '.'), false, loader);
                d = Class.forName(type2.replace('/', '.'), false, loader);
            } catch (Exception e) {
                throw new RuntimeException(e.toString());
            }
            if (c.isAssignableFrom(d)) {
                return type1;
            }
            if (d.isAssignableFrom(c)) {
                return type2;
            }
            if (c.isInterface() || d.isInterface()) {
                return "java/lang/Object";
            } else {
                do {
                    c = c.getSuperclass();
                } while (!c.isAssignableFrom(d));
                return c.getName().replace('.', '/');
            }
        }
    }

    static class JsClassLoaderImpl extends JsClassLoader {

        private final FindResources f;
        private final Fn.Presenter d;

        public JsClassLoaderImpl(ClassLoader parent, FindResources f, Fn.Presenter d) {
            super(parent);
            setDefaultAssertionStatus(JsClassLoader.class.desiredAssertionStatus());
            this.f = f;
            this.d = d;
        }

        @Override
        protected URL findResource(String name) {
            List<URL> l = res(name, true);
            return l.isEmpty() ? null : l.get(0);
        }

        @Override
        protected Enumeration<URL> findResources(String name) {
            return Collections.enumeration(res(name, false));
        }
        
        private List<URL> res(String name, boolean oneIsEnough) {
            List<URL> l = new ArrayList<URL>();
            f.findResources(name, l, oneIsEnough);
            return l;
        }
    
        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (name.startsWith("javafx")) {
                return Class.forName(name);
            }
            if (name.startsWith("netscape")) {
                return Class.forName(name);
            }
            if (name.startsWith("com.sun")) {
                return Class.forName(name);
            }
            if (name.startsWith("org.netbeans.html.context.spi")) {
                return Class.forName(name);
            }
            if (name.startsWith("net.java.html.BrwsrCtx")) {
                return Class.forName(name);
            }
            if (name.equals(JsClassLoader.class.getName())) {
                return JsClassLoader.class;
            }
            if (name.equals(Fn.class.getName())) {
                return Fn.class;
            }
            if (name.equals(Fn.Presenter.class.getName())) {
                return Fn.Presenter.class;
            }
            if (name.equals(Fn.ToJavaScript.class.getName())) {
                return Fn.ToJavaScript.class;
            }
            if (name.equals(Fn.FromJavaScript.class.getName())) {
                return Fn.FromJavaScript.class;
            }
            if (name.equals(FnUtils.class.getName())) {
                return FnUtils.class;
            }
            if (
                name.equals("org.netbeans.html.boot.spi.Fn") ||
                name.equals("org.netbeans.html.boot.impl.FnUtils") ||
                name.equals("org.netbeans.html.boot.impl.FnContext")
            ) {
                return Class.forName(name);
            }
            URL u = findResource(name.replace('.', '/') + ".class");
            if (u != null) {
                InputStream is = null;
                try {
                    is = u.openStream();
                    byte[] arr = new byte[is.available()];
                    int len = 0;
                    while (len < arr.length) {
                        int read = is.read(arr, len, arr.length - len);
                        if (read == -1) {
                            throw new IOException("Can't read " + u);
                        }
                        len += read;
                    }
                    is.close();
                    is = null;
                    if (JsPkgCache.process(this, name)) {
                        arr = FnUtils.transform(arr, this);
                    }
                    return defineClass(name, arr, 0, arr.length);
                } catch (IOException ex) {
                    throw new ClassNotFoundException("Can't load " + name, ex);
                } finally {
                    try {
                        if (is != null) is.close();
                    } catch (IOException ex) {
                        throw new ClassNotFoundException(null, ex);
                    }
                }
            }
            return super.findClass(name);
        }
    
        protected Fn defineFn(String code, String... names) {
            return d.defineFn(code, names);
        }
        
        protected void loadScript(Reader code) throws Exception {
            d.loadScript(code);
        }
    }
}
