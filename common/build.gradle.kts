plugins {
    `java-library`
}

dependencies {
    implementation("com.zaxxer:HikariCP:5.0.1")
    implementation("org.slf4j:slf4j-api:2.0.7")
    implementation("mysql:mysql-connector-java:8.0.33")
    implementation("org.xerial:sqlite-jdbc:3.42.0.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
}
