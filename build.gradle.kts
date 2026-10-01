import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

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
        val pluginVersion = project.version.toString() // read now: Task.project at execution time is deprecated
        tasks.named<ProcessResources>("processResources") {
            inputs.property("version", pluginVersion)
            filesMatching(listOf("plugin.yml", "bungee.yml")) { expand("version" to pluginVersion) }
        }
    }
}
