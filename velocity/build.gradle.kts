plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

// Project version (root build) must match @Plugin(version) in FriendsVelocity.

dependencies {
    implementation(project(":common"))
    compileOnly("com.velocitypowered:velocity-api:4.2.0")
    annotationProcessor("com.velocitypowered:velocity-api:4.2.0") // generates velocity-plugin.json
    compileOnly("net.luckperms:api:5.5")
}

configurations.runtimeClasspath {
    exclude(group = "org.slf4j")           // provided by Velocity
    exclude(group = "com.google.code.gson") // provided by Velocity
    exclude(group = "com.google.protobuf") // only used by MySQL's X DevAPI
}

tasks.shadowJar {
    archiveBaseName = "friends-velocity"
    archiveClassifier = ""
    // sqlite-jdbc is not relocated: its JNI symbols are bound to the org.sqlite package name.
    relocate("com.zaxxer.hikari", "com.friends.lib.hikari")
    relocate("com.mysql", "com.friends.lib.mysql")
    relocate("redis.clients", "com.friends.lib.jedis")
    relocate("org.apache.commons.pool2", "com.friends.lib.pool2")
    relocate("org.json", "com.friends.lib.json")
    // No Java 25 runtime exists for 32-bit x86 (JEP 503/479) or 32-bit ARM Windows: drop those natives (~5 MB).
    exclude("org/sqlite/native/*/x86/**", "org/sqlite/native/Windows/armv7/**")
    mergeServiceFiles()
    filesMatching("META-INF/services/**") { duplicatesStrategy = DuplicatesStrategy.INCLUDE } // let both JDBC drivers merge
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}
