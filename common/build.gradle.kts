dependencies {
    compileOnly("me.lucko:jar-relocator:1.7")
    testImplementation(project(":common-util"))
}

val reflexVersion: String by project
val generatedReflexVersion = layout.buildDirectory.dir("generated/sources/reflex-version/java")

val generateReflexVersion by tasks.registering {
    inputs.property("reflexVersion", reflexVersion)
    outputs.dir(generatedReflexVersion)
    doLast {
        val source = generatedReflexVersion.get().file("taboolib/common/ReflexVersion.java").asFile
        source.parentFile.mkdirs()
        source.writeText(
            """
            package taboolib.common;

            final class ReflexVersion {

                static final String VERSION = "$reflexVersion";

                private ReflexVersion() {
                }
            }
            """.trimIndent()
        )
    }
}

sourceSets.main {
    java.srcDir(generatedReflexVersion)
}

tasks.named("compileJava") {
    dependsOn(generateReflexVersion)
}

tasks.named("kotlinSourcesJar") {
    dependsOn(generateReflexVersion)
}

tasks.named("sourcesJar") {
    dependsOn(generateReflexVersion)
}

tasks.test {
    systemProperty("reflexVersion", reflexVersion)
}
