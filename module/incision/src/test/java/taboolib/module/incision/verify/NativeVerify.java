package taboolib.module.incision.verify;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * native 产物校验。
 *
 * <p>1. 检查 6 个平台产物的文件格式与 JNI_OnLoad 导出；
 * <p>2. 确认目录中没有 zig 编译 Windows 目标时顺带产生的 .pdb/.lib 等副产物；
 * <p>3. 比对 C 源码哈希，捕捉「改了 C 源码却忘记重新编译」的不同步；
 * <p>4. 在当前平台上走 {@code JvmtiBackend.tryLoad()} 做真实加载验证
 *       （从 classpath 提取 → System.load → JNI_OnLoad 注册 → JVMTI 初始化）。
 *
 * <p>任一项失败即以退出码 1 结束，使构建失败。
 */
public final class NativeVerify {

    /** {相对路径, 期望的格式魔数标识} */
    private static final String[][] TARGETS = {
            {"windows/x64/incision-jvmti.dll", "PE"},
            {"windows/arm64/incision-jvmti.dll", "PE"},
            {"linux/x64/libincision-jvmti.so", "ELF"},
            {"linux/arm64/libincision-jvmti.so", "ELF"},
            {"macos/x64/libincision-jvmti.dylib", "MACHO"},
            {"macos/arm64/libincision-jvmti.dylib", "MACHO"},
    };

    /** 不允许出现在产物目录里的副产物后缀 */
    private static final String[] FORBIDDEN_SUFFIXES = {".pdb", ".lib", ".bak", ".exp", ".ilk", ".o"};

    /** 由构建脚本在编译成功后写入的源码哈希记录 */
    private static final String SOURCE_LOCK = "native-source.sha256";

    private static final String SOURCE_FILE = "incision_jvmti.c";

    public static void main(String[] args) throws Exception {
        File nativeRoot = new File(args[0]);
        File cSourceDir = new File(args[1]);
        if (!nativeRoot.isDirectory()) {
            System.out.println("[verifyNative] 找不到产物目录：" + nativeRoot);
            System.exit(1);
        }

        List<String> problems = new ArrayList<>();

        System.out.println("[verifyNative] 产物静态检查：" + nativeRoot.getPath());
        for (String[] target : TARGETS) {
            String label = String.format("  %-34s", target[0]);
            File file = new File(nativeRoot, target[0]);
            try {
                long size = inspect(file, target[1]);
                System.out.println(label + "✅ " + String.format("%8d 字节", size));
            } catch (Throwable t) {
                System.out.println(label + "❌ " + t.getMessage());
                problems.add(target[0] + " — " + t.getMessage());
            }
        }

        // zig 编译 Windows 目标时会在输出目录顺带生成 .pdb/.lib，历史上曾因此把 2.2MB
        // 调试符号提交进仓库并打进 JAR，这里做一道防回归。
        List<String> strays = new ArrayList<>();
        collectStrays(nativeRoot, strays);
        if (strays.isEmpty()) {
            System.out.println("\n[verifyNative] 无多余副产物 ✅");
        } else {
            System.out.println("\n[verifyNative] 发现多余副产物 ❌");
            for (String s : strays) {
                System.out.println("    " + s);
                problems.add("多余副产物 " + s);
            }
        }

        // 源码同步：6 个二进制是预编译入库的，改了 C 源码如果不重跑构建脚本不会有任何编译错误，
        // 产物会一直停留在旧版本（历史上就发生过源码修复从未进入产物的情况）。
        System.out.println("\n[verifyNative] C 源码同步校验：" + SOURCE_FILE);
        try {
            checkSourceSync(cSourceDir);
            System.out.println("  ✅ 产物与当前源码一致");
        } catch (Throwable t) {
            System.out.println("  ❌ " + t.getMessage());
            problems.add("源码不同步 — " + t.getMessage());
        }

        // 真实加载验证只在当前平台有意义；其他平台仅做上面的静态检查。
        String triple = currentTriple();
        System.out.println("\n[verifyNative] 当前平台加载验证：" + (triple == null ? "未知平台，跳过" : triple));
        if (triple != null) {
            try {
                loadAndVerify();
                System.out.println("  ✅ tryLoad() = true，JVMTI 已就绪");
            } catch (Throwable t) {
                Throwable cause = t.getCause() != null ? t.getCause() : t;
                System.out.println("  ❌ " + cause);
                problems.add("当前平台加载失败 — " + cause);
            }
        }

        if (!problems.isEmpty()) {
            System.out.println("\n[verifyNative] 校验失败，共 " + problems.size() + " 项：");
            for (String p : problems) {
                System.out.println("  - " + p);
            }
            System.exit(1);
        }
        System.out.println("\n[verifyNative] 全部校验通过");
    }

