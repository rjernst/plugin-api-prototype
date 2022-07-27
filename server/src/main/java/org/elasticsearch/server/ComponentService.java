package org.elasticsearch.server;

import org.elasticsearch.component.ExtensibleComponent;
import org.elasticsearch.component.NamedComponent;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toUnmodifiableMap;

public class ComponentService {

    private final Map<Class<?>, Map<String, Class<?>>> componentInfo;

    ComponentService(Path cratesDir) throws IOException, ClassNotFoundException {

        // TODO: do this at build time, using asm
        Map<String, String> extensible = findExtensible();
        System.out.println("EXTENSIBLE");
        for (var e : extensible.entrySet()) {
            String v = e.getKey();
            if (e.getKey().equals(e.getValue()) == false) {
                v += " (" + e.getValue() + ")";
            }
            System.out.println("  " + v);
        }
        Map<Class<?>, Map<String, Class<?>>> componentInfo = new HashMap<>();

        try (Stream<Path> stream = Files.list(cratesDir)) {
            for (Path crateDir : stream.toList()) {
                String crateName = crateDir.getFileName().toString();
                List<Path> jars = findJarFiles(crateDir);
                ClassLoader loader = new URLClassLoader("plugin:" + crateName, toURLs(jars), ClassLoader.getSystemClassLoader());

                // scan jars for components
                Map<Class<?>, Map<String, Class<?>>> crateComponentInfo = maybeLoadComponentInfoCache(crateDir, loader);
                if (crateComponentInfo == null) {
                    // no cache, scan the jar files
                    crateComponentInfo = findComponentInfo(jars, loader, extensible);
                    System.out.println("caching component info for " + crateName);
                    writeComponentInfoCache(crateDir, crateComponentInfo);
                } else {
                    System.out.println("loaded cached component info for " + crateName);
                }
                crateComponentInfo.forEach((baseClass, impls) -> {
                    var globalInfos = componentInfo.computeIfAbsent(baseClass, k -> new HashMap<>());
                    // TODO: assert all impls provided are unique
                    globalInfos.putAll(impls);
                });
            }
        }
        this.componentInfo = componentInfo.entrySet().stream().collect(toUnmodifiableMap(Map.Entry::getKey, e -> Map.copyOf(e.getValue())));
        System.out.println("COMPONENTS");
        for (var e : this.componentInfo.entrySet()) {
            System.out.println(e.getKey().toString());
            for (var impl : e.getValue().entrySet()) {
                System.out.println("  " + impl.getKey() + " -> " + impl.getValue());
            }
        }
    }

    public <T> T getNamedComponent(String name, Class<T> type) {
        // TODO: caching!

        Map<String, Class<?>> impls = componentInfo.get(type);
        if (impls == null) {
            throw new IllegalArgumentException("No implementations found for class " + type);
        }
        Class<?> implClass = impls.get(name);
        if (implClass == null) {
            throw new IllegalArgumentException("No implementation [" + name + "] found for class " + type);
        }
        assert type.isAssignableFrom(implClass);

        try {
            Constructor<?> ctor = implClass.getConstructor();
            Object component = ctor.newInstance();
            return type.cast(component);
        } catch (NoSuchMethodException | InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new AssertionError(e);
        }
    }

    public String getCrateNameForInstance(Object o) {
        return o.getClass().getClassLoader().getName();
    }

    private static List<Path> findJarFiles(Path crateDir) throws IOException {
        try (Stream<Path> stream = Files.list(crateDir)) {
            return stream.filter(p -> p.toString().endsWith(".jar")).toList();
        }
    }

    private static Path getCacheFile(Path crateDir) {
        return crateDir.resolve("component-info.txt");
    }

    private void writeComponentInfoCache(Path crateDir, Map<Class<?>, Map<String, Class<?>>> crateComponentInfo) throws IOException {
        Path cacheFile = getCacheFile(crateDir);
        List<String> lines = new ArrayList<>();
        crateComponentInfo.forEach((extensibleClass, namedComponents) -> {
            String extensibleClassname = extensibleClass.getName();
            namedComponents.forEach((name, impl) -> {
                lines.add(extensibleClassname + " " + name + " " + impl.getName());
            });
        });
        Files.write(cacheFile, lines);
    }

