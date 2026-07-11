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
}

publishConfiguration {
    name = "Advanced Slime Paper MySQL Loader"
    description = "MySQL loader for Advanced Slime Paper"
}
