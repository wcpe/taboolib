import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

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
    withType<ShadowJar> {
        dependsOn(project(":common").tasks.named("shadowJar"))
        dependsOn(project(":platform:platform-bukkit").tasks.named("shadowJar"))
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

    withType<ShadowJar> {
        archiveClassifier.set("")
    }
}
