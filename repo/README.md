# BTC-CORE Maven Repository

## Structure a uploader sur borntocraftstudio.net/repo/

```
repo/
└── dev/btc/core/
    ├── api/26.1.2.build.19-alpha/
    │   ├── api-26.1.2.build.19-alpha.jar
    │   ├── api-26.1.2.build.19-alpha.pom
    │   ├── api-26.1.2.build.19-alpha-sources.jar
    │   └── api-26.1.2.build.19-alpha-javadoc.jar
    ├── btccore-server/26.1.2.build.19-alpha/
    └── btccore-plugin/4.2.0-SNAPSHOT/
```

## Publier une release

```bash
# 1. Builder
./gradlew applyAllPatches --offline
python scripts/apply-btccore-patches.py
./gradlew :aspaper-server:createPaperclipJar --offline

# 2. Publier l'API sur Maven local
./gradlew :api:publishToMavenLocal --offline

# 3. Copier les artifacts
cp -r ~/.m2/repository/dev/btc/core/ repo/dev/btc/core/

# 4. Uploader repo/ sur borntocraftstudio.net/repo/
# (Nginx/Apache: servir les fichiers statiques)
```

## Configuration Maven client

### Gradle (Kotlin DSL)
```kotlin
repositories {
    maven("https://borntocraftstudio.net/repo/")
}
dependencies {
    compileOnly("dev.btc.core:api:26.1.2.build.19-alpha")
}
```

### Maven (XML)
```xml
<repository>
    <id>btcstudio</id>
    <url>https://borntocraftstudio.net/repo/</url>
</repository>
<dependency>
    <groupId>dev.btc.core</groupId>
    <artifactId>api</artifactId>
    <version>26.1.2.build.19-alpha</version>
    <scope>provided</scope>
</dependency>
```

### Plugin Paper
```yaml
# paper-plugin.yml
dependencies:
  - name: BTCCore
    required: true
```

## Server Deploy

1. Telecharger le jar paperclip: `aspaper-server/build/libs/btccore-paperclip-26.1.2-R0.1-SNAPSHOT.jar`
2. Placer dans le dossier du serveur
3. Ajouter les plugins (LuckPerms, PlaceholderAPI, Typewriter, etc.)
4. Configurer btccore.yml, purpur.yml, anticheat.yml
5. Demarrer: `java -Xms4G -Xmx6G -XX:+UseZGC -jar btccore-paperclip-26.1.2-R0.1-SNAPSHOT.jar nogui`

## Version actuelle
- **Version**: 26.1.2.build.19-alpha
- **API Version**: 26.1.2
- **Java**: 25
- **Base**: AdvancedSlimePaper dev/26.1.1 (Paper 26.1.2 + Folia + SlimeWorld)
