plugins {
    java
    id("com.gradleup.shadow")
}

// Project version (root build) must match @Plugin(version) in FriendsVelocity.

dependencies {
    implementation(project(":common"))
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
