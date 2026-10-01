plugins {
    java
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":common"))
    compileOnly("net.md-5:bungeecord-api:1.21-R0.4")
    compileOnly("net.luckperms:api:5.5")

    testImplementation("net.md-5:bungeecord-api:1.21-R0.4")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // BungeeCord has no Adventure or SLF4J: bundle them (relocated below); SLF4J logs to BungeeCord's JUL logger.
    implementation(platform("net.kyori:adventure-bom:5.2.0"))
    implementation("net.kyori:adventure-api")
    implementation("net.kyori:adventure-text-minimessage")
    implementation("net.kyori:adventure-text-serializer-legacy")
    implementation("org.slf4j:slf4j-api:2.0.17")
    runtimeOnly("org.slf4j:slf4j-jdk14:2.0.17")
    implementation("com.google.code.gson:gson:2.14.0") // records support for the Redis wire format
}

tasks.shadowJar {
    exclude("com/friends/api/bukkit/**", "com/friends/api/velocity/**") // other platforms' events
    relocate("net.kyori", "com.friends.lib.kyori")
    relocate("org.slf4j", "com.friends.lib.slf4j")
    relocate("com.google.gson", "com.friends.lib.gson")
}
