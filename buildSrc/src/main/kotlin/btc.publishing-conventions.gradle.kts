import org.kordamp.gradle.plugin.profiles.ProfilesExtension

plugins {
    `maven-publish`
    signing
    id("org.kordamp.gradle.profiles")
}

extensions.configure<ProfilesExtension>("profiles") {
    profile("publish") {
        activation {
            property {
                setKey("publish")
                setValue("true")
            }
        }
        action {
            extensions.configure<PublishingExtension>("publishing") {
                publications {
                    create<MavenPublication>("maven") {
                        groupId = "${project.group}"
                        artifactId = project.name
                        version = "${project.version}"

                        from(components["java"])

                        pom {
                            name.set("BTC Core")
                            description.set("BTC Core - Fork Paper 26.1.2 optimise pour BornToCraft")
                            url.set("https://borntocraftstudio.net")
                            licenses {
                                license {
                                    name.set("GNU General Public License, Version 3.0")
                                    url.set("https://www.gnu.org/licenses/gpl-3.0.txt")
                                }
                            }
                            developers {
                                developer {
                                    id.set("BTC-Studio")
                                    name.set("BTC Studio Team")
                                    url.set("https://borntocraftstudio.net")
                                }
                            }
                            scm {
                                connection.set("scm:git:https://github.com/RenaudRl/BTC-CORE.git")
                                url.set("https://github.com/RenaudRl/BTC-CORE")
                            }
                        }
                    }
                }
                repositories {
                    maven {
                        name = "btcstudio"
                        url = if("${project.version}".endsWith("-SNAPSHOT")) {
                            uri("https://borntocraftstudio.net/public/repo/snapshots/")
                        } else {
                            uri("https://borntocraftstudio.net/public/repo/releases/")
                        }
                        credentials {
                            username = project.findProperty("btcRepoUser") as String? ?: ""
                            password = project.findProperty("btcRepoPassword") as String? ?: ""
                        }
                    }
                }
            }
        }
    }
}
