plugins {
    `java-library`
}

dependencies {
    api(project(":api"))

    // Provided at runtime by Velocity (and Paper), so compileOnly here.
    compileOnly(platform("net.kyori:adventure-bom:5.2.0"))
    compileOnly("net.kyori:adventure-api")
    compileOnly("net.kyori:adventure-text-minimessage")
    compileOnly("net.kyori:adventure-text-serializer-legacy")
    compileOnly("org.slf4j:slf4j-api:2.0.17")
    compileOnly("com.google.code.gson:gson:2.14.0")
    // Oldest chat API (Spigot 1.8.8's) so BungeeChat only uses what every BungeeCord/Paper version has.
    compileOnly("net.md-5:bungeecord-chat:1.8-SNAPSHOT")
    compileOnly("net.luckperms:api:5.5")

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
    // Tests compile against the 1.8 chat API and run against the newest (testChat1_8 re-runs them on 1.8).
    testCompileOnly("net.md-5:bungeecord-chat:1.8-SNAPSHOT")
    testRuntimeOnly("net.md-5:bungeecord-chat:1.21-R0.4")
    testRuntimeOnly("net.md-5:bungeecord-serializer:1.21-R0.4") // ComponentSerializer moved here after 1.8
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Paper 1.8.8 and 1.12.2 bundle these sqlite-jdbc versions and Bukkit loads them before ours:
// run the SQLite suites against them too (SQL must stay portable to SQLite 3.7.2).
for (version in listOf("3.7.2", "3.21.0.1")) {
    val id = version.replace(".", "_")
    val driver = configurations.create("sqlite$id")
    dependencies.add(driver.name, "org.xerial:sqlite-jdbc:$version")
    val task = tasks.register<Test>("testSqlite$id") {
        description = "Storage and core tests on sqlite-jdbc $version"
        group = "verification"
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath.filter { !it.name.startsWith("sqlite-jdbc-") } + driver
        filter {
            includeTestsMatching("com.friends.common.StorageTest")
            includeTestsMatching("com.friends.common.FriendsTest")
        }
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        // 3.21.0.1 has no Apple Silicon native (Linux x86_64/aarch64 are fine); 3.7.2 falls back to pure Java.
        onlyIf("sqlite-jdbc $version has a native library here") {
            version != "3.21.0.1" || !(System.getProperty("os.name").startsWith("Mac") && System.getProperty("os.arch") == "aarch64")
        }
    }
    tasks.check { dependsOn(task) }
}

val chat18 = configurations.create("chat18")
dependencies.add(chat18.name, "net.md-5:bungeecord-chat:1.8-SNAPSHOT")
val testChat18 = tasks.register<Test>("testChat1_8") {
    description = "Chat conversion tests on the 1.8 bungeecord-chat API (Paper 1.8.8)"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath.filter { !it.name.startsWith("bungeecord-") } + chat18
    filter { includeTestsMatching("com.friends.common.BungeeChatTest") }
}
tasks.check { dependsOn(testChat18) }

tasks.test {
    jvmArgs("--enable-native-access=ALL-UNNAMED") // sqlite-jdbc loads a native library
    // Opt-in MySQL run: -Dfriends.mysql=host:port/db:user:password
    System.getProperty("friends.mysql")?.let { systemProperty("friends.mysql", it) }
    // Opt-in Redis run: -Dfriends.redis=host:port (uses a throwaway key namespace)
    System.getProperty("friends.redis")?.let { systemProperty("friends.redis", it) }
}
