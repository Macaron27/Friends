plugins {
    java
    id("com.gradleup.shadow")
}

// Two layers (see FriendsBootstrap): a Java 8 bootstrap Bukkit loads, and the real plugin as an embedded jar.
val bootstrap: SourceSet = sourceSets.create("bootstrap")

dependencies {
    implementation(project(":common"))
    // The oldest supported API: anything that compiles here exists on every Paper from 1.8.8 to 26.x.
    compileOnly("org.github.paperspigot:paperspigot-api:1.8.8-R0.1-SNAPSHOT")
    "bootstrapCompileOnly"("org.github.paperspigot:paperspigot-api:1.8.8-R0.1-SNAPSHOT")
    compileOnly("net.luckperms:api:5.5")

    // Paper < 1.16.5 has no Adventure and < 1.17 no SLF4J: bundle them (relocated below); SLF4J logs to the JUL logger.
    implementation(platform("net.kyori:adventure-bom:5.2.0"))
    implementation("net.kyori:adventure-api")
    implementation("net.kyori:adventure-text-minimessage")
    implementation("net.kyori:adventure-text-serializer-legacy")
    implementation("org.slf4j:slf4j-api:2.0.17")
    runtimeOnly("org.slf4j:slf4j-jdk14:2.0.17")
}

tasks.named<JavaCompile>("compileBootstrapJava") {
    options.release = 8 // readable by every CraftBukkit class rewriter
    options.compilerArgs.add("-Xlint:-options") // yes, javac, release 8 is old: that's the point
}

configurations.runtimeClasspath {
    // Every Paper bundles sqlite-jdbc (3.7.2 on 1.8.8 ... 3.49 on 26.x) and Bukkit loads the server's copy first,
    // so ours would be ~11 MB of dead natives. Storage's SQLite SQL is kept portable to 3.7.2 for this.
    exclude(group = "org.xerial", module = "sqlite-jdbc")
    // Redis (multi-proxy) is for proxies only: no Jedis stack on Paper.
    exclude(group = "redis.clients")
    exclude(group = "org.apache.commons", module = "commons-pool2")
    exclude(group = "org.json")
    exclude(group = "com.google.code.gson")
}

// The embedded implementation jar.
tasks.shadowJar {
    archiveBaseName = "friends-paper-impl"
    relocate("net.kyori", "com.friends.lib.kyori")
    relocate("org.slf4j", "com.friends.lib.slf4j")
    exclude("plugin.yml")
}

// The plugin jar: bootstrap + plugin.yml + config.yml + the implementation jar.
val pluginJar = tasks.register<Jar>("pluginJar") {
    archiveBaseName = "friends-paper"
    from(bootstrap.output)
    from(tasks.processResources) { include("plugin.yml") }
    from(project(":common").file("src/main/resources/config.yml"))
    from(tasks.shadowJar) { rename { "friends-paper-impl.jar" } }
    // No NMS/CraftBukkit use: tell Paper 1.20.5+ not to spend startup time remapping this jar.
    manifest { attributes("paperweight-mappings-namespace" to "mojang") }
}
tasks.assemble { dependsOn(pluginJar) }
