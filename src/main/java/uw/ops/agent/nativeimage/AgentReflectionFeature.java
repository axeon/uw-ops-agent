package uw.ops.agent.nativeimage;

import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeReflection;

import java.io.IOException;
import java.lang.reflect.Array;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Registers application-owned JSON models for reflection during Native Image builds.
 */
public final class AgentReflectionFeature implements Feature {

    private static final String MODEL_PACKAGE = "uw.ops.agent.vo";
    private static final String MODEL_PATH = MODEL_PACKAGE.replace('.', '/');

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        Set<String> classNames = findModelClassNames(access.getApplicationClassPath());
        if (classNames.isEmpty()) {
            throw new IllegalStateException("No native reflection models found under " + MODEL_PACKAGE + '.');
        }

        for (String className : classNames) {
            Class<?> modelClass = access.findClassByName(className);
            if (modelClass == null) {
                throw new IllegalStateException("Native reflection model is not on the application classpath: " + className);
            }
            RuntimeReflection.register(modelClass, Array.newInstance(modelClass, 0).getClass());
            RuntimeReflection.register(modelClass.getDeclaredConstructors());
            RuntimeReflection.register(modelClass.getDeclaredMethods());
            RuntimeReflection.register(modelClass.getDeclaredFields());
        }
    }

    private static Set<String> findModelClassNames(Iterable<Path> classPathEntries) {
        Set<String> classNames = new TreeSet<>();
        for (Path classPathEntry : classPathEntries) {
            try {
                if (Files.isDirectory(classPathEntry)) {
                    collectDirectoryClasses(classPathEntry, classNames);
                } else if (classPathEntry.toString().endsWith(".jar")) {
                    collectJarClasses(classPathEntry, classNames);
                }
            } catch (IOException e) {
                throw new IllegalStateException("Failed to scan native image classpath entry: " + classPathEntry, e);
            }
        }
        return classNames;
    }

    private static void collectDirectoryClasses(Path classPathRoot, Set<String> classNames) throws IOException {
        Path modelRoot = classPathRoot.resolve(MODEL_PATH);
        if (!Files.isDirectory(modelRoot)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(modelRoot)) {
            paths.filter(Files::isRegularFile)
                    .map(classPathRoot::relativize)
                    .map(Path::toString)
                    .filter(AgentReflectionFeature::isModelClass)
                    .map(AgentReflectionFeature::toClassName)
                    .forEach(classNames::add);
        }
    }

    private static void collectJarClasses(Path jarPath, Set<String> classNames) throws IOException {
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            jarFile.stream()
                    .map(JarEntry::getName)
                    .filter(AgentReflectionFeature::isModelClass)
                    .map(AgentReflectionFeature::toClassName)
                    .forEach(classNames::add);
        }
    }

    private static boolean isModelClass(String path) {
        String normalizedPath = path.replace('\\', '/');
        return normalizedPath.startsWith(MODEL_PATH + '/')
                && normalizedPath.endsWith(".class")
                && !normalizedPath.endsWith("module-info.class")
                && !normalizedPath.endsWith("package-info.class");
    }

    private static String toClassName(String path) {
        String normalizedPath = path.replace('\\', '/');
        return normalizedPath.substring(0, normalizedPath.length() - ".class".length()).replace('/', '.');
    }
}
