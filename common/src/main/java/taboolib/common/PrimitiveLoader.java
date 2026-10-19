package taboolib.common;

import me.lucko.jarrelocator.JarRelocator;
import me.lucko.jarrelocator.Relocation;
import taboolib.common.classloader.IsolatedClassLoader;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static taboolib.common.PrimitiveIO.t;
import static taboolib.common.PrimitiveSettings.*;

/**
 * 原始加载器，必须在 IsolatedClassLoader 中加载
 *
 * @author 坏黑
 * @since 2024/1/24 20:29
 */
@SuppressWarnings({"DataFlowIssue"})
public class PrimitiveLoader {

    public static final String TABOOLIB_GROUP = "!io.izzel.taboolib".substring(1);

    public static final String TABOOLIB_PACKAGE_NAME = "taboolib";

    public static final String TABOOPROJECT_GROUP = "!org.tabooproject".substring(1);

    public static final String ASM_GROUP = "!org.objectweb.asm".substring(1);

    public static final String JR_GROUP = "!me.lucko.jarrelocator".substring(1);

    static String projectPackageName;

    static {
        try {
            projectPackageName = "taboolib".substring(0, "taboolib".length() - 9);
        } catch (Throwable ex) {
            projectPackageName = "taboolib";
        }
    }

    /**
     * 基础依赖（隔离加载）
     */
    static List<String[]> deps() {
        List<String[]> deps = new ArrayList<>();
        deps.add(new String[]{"!me.lucko".substring(1), "jar-relocator", "1.7"});
        deps.add(new String[]{"!org.ow2.asm".substring(1), "asm", "9.8"});
        deps.add(new String[]{"!org.ow2.asm".substring(1), "asm-util", "9.8"});
        deps.add(new String[]{"!org.ow2.asm".substring(1), "asm-commons", "9.8"});
        // 都是必要的 incision 强制依赖
        deps.add(new String[]{"!org.ow2.asm".substring(1), "asm-tree", "9.8"});
        deps.add(new String[]{"!org.ow2.asm".substring(1), "asm-analysis", "9.8"});
        return deps;
    }

    /**
     * 默认的重定向规则
     */
    static List<String[]> rule() {
        ArrayList<String[]> rule = new ArrayList<>();
        rule.add(new String[]{TABOOPROJECT_GROUP, TABOOLIB_PACKAGE_NAME + ".library"});
        rule.add(new String[]{JR_GROUP + ".", JR_GROUP + "15."});
        rule.add(new String[]{ASM_GROUP + ".", ASM_GROUP + "9."});
        // 不跳过 TabooLib 重定向
        if (!SKIP_TABOOLIB_RELOCATE) {
            rule.add(new String[]{PrimitiveSettings.ID, TABOOLIB_PACKAGE_NAME});
        }
        return rule;
    }

    /**
     * 初始化各模块
     * 该方法必须在 IsolatedClassLoader 中运行，才能确保从 ClassAppender 初始化的 TabooLib 类为隔离状态
     * <p>
     * ClassLoader loader = TabooLib.class.getClassLoader();
     * if (loader instanceof IsolatedClassLoader) { ... }
     */
    public static void init() throws Throwable {
        // 开发版本
        PrimitiveIO.debug("当前以调试模式运行。", PrimitiveIO.getRunningFileName());
        long time = TabooLib.execution(() -> {
            // 基础依赖是否隔离加载
            boolean isIsolated = PrimitiveLoader.class.getClassLoader() instanceof IsolatedClassLoader;
            // 在隔离空间加载基础依赖
            // 用于引导 Env 启动
            for (String[] i : deps()) {
                load(REPO_CENTRAL, i[0], i[1], i[2], isIsolated, true, new ArrayList<>());
            }
            // 重新加载基础依赖用于正式使用
            for (String[] i : deps()) {
                load(REPO_CENTRAL, i[0], i[1], i[2], IS_ISOLATED_MODE, true, rule());
            }
            // 加载反射模块
            load(REPO_REFLEX, TABOOPROJECT_GROUP + ".reflex", "reflex", ReflexVersion.VERSION, IS_ISOLATED_MODE, true, rule());
            load(REPO_REFLEX, TABOOPROJECT_GROUP + ".reflex", "analyser", ReflexVersion.VERSION, IS_ISOLATED_MODE, true, rule());
        });
        PrimitiveIO.debug("基础依赖加载完成，用时 {0} 毫秒。", time);
        // 加载完整模块
        loadAll();
    }

