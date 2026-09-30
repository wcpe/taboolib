import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("top.wcpe.mc-testkit")
}

val localMavenRepository = file("${System.getProperty("user.home")}/.m2/repository").toURI().toString().removeSuffix("/")
val localTabooLibVersion = "${project.version}-local"

dependencies {
    implementation(project(":common"))
    implementation(project(":platform:platform-bukkit"))
    compileOnly(project(":common-util"))
    compileOnly(project(":common-platform-api"))
    compileOnly("ink.ptms.core:v12104:12104:universal")
}

tasks {
    // thin jar 与 shadowJar 在本模块同名（根构建把 shadowJar 的 classifier 设为空）会互相覆盖，
    // 本模块的产物就是 fat jar，故禁用 thin jar
    jar {
        enabled = false
    }

    withType<ShadowJar> {
        dependsOn(project(":common").tasks.named("shadowJar"))
        dependsOn(project(":platform:platform-bukkit").tasks.named("shadowJar"))
        archiveClassifier.set("")
    }

    test {
        dependsOn(project(":common").tasks.named("shadowJar"))
        dependsOn(project(":platform:platform-bukkit").tasks.named("shadowJar"))
    }

    processResources {
        inputs.property("localMavenRepository", localMavenRepository)
        inputs.property("localTabooLibVersion", localTabooLibVersion)
        filesMatching("META-INF/taboolib/env.properties") {
            expand("localMavenRepository" to localMavenRepository)
        }
        filesMatching("META-INF/taboolib/version.properties") {
            expand("localTabooLibVersion" to localTabooLibVersion)
        }
    }
}

// NMS 兼容性断点矩阵（Paper only）：mc-testkit versionMatrix 一等公民声明。
// 每个 Minecraft 大版本的最新断点跑 bot-full，其余断点跑 smoke。
// 展开为 backend v<key> + scenario full/smoke-<key>，聚合任务 e2eMatrixNms / e2eMatrixNmsSmokeOnly。
mcTestkit {
    // 待测产物 = 本模块 fat jar（ADR-0025）：框架据此自动建立产物任务依赖，无需手写 dependsOn
    dependencies {
        pluginUnderTest(from = tasks.named<ShadowJar>("shadowJar"))
    }

    versionMatrix("nms") {
        platform = paper
        portBase = 25600
        botUsernamePrefix = "Tb"
        botAction = "taboolib-full"

        // 1.12–1.17：Paperclip 在现代 Maven 上可能解析到 snakeyaml 2.x 导致旧服务端拒启；
        // 先 smoke 验证插件/NMS，bot-full 从 1.18+ 起（mineflayer + Paperclip 更稳）。
        entry("1.12.2")
        entry("1.13.2")
        entry("1.14.4")
        entry("1.15.2")
        entry("1.16.5")
        entry("1.17.1")
        entry("1.18.2") { bot = true }
        entry("1.19.2")
        entry("1.19.3")
        entry("1.19.4") { bot = true }
        entry("1.20.1")
        entry("1.20.2")
        entry("1.20.4")
        entry("1.20.6") { bot = true }
        entry("1.21.1")
        entry("1.21.5")
        entry("1.21.8") { bot = true }
        // 26.x：mineflayer 尚不支持协议 777，统一 smoke（无 bot）
        entry("26.1.2")
        entry("26.2")
        entry("26.3")
    }
}
