import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

dependencies {
    compileOnly(project(":common-util"))
    compileOnly(project(":module:bukkit-nms"))
    compileOnly(project(":module:bukkit-nms:bukkit-nms-data-serializer"))
    compileOnly("ink.ptms.core:v12005:12005:mapped")
    compileOnly("ink.ptms:nms-all:1.0.0")
    compileOnly("com.mojang:brigadier:1.0.18")
    compileOnly("io.netty:netty-all:4.1.73.Final")
}

tasks {
    withType<ShadowJar> {
        archiveClassifier.set("")
        relocate("org.tabooproject", "taboolib.library")
    }
    build {
        dependsOn(shadowJar)
    }
}