    /**
     * 从仓库下载模块并加载
     *
     * @param repo       仓库地址
     * @param group      组
     * @param name       构件名
     * @param version    版本
     * @param isIsolated 是否进入沙盒
     * @param isExternal 是否属于外部库（不会扫描类）
     */
    static boolean load(String repo, String group, String name, String version, boolean isIsolated, boolean isExternal, List<String[]> relocate) {
        if (name.isEmpty()) return false;
        boolean downloaded = false;
        // SNAPSHOT 版本需要先查询 maven-metadata.xml 获取实际带时间戳的文件名
        String resolvedFileName = version.endsWith("-SNAPSHOT")
                ? resolveSnapshotFileName(repo, group, name, version)
                : null;
        // 本地保存文件名：SNAPSHOT 使用带时间戳的文件名，否则使用标准格式
        String localFileName = resolvedFileName != null ? resolvedFileName : String.format("%s-%s.jar", name, version);
        File envFile = new File(getLibraryFile(), String.format("%s/%s/%s/%s", group.replace(".", "/"), name, version, localFileName));
        File shaFile = new File(getLibraryFile(), String.format("%s/%s/%s/%s.sha1", group.replace(".", "/"), name, version, localFileName));
        // 检查文件有效性
        if (!PrimitiveIO.validation(envFile, shaFile) || (IS_FORCE_DOWNLOAD_IN_DEV_MODE && IS_DEV_MODE && group.equals(TABOOLIB_GROUP))) {
            try {
                PrimitiveIO.println(t("正在下载依赖 {0}:{1}:{2}", "Downloading library {0}:{1}:{2}"),
                        group,
                        name,
                        version
                );
                // 获取远程下载地址
                String url;
                if (resolvedFileName != null) {
                    url = String.format("%s/%s/%s/%s/%s", repo, group.replace(".", "/"), name, version, resolvedFileName);
                } else {
                    url = String.format("%s/%s/%s/%s/%s-%s.jar", repo, group.replace(".", "/"), name, version, name, version);
                }
                // 下载 jar
                PrimitiveIO.downloadFile(new URL(url), envFile);
                // 尝试下载 sha1 文件；本地 file:// 仓库可能没有 sha1，则用 jar 自行生成
                try {
                    PrimitiveIO.downloadFile(new URL(url + ".sha1"), shaFile);
                } catch (IOException ignored) {
                    if (IS_DEV_MODE) {
                        PrimitiveIO.debug("无法下载 {0}-{1}.jar.sha1，将用 jar 自行生成。", name, version);
                        if (envFile.exists()) {
                            generateSha1File(envFile, shaFile);
                        }
                    } else {
                        throw new IOException("无法下载 " + url + ".sha1");
                    }
                }
                downloaded = true;
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            }
            // 检查合法性
            if (!PrimitiveIO.validation(envFile, shaFile)) {
                PrimitiveIO.println(t("无法下载 {0}-{1}.jar", "Failed to download {0}-{1}.jar"), name, version);
                return false;
            }
        }
        // 加载
        try {
            loadFile(envFile, isIsolated, isExternal, relocate, downloaded);
        } catch (Throwable ex) {
            throw new RuntimeException(t("无法加载 " + envFile, "Failed to load " + envFile), ex);
        }
        return true;
    }

    /**
     * 加载完整模块
     */
    static void loadAll() throws Throwable {
        // 若未指定 TabooLib 版本，则跳过加载
        if (TABOOLIB_VERSION.equals("skip")) {
            PrimitiveIO.println(t("TabooLib 版本没有定义，将跳过加载。", "TabooLib version is not specified, skip loading."));
            return;
        }
        long time = TabooLib.execution(() -> {
            List<String[]> rule = rule();
            // 加载 env 启动 Kotlin 环境
            load(REPO_TABOOLIB, TABOOLIB_GROUP, "common-env", TABOOLIB_VERSION, IS_ISOLATED_MODE, true, rule);
            // 如果 Kotlin 环境启动失败
            if (!TabooLib.isKotlinEnvironment()) {
                String kotlinClass = "kotlin.Lazy";
                throw new IllegalStateException(t(
                        "无法启动 Kotlin 环境。(未能找到 " + kotlinClass + ")",
                        "Failed to setup Kotlin environment. (" + kotlinClass + " not found)"
                ));
            }
            // 加载 util 注册 ClassAppender Callback 回调函数
            load(REPO_TABOOLIB, TABOOLIB_GROUP, "common-util", TABOOLIB_VERSION, IS_ISOLATED_MODE, true, rule);
            // 加载剩余模块 >> 此时 isExternal 参数才有实际作用
            load(REPO_TABOOLIB, TABOOLIB_GROUP, "common-legacy-api", TABOOLIB_VERSION, IS_ISOLATED_MODE, false, rule);
            load(REPO_TABOOLIB, TABOOLIB_GROUP, "common-platform-api", TABOOLIB_VERSION, IS_ISOLATED_MODE, false, rule);
            // 加载自选模块
            for (String i : INSTALL_MODULES) {
                load(REPO_TABOOLIB, TABOOLIB_GROUP, i, TABOOLIB_VERSION, IS_ISOLATED_MODE, false, rule);
            }
        });
        PrimitiveIO.debug("所有依赖加载完成，用时 {0} 毫秒。", time);
    }

    /**
     * 加载文件
     *
     * @param file          文件
     * @param isIsolated    是否进入沙盒
     * @param isExternal    是否属于外部库（不会扫描类）
     * @param relocate      重定向规则
     * @param forceRelocate 是否强制重定向
     */
    static void loadFile(File file, boolean isIsolated, boolean isExternal, List<String[]> relocate, boolean forceRelocate) throws Throwable {
        File jar = file;
        // 确保在 jar-relocator 加载后运行 >> java.lang.NoClassDefFoundError
        if (!relocate.isEmpty()) {
            List<Relocation> rel = new ArrayList<>();
            for (String[] r : relocate) {
                rel.add(new Relocation(r[0], r[1]));
            }
            // 启用 Kotlin 重定向
            if (!SKIP_KOTLIN_RELOCATE) {
                String kt = "!kotlin".substring(1);
                String ktc = "!kotlinx.coroutines".substring(1);
                rel.add(new Relocation(kt + ".", PrimitiveSettings.getRelocatedKotlinVersion() + "."));
                rel.add(new Relocation(ktc + ".", PrimitiveSettings.getRelocatedKotlinCoroutinesVersion() + "."));
            }
            // 计算缓存文件
            String hash = relocateCacheHash(file, relocate);
            String name = file.getName().substring(0, file.getName().lastIndexOf('.'));
            jar = new File(getCacheFile(), name + "-" + hash.substring(0, 8) + ".jar");
            // 缓存不可用时重建
            if (!isCacheUsable(jar, hash) || (IS_FORCE_DOWNLOAD_IN_DEV_MODE && IS_DEV_MODE) || forceRelocate) {
                jar.getParentFile().mkdirs();
                try {
                    new JarRelocator(PrimitiveIO.copyFile(file, File.createTempFile(file.getName(), ".jar")), jar, rel).run();
                } catch (Throwable e) {
                    // 重定向失败时不留下半成品，否则下次启动会把它当成有效缓存复用
                    //noinspection ResultOfMethodCallIgnored
                    jar.delete();
                    throw new RuntimeException(t("无法重定向 " + file, "Failed to relocate " + file), e);
                }
                // 写入标记：内容指纹与重定向规则指纹，供后续启动校验
                writeCacheMarker(jar, hash);
            }
        }
        ClassLoader loader = ClassAppender.addPath(jar.toPath(), isIsolated, isExternal);
        // 读取 "META-INF/taboolib/extra.properties"
        try (JarFile jarFile = new JarFile(jar)) {
            JarEntry extra = jarFile.getJarEntry("META-INF/taboolib/extra.properties");
            if (extra != null) {
                PrimitiveIO.debug("从 {0} 中加载 extra.properties 配置", jar.getName());
                Properties extraProps = new Properties();
                extraProps.load(jarFile.getInputStream(extra));
                // 获取主类
                String main = extraProps.getProperty("main");
                String mainMethod = extraProps.getProperty("main-method");
                if (main != null && mainMethod != null) {
                    for (String cls : main.split(",")) {
                        Class<?> mainClass = Class.forName(TABOOLIB_PACKAGE_NAME + "." + cls, true, loader);
                        // 反射调用初始化函数
                        Method declaredMethod = mainClass.getDeclaredMethod(mainMethod);
                        declaredMethod.setAccessible(true);
                        declaredMethod.invoke(null);
                        PrimitiveIO.debug(" = 调用 {0}.{1}()", mainClass.getName(), mainMethod);
                    }
                }
            }
        }
    }

