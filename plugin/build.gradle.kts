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
    // NMS ServerLevel/ServerPlayer signatures reference DataFixerUpper (com.mojang.datafixers.util.Pair) ;
    // requis sur le classpath de compilation du plugin car compileOnly(project(":aspaper-server")) n'est pas transitif.
    compileOnly("com.mojang:datafixerupper:10.0.21")
    // Soft dependency: BTCCoreExpansion is only registered at runtime when MiniPlaceholders is installed.
    compileOnly("io.github.miniplaceholders:miniplaceholders-api:3.2.0-26.2-BTC-1")
}

tasks {
    withType<Jar> {
        archiveBaseName.set("asp-plugin")
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
    name = "ASPaperPlugin"
    description = "ASP plugin for Paper, providing utilities for the ASP platform"
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
