@file:Suppress("GradlePackageUpdate", "VulnerableLibrariesLocal")

repositories {
    maven { url = uri("https://jitpack.io") }
    maven { url = uri("https://libraries.minecraft.net") }
    maven { url = uri("https://repo.papermc.io/repository/maven-public/") }
}

val foliaApi = "io.paper:folia-api:1.21.4"
val adventureApi = "net.kyori:adventure-api:4.9.2"
// 仅桥接编译时使用 Folia 的 Bukkit 接口，避免替换主源集依赖的旧 Bukkit 接口。
val foliaBridge = sourceSets.create("foliaBridge")
sourceSets.main { output.dir(foliaBridge.output) }
tasks.named<Jar>("sourcesJar") { from(foliaBridge.allSource) }

dependencies {
    add(foliaBridge.compileOnlyConfigurationName, foliaApi)
    add(foliaBridge.compileOnlyConfigurationName, adventureApi) { isTransitive = false }
    compileOnly(foliaBridge.output)
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
}