    /**
     * 获取缓存路径
     */
    public static File getCacheFile() {
        File file = new File("cache/taboolib/" + projectPackageName);
        if (!file.exists()) {
            file.mkdirs();
        }
        return file;
    }

    /**
     * 获取文件保存路径
     */
    public static File getLibraryFile() {
        File file = new File(FILE_LIBS);
        if (!file.exists()) {
            file.mkdirs();
        }
        return file;
    }

    /**
     * 解析 SNAPSHOT 版本的实际文件名
     * 从快照仓库的 maven-metadata.xml 中获取带时间戳的实际文件名
     * 例如：common-env-6.2.4-20240301.123456-1.jar
     * 若解析失败则返回 null，回退到标准文件名格式
     */
    static String resolveSnapshotFileName(String repo, String group, String name, String version) {
        try {
            String metadataUrl = String.format("%s/%s/%s/%s/maven-metadata.xml",
                    repo, group.replace(".", "/"), name, version);
            java.io.InputStream ins = new URL(metadataUrl).openStream();
            javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
            org.w3c.dom.Document doc = builder.parse(ins);
            org.w3c.dom.NodeList timestampNodes = doc.getElementsByTagName("timestamp");
            org.w3c.dom.NodeList buildNumberNodes = doc.getElementsByTagName("buildNumber");
            if (timestampNodes.getLength() > 0 && buildNumberNodes.getLength() > 0) {
                String timestamp = timestampNodes.item(0).getTextContent();
                String buildNumber = buildNumberNodes.item(0).getTextContent();
                // 将版本中的 -SNAPSHOT 替换为 -时间戳-构建号
                String baseVersion = version.substring(0, version.length() - "-SNAPSHOT".length());
                return String.format("%s-%s-%s-%s.jar", name, baseVersion, timestamp, buildNumber);
            }
        } catch (Throwable ignored) {
            PrimitiveIO.debug("无法解析快照版本的实际文件名，回退到标准格式：{0}", name + "-" + version + ".jar");
        }
        return null;
    }

