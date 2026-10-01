plugins {
    java
}

// A test-only plugin using the API like any other plugin would (see tools/e2e/run.py, "api-*" scenarios). One jar for
// Paper, BungeeCord and Velocity: each reads its own descriptor. Java 8, like the API: Paper 1.13-1.20's class
// rewriter must read it.
java {
    disableAutoTargetJvm() // velocity-api is Java 25 bytecode: only referenced
}

tasks.compileJava {
    options.release = 8
    options.compilerArgs.add("-Xlint:-options")
}

dependencies {
    compileOnly(project(":api"))
    compileOnly("org.github.paperspigot:paperspigot-api:1.8.8-R0.1-SNAPSHOT")
    compileOnly("com.velocitypowered:velocity-api:4.2.0")
    compileOnly("net.md-5:bungeecord-api:1.21-R0.4")
}

tasks.jar {
    archiveFileName = "friends-probe.jar"
    manifest { attributes("paperweight-mappings-namespace" to "mojang") } // no NMS: Paper needn't remap it
}
