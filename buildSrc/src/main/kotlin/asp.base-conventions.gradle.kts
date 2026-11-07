plugins {
    `java-library`
}

group = rootProject.providers.gradleProperty("group").get()
version = rootProject.providers.gradleProperty("aspApiVersion").get()

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(JAVA_VERSION))
    }
    withJavadocJar()
    withSourcesJar()
}

repositories {
    mavenLocal()
    mavenCentral()

    maven(PAPER_MAVEN_PUBLIC_URL)

    maven("https://repo.codemc.io/repository/nms/")
    maven("https://repo.glaremasters.me/repository/concuncan/")
    maven("https://s01.oss.sonatype.org/content/repositories/snapshots/")
    maven("https://oss.sonatype.org/content/repositories/snapshots/")

    // BTC Studio unified static Maven repo: committed under BTCVelocity/repo and uploaded as-is
    // to https://borntocraftstudio.net/repo/ . Serves miniplaceholders-api (soft dep).
    // Overridable via -PbtcRepoDir so this fork still builds when BTCVelocity is not checked out
    // next to it. Replaces repo.extendedclip.com/content/repositories/placeholderapi/, which is
    // a dead URL and served the now-removed PlaceholderAPI dependency.
    //
    // exclusiveContent, not a plain maven{}: the remote repos above are flaky (rapture.pw returns
    // 502 intermittently) and a single failed GET aborts resolution before this one is reached.
    // Pinning the group here means the coordinate is only ever looked up locally.
    exclusiveContent {
        forRepository {
            maven {
                name = "btcRepo"
                url = uri(
                    providers.gradleProperty("btcRepoDir")
                        .getOrElse(rootProject.file("../BTCVelocity/repo").absolutePath)
                )
            }
        }
        filter {
            includeGroup("io.github.miniplaceholders")
        }
    }
}

dependencies {
//    api(platform(project(":gradle:platform")))
}

tasks {
    compileJava {
        options.encoding = Charsets.UTF_8.name()
        options.release.set(JAVA_VERSION)
    }

    javadoc {
        options.encoding = Charsets.UTF_8.name()
        (options as StandardJavadocDocletOptions)
            .tags("apiNote:a:API Note", "implSpec:a:Implementation Requirements", "implNote:a:Implementation Note")
    }

    processResources {
        filteringCharset = Charsets.UTF_8.name()
        // Declared input: expand() values are not part of the task's cache key, so without this a
        // build-cache hit from an older commit ships that commit's id in the jar.
        val gitCommitId = gitCommit().orElse("unknown")
        inputs.property("gitCommitId", gitCommitId)
        filesMatching(listOf("paper-plugin.yml", "version.txt")) {
            expand("gitCommitId" to gitCommitId.get())
        }
    }
}
