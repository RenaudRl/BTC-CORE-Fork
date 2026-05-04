# BTC-CORE

![Version](https://img.shields.io/badge/version-26.1.2-blue)
![Java](https://img.shields.io/badge/java-25-orange)
![Base](https://img.shields.io/badge/base-AdvancedSlimePaper%2026.1.2-purple)

**Fork serveur Minecraft optimisé pour BornToCraft Studio**, basé sur AdvancedSlimePaper 26.1.2 (Paper + SlimeWorld + Folia/Moonrise).

## Build

```bash
git clone https://github.com/RenaudRl/BTC-CORE-Fork.git
cd BTC-CORE-Fork && git checkout dev/26.1.1
./gradlew applyAllPatches --offline
python scripts/apply-btccore-patches.py
./gradlew :aspaper-server:createPaperclipJar --offline
```

## Features

- Paper 26.1.2 + Folia (SlimeWorld + vanilla worlds)
- 9 hooks overlay (FreedomChat, CPS, Branding, Zero×4, purpurConfig, Rideables)
- 31 modules owned (perf, sécu, qol, async, entity, world, config)
- Anticheat natif (CPS, reach, combat log, exploit, auto-ban)
- Purpur integration (18 events, PurpurConfig)
- API publique (BTCCoreAPI, BTCCoreVisualAPI, BlockValueCache)

## API

```kotlin
repositories { maven("https://borntocraftstudio.net/repo/") }
dependencies { compileOnly("dev.btc.core:api:26.1.2.build.19-alpha") }
```

## Licence

GPL v3.0
