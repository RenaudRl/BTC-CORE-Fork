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
    // Soft dependency: Floodgate resolves a session's client platform. Absent in production, so the
    // bridge states nothing rather than guessing. Snapshot pinned to its timestamp — Floodgate
    // publishes nothing else — and without transitives: a single third-party jar at compile time.
    compileOnly("org.geysermc.floodgate:api:2.2.5-20260809.110940-20") { isTransitive = false }
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

    // Pins the frame signature to the test vector BTCVelocity pins too: a drift between the proxy's
    // copy and this one must fail a build, not refuse every bridge message on a live server.
    val bridgeFrameCheck by registering(JavaExec::class) {
        description = "Runs the dependency-free bridge frame signature checks"
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        classpath = sourceSets["test"].runtimeClasspath
        mainClass.set("dev.btc.core.bridge.BridgeFrameCheck")
        jvmArgs("-ea")
    }

    // Third of the same family, for the payload rather than the envelope: two codecs that diverge
    // on the shape of a message produce a pair that compiles, starts, and then refuses everything
    // on a live server. The literals are pinned identically in BTCVelocity's BridgeCodecTest.
    val bridgeCodecPlatformCheck by registering(JavaExec::class) {
        description = "Runs the dependency-free origin platform codec checks"
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        classpath = sourceSets["test"].runtimeClasspath
        mainClass.set("dev.btc.core.bridge.BridgeCodecPlatformCheck")
        jvmArgs("-ea")
    }

    // Same shape, same reason: the graceful-shutdown decision must be verifiable with nothing but
    // the JDK, because what it decides — whether it is safe to end a server carrying players — is
    // exactly what nobody wants to discover by trying it on production.
    val gracefulShutdownCheck by registering(JavaExec::class) {
        description = "Runs the dependency-free graceful shutdown checks"
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        classpath = sourceSets["test"].runtimeClasspath
        mainClass.set("dev.btc.core.bridge.GracefulShutdownCheck")
        jvmArgs("-ea")
    }

    check {
        dependsOn(bridgeCodecCheck)
        dependsOn(bridgeCodecPlatformCheck)
        dependsOn(gracefulShutdownCheck)
        dependsOn(bridgeFrameCheck)
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
