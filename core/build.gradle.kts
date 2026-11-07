plugins {
    id("asp.base-conventions")
    id("asp.internal-conventions")
    id("asp.publishing-conventions")
}

dependencies {
    compileOnly(project(":api"))
    compileOnly(paperApi())
    implementation(libs.zstd)

    // The lease keeper checks run as a plain JVM, outside a server: they need what the server
    // provides at runtime (the API, slf4j through paper-api).
    testImplementation(project(":api"))
    testImplementation(paperApi())
}

publishConfiguration {
    name = "Advanced Slime Paper Core"
    description = "Core logic for Advanced Slime Paper"
}

tasks {
    // Same idiom as the plugin's bridge checks: dependency-free `main` + assertions, run as a JVM
    // and wired into `check`, because `test` discovers nothing without a test engine.
    val worldLeaseKeeperCheck by registering(JavaExec::class) {
        description = "Runs the dependency-free world lease keeper checks (AD-096)"
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        classpath = sourceSets["test"].runtimeClasspath
        mainClass.set("com.infernalsuite.asp.lock.WorldLeaseKeeperCheck")
        jvmArgs("-ea")
    }

    test {
        enabled = false
    }

    check {
        dependsOn(worldLeaseKeeperCheck)
    }
}
