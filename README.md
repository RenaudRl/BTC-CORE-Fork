# BTC-CORE

![Version](https://img.shields.io/badge/version-26.1.2-blue)
![Java](https://img.shields.io/badge/java-25-orange)
![Base](https://img.shields.io/badge/base-AdvancedSlimePaper%2026.1.2-purple)
![Folia](https://img.shields.io/badge/folia-compatible-green)
![Hooks](https://img.shields.io/badge/hooks-16-success)

Fork serveur Minecraft optimise pour **BornToCraft Studio**, base sur AdvancedSlimePaper 26.1.2 (Paper + SlimeWorld + Folia/Moonrise).

## Build

```bash
git clone https://github.com/InfernalSuite/AdvancedSlimePaper.git
cd AdvancedSlimePaper && git checkout dev/26.1.1
# Copier les assets BTC-CORE (modules, scripts, config, API)
./gradlew applyAllPatches --offline
python scripts/apply-btccore-patches.py
./gradlew :aspaper-server:createPaperclipJar --offline
```

Jar: `aspaper-server/build/libs/aspaper-paperclip-26.1.2.build.19-alpha.jar`

## Deploiement

```bash
java -Xms4G -Xmx6G -XX:+UseZGC -XX:+AlwaysPreTouch -jar aspaper-paperclip-26.1.2.build.19-alpha.jar nogui
```

## Features

### Base (AdvancedSlimePaper 26.1.2)
- Paper 26.1.2 + Folia (regionized multithreading) — SlimeWorld + vanilla worlds
- SlimeWorld Manager (SRF format, database backends: MySQL/Redis/Mongo/File)
- Moonrise chunk system (priority-based loading, async chunk tasks)
- Alternate Current redstone (high-performance, Moonrise)
- SIMD vectorization (Pufferfish/Canvas, 8x faster map rendering)

### 16 Hooks Overlay
| # | Hook | Fichier | Effet |
|---|------|---------|-------|
| 1 | FreedomChat | PlayerList | Chat non-signe |
| 2 | CPS Limiting | ServerGamePacketListenerImpl | Anti-click-spam |
| 3 | Branding | ServerBuildInfoImpl | "BTC Core" dans /version |
| 4 | Zero: Stats | ServerStatsCounter | Desactive les stats |
| 5 | Zero: Advancements | PlayerAdvancements | Desactive les adv |
| 6 | Zero: Light Engine | ThreadedLevelLightEngine | Skip light updates |
| 7 | Zero: Void Generator | ServerChunkCache | Generation vide |
| 8 | Redstone Throttle | Level.neighborChanged | Limite updates/chunk/tick |
| 9 | Silk-touch spawners | VanillaBlockLoot | Spawners minables |
| 10 | Ender pearl | EnderPearlItem | Pas de cooldown creatif |
| 11 | Elytra kinetic | LivingEntity | Degats cinetiques toggle |
| 12 | Minecart speed | AbstractMinecart | Vitesse configurable |
| 13 | purpurConfig | Level | Config Purpur globale |
| 14 | Rideables foundation | Mob, ServerLevel | isControllable, onSpacebar |
| 15 | PreDamageCalculationEvent | LivingEntity | Event custom degats |
| 16 | EntityTargetPlayerEvent | Mob | Event custom ciblage |

### 81 Modules Owned
| Module | Features |
|--------|----------|
| `performance/` | Hopper/collision/redstone throttle, scoreboard opt, NBT compression cache, chunk prefetch, batched inventory |
| `security/` | CPS limiting, combat log, exploit logger, Sentinel DB, async packet validator, reach validation |
| `qol/` | Maintenance mode, join queue, teleport warmup, vanish, player data backup |
| `async/` | Async pathfinding, async entity tracker, async mob spawning |
| `entity/` | Cross-world entity transfer, collision throttle |
| `world/` | Void chunk generator, BlockValueCache (125 lignes, cache valeurs blocs pour calcul niveau ile) |
| `config/` | BTCCoreConfig, SlimeWorldConfig, AnticheatConfig, PurpurConfig |
| `event/` | PreDamageCalculationEvent, EntityTargetPlayerEvent |

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

### Anticheat (LightningGrim alternative)
```yaml
# anticheat.yml
cps-limit: { enabled: false, max-cps: 20, action-on-violation: KICK }
reach: { enabled: false, max-survival-reach: 4.5 }
combat: { combat-log: { enabled: true, kill-on-logout: true } }
exploit: { block-interact-distance: 6.0, invalid-packets: true }
auto-ban: { enabled: true, ban-command: "ban {player} {reason}" }
```

## Configuration

### btccore.yml (extrait)
```yaml
performance:
  hopper: { throttling: true }
  collision: { throttle: true, max-per-tick: 10 }
  redstone: { throttle: true, max-updates-per-chunk: 100 }
security:
  freedom-chat: { enabled: true, rewrite-chat: true }
  cps-limit: { enabled: false, max-cps: 20 }
zero-features:
  light-engine: false
  force-void-generator: false
```

### purpur.yml (extrait)
```yaml
settings:
  minecart: { controllable: true, max-speed: 0.4 }
  elytra: { kinetic-damage: true, damage-per-second: 1 }
  spawners: { silk-touch: true }
  barrel: { rows: 3 }
```

## API
```java
// World management
BTCCoreAPI.instance().createWorld(loader, worldName, properties);
BTCCoreAPI.instance().cloneWorld(worldName, newName);

// Block value cache (island leveling)
double val = BlockValueCache.getChunkValue(level, chunkX, chunkZ);
BlockValueCache.scanAndCacheChunk(chunk);

// Custom events
@EventHandler
public void onPreDamage(PreDamageCalculationEvent e) {
    e.setFinalDamage(e.getFinalDamage() * 1.5); // +50% degats
}
```

## Structure
```
btccore-new/
├── aspaper-server/
│   ├── src/main/java/dev/btc/core/   # 81 modules owned
│   ├── src/main/java/org/purpurmc/   # Purpur integration (18 events + config)
│   ├── src/main/resources/           # btccore.yml, purpur.yml, anticheat.yml
│   ├── minecraft-patches/            # Patches ASP (Paper upstream)
│   └── paper-patches/                # Patches Paper API
├── api/                              # BTCCoreAPI + BTCCoreVisualAPI
├── plugin/                           # Plugin Bukkit (SWPlugin)
├── loaders/                          # MySQL, Redis, Mongo, File, API
├── scripts/
│   ├── apply-btccore-patches.py      # 16 hooks overlay (Python)
│   └── rename-jar.sh                # Rename post-build
├── repo/                             # Publication Maven
└── buildSrc/                         # Configuration Gradle
```

## Publication Maven
```gradle
repositories { maven("https://borntocraftstudio.net/repo/") }
dependencies { compileOnly("dev.btc.core:api:26.1.2.build.19-alpha") }
```

## Tags Git
`btccore-clean-build` → `btccore-phase21` (22 tags)

## Licence
GPL v3.0 (heritee d'AdvancedSlimePaper)
