import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

dependencies {
    implementation("org.tabooproject.reflex:reflex:1.2.6-c62cfeb")
    implementation("org.tabooproject.reflex:analyser:1.2.6-c62cfeb")
}

tasks {
    withType<ShadowJar> {
        dependencies {
            include(dependency("org.tabooproject.reflex:reflex:1.2.6-c62cfeb"))
            include(dependency("org.tabooproject.reflex:analyser:1.2.6-c62cfeb"))
        }
        relocate("org.taboooproject", "taboolib.library")
    }
}