plugins {
    `java-library`
    `maven-publish`
}

// Java 8 bytecode: on Paper these classes live in the bootstrap jar (see FriendsBootstrap), which every CraftBukkit
// class rewriter must be able to read, and plugins built for any Java version can compile against them.
base.archivesName = "friends-api"

java {
    withSourcesJar()
    withJavadocJar()
    disableAutoTargetJvm() // velocity-api is Java 25 bytecode: we only reference it, so don't let Gradle refuse it
}

tasks.compileJava {
    options.release = 8
    options.compilerArgs.addAll(listOf("-Xlint:all,-options", "-Werror"))
}

tasks.javadoc {
    // Every public type and member must be documented: a missing @param or comment fails the build.
    (options as StandardJavadocDocletOptions).apply {
        addBooleanOption("Xdoclint:all", true)
        addBooleanOption("Werror", true)
        addStringOption("-release", "8")
    }
}

dependencies {
    // Events extend each platform's event type; the oldest supported APIs, like the plugins themselves.
    compileOnly("org.github.paperspigot:paperspigot-api:1.8.8-R0.1-SNAPSHOT")
    compileOnly("com.velocitypowered:velocity-api:4.2.0")
    compileOnly("net.md-5:bungeecord-api:1.21-R0.4")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "friends-api"
            from(components["java"])
        }
    }
}
