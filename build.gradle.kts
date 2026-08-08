

plugins {
    id("java")
    kotlin("jvm") version "1.9.25"
    id("org.jetbrains.intellij.platform") version "2.16.0"
    id("org.jetbrains.changelog") version "2.4.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        goland("2024.3")
    }

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    // Required so Gradle can launch JUnit Platform under IntelliJ Platform test runtime
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

intellijPlatform {
    pluginConfiguration {
        id = providers.gradleProperty("pluginId").get()
        name = providers.gradleProperty("pluginName").get()
        version = providers.gradleProperty("pluginVersion").get()

        vendor {
            name = "Waryway"
            url = "https://waryway.com"
        }
    }
}

tasks {
    wrapper {
        gradleVersion = "9.0"
    }

    processResources {
        from("mcps/goland1/tools") {
            into("goland-mcp/tools")
        }
    }

    patchPluginXml {
        sinceBuild = "243"
        untilBuild = provider { null }
    }

    signPlugin {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishPlugin {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }

    test {
        useJUnitPlatform()
    }

    buildSearchableOptions {
        enabled = false
    }

    /**
     * `runIde` starts GoLand via JavaExec + sandbox classpath (plugin loaded from
     * `build/.../sandbox`), not `bin\goland.bat` / `bin\goland64.exe`.
     *
     * Without this flag, recent IDEs show:
     *   "IDE seems to be launched with a script launcher … switch to … goland64.exe"
     * That tip is for normal desktop shortcuts — not applicable to Gradle plugin sandbox runs.
     * IntelliJ Platform Gradle Plugin sets this too; we set it explicitly so it always sticks.
     */
    runIde {
        systemProperty("ide.native.launcher", "false")
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-receivers")
    }
}