plugins {
    id("asp.base-conventions")
    id("asp.publishing-conventions")
}

dependencies {
    compileOnly(project(":api"))

    api(libs.hikari)
    compileOnly(paperApi())

    // PostgreSQL driver for PostgresLoader
    compileOnly("org.postgresql:postgresql:42.7.3")

    testImplementation(project(":api"))
    testRuntimeOnly("org.postgresql:postgresql:42.7.3")
}

publishConfiguration {
    name = "Advanced Slime Paper MySQL Loader"
    description = "MySQL loader for Advanced Slime Paper"
}

tasks {
    // Integration checks of the world lease (AD-096) against a real PostgreSQL. Deliberately NOT
    // wired into `check`: it needs a throwaway database, given by BTC_SLIME_PG_TEST_URL / _USER /
    // _PASSWORD, and fails when they are missing rather than skipping in silence.
    val postgresLockCheck by registering(JavaExec::class) {
        description = "Runs the PostgreSQL world lease integration checks (needs BTC_SLIME_PG_TEST_*)"
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        classpath = sourceSets["test"].runtimeClasspath
        mainClass.set("com.infernalsuite.asp.loaders.mysql.PostgresLoaderLockCheck")
        jvmArgs("-ea")
    }

    test {
        enabled = false
    }
}
