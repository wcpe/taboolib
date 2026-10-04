dependencies {
    compileOnly(project(":common"))
    compileOnly(project(":common-env"))
    compileOnly(project(":common-platform-api"))
    compileOnly(project(":common-util"))
    compileOnly(project(":common-platform-api"))
    compileOnly(project(":platform:platform-bukkit"))
    compileOnly(project(":platform:platform-bukkit-impl"))
    // 可选 — 检测到时启用 NMS NameResolver
    compileOnly(project(":module:bukkit-nms"))
    // ASM
    compileOnly("org.ow2.asm:asm:9.8")
    compileOnly("org.ow2.asm:asm-commons:9.8")
    compileOnly("org.ow2.asm:asm-util:9.8")
    // self-attach 默认路径
    compileOnly("net.bytebuddy:byte-buddy-agent:1.14.18")
    // 测试
    testImplementation(project(":common"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    // BodiesClassGenerator 在运行期直接使用 ASM，测试需要 ASM 运行时
    testImplementation("org.ow2.asm:asm:9.8")
    testImplementation("org.ow2.asm:asm-commons:9.8")
    testImplementation("org.ow2.asm:asm-util:9.8")
}

// 性能验收用例（@Tag("perf")，如 WovenInvocationOverheadTest）耗时且数值受机器/GC 噪声影响，
// 不适合作为每次构建的门禁：默认从常规 test 里排除，需要人工看数值时显式点名或显式开关放行。
//   ./gradlew :module:incision:test --tests "*WovenInvocationOverheadTest*"
//   ./gradlew :module:incision:test -PincisionPerf
val runPerfTaggedTests: Boolean = hasProperty("incisionPerf") ||
    gradle.startParameter.taskRequests.any { request ->
        request.args.any { it == "--tests" || it.startsWith("--tests=") }
    }

tasks.test {
    useJUnitPlatform {
        if (!runPerfTaggedTests) {
            excludeTags("perf")
        }
    }
}

// native 产物校验：6 平台二进制是预编译入库的，C 源码改了却忘记重编不会有任何编译错误。
// 这里加一道门禁 —— 静态检查格式与 JNI_OnLoad 导出、拦截 .pdb/.lib 副产物，
// 并在当前平台走 JvmtiBackend.tryLoad() 做真实加载验证。
tasks.register<JavaExec>("verifyNative") {
    group = "verification"
    description = "校验 6 平台 native 产物的格式与 JNI_OnLoad 导出，并在当前平台真实加载验证"
    // 独立 JVM 中运行：native 库一经加载便无法在同一 JVM 内卸载，不能污染 Gradle daemon
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("taboolib.module.incision.verify.NativeVerify")
    // classpath 里的 :common 产物由 shadowJar 生成，Gradle 无法从 FileCollection 推断，
    // 必须显式声明，否则 Gradle 会按「隐式依赖」报错
    dependsOn(project(":common").tasks.named("shadowJar"))
    args(
        layout.buildDirectory.dir("resources/main/native").get().asFile.absolutePath,
        layout.projectDirectory.dir("src/main/c").asFile.absolutePath,
    )
}

tasks.named("check") {
    dependsOn(tasks.named("verifyNative"))
}
