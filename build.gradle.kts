plugins {
    application
}

group = "dev.kamil"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

val javafxVersion = "21.0.11"
val javafxPlatform = "linux"

dependencies {
    implementation("com.techsenger.jeditermfx:jeditermfx-ui:1.1.0")
    implementation("org.jetbrains.pty4j:pty4j:0.14.0")
    for (module in listOf("base", "graphics", "controls", "media")) {
        implementation("org.openjfx:javafx-$module:$javafxVersion:$javafxPlatform")
    }
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.3")
    implementation("com.h2database:h2:2.5.252")
    implementation("org.liquibase:liquibase-core:5.0.4")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.16")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

application {
    // Plain main class: JavaFX runs from the classpath, no module setup needed.
    mainClass = "dev.kamil.fabric.Launcher"
    applicationDefaultJvmArgs = listOf(
        "-Dprism.lcdtext=false",
        "-Xmx1g",
        "-XX:+UseG1GC",
        "-XX:G1PeriodicGCInterval=30000",
        "-XX:MinHeapFreeRatio=10",
        "-XX:MaxHeapFreeRatio=30",
        "-XX:+UseStringDeduplication",
        "-Xss512k"
    )
}

tasks.named<JavaExec>("run") {
    // ./gradlew run --args="/path/to/project"
    workingDir = projectDir
}
