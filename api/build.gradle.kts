plugins {
    id("asp.base-conventions")
    id("asp.publishing-conventions")
}

dependencies {
    api(libs.annotations)
    api(libs.adventure.nbt)

    compileOnly(paperApi())
}

publishConfiguration {
    name = "Advanced Slime Paper API"
    description = "API for Advanced Slime Paper"
}

// ---------------------------------------------------------------------------
// dev.btc.core:api — the public BTC-CORE API, served from the BTC Studio static
// Maven repository at https://borntocraftstudio.net/repo/ .
//
// The same classes as the ASP publication above, under our own coordinate and our
// own version line: this fork's releases do not follow ASP's cadence, so the two
// version numbers are deliberately independent.
//
// This exists because the artifact used to be copied into BTCVelocity/repo by
// hand. That is how 26.2.build.5-alpha ended up mutated in place — same
// coordinate, different jar — which no consumer can detect: a cached copy never
// updates, a fresh one gets something else, and both fail silently. Publishing
// through Gradle makes a new version the only way to ship new content.
//
// Do NOT restore buildSrc/btc.publishing-conventions.gradle.kts: it was applied by
// no module at all, and its URL (borntocraftstudio.net/public/repo/) 404s — nginx
// serves the site from .../public, so the public path is /repo/, not /public/repo/.
// ---------------------------------------------------------------------------
publishing {
    publications {
        create<MavenPublication>("btcApi") {
            groupId = "dev.btc.core"
            artifactId = "api"
            version = providers.gradleProperty("btcApiVersion").get()

            from(components["java"])

            pom {
                name.set("BTC-CORE API")
                description.set(
                    "API for BTC-CORE, a high-performance Minecraft server fork based on " +
                        "AdvancedSlimePaper 26.2 (Paper + SlimeWorld)"
                )
                url.set("https://borntocraftstudio.net")
                licenses {
                    license {
                        name.set("GNU General Public License, Version 3.0")
                        url.set("https://www.gnu.org/licenses/gpl-3.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("btcstudio")
                        name.set("BTC Studio")
                        url.set("https://borntocraftstudio.net")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/RenaudRl/BTC-CORE-Fork.git")
                    url.set("https://github.com/RenaudRl/BTC-CORE-Fork")
                }
                // paper-api is compileOnly here, so it is absent from components["java"].
                // A consumer still needs it on its own compile classpath, hence `provided`.
                withXml {
                    val deps = asNode().children()
                        .filterIsInstance<groovy.util.Node>()
                        .firstOrNull { it.name().toString().substringAfterLast('}') == "dependencies" }
                        ?: asNode().appendNode("dependencies")
                    deps.appendNode("dependency").apply {
                        appendNode("groupId", "io.papermc.paper")
                        appendNode("artifactId", "paper-api")
                        appendNode("version", providers.gradleProperty("paperApiVersion").get())
                        appendNode("scope", "provided")
                    }
                }
            }
        }
    }

    repositories {
        // Same directory the rest of the fork resolves from (see asp.base-conventions).
        // Override with -PbtcRepoDir when BTCVelocity is not checked out next to this fork.
        maven {
            name = "btcRepo"
            url = uri(
                providers.gradleProperty("btcRepoDir")
                    .getOrElse(rootProject.file("../BTCVelocity/repo").absolutePath)
            )
        }
    }
}
