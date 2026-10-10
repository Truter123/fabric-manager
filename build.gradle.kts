plugins {
    application
}

group = "dev.kamil"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

val javafxVersion = "21.0.5"
val javafxPlatform = "linux"

dependencies {
    implementation("com.techsenger.jeditermfx:jeditermfx-ui:1.1.0")
    implementation("org.jetbrains.pty4j:pty4j:0.14.0")
    for (module in listOf("base", "graphics", "controls")) {
        implementation("org.openjfx:javafx-$module:$javafxVersion:$javafxPlatform")
    }
    runtimeOnly("org.slf4j:slf4j-simple:2.0.16")
}

application {
    // Plain main class: JavaFX runs from the classpath, no module setup needed.
    mainClass = "dev.kamil.cterm.Launcher"
    applicationDefaultJvmArgs = listOf("-Dprism.lcdtext=false")
}

tasks.named<JavaExec>("run") {
    // ./gradlew run --args="/path/to/project"
    workingDir = projectDir
}
