plugins {
    `java-library`
}

group = "com.example.friends"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}
