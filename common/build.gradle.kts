plugins {
    `java-library`
}

dependencies {
    // Provided at runtime by Velocity (and Paper), so compileOnly here.
    compileOnly(platform("net.kyori:adventure-bom:5.2.0"))
    compileOnly("net.kyori:adventure-api")
    compileOnly("net.kyori:adventure-text-minimessage")
    compileOnly("net.kyori:adventure-text-serializer-legacy")
    compileOnly("org.slf4j:slf4j-api:2.0.17")
    compileOnly("com.google.code.gson:gson:2.14.0")

    // Shaded into the plugin jar.
    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
    implementation("com.mysql:mysql-connector-j:26.7.0")
    implementation("redis.clients:jedis:8.0.1")

    testImplementation(platform("net.kyori:adventure-bom:5.2.0"))
    testImplementation("net.kyori:adventure-api")
    testImplementation("net.kyori:adventure-text-minimessage")
    testImplementation("net.kyori:adventure-text-serializer-legacy")
    testImplementation("net.kyori:adventure-text-serializer-plain")
    testImplementation("org.slf4j:slf4j-api:2.0.17")
    testImplementation("com.google.code.gson:gson:2.14.0")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    jvmArgs("--enable-native-access=ALL-UNNAMED") // sqlite-jdbc loads a native library
    // Opt-in MySQL run: -Dfriends.mysql=host:port/db:user:password
    System.getProperty("friends.mysql")?.let { systemProperty("friends.mysql", it) }
    // Opt-in Redis run: -Dfriends.redis=host:port (uses a throwaway key namespace)
    System.getProperty("friends.redis")?.let { systemProperty("friends.redis", it) }
}
