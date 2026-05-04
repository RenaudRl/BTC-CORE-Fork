# BTC-CORE Maven Repository

## Structure
```
repo/
├── dev/btc/core/api/26.1.2.build.19-alpha/   # API jar + pom
├── dev/btc/core/btccore-server/26.1.2.build.19-alpha/  # Server jar
└── dev/btc/core/btccore-plugin/4.2.0-SNAPSHOT/  # Plugin jar
```

## Deploy
1. Copier ce dossier sur `borntocraftstudio.net/repo/`
2. Les jars sont servis en statique par Nginx/Apache
3. Config Maven client :
```xml
<repository>
    <id>btcstudio</id>
    <url>https://borntocraftstudio.net/repo/</url>
</repository>
```

## Publier une nouvelle version
```bash
./gradlew :api:publish -Ppublish=true
# avec btcRepoUser / btcRepoPassword dans ~/.gradle/gradle.properties
```
