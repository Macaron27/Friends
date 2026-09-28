plugins {
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

dependencies {
    implementation(project(":common"))
    compileOnly("io.papermc.paper:paper-api:1.20.2-R0.1-SNAPSHOT") // pinned to a resolvable Paper snapshot for local builds
}

java {
    withJavadocJar()
    withSourcesJar()
}

// Produce a shadow (fat) jar that includes ':common' so the plugin can load at runtime
tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveBaseName.set("friends-paper")
    archiveVersion.set("0.1.0")
    // include runtime classpath (project dependencies like :common will be bundled)
    mergeServiceFiles()
}

// Convenience: copy the shadow jar to the root build/libs so both plugin jars are available for testing
tasks.register<Copy>("copyPaperToRoot") {
    dependsOn(tasks.named("shadowJar"))
    from(tasks.named("shadowJar"))
    into(rootProject.layout.buildDirectory.dir("libs"))
}

// Make the local 'assemble' depend on the copy task so `./gradlew build` will provide the paper shadow jar in root/build/libs
tasks.named("assemble") {
    dependsOn(tasks.named("copyPaperToRoot"))
} 
