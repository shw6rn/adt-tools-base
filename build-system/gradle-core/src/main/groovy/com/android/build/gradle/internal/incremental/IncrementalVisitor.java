/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.build.gradle.internal.incremental;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.utils.FileUtils;
import com.google.common.io.Files;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.commons.Method;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

public class IncrementalVisitor extends ClassVisitor {

    protected static boolean TRACING_ENABLED = Boolean.getBoolean("FDR_TRACING");

    protected String visitedClassName;
    protected String visitedSuperName;
    @NonNull
    protected final ClassNode classNode;
    @NonNull
    protected final List<ClassNode> parentNodes;

    public IncrementalVisitor(@NonNull ClassNode classNode, List<ClassNode> parentNodes, ClassVisitor classVisitor) {
        super(Opcodes.ASM5, classVisitor);
        this.classNode = classNode;
        this.parentNodes = parentNodes;
        System.out.println("Visiting " + classNode.name);
    }

    @Nullable
    FieldNode getFieldByName(String fieldName) {
        FieldNode fieldNode = getFieldByNameInClass(fieldName, classNode);
        Iterator<ClassNode> iterator = parentNodes.iterator();
        while(fieldNode == null && iterator.hasNext()) {
            ClassNode parentNode = iterator.next();
            fieldNode = getFieldByNameInClass(fieldName, parentNode);
        }
        return fieldNode;
    }

    FieldNode getFieldByNameInClass(String fieldName, ClassNode classNode) {
        List<FieldNode> fields = classNode.fields;
        for (FieldNode field: fields) {
            if (field.name.equals(fieldName)) {
                return field;
            }
        }
        return null;
    }

    MethodNode getMethodByName(String methodName, String desc) {
        MethodNode methodNode = getMethodByNameInClass(methodName, desc, classNode);
        Iterator<ClassNode> iterator = parentNodes.iterator();
        while(methodNode == null && iterator.hasNext()) {
            ClassNode parentNode = iterator.next();
            methodNode = getMethodByNameInClass(methodName, desc, parentNode);
        }
        return methodNode;
    }

    MethodNode getMethodByNameInClass(String methodName, String desc, ClassNode classNode) {
        List<MethodNode> methods = classNode.methods;
        for (MethodNode method : methods) {
            if (method.name.equals(methodName) && method.desc.equals(desc)) {
                return method;
            }
        }
        return null;
    }

    protected void trace(GeneratorAdapter mv, String s) {
        mv.push(s);
        mv.invokeStatic(Type.getType(IncrementalSupportRuntime.class),
                Method.getMethod("void trace(String)"));
    }

    protected void trace(GeneratorAdapter mv, String s1, String s2) {
        mv.push(s1);
        mv.push(s2);
        mv.invokeStatic(Type.getType(IncrementalSupportRuntime.class),
                Method.getMethod("void trace(String, String)"));
    }

    protected void trace(GeneratorAdapter mv, String s1, String s2, String s3) {
        mv.push(s1);
        mv.push(s2);
        mv.push(s3);
        mv.invokeStatic(Type.getType(IncrementalSupportRuntime.class),
                Method.getMethod("void trace(String, String, String)"));
    }

    protected void trace(GeneratorAdapter mv, int argsNumber) {
        StringBuilder methodSignture = new StringBuilder("void trace(String");
        for (int i=0 ; i < argsNumber-1; i++) {
            methodSignture.append(", String");
        }
        methodSignture.append(")");
        mv.invokeStatic(Type.getType(IncrementalSupportRuntime.class),
                Method.getMethod(methodSignture.toString()));
    }

    /**
     * Simple Builder interface for common methods between all byte code visitors.
     */
    public interface VisitorBuilder {
        IncrementalVisitor build(@NonNull ClassNode classNode,
                List<ClassNode> parentNodes, ClassVisitor classVisitor);

        boolean processParents();
    }

    protected static void main(String[] args, VisitorBuilder visitorBuilder) throws IOException {

        if (args.length != 2) {
            throw new IllegalArgumentException("Needs to be given an input and output directory");
        }

        File srcLocation = new File(args[0]);
        File baseInstrumentedCompileOutputFolder = new File(args[1]);
        FileUtils.emptyFolder(baseInstrumentedCompileOutputFolder);
        instrumentClasses(srcLocation,
                baseInstrumentedCompileOutputFolder, visitorBuilder);
    }

    private static void instrumentClasses(File rootLocation, File outLocation, VisitorBuilder visitorBuilder)
            throws IOException {

        Iterable<File> files =
                Files.fileTreeTraverser().preOrderTraversal(rootLocation).filter(Files.isFile());

        for (File inputFile : files) {
            File outputFile = new File(outLocation,
                    FileUtils.relativePath(inputFile, rootLocation));

            instrumentClass(inputFile, outputFile, visitorBuilder);
        }
    }

    public static void instrumentClass(
            File inputFile, File outputFile, VisitorBuilder visitorBuilder) throws IOException {

        byte[] classBytes;
        classBytes = Files.toByteArray(inputFile);
        ClassReader classReader = new ClassReader(classBytes);
        ClassWriter classWriter = new ClassWriter(classReader, ClassWriter.COMPUTE_FRAMES);

        ClassNode classNode = new ClassNode();
        classReader.accept(classNode, ClassReader.SKIP_FRAMES);

        Files.createParentDirs(outputFile);

        // when dealing with interface, we just copy the inputFile over without any changes.
        if ((classNode.access & Opcodes.ACC_INTERFACE) != 0) {
            Files.write(classBytes, outputFile);
            return;
        }

        List<ClassNode> parentsNodes;
        if (visitorBuilder.processParents()) {
            parentsNodes = parseParents(inputFile, classNode);
        } else {
            parentsNodes = Collections.emptyList();
        }

        IncrementalVisitor visitor = visitorBuilder.build(classNode, parentsNodes, classWriter);
        if (visitorBuilder.processParents()) {
            // not sure why we need to reparse from the classReader, it does not work to just
            // reuse classNode.
            classReader.accept(visitor, ClassReader.SKIP_FRAMES);
        } else {
            classNode.accept(visitor);
        }

        Files.write(classWriter.toByteArray(), outputFile);
    }

    private static List<ClassNode> parseParents(File inputFile, ClassNode classNode) throws IOException {
        File binaryFolder = new File(inputFile.getAbsolutePath().substring(0,
                inputFile.getAbsolutePath().length() - (classNode.name.length() + ".class".length())));
        List<ClassNode> parentNodes = new ArrayList<ClassNode>();
        String currentParentName = classNode.superName;
        while (!currentParentName.equals(Type.getType(Object.class).getInternalName())) {
            File parentFile = new File(binaryFolder, currentParentName + ".class");
            System.out.println("parsing " + parentFile);
            if (parentFile.exists()) {
                InputStream parentFileClassReader = new BufferedInputStream(new FileInputStream(parentFile));
                ClassReader parentClassReader = new ClassReader(parentFileClassReader);
                ClassNode parentNode = new ClassNode();
                parentClassReader.accept(parentNode, ClassReader.EXPAND_FRAMES);
                parentNodes.add(parentNode);
                currentParentName = parentNode.superName;
            } else {
                return parentNodes;
            }
        }
        return parentNodes;
    }
}
