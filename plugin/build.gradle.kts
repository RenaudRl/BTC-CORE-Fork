plugins {
    id("asp.base-conventions")
    id("asp.publishing-conventions")
    id("net.minecrell.plugin-yml.paper")
    id("com.gradleup.shadow")
}

dependencies {
    compileOnly(project(":api"))
    compileOnly(project(":aspaper-server"))
    implementation(project(":loaders"))

    implementation(libs.configurate.yaml)
    implementation(libs.bstats)
    implementation(libs.cloud.paper)
    implementation(libs.cloud.minecraft.extras)
    implementation(libs.cloud.annotations)

    compileOnly(paperApi())
    // Bridge codec. Paper ships Gson on the server classpath, so it is never bundled here — but the
    // codec checks run outside a server and need a real Gson to run against.
    compileOnly("com.google.code.gson:gson:2.14.0")
    testRuntimeOnly("com.google.code.gson:gson:2.14.0")
    // NMS ServerLevel/ServerPlayer signatures reference DataFixerUpper (com.mojang.datafixers.util.Pair) ;
    // requis sur le classpath de compilation du plugin car compileOnly(project(":aspaper-server")) n'est pas transitif.
    compileOnly("com.mojang:datafixerupper:10.0.21")
    // Soft dependency: BTCCoreExpansion is only registered at runtime when MiniPlaceholders is installed.
    compileOnly("io.github.miniplaceholders:miniplaceholders-api:3.2.0-26.2-BTC-1")
}

tasks {
    withType<Jar> {
        archiveBaseName.set("btccore-plugin")
    }

    // The bridge codec checks are deliberately framework-free: they exercise wire encoding, which
    // must keep working with nothing but the JDK on the classpath. `test` cannot run them — Gradle's
    // test task discovers nothing without a test engine and fails — so they run as a plain JVM with
    // assertions on, and are wired into `check` so they actually execute.
    val bridgeCodecCheck by registering(JavaExec::class) {
        description = "Runs the dependency-free bridge codec checks"
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        classpath = sourceSets["test"].runtimeClasspath
        mainClass.set("dev.btc.core.bridge.BridgeCodecTest")
        jvmArgs("-ea")
    }

    test {
        // Nothing here uses a test engine; the real verification is bridgeCodecCheck.
        enabled = false
    }

    check {
        dependsOn(bridgeCodecCheck)
    }

    shadowJar {
        archiveClassifier.set("")
        // Both `jar` and `shadowJar` output the same file name (classifier ""), so their
        // relative order decides the final artifact. Force the fat/relocated shadowJar to run
        // last, otherwise a later `jar` run overwrites it with a dependency-less thin jar
        // (causes NoClassDefFoundError: com/mongodb/... at runtime).
        mustRunAfter(jar)

        relocate("org.bstats", "com.infernalsuite.asp.libs.bstats")
        relocate("org.spongepowered.configurate", "com.infernalsuite.asp.libs.configurate")
        relocate("com.zaxxer.hikari", "com.infernalsuite.asp.libs.hikari")
        relocate("com.mongodb", "com.infernalsuite.asp.libs.mongo")
        relocate("io.lettuce", "com.infernalsuite.asp.libs.lettuce")
        relocate("org.bson", "com.infernalsuite.asp.libs.bson")
    }

    assemble {
        dependsOn(shadowJar)
    }
}

paper {
    // Renamed from ASPaperPlugin when the BTCBridge plugin was folded in: one runtime plugin, one
    // lifecycle. Anything looking the plugin up by name must ask for BTCCore.
    name = "BTCCore"
    // Keep this short and free of colons. `processResources` runs `expand()` over paper-plugin.yml,
    // and plugin-yml wraps a long value with YAML's backslash continuation — which Groovy's template
    // engine then eats, silently collapsing the following lines. A description long enough to wrap
    // cost us `main` and `bootstrapper` and made the plugin fail to load with no obvious cause.
    description = "BTC Core runtime plugin for Paper"
    version = "\${gitCommitId}"
    apiVersion = "1.21"
    main = "com.infernalsuite.asp.plugin.SWPlugin"
    authors = listOf("InfernalSuite")
    bootstrapper = "com.infernalsuite.asp.plugin.SlimePluginBootstrap"

    // Soft dependency: load MiniPlaceholders before us so the <btccore_*> expansion registers cleanly.
    serverDependencies {
        register("MiniPlaceholders") {
            required = false
            load = net.minecrell.pluginyml.paper.PaperPluginDescription.RelativeLoadOrder.BEFORE
        }
    }
}
