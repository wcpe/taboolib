import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

val reflexVersion: String by project

plugins {
    `maven-publish`
    java
    id("org.jetbrains.kotlin.jvm") version "1.8.22" apply false
    id("com.github.johnrengelman.shadow") version "7.1.2" apply false
}

// 版本号直接来自 gradle.properties（Debian 式：上游版本 + wcpe 修订后缀，如 6.3.0-wcpe.1），
// 不再拼接 git 短哈希；发版时手动递增 gradle.properties 的 version 并打同名 tag。
allprojects {
    version = rootProject.providers.gradleProperty("version").get()
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "com.github.johnrengelman.shadow")
    apply(plugin = "maven-publish")

    repositories {
        maven("https://jitpack.io")
        maven("https://libraries.minecraft.net")
        maven("https://repo1.maven.org/maven2")
        maven("https://maven.aliyun.com/repository/central")
        maven("https://repo.codemc.io/repository/nms/")
        maven("https://repo.tabooproject.org/repository/releases")
        maven("https://maven.wcpe.top/repository/maven-public")
        mavenLocal()
        mavenCentral()
    }

    dependencies {
        compileOnly(kotlin("stdlib"))
        compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
        compileOnly("com.google.guava:guava:21.0")
        compileOnly("com.google.code.gson:gson:2.8.7")
        compileOnly("org.apache.commons:commons-lang3:3.5")
        compileOnly("org.tabooproject.reflex:reflex:$reflexVersion")
        compileOnly("org.tabooproject.reflex:analyser:$reflexVersion")

        // 测试依赖
        testImplementation(kotlin("stdlib"))
        testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
        testImplementation("com.google.guava:guava:21.0")
        testImplementation("com.google.code.gson:gson:2.8.7")
        testImplementation("org.apache.commons:commons-lang3:3.5")
        testImplementation("org.tabooproject.reflex:reflex:$reflexVersion")
        testImplementation("org.tabooproject.reflex:analyser:$reflexVersion")
        testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.8.1")
        testImplementation("org.junit.jupiter:junit-jupiter-api:5.8.1")
    }

    java {
        withSourcesJar()
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }

    // Gradle 8.9：test 类路径消费依赖工程 shadowJar（classifier 为空）时须显式 dependsOn
    afterEvaluate {
        tasks.withType<Test>().configureEach {
            val shadowTasks = configurations.findByName("testImplementation")?.dependencies
                ?.filterIsInstance<org.gradle.api.artifacts.ProjectDependency>()
                ?.mapNotNull { dep ->
                    val depProject = dep.dependencyProject
                    depProject.tasks.findByName("shadowJar")?.let { depProject.tasks.named("shadowJar") }
                }
                ?: emptyList()
            if (shadowTasks.isNotEmpty()) {
                dependsOn(shadowTasks)
            }
        }
    }

    tasks.withType<ShadowJar> {
        archiveClassifier.set("")
        relocate("org.tabooproject", "taboolib.library")
    }

    tasks.build {
        dependsOn("shadowJar")
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-XDenableSunApiLintControl"))
    }

    tasks.withType<KotlinCompile> {
        kotlinOptions {
            jvmTarget = "1.8"
            freeCompilerArgs = listOf("-Xjvm-default=all")
        }
    }

    configure<JavaPluginConvention> {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

gradle.buildFinished {
    buildDir.deleteRecursively()
}

subprojects
    .filter { it.name != "module" && it.name != "platform" && it.name != "expansion" && it.name != "e2e-harness" && !it.name.startsWith("impl") }
    .forEach { proj ->
        proj.publishing { applyToSub(proj) }
    }

fun PublishingExtension.applyToSub(subProject: Project) {
    repositories {
        maven("https://maven.wcpe.top/repository/maven-tabooproject-release/") {
            credentials {
                username = project.findProperty("wcpeUsername").toString()
                password = project.findProperty("wcpePassword").toString()
            }
            authentication {
                create<BasicAuthentication>("basic")
            }
        }
//        maven("http://repo.aeoliancloud.com/repository/releases") {
//            isAllowInsecureProtocol = true
//            credentials {
//                username = project.findProperty("aeolianUsername").toString()
//                password = project.findProperty("aeolianPassword").toString()
//            }
//            authentication {
//                create<BasicAuthentication>("basic")
//            }
//        }
        mavenLocal()
    }
    publications {
        create<MavenPublication>("maven") {
            // 构件名
            artifactId = if (subProject.ext.has("publishId")) subProject.ext.get("publishId").toString() else subProject.name
            // 组
            groupId = "io.izzel.taboolib"
            // 版本号
            // 注意：不能用 "-dev" 后缀——TabooLib 运行时 IS_DEV_MODE 会因版本以 -dev 结尾
            // 而强制联网下载 taboolib 模块（无视本地 libraries 缓存），导致 local-dev 版本
            // 在无网络/远程无此版本时无法加载。改用不带 -dev 的本地唯一版本号。
            version = when {
                project.hasProperty("devLocal") -> "${project.version}-local"
                project.hasProperty("dev") -> "${project.version}-local"
                else -> "${project.version}"
            }
            // 构件
            artifact(subProject.tasks["kotlinSourcesJar"])
            artifact(subProject.tasks["shadowJar"])
            println("> Apply \"$groupId:$artifactId:$version\"")
        }
    }
}
