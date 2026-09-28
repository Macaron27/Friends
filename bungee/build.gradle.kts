plugins {
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

dependencies {
    implementation(project(":common"))
    // Compile against a local stub so builds don't require the Bungee snapshot repo.
    compileOnly(project(":bungee-api-stub"))
}

java {
    withJavadocJar()
    withSourcesJar()
    toolchain {
        languageVersion.set(org.gradle.jvm.toolchain.JavaLanguageVersion.of(21))
    }
}

// Produce a shadow (fat) jar for the proxy so ':common' is bundled
tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveBaseName.set("friends-bungee")
    archiveVersion.set("0.1.0")
    mergeServiceFiles()
}

// Convenience: copy the bungee shadow jar to the root build/libs for easy testing
tasks.register<Copy>("copyBungeeToRoot") {
    dependsOn(tasks.named("shadowJar"))
    from(tasks.named("shadowJar"))
    into(rootProject.layout.buildDirectory.dir("libs"))
    // The produced file will be e.g. friends-bungee-0.1.0.jar
}

// Make the local 'assemble' depend on the copy task so `./gradlew build` will provide the bungee shadow jar in root/build/libs
tasks.named("assemble") {
    dependsOn(tasks.named("copyBungeeToRoot"))
}