    private static Map<Class<?>, Map<String, Class<?>>> maybeLoadComponentInfoCache(Path crateDir, ClassLoader loader) throws IOException, ClassNotFoundException {
        // TODO: this should be a json file or something more structured, to support named and non-named
        // it is essentially like spring xml, just generated
        Path cacheFile = getCacheFile(crateDir);
        if (Files.exists(cacheFile) == false) {
            return null;
        }

        Map<Class<?>, Map<String, Class<?>>> componentInfo = new HashMap<>();
        for (String line : Files.readAllLines(cacheFile)) {
            String[] parts = line.split(" ");
            // this only works for named components, need more structure for non-named, but that is post MVP.
            // however, the file format should support a version so that we can modify the structure over time
            assert parts.length == 3;
            Class<?> extensibleClass = loader.loadClass(parts[0]);
            String componentName = parts[1];
            Class<?> componentImplClass = loader.loadClass(parts[2]);
            componentInfo.computeIfAbsent(extensibleClass, k -> new HashMap<>()).put(componentName, componentImplClass);
        }
        return componentInfo;
    }

    private static Class<?> loadClass(ClassLoader loader, String className) {
        try {
            return loader.loadClass(className.replace("/", "."));
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }

    private static Map<Class<?>, Map<String, Class<?>>> findComponentInfo(List<Path> jars, ClassLoader loader, Map<String, String> extensible) throws IOException {
        Map<String, String> namedComponents = new HashMap<>();
        var visitor = new AnnotatedHierarchyVisitor(NamedComponent.class, classname ->
            new AnnotationVisitor(Opcodes.ASM9) {
                @Override
                public void visit(String name, Object value) {
                    assert name.equals("name");
                    assert value instanceof String;
                    namedComponents.put(value.toString(), classname);
                }
        });

        for (Path jar : jars) {
            forEachClassInJar(jar, classReader -> {
                classReader.accept(visitor, ClassReader.SKIP_CODE);
            });
        }

        var localExtensible = new HashMap<>(extensible); // copy extensible so we can add local extensible classes
        addExtensibleDescendants(localExtensible, visitor.getClassHierarchy());

        Map<String, Map<String, Class<?>>> componentInfo = new HashMap<>();
        for (var e : namedComponents.entrySet()) {
            String name = e.getKey();
            String classname = e.getValue();
            String extensibleClassname = localExtensible.get(classname);
            if (extensibleClassname == null) {
                throw new RuntimeException("Named component " + name + "(" + classname + ") does not extend from an extensible class");
            }
            var named = componentInfo.computeIfAbsent(extensibleClassname, k -> new HashMap<>());
            var existing = named.put(name, loadClass(loader, classname));
            assert existing == null;
            // TODO: RANDOM THOUGHT: namespacing
            // should named components in the new system be namespaced so that they cannot conflict with
            // those from other plugins? then the distribution could have it's own namespace that we can control, much
            // like std:: in c++. we've hit the potential for namespace conflicts before in various parts of the system,
            // eg in scripting, and without it it is difficult to add new things without potentially breaking
            // users/plugins/whatever.
        }

        return componentInfo.entrySet().stream().collect(toMap(e -> loadClass(loader, e.getKey()), Map.Entry::getValue));
    }

    private static void forEachClassInJar(Path jar, Consumer<ClassReader> classConsumer) throws IOException {
        try (FileSystem jarFs = FileSystems.newFileSystem(jar)) {
            Path root = jarFs.getPath("/");
            forEachClassInPath(root, classConsumer);
        }
    }

    private static void forEachClassInPath(Path root, Consumer<ClassReader> classConsumer) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(p -> p.toString().endsWith(".class")).forEach(p -> {
                try (InputStream is = Files.newInputStream(p)) {
                    byte[] classBytes = is.readAllBytes();
                    ClassReader classReader = new ClassReader(classBytes);
                    classConsumer.accept(classReader);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    private static void forEachClass(Path p, Consumer<ClassReader> classConsumer) throws IOException {
        if (p.toString().endsWith(".jar")) {
            forEachClassInJar(p, classConsumer);
        } else {
            forEachClassInPath(p, classConsumer);
        }
    }

    private static Map<String, String> findExtensible() throws IOException {
        Map<String, String> extensibleClasses = new HashMap<>();
        var visitor = new AnnotatedHierarchyVisitor(ExtensibleComponent.class, classname -> {
            extensibleClasses.put(classname, classname);
            return null;
        });

        // scan for classes with the extensible annotation, also capturing the class hierarchy
        String classpath = System.getProperty("java.class.path");
        String[] pathelements = classpath.split(":");
        for (String pathelement : pathelements) {
            Path p = Paths.get(pathelement);
            if (Files.exists(p) == false) {
                continue;
            }
            forEachClass(p, classReader -> {
                classReader.accept(visitor, ClassReader.SKIP_CODE);
            });
        }

        /* debug
        System.out.println("class to subclass");
        for (var e : classToSubclasses.entrySet()) {
            System.out.println("  " + e.getKey());
            e.getValue().forEach(s -> System.out.println("    " + s));
        }

        System.out.println("raw extensible");
        extensibleClasses.forEach(s -> System.out.println("  " + s));
        */

        // find all classes descending from the extensible ones by going through each
        // extensible class and finding its descendants, then finding their descendants, and so on
        addExtensibleDescendants(extensibleClasses, visitor.getClassHierarchy());

        return extensibleClasses;
    }

    /**
     * Iterate through the existing extensible classes, and add all descendents as extensible.
     *
     * @param extensible A map from class name, to the original class that has the ExtensibleComponent annotation
     */
    private static void addExtensibleDescendants(Map<String, String> extensible, Map<String, Set<String>> classToSubclasses) {
        Deque<Map.Entry<String, String>> toCheckDescendants = new ArrayDeque<>(extensible.entrySet());
        Set<String> processed = new HashSet<>();
        while (toCheckDescendants.isEmpty() == false) {
            var e = toCheckDescendants.removeFirst();
            String classname = e.getKey();
            if (processed.contains(classname)) {
                continue;
            }
            Set<String> subclasses = classToSubclasses.get(classname);
            if (subclasses == null) {
                continue;
            }

            for (String subclass : subclasses) {
                extensible.put(subclass, e.getValue());
                toCheckDescendants.addLast(Map.entry(subclass, e.getValue()));
            }
            processed.add(classname);
        }
    }

    private static URL[] toURLs(List<Path> paths) {
        URL[] urls = new URL[paths.size()];
        int i = 0;
        for (Path p : paths) {
            urls[i++] = toURL(p);
        }
        return urls;
    }

    private static URL toURL(Path p) {
        try {
            return p.toUri().toURL();
        } catch (MalformedURLException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * A class visitor that captures the class hierarchy, as well as finds a specific annotation.
     */
    private static class AnnotatedHierarchyVisitor extends ClassVisitor {
        private String currentName;
        private final String targetAnnotationDescriptor;
        // a function taking the current class name the target annotation appeared on, and returning an AnnotationVisitor
        // that can be used to capture annotation specific args
        private final Function<String, AnnotationVisitor> visitor;
        private final Map<String, Set<String>> classToSubclasses = new HashMap<>();

        AnnotatedHierarchyVisitor(Class<?> targetAnnotation, Function<String, AnnotationVisitor> visitor) {
            super(Opcodes.ASM9);
            this.targetAnnotationDescriptor = Type.getDescriptor(targetAnnotation);
            this.visitor = visitor;
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            currentName = name;
            classToSubclasses.computeIfAbsent(superName, k -> new HashSet<>()).add(name);
            for (String iface : interfaces) {
                classToSubclasses.computeIfAbsent(iface, k -> new HashSet<>()).add(name);
            }
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            if (descriptor.equals(targetAnnotationDescriptor)) {
                return visitor.apply(currentName);
            }
            return null;
        }

        /** Returns a mapping of class name to subclasses of that class */
        public Map<String, Set<String>> getClassHierarchy() {
            return classToSubclasses;
        }
    }
}