    /**
     * 根据 jar 文件内容计算 sha1 并写入 sha1 文件
     * 用于本地 file:// 仓库不提供 sha1 文件的场景
     */
    static void generateSha1File(File jarFile, File shaFile) {
        try {
            String hash = PrimitiveIO.getHash(jarFile);
            shaFile.getParentFile().mkdirs();
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(shaFile)) {
                fos.write(hash.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            PrimitiveIO.debug("无法生成 {0}，将用 jar 自行生成。", shaFile.getName());
        }
    }

    /**
     * 计算重定位缓存的指纹。
     *
     * <p>指纹必须覆盖<b>所有会改变产物内容的输入</b>：源文件名、重定向规则、以及
     * Kotlin 相关的重定位开关与目标包名。历史上这里只用了源文件名、规则列表和 Kotlin
     * 版本号，而真正参与重定位的规则还额外包含两条 Kotlin 规则（见 {@link #loadFile}），
     * 两者不完全重合，因此当"规则变了但版本号没变"时会产生同名不同内容的缓存。</p>
     *
     * @param file     源文件
     * @param relocate 重定向规则
     * @return 十六进制指纹
     */
    static String relocateCacheHash(File file, List<String[]> relocate) {
        StringBuilder sb = new StringBuilder();
        sb.append(file.getName()).append('|');
        // 规则顺序会影响产物，按原顺序拼接
        for (String[] r : relocate) {
            sb.append(r[0]).append('>').append(r[1]).append(';');
        }
        sb.append('|');
        // Kotlin 重定位的实际参数
        sb.append(SKIP_KOTLIN_RELOCATE).append(';');
        sb.append(KOTLIN_VERSION).append(';');
        sb.append(KOTLIN_COROUTINES_VERSION).append(';');
        if (!SKIP_KOTLIN_RELOCATE) {
            sb.append(PrimitiveSettings.getRelocatedKotlinVersion()).append(';');
            sb.append(PrimitiveSettings.getRelocatedKotlinCoroutinesVersion()).append(';');
        }
        return PrimitiveIO.getHash(sb.toString());
    }

    /**
     * 判断重定位缓存是否可复用。
     *
     * <p>除"文件存在且非空"外，还校验伴随标记文件里写下的规则指纹与产物内容指纹，
     * 避免复用到由不同规则生成、或内容已被破坏的旧产物。缺标记的旧缓存一律视为不可用，
     * 会被重建一次（这正是历史缺陷的兜底：旧版本生成的缓存没有标记，升级后自动重建）。</p>
     *
     * @param jar      缓存文件
     * @param ruleHash 当前预期的规则指纹
     * @return 是否可复用
     */
    static boolean isCacheUsable(File jar, String ruleHash) {
        if (!jar.isFile() || jar.length() == 0) {
            return false;
        }
        File marker = cacheMarkerFile(jar);
        if (!marker.isFile()) {
            return false;
        }
        try {
            String expected = PrimitiveIO.readFile(marker).trim();
            return !expected.isEmpty() && expected.equals(cacheMarkerContent(jar, ruleHash));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 写入缓存标记，记录重定向规则指纹。
     */
    static void writeCacheMarker(File jar, String ruleHash) {
        try {
            File marker = cacheMarkerFile(jar);
            marker.getParentFile().mkdirs();
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(marker)) {
                fos.write(cacheMarkerContent(jar, ruleHash).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            PrimitiveIO.debug("无法写入重定位缓存标记 {0}，下次启动将重建该缓存。", jar.getName());
        }
    }

    /**
     * 缓存标记文件路径。
     */
    static File cacheMarkerFile(File jar) {
        return new File(jar.getParentFile(), jar.getName() + ".marker");
    }

    /**
     * 标记内容：规则指纹 + 产物自身内容指纹。
     *
     * <p>产物内容指纹用于发现"文件被替换或被截断"这类与规则无关的损坏。</p>
     */
    private static String cacheMarkerContent(File jar, String ruleHash) {
        return jar.getName() + ':' + (ruleHash == null ? "" : ruleHash) + ':' + PrimitiveIO.getHash(jar);
    }
}
