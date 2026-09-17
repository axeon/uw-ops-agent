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
 * 应用 JSON 模型的 Native Image 反射注册器。
 * <p>
 * 构建期递归扫描 {@link #MODEL_PACKAGES} 各模型包（含 vo.sub 等全部子包）下的类，
 * 为每个模型类注册 declared 构造器/方法/字段与数组类——这是 Jackson 在 native 运行时
 * 绑定协议 vo（上下行 JSON）与 Engine API 响应 vo 的反射前提。
 * 模型包按"参与 JSON 绑定"划分：{@code uw.ops.agent.vo}（上报/下行协议）与
 * {@code uw.ops.agent.helper.vo}（Engine API 输入形状 *Raw）。helper.vo 内与形状类
 * 同居的差分快照（*Counters）不参与 JSON，注册以包为单位会被连带登记——
 * 仅多几十 KB 镜像元数据、无运行时影响，是结构统一下可接受的取舍。
 * <p>
 * 配套约束：
 * <ul>
 *   <li>native-image.properties 的 {@code --exact-reachability-metadata} 包白名单必须包含
 *       新增的模型包，否则该包的注册会被 exact 过滤丢弃；</li>
 *   <li>reachability-metadata.json 只登记第三方类（JDK/JNA/oshi），应用模型一律由本类注册，
 *       不得手写进该文件（NativeImageMetadataVerifierTest 有对应断言）；</li>
 *   <li>新增模型包或子包无需改本类（递归扫描自动覆盖），但需在发布前跑
 *       {@code uw-ops-agent --verify-native-metadata}（{@link NativeImageMetadataVerifier}）
 *       验证绑定路径在 native 下真实可用。</li>
 * </ul>
 */
public final class AgentReflectionFeature implements Feature {

    /**
     * JSON 模型包（递归扫描子包）：协议 vo 与 Docker Engine API 输入 vo。
     */
    private static final String[] MODEL_PACKAGES = {"uw.ops.agent.vo", "uw.ops.agent.helper.vo"};

    /**
     * 构建期入口：扫描应用类路径上的 vo 模型类并注册反射元数据。
     *
     * @param access 构建期访问器
     */
    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        Set<String> classNames = findModelClassNames(access.getApplicationClassPath());
        if (classNames.isEmpty()) {
            throw new IllegalStateException("No native reflection models found under " + String.join(",", MODEL_PACKAGES) + '.');
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

    /**
     * 从应用类路径（目录或 jar）收集各模型包下全部模型类名。
     *
     * @param classPathEntries 类路径条目
     * @return 类全名集合
     */
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

    /**
     * 收集目录形态类路径下的模型类（递归子目录）。
     *
     * @param classPathRoot 类路径根
     * @param classNames    收集结果
     * @throws IOException 目录遍历失败
     */
    private static void collectDirectoryClasses(Path classPathRoot, Set<String> classNames) throws IOException {
        for (String modelPackage : MODEL_PACKAGES) {
            Path modelRoot = classPathRoot.resolve(modelPackage.replace('.', '/'));
            if (!Files.isDirectory(modelRoot)) {
                continue;
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
    }

    /**
     * 收集 jar 形态类路径下的模型类（shade 产物场景）。
     *
     * @param jarPath   jar 路径
     * @param classNames 收集结果
     * @throws IOException jar 读取失败
     */
    private static void collectJarClasses(Path jarPath, Set<String> classNames) throws IOException {
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            jarFile.stream()
                    .map(JarEntry::getName)
                    .filter(AgentReflectionFeature::isModelClass)
                    .map(AgentReflectionFeature::toClassName)
                    .forEach(classNames::add);
        }
    }

    /**
     * 判断类文件是否属于任一模型包（排除 module-info/package-info）。
     *
     * @param path 类文件相对路径
     * @return 属于模型时返回 true
     */
    private static boolean isModelClass(String path) {
        String normalizedPath = path.replace('\\', '/');
        if (!normalizedPath.endsWith(".class")
                || normalizedPath.endsWith("module-info.class")
                || normalizedPath.endsWith("package-info.class")) {
            return false;
        }
        for (String modelPackage : MODEL_PACKAGES) {
            String modelPath = modelPackage.replace('.', '/');
            if (normalizedPath.startsWith(modelPath + '/')) {
                return true;
            }
        }
        return false;
    }

    /**
     * 类文件相对路径转类全名。
     *
     * @param path 类文件相对路径
     * @return 类全名
     */
    private static String toClassName(String path) {
        String normalizedPath = path.replace('\\', '/');
        return normalizedPath.substring(0, normalizedPath.length() - ".class".length()).replace('/', '.');
    }
}