    /** 校验单个产物的魔数与导出符号，返回文件大小 */
    private static long inspect(File file, String expectFormat) throws Exception {
        if (!file.isFile()) {
            throw new IllegalStateException("文件不存在");
        }
        byte[] data = Files.readAllBytes(file.toPath());
        if (data.length < 64) {
            throw new IllegalStateException("文件过小，疑似构建失败");
        }

        String actual = detectFormat(data);
        if (!expectFormat.equals(actual)) {
            throw new IllegalStateException("格式不符，期望 " + expectFormat + " 实际 " + actual);
        }

        // strip 只移除静态符号表与调试段，运行时查找依赖的动态符号表/导出表会保留 JNI_OnLoad，
        // 因此这里按字节串检索即可覆盖 6 个平台。
        String latin1 = new String(data, StandardCharsets.ISO_8859_1);
        if (!latin1.contains("JNI_OnLoad")) {
            throw new IllegalStateException("未找到 JNI_OnLoad，JVM 将无法加载该库");
        }
        return data.length;
    }

    private static String detectFormat(byte[] d) {
        if (d[0] == 0x4D && d[1] == 0x5A) {
            return "PE";
        }
        if (d[0] == 0x7F && d[1] == 'E' && d[2] == 'L' && d[3] == 'F') {
            return "ELF";
        }
        if ((d[0] & 0xFF) == 0xCF && (d[1] & 0xFF) == 0xFA && (d[2] & 0xFF) == 0xED && (d[3] & 0xFF) == 0xFE) {
            return "MACHO";
        }
        if ((d[0] & 0xFF) == 0xFE && (d[1] & 0xFF) == 0xED && (d[2] & 0xFF) == 0xFA && (d[3] & 0xFF) == 0xCF) {
            return "MACHO";
        }
        return "UNKNOWN";
    }

    private static void collectStrays(File dir, List<String> out) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collectStrays(child, out);
                continue;
            }
            String name = child.getName().toLowerCase();
            for (String suffix : FORBIDDEN_SUFFIXES) {
                if (name.endsWith(suffix)) {
                    out.add(child.getPath());
                    break;
                }
            }
        }
    }

    /** 比对源码哈希，确认二进制确实是当前源码编译出来的 */
    private static void checkSourceSync(File cSourceDir) throws Exception {
        File source = new File(cSourceDir, SOURCE_FILE);
        if (!source.isFile()) {
            throw new IllegalStateException("找不到源码 " + source.getPath());
        }
        File lock = new File(cSourceDir, SOURCE_LOCK);
        if (!lock.isFile()) {
            throw new IllegalStateException("缺少 " + SOURCE_LOCK + "，请先运行 src/main/c/build-native.sh（或 build-native.bat）");
        }

        List<String> lines = Files.readAllLines(lock.toPath(), StandardCharsets.UTF_8);
        String recorded = null;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] parts = trimmed.split("\\s+");
            recorded = parts[0].toLowerCase();
            break;
        }
        if (recorded == null || recorded.length() != 64) {
            throw new IllegalStateException(SOURCE_LOCK + " 内容无效，请重新运行构建脚本");
        }

        String actual = sha256(source);
        if (!actual.equals(recorded)) {
            throw new IllegalStateException(
                    "incision_jvmti.c 已改动但 6 个平台产物未重新编译，请运行 "
                            + "src/main/c/build-native.sh（Linux/macOS）或 build-native.bat（Windows）");
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(Files.readAllBytes(file.toPath()));
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** 当前平台标识，用于判断是否可做本机加载验证 */
    private static String currentTriple() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        String a = (arch.contains("aarch64") || arch.contains("arm64")) ? "arm64" : "x64";
        if (os.contains("win")) {
            return "windows/" + a;
        }
        if (os.contains("mac")) {
            return "macos/" + a;
        }
        if (os.contains("linux")) {
            return "linux/" + a;
        }
        return null;
    }

    /** 走真实路径加载 native：JvmtiBackend.tryLoad() 内部会提取资源、System.load 并初始化 JVMTI */
    private static void loadAndVerify() throws Exception {
        Class<?> backend = Class.forName("taboolib.module.incision.loader.JvmtiBackend");
        Object instance = backend.getField("INSTANCE").get(null);
        Method tryLoad = backend.getMethod("tryLoad");
        Object result = tryLoad.invoke(instance);
        if (!Boolean.TRUE.equals(result)) {
            throw new IllegalStateException("tryLoad() 返回 " + result);
        }
    }
}
