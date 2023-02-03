// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.dex;

import androidx.annotation.IntRange;
import androidx.annotation.NonNull;

import org.jf.baksmali.Adaptors.ClassDefinition;
import org.jf.baksmali.BaksmaliOptions;
import org.jf.baksmali.formatter.BaksmaliFormatter;
import org.jf.baksmali.formatter.BaksmaliWriter;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.analysis.InlineMethodResolver;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.dexbacked.DexBackedOdexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.MultiDexContainer;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

import io.github.muntashirakon.AppManager.BuildConfig;

// FIXME: 8/2/22 Add support for lower SDKs by fixing Smali/Baksmali
public class DexClasses implements Closeable {
    private final HashMap<String, ClassDef> classNameClassDefMap = new HashMap<>();
    private final HashMap<String, List<String>> baseClassNestedClassMap = new HashMap<>();
    // TODO: 18/10/21 Load frameworks.jar and add its dex files as options.classPath
    private final BaksmaliOptions options;
    private final Opcodes opcodes;

    public DexClasses(@NonNull File apkFile, @IntRange(from = -1) int apiLevel) throws IOException {
        this.opcodes = apiLevel < 0 ? Opcodes.getDefault() : Opcodes.forApi(apiLevel);
        this.options = new BaksmaliOptions();
        // options
        options.deodex = false;
        options.implicitReferences = false;
        options.parameterRegisters = true;
        options.localsDirective = true;
        options.sequentialLabels = true;
        options.debugInfo = BuildConfig.DEBUG;
        options.codeOffsets = false;
        options.accessorComments = false;
        options.registerInfo = 0;
        options.inlineResolver = null;
        BaksmaliFormatter formatter = new BaksmaliFormatter();
        MultiDexContainer<? extends DexBackedDexFile> container = DexUtils.loadApk(apkFile, apiLevel);
        List<String> dexEntryNames = container.getDexEntryNames();
        for (String dexEntryName : dexEntryNames) {
            MultiDexContainer.DexEntry<? extends DexBackedDexFile> dexEntry =
                    Objects.requireNonNull(container.getEntry(dexEntryName));
            DexBackedDexFile dexFile = dexEntry.getDexFile();
            // Store list of classes
            for (ClassDef classDef : dexFile.getClasses()) {
                String name = formatter.getType(classDef.getType());
                if (name.endsWith(";")) name = name.substring(0, name.length() - 1);
                if (name.startsWith("L")) {
                    name = name.substring(1).replace('/', '.');
                }
                classNameClassDefMap.put(name, classDef);
                String baseClass = DexUtils.getClassNameWithoutInnerClasses(name);
                List<String> classes = baseClassNestedClassMap.get(baseClass);
                if (classes == null) {
                    classes = new ArrayList<>();
                    baseClassNestedClassMap.put(baseClass, classes);
                }
                classes.add(name);
            }
            if (dexFile.supportsOptimizedOpcodes()) {
                throw new IOException("ODEX isn't supported.");
            }
            if (dexFile instanceof DexBackedOdexFile) {
                options.inlineResolver = InlineMethodResolver.createInlineMethodResolver(
                        ((DexBackedOdexFile) dexFile).getOdexVersion());
            }
        }
    }

    public DexClasses(@NonNull InputStream inputStream, @IntRange(from = -1) int apiLevel) throws IOException {
        this.opcodes = apiLevel < 0 ? Opcodes.getDefault() : Opcodes.forApi(apiLevel);
        this.options = new BaksmaliOptions();
        // options
        options.deodex = false;
        options.implicitReferences = false;
        options.parameterRegisters = true;
        options.localsDirective = true;
        options.sequentialLabels = true;
        options.debugInfo = BuildConfig.DEBUG;
        options.codeOffsets = false;
        options.accessorComments = false;
        options.registerInfo = 0;
        options.inlineResolver = null;
        BaksmaliFormatter formatter = new BaksmaliFormatter();
        InputStream is = new BufferedInputStream(inputStream);
        DexBackedDexFile dexFile = DexUtils.loadDexContainer(is, apiLevel);
        // Store list of classes
        for (ClassDef classDef : dexFile.getClasses()) {
            String name = formatter.getType(classDef.getType());
            if (name.endsWith(";")) name = name.substring(0, name.length() - 1);
            if (name.startsWith("L")) {
                name = name.substring(1).replace('/', '.');
            }
            classNameClassDefMap.put(name, classDef);
            String baseClass = DexUtils.getClassNameWithoutInnerClasses(name);
            List<String> classes = baseClassNestedClassMap.get(baseClass);
            if (classes == null) {
                classes = new ArrayList<>();
                baseClassNestedClassMap.put(baseClass, classes);
            }
            classes.add(name);
        }
        if (dexFile.supportsOptimizedOpcodes()) {
            throw new IOException("ODEX isn't supported.");
        }
        if (dexFile instanceof DexBackedOdexFile) {
            options.inlineResolver = InlineMethodResolver.createInlineMethodResolver(
                    ((DexBackedOdexFile) dexFile).getOdexVersion());
        }
    }

    @NonNull
    public List<String> getClassNames() {
        return new ArrayList<>(classNameClassDefMap.keySet());
    }

    @NonNull
    public List<String> getBaseClassNames() {
        return new ArrayList<>(baseClassNestedClassMap.keySet());
    }

    @NonNull
    public ClassDef getClassDef(@NonNull String className) throws ClassNotFoundException {
        ClassDef classDef = classNameClassDefMap.get(className);
        if (classDef == null) throw new ClassNotFoundException(className + " could not be found.");
        return classDef;
    }

    @NonNull
    public String getJavaCode(@NonNull String className) throws ClassNotFoundException {
        try {
            String baseClass = DexUtils.getClassNameWithoutInnerClasses(className);
            List<String> classes = baseClassNestedClassMap.get(baseClass);
            if (classes == null || classes.isEmpty() || !classes.contains(className)) {
                throw new ClassNotFoundException();
            }
            List<ClassDef> classDefs = new ArrayList<>(classes.size());
            for (String cls : classes) {
                classDefs.add(getClassDef(cls));
            }
            return DexUtils.toJavaCode(classDefs, this.opcodes);
        } catch (IOException e) {
            throw new ClassNotFoundException(e.getMessage(), e);
        }
    }

    @NonNull
    public String getClassContents(@NonNull String className) throws ClassNotFoundException {
        return getClassContents(getClassDef(className));
    }

    @NonNull
    public String getClassContents(@NonNull ClassDef classdef) throws ClassNotFoundException {
        StringWriter stringWriter = new StringWriter();
        try (BaksmaliWriter baksmaliWriter = new BaksmaliWriter(stringWriter)) {
            ClassDefinition classDefinition = new ClassDefinition(this.options, classdef);
            classDefinition.writeTo(baksmaliWriter);
            return stringWriter.toString();
        } catch (IOException e) {
            throw new ClassNotFoundException(e.getMessage(), e);
        }
    }

    @Override
    public void close() throws IOException {
    }
}
