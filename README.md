# BTC-CORE

![Version](https://img.shields.io/badge/version-26.1.2-blue)
![Java](https://img.shields.io/badge/java-25-orange)
![Base](https://img.shields.io/badge/base-AdvancedSlimePaper%2026.1.2-purple)

Fork serveur Minecraft optimise pour BornToCraft Studio, base sur AdvancedSlimePaper 26.1.2 (Paper + SlimeWorld + Folia/Moonrise).

## Build

```bash
git clone https://github.com/InfernalSuite/AdvancedSlimePaper.git
cd AdvancedSlimePaper && git checkout dev/26.1.1
# Copier les assets BTC-CORE (modules, scripts, config, API)
./gradlew applyAllPatches --offline
python scripts/apply-btccore-patches.py
./gradlew :aspaper-server:createPaperclipJar --offline
bash scripts/rename-jar.sh  # Optionnel: renomme le jar
```

Jar: `aspaper-server/build/libs/aspaper-paperclip-26.1.2.build.19-alpha.jar`

## Deploiement

```bash
java -Xms4G -Xmx6G -XX:+UseZGC -XX:+AlwaysPreTouch -jar aspaper-paperclip-26.1.2.build.19-alpha.jar nogui
```

## Configuration (btccore.yml)

```yaml
performance:
  hopper: { throttling: true }
  collision: { throttle: true }
  redstone: { throttle: true }
security:
  freedom-chat: { enabled: true, rewrite-chat: true }
  cps-limit: { enabled: false, max-cps: 20 }
zero-features:
  light-engine: false      # Skip light updates
  force-void-generator: false  # Generate void chunks
  stats: false
  advancements: false
```

## Features

### Modules owned (79 fichiers)
| Module | Features |
|--------|----------|
| performance | Hopper/collision/redstone throttle, scoreboard, NBT cache, chunk prefetch |
| security | CPS limiting, combat log, exploit logger, Sentinel DB |
| qol | Maintenance mode, join queue, teleport warmup, vanish |
| async | Pathfinding, entity tracker |
| world | Void chunk generator |
| config | BTCCoreConfig, SlimeWorldConfig, AnticheatConfig |

### Hooks overlay (7/7)
- FreedomChat (chat non-signe)
- CPS Limiting (anti-click-spam)
- Zero Features : Stats, Advancements, Light Engine, Void Generator

### API
- BTCCoreAPI : World management (create, clone, load)
- BTCCoreVisualAPI : Display entities, virtual inventories

## Publication Maven

```gradle
repositories { maven("https://borntocraftstudio.net/repo/") }
dependencies { compileOnly("dev.btc.core:api:26.1.2.build.19-alpha") }
```

Publier: `./gradlew :api:publish -Ppublish=true`
Credentials: `btcRepoUser` / `btcRepoPassword` dans `~/.gradle/gradle.properties`

## Structure

```
btccore-new/
├── aspaper-server/          # Serveur fork
│   ├── src/main/java/dev/btc/core/  # Modules owned
│   ├── minecraft-patches/           # Patches ASP
│   └── paper-patches/               # Patches Paper
├── api/                     # API publique
├── plugin/                  # Plugin Bukkit
├── loaders/                 # Backends (MySQL, Redis, Mongo)
├── scripts/                 # Scripts (Python + Bash)
│   ├── apply-btccore-patches.py
│   └── rename-jar.sh
└── buildSrc/                # Configuration Gradle
```

## Tags Git
`btccore-clean-build` → `btccore-phase14`

## Licence
GPL v3.0 (heritee d'AdvancedSlimePaper)
