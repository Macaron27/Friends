import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

plugins {
    `java`
    id("com.gradleup.shadow") version "9.6.1" apply false
}

allprojects {
    group = "com.friends"
    version = "0.5.0"

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

subprojects {
    apply(plugin = "java")

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }

    // Plugin jars (velocity, bungee, paper): what every one of them shades the same way.
    pluginManager.withPlugin("com.gradleup.shadow") {
        configurations.named("runtimeClasspath") {
            exclude(group = "com.google.protobuf")    // only used by MySQL's X DevAPI
            exclude(group = "org.jspecify")           // annotations only
            exclude(group = "com.google.errorprone")  // annotations only
        }
        tasks.named<ShadowJar>("shadowJar") {
            archiveBaseName = "friends-${project.name}"
            archiveClassifier = ""
            // sqlite-jdbc is never relocated: its JNI symbols are bound to the org.sqlite package name.
            relocate("com.zaxxer.hikari", "com.friends.lib.hikari")
            relocate("com.mysql", "com.friends.lib.mysql")
            relocate("redis.clients", "com.friends.lib.jedis")
            relocate("org.apache.commons.pool2", "com.friends.lib.pool2")
            relocate("org.json", "com.friends.lib.json")
            // No Java 25 runtime exists for 32-bit x86 (JEP 503/479) or 32-bit ARM Windows: drop those natives.
            exclude("org/sqlite/native/*/x86/**", "org/sqlite/native/Windows/armv7/**")
            mergeServiceFiles()
            filesMatching("META-INF/services/**") { duplicatesStrategy = DuplicatesStrategy.INCLUDE } // both JDBC drivers
        }
        tasks.named("assemble") { dependsOn("shadowJar") }
        tasks.named("jar") { enabled = false } // the thin jar isn't a plugin: only the shaded one ships
        val pluginVersion = project.version.toString() // read now: Task.project at execution time is deprecated
        tasks.named<ProcessResources>("processResources") {
            inputs.property("version", pluginVersion)
            filesMatching(listOf("plugin.yml", "bungee.yml")) { expand("version" to pluginVersion) }
        }
    }
}

// The jars to ship, one per platform plus the API: builds/friends-<name>.jar. Synced, so stale or duplicated files go.
evaluationDependsOnChildren()
val builds = tasks.register<Sync>("builds") {
    description = "Collects the plugin and API jars into builds/"
    group = "build"
    into(layout.projectDirectory.dir("builds"))
    mapOf(":api" to "jar", ":paper" to "pluginJar", ":bungee" to "shadowJar", ":velocity" to "shadowJar").forEach { (module, jar) ->
        from(project(module).tasks.named(jar)) { rename { "friends-${module.removePrefix(":")}.jar" } }
    }
}
tasks.assemble { dependsOn(builds) }

// What a build-script slip (or a file-sync tool writing into build/) would silently break in the shipped jars.
val verifyBuilds = tasks.register("verifyBuilds") {
    description = "Checks the jars in builds/"
    group = "verification"
    dependsOn(builds)
    val dir = layout.projectDirectory.dir("builds").asFile
    val expected = version.toString()
    doLast {
        fun read(name: String) = ZipFile(dir.resolve(name)).use { z ->
            z.entries().asSequence().associate { it.name to z.getInputStream(it).readBytes() }
        }
        val api = read("friends-api.jar")
        val paper = read("friends-paper.jar")
        val bungee = read("friends-bungee.jar")
        val velocity = read("friends-velocity.jar")
        val paperImpl = ZipInputStream(paper.getValue("friends-paper-impl.jar").inputStream()).use { zin ->
            generateSequence { zin.nextEntry }.associate { it.name to zin.readBytes() }
        }
        val jars = mapOf("api" to api, "paper" to paper, "paper-impl" to paperImpl, "bungee" to bungee, "velocity" to velocity)
        val problems = mutableListOf<String>()
        fun expect(ok: Boolean, problem: String) { if (!ok) problems += problem }
        fun Map<String, ByteArray>.text(entry: String) = get(entry)?.decodeToString() ?: ""

        expect(dir.list()!!.sorted() == jars.keys.filter { it != "paper-impl" }.map { "friends-$it.jar" }.sorted(), "builds/ holds other files: ${dir.list()!!.sorted()}")
        jars.forEach { (name, e) -> expect(e.keys.none { ' ' in it }, "$name: file-sync copies inside (e.g. \"Foo 2.class\"): ${e.keys.filter { ' ' in it }.take(3)}") }
        expect(paper.text("plugin.yml").contains("\nversion: $expected\n"), "paper: plugin.yml is not version $expected")
        expect(paper.text("plugin.yml").contains("\nfolia-supported: true\n"), "paper: plugin.yml doesn't declare Folia support")
        expect(bungee.text("bungee.yml").contains("\nversion: $expected\n"), "bungee: bungee.yml is not version $expected")
        expect(velocity.text("velocity-plugin.json").contains("\"version\":\"$expected\""), "velocity: velocity-plugin.json is not version $expected")
        listOf(paper, bungee, velocity).forEach { expect("config.yml" in it, "a plugin jar has no config.yml") }
        expect("com/friends/paper/FriendsBootstrap.class" in paper && "com/friends/paper/PaperFriends.class" in paperImpl, "paper: bootstrap or implementation missing")
        // Each platform ships only its own events.
        mapOf(paper to listOf("velocity", "bungee"), bungee to listOf("bukkit", "velocity"), velocity to listOf("bukkit", "bungee")).forEach { (jar, others) ->
            others.forEach { other -> expect(jar.keys.none { it.startsWith("com/friends/api/$other/") }, "a jar contains com.friends.api.$other events") }
        }
        // Bundled libraries are relocated (unrelocated copies clash with other plugins); Adventure/SLF4J never ship as is.
        val unrelocated = listOf("com/zaxxer/", "com/mysql/", "redis/clients/", "org/apache/commons/pool2/", "org/json/", "net/kyori/", "org/slf4j/", "com/google/gson/")
        listOf("paper-impl", "bungee", "velocity").forEach { name ->
            val leaked = jars.getValue(name).keys.filter { e -> unrelocated.any { e.startsWith(it) } }
            expect(leaked.isEmpty(), "$name: unrelocated libraries: ${leaked.take(3)}")
        }
        expect(paperImpl.keys.none { it.startsWith("org/sqlite/") }, "paper: bundles sqlite-jdbc (every Paper has its own)")
        // Java 8 bytecode where old servers' class rewriters (and plugins on any Java) read it.
        fun major(b: ByteArray) = ((b[6].toInt() and 0xff) shl 8) or (b[7].toInt() and 0xff)
        listOf("api" to api, "paper" to paper).forEach { (name, jar) ->
            val newer = jar.filter { (e, b) -> e.endsWith(".class") && major(b) > 52 }.keys
            expect(newer.isEmpty(), "$name: classes newer than Java 8: ${newer.take(3)}")
        }
        if (problems.isNotEmpty()) throw GradleException("builds/ is wrong:\n- " + problems.joinToString("\n- "))
        logger.lifecycle("builds/: ${dir.list()!!.sorted().joinToString()} verified (version $expected)")
    }
}
tasks.check { dependsOn(verifyBuilds) }
