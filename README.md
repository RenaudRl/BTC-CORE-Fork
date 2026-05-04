# BTC-CORE

![Version](https://img.shields.io/badge/version-26.1.2-blue)
![Java](https://img.shields.io/badge/java-25-orange)
![Base](https://img.shields.io/badge/base-AdvancedSlimePaper%2026.1.2-purple)
![Folia](https://img.shields.io/badge/folia-compatible-green)

Fork serveur Minecraft optimise pour **BornToCraft Studio**, base sur AdvancedSlimePaper 26.1.2 (Paper + SlimeWorld + Folia/Moonrise).

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

## Configuration

### btccore.yml
```yaml
performance:
  hopper: { throttling: true }
  collision: { throttle: true, max-per-tick: 10 }
  redstone: { throttle: true }
  suffocation-optimization: true
  inactive-goal-selector-throttle: true

async:
  entity-tracker: { enabled: false }
  pathfinding: { enabled: false }
  mob-spawning: { enabled: true }

security:
  freedom-chat: { enabled: true, rewrite-chat: true }
  cps-limit: { enabled: false, max-cps: 20 }
  combat-log: { enabled: true }

zero-features:
  light-engine: false       # Skip light updates (perf)
  force-void-generator: false  # Generate void chunks
  stats: false
  advancements: false
```

### purpur.yml
```yaml
settings:
  minecart:
    controllable: true
    max-speed: 0.4
  elytra:
    kinetic-damage: true
    damage-per-second: 1
  spawners:
    silk-touch: true
  barrel:
    rows: 3
  ender-chest:
    six-rows: false
```

### anticheat.yml
```yaml
anticheat:
  enabled: true
cps-limit:
  enabled: false
  max-cps: 20
  action-on-violation: KICK
reach:
  enabled: false
combat:
  combat-log:
    enabled: true
    kill-on-logout: true
```

## Features

### Base (AdvancedSlimePaper 26.1.2)
- Paper 26.1.2 + Folia (regionized multithreading) — SlimeWorld + vanilla worlds
- SlimeWorld Manager (SRF format, database backends: MySQL/Redis/Mongo/File)
- Moonrise chunk system (priority-based loading, async chunk tasks)
- Alternate Current redstone (high-performance)
- SIMD vectorization (Pufferfish/Canvas, 8x faster map rendering)
- Paper performance patches (DEAR/DAB, async chunk loading, etc.)

### Modules Owned (79 fichiers)
| Module | Features |
|--------|----------|
| `performance/` | Hopper throttle, collision throttle, redstone throttle, scoreboard opt, NBT compression cache, chunk prefetch, batched inventory updates |
| `security/` | CPS limiting, combat log, exploit logger, Sentinel anticheat DB, async packet validator, reach validation |
| `qol/` | Maintenance mode, join queue, teleport warmup, vanish, player data backup |
| `async/` | Async pathfinding (Leaf), async entity tracker (Leaf), async mob spawning (Pufferfish) |
| `entity/` | Cross-world entity transfer, collision throttle |
| `world/` | Void chunk generator |
| `config/` | BTCCoreConfig, SlimeWorldConfig, AnticheatConfig, PurpurConfig |

### Hooks Overlay (13 verified)
| # | Hook | Fichier | Effet |
|---|------|---------|-------|
| 1 | FreedomChat | PlayerList | Chat non-signe |
| 2 | CPS Limiting | ServerGamePacketListenerImpl | Anti-click-spam |
| 3 | Branding | ServerBuildInfoImpl | "BTC Core" dans /version |
| 4 | Zero: Stats | ServerStatsCounter | Desactive les stats |
| 5 | Zero: Advancements | PlayerAdvancements | Desactive les adv |
| 6 | Zero: Light Engine | ThreadedLevelLightEngine | Skip light updates |
| 7 | Zero: Void Generator | ServerChunkCache | Generation vide |
| 8 | Silk-touch spawners | VanillaBlockLoot | Spawners minables |
| 9 | Ender pearl | EnderPearlItem | Pas de cooldown creatif |
| 10 | Elytra kinetic | LivingEntity | Degats cinetiques toggle |
| 11 | Minecart speed | AbstractMinecart | Vitesse configurable |
| 12 | purpurConfig | Level | Config Purpur globale |
| 13 | Rideables foundation | Mob, ServerLevel | isControllable, onSpacebar |

### Zero Features (tous les mondes)
| Feature | Config key | Effet |
|---------|-----------|-------|
| Stats | `zero-features.stats` | Pas de stats joueur |
| Advancements | `zero-features.advancements` | Pas d'advancements |
| Light Engine | `zero-features.light-engine` | Pas de light updates |
| Void Generator | `zero-features.force-void-generator` | Chunks vides |
| Collisions | `zero-features.collisions` | Pas de collisions |
| Cramming | `zero-features.cramming` | Pas de cramming |
| Block Updates | `zero-features.block-updates` | Pas de block updates |
| Sleep Tick | `zero-features.sleep-tick` | Skip sleep |

> Les Zero Features s'appliquent a TOUS les mondes (SlimeWorld et vanilla) quand actives.
> Configurer `zero-features.worlds` pour restreindre a des mondes specifiques.

### API
```java
// BTCCoreAPI - World management
BTCCoreAPI.instance().createWorld(loader, worldName, properties);
BTCCoreAPI.instance().cloneWorld(worldName, newName);

// BTCCoreVisualAPI - Display entities
BTCCoreVisualAPI.instance().spawnDisplayEntity(location, type);
```

## Structure

```
btccore-new/
├── aspaper-server/                  # Serveur fork
│   ├── src/main/java/dev/btc/core/  # Modules owned (79 fichiers)
│   ├── src/main/java/org/purpurmc/  # Purpur integration (18 events + config)
│   ├── src/minecraft/               # Overlay (genere par applyAllPatches)
│   ├── minecraft-patches/           # Patches ASP
│   └── paper-patches/               # Patches Paper
├── api/                             # API publique (BTCCoreAPI + BTCCoreVisualAPI)
├── plugin/                          # Plugin Bukkit (SWPlugin, init modules)
├── loaders/                         # Database backends (MySQL, Redis, Mongo, File, API)
├── scripts/
│   ├── apply-btccore-patches.py     # Script de repro overlay (Python)
│   └── rename-jar.sh               # Rename post-build
├── repo/                            # Publication Maven
└── buildSrc/                        # Configuration Gradle
```

## Publication Maven

```gradle
repositories { maven("https://borntocraftstudio.net/repo/") }
dependencies { compileOnly("dev.btc.core:api:26.1.2.build.19-alpha") }
```

Publier: `./gradlew :api:publish -Ppublish=true`
Credentials: `btcRepoUser` / `btcRepoPassword` dans `~/.gradle/gradle.properties`

## Tags Git
`btccore-clean-build` → `btccore-phase19` (19 tags)

## Licence
GPL v3.0 (heritee d'AdvancedSlimePaper)
