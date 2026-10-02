plugins {
    java
    id("com.gradleup.shadow")
}

// @Plugin(version) must be a compile-time constant: generate it from the project version (one source of truth).
val generateConstants = tasks.register<Copy>("generateConstants") {
    val pluginVersion = project.version.toString()
    inputs.property("version", pluginVersion)
    from("src/main/templates")
    into(layout.buildDirectory.dir("generated/sources/templates"))
    expand("version" to pluginVersion)
}
sourceSets.main { java.srcDir(generateConstants) }

dependencies {
    implementation(project(":core"))
    compileOnly("com.velocitypowered:velocity-api:4.2.0")
    annotationProcessor("com.velocitypowered:velocity-api:4.2.0") // generates velocity-plugin.json
    compileOnly("net.luckperms:api:5.5")

    testImplementation("com.velocitypowered:velocity-api:4.2.0")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

configurations.runtimeClasspath {
    exclude(group = "org.slf4j")            // provided by Velocity
    exclude(group = "com.google.code.gson") // provided by Velocity
}

tasks.shadowJar {
    exclude("com/friends/api/bukkit/**", "com/friends/api/bungee/**") // other platforms' events
}
