version = "${rootProject.providers.gradleProperty("apiVersion").get()}-${gitCommit().orNull ?: "SNAPSHOT"}"
