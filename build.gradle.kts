import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.gradle.kotlin.dsl.register

plugins {
    java
    id("io.papermc.paperweight.patcher")
}

paperweight {
    upstreams.paper {
        ref = providers.gradleProperty("paperRef")

        patchDir("paperApi") {
            upstreamPath = "paper-api"
            excludes = setOf("build.gradle.kts")
            patchesDir = file("aspaper-api/paper-patches")
            outputDir = file("paper-api")
        }
    }
}

// BTC Core patches — applied after Paperweight patches
// These handle build.gradle.kts modifications that ASP patches no longer support
tasks.register("applyBTCCorePatches") {
    group = "btccore"
    description = "Applies BTC Core build patches after Paperweight"

    doLast {
        // Patch aspaper-server/build.gradle.kts
        val serverBuild = file("aspaper-server/build.gradle.kts")
        if (serverBuild.exists()) {
            var content = serverBuild.readText()
            // Replace paper-api dependency with aspaper-api
            if (content.contains("implementation(project(\":paper-api\"))")) {
                content = content.replace("implementation(project(\":paper-api\"))",
                    "implementation(project(\":aspaper-api\")) //BTC")
                serverBuild.writeText(content)
                logger.lifecycle("[BTCCore] Patched aspaper-server/build.gradle.kts dependencies")
            }
            // Add aspaper fork config if missing
            if (!content.contains("val aspaper = forks.register")) {
                val oldBlock = """    updatingMinecraft {
        // oldPaperCommit = "d4fe85375af18bfa88f44d7c1e6a61904ae550cc"
    }"""
                val newBlock = """    val aspaper = forks.register("aspaper") {
        upstream.patchDir("paperServer") {
            upstreamPath = "paper-server"
            excludes = setOf("src/minecraft", "patches", "build.gradle.kts")
            patchesDir = rootDirectory.dir("aspaper-server/paper-patches")
            outputDir = rootDirectory.dir("paper-server")
        }
    }

    activeFork = aspaper

//    updatingMinecraft {
//        oldPaperCommit = "d4fe85375af18bfa88f44d7c1e6a61904ae550cc"
//    }"""
                if (content.contains(oldBlock)) {
                    content = content.replace(oldBlock, newBlock)
                    serverBuild.writeText(content)
                    logger.lifecycle("[BTCCore] Patched aspaper-server/build.gradle.kts paperweight config")
                }
            }
        }

        // Patch aspaper-api/build.gradle.kts
        val apiBuild = file("aspaper-api/build.gradle.kts")
        if (apiBuild.exists()) {
            var content = apiBuild.readText()
            if (!content.contains("api(project(\":api\")) //BTC")) {
                content = content.replace("dependencies {\n    // api dependencies are listed transitively to API consumers",
                    "dependencies {\n    api(project(\":api\")) //BTC\n    // api dependencies are listed transitively to API consumers")
                apiBuild.writeText(content)
                logger.lifecycle("[BTCCore] Patched aspaper-api/build.gradle.kts")
            }
        }
    }
}

// Make patch tasks depend on our custom task (Paperweight v2 uses 'applyPatches')
afterEvaluate {
    tasks.findByName("applyAllPatches")?.dependsOn("applyBTCCorePatches")
    tasks.findByName("applyPatches")?.dependsOn("applyBTCCorePatches")
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(JAVA_VERSION)
        }
    }

    repositories {
        mavenCentral()
        maven(PAPER_MAVEN_PUBLIC_URL)
    }

    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
    tasks.withType<JavaCompile> {
        options.encoding = Charsets.UTF_8.name()
        options.release = JAVA_VERSION
        options.isFork = true
    }
    tasks.withType<Javadoc> {
        options.encoding = Charsets.UTF_8.name()
    }
    tasks.withType<ProcessResources> {
        filteringCharset = Charsets.UTF_8.name()
    }
    tasks.withType<Test> {
        testLogging {
            showStackTraces = true
            exceptionFormat = TestExceptionFormat.FULL
            events(TestLogEvent.STANDARD_OUT)
        }
    }
}

tasks.register<DefaultTask>("createMojmapPaperclipJar") {
    description = "Fallback to the old mojmap naming for our CI... Yes this was the easiest fix I could come up with."
    dependsOn(":aspaper-server:createPaperclipJar")
}