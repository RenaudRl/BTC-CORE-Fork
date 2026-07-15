# BTC-CORE Repository

## Upload sur borntocraftstudio.net

Copier le contenu de `repo/` sur le serveur web :

```
borntocraftstudio.net/
├── repo/                           ← repo/dev/btc/core/...
│   └── dev/btc/core/api/
│       ├── maven-metadata.xml
│       └── 26.1.2.build.19-alpha/
│           ├── api-26.1.2.build.19-alpha.jar
│           ├── api-26.1.2.build.19-alpha-sources.jar
│           ├── api-26.1.2.build.19-alpha-javadoc.jar
│           └── api-26.1.2.build.19-alpha.pom
└── javadoc/                        ← repo/javadoc/*
    └── index.html
```

## Utilisation

### Gradle (Kotlin DSL)
```kotlin
repositories {
    maven("https://borntocraftstudio.net/public/repo/")
}
dependencies {
    compileOnly("dev.btc.core:api:26.1.2.build.19-alpha")
}
```

### Maven
```xml
<repository>
    <id>btcstudio</id>
    <url>https://borntocraftstudio.net/public/repo/</url>
</repository>
<dependency>
    <groupId>dev.btc.core</groupId>
    <artifactId>api</artifactId>
    <version>26.1.2.build.19-alpha</version>
    <scope>provided</scope>
</dependency>
```

### Javadoc
```
https://borntocraftstudio.net/javadoc/
```

## Deploiement serveur

1. Telecharger `btccore-paperclip-26.1.2-R0.1-SNAPSHOT.jar` (ou `aspaper-paperclip-26.1.2.build.19-alpha.jar`)
2. Placer dans le dossier serveur
3. Ajouter `asp-plugin-4.2.0-SNAPSHOT.jar` dans `plugins/`
4. Configurer `btccore.yml`, `purpur.yml`, `anticheat.yml`
5. Demarrer: `java -Xms4G -Xmx6G -XX:+UseZGC -jar btccore-paperclip-*.jar nogui`

## Build reproductible

```bash
git clone https://github.com/InfernalSuite/AdvancedSlimePaper.git
cd AdvancedSlimePaper && git checkout dev/26.1.1
# Copier les assets BTC-CORE
./gradlew applyAllPatches --offline
python scripts/apply-btccore-patches.py
./gradlew :aspaper-server:createPaperclipJar --offline
```
