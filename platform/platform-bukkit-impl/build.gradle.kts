@file:Suppress("GradlePackageUpdate", "VulnerableLibrariesLocal")

repositories {
    maven { url = uri("https://jitpack.io") }
    maven { url = uri("https://libraries.minecraft.net") }
    maven { url = uri("https://repo.papermc.io/repository/maven-public/") }
}

val foliaApi = "io.paper:folia-api:1.21.4"
val adventureApi = "net.kyori:adventure-api:4.9.2"

dependencies {
    compileOnly(project(":common"))
    compileOnly(project(":common-platform-api"))
    compileOnly(project(":common-util"))
    compileOnly(project(":platform:platform-bukkit"))
    // 仅限 XItemStack
    compileOnly(project(":module:basic:basic-configuration"))
    compileOnly(project(":module:minecraft:minecraft-chat"))

    // 服务端
    compileOnly("ink.ptms.core:v12110:12110:mapped")
    compileOnly(foliaApi)
    compileOnly("net.md-5:bungeecord-chat:1.20")

    // 用于处理命令
    // ClassCastException: Cannot cast java.lang.String to net.kyori.adventure.text.Component
    compileOnly(adventureApi)
    // Mojang API
    compileOnly("com.mojang:brigadier:1.0.18")

    // XSeries
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")
    compileOnly("org.apache.logging.log4j:log4j-api:2.14.1")

    // 实体退休契约测试只需调度器接口，不起服务端
    testImplementation(foliaApi)
    testImplementation(project(":common-platform-api"))
    testRuntimeOnly("org.slf4j:slf4j-api:2.0.9")
    testRuntimeOnly("org.apache.logging.log4j:log4j-api:2.14.1")
    testRuntimeOnly(adventureApi)
    testRuntimeOnly("net.kyori:adventure-text-logger-slf4j:4.11.0")
}

// folia-api 按 Java 21 编译，本模块测试 JVM 需要用 21 跑
tasks.withType<Test>().configureEach {
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}