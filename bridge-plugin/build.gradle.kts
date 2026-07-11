plugins {
    java
}

dependencies {
    compileOnly(project(":api"))
    compileOnly(paperApi())
    compileOnly("com.google.code.gson:gson:2.14.0")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}
