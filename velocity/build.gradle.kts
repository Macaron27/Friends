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
}

configurations.runtimeClasspath {
    exclude(group = "org.slf4j")            // provided by Velocity
    exclude(group = "com.google.code.gson") // provided by Velocity
}
