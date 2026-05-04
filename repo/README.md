# BTC-CORE Maven Repository

## Upload sur borntocraftstudio.net/repo/

Structure a uploader :
```
repo/dev/btc/core/
├── api/26.1.2.build.19-alpha/
│   ├── api-26.1.2.build.19-alpha.jar
│   ├── api-26.1.2.build.19-alpha.pom
│   ├── api-26.1.2.build.19-alpha-sources.jar
│   └── api-26.1.2.build.19-alpha-javadoc.jar
├── btccore-server/26.1.2.build.19-alpha/
└── btccore-plugin/4.2.0-SNAPSHOT/
```

## Generer les artifacts

```bash
# Builder le serveur
./gradlew applyAllPatches --offline
python scripts/apply-btccore-patches.py
./gradlew :aspaper-server:createPaperclipJar --offline

# Publier l'API en local
./gradlew :api:publishToMavenLocal --offline

# Copier dans repo/
mkdir -p repo/dev/btc/core
cp -r ~/.m2/repository/dev/btc/core/* repo/dev/btc/core/
```

## Upload

Copier le dossier `repo/` sur le serveur web :
- Nginx : `scp -r repo/* user@server:/var/www/borntocraftstudio.net/repo/`
- Apache : `scp -r repo/* user@server:/var/www/html/repo/`

## Utilisation

### Gradle (Kotlin DSL)
```kotlin
repositories {
    maven("https://borntocraftstudio.net/repo/")
}
dependencies {
    compileOnly("dev.btc.core:api:26.1.2.build.19-alpha")
}
```

### Maven
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

### Deploiement serveur
1. Telecharger `btccore-paperclip-26.1.2-R0.1-SNAPSHOT.jar`
2. Placer dans le dossier serveur
3. Ajouter plugins (LuckPerms, PlaceholderAPI, Typewriter...)
4. Configurer btccore.yml, purpur.yml, anticheat.yml
5. Demarrer: `java -Xms4G -Xmx6G -XX:+UseZGC -jar btccore-paperclip-*.jar nogui`

## Version
- **Serveur**: 26.1.2.build.19-alpha
- **API**: 26.1.2
- **Java**: 25
- **Base**: AdvancedSlimePaper dev/26.1.1
