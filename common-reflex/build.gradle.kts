import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

val reflexVersion: String by project

dependencies {
    implementation("org.tabooproject.reflex:reflex:$reflexVersion")
    implementation("org.tabooproject.reflex:analyser:$reflexVersion")
}

tasks {
    withType<ShadowJar> {
        dependencies {
            include(dependency("org.tabooproject.reflex:reflex:$reflexVersion"))
            include(dependency("org.tabooproject.reflex:analyser:$reflexVersion"))
        }
        relocate("org.taboooproject", "taboolib.library")
    }
}
