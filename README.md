# BTC-CORE

![Java Version](https://img.shields.io/badge/Java-25-orange)
![Build Status](https://img.shields.io/badge/build-passing-brightgreen)
![Target](https://img.shields.io/badge/Target-BTCCORE%2026.2-blue)
![Base](https://img.shields.io/badge/Base-AdvancedSlimePaper%2026.2-purple)
[![Wiki](https://img.shields.io/badge/Wiki-DeepWiki-blue)](https://deepwiki.com/RenaudRl/BTC-CORE-Fork-Fork)

## Documentation
 [API.md](API.md) for the public plugin API documentation.

## Building from Source

BTC-CORE is a **Paperweight v2 (Moonrise)** server fork. Building it decompiles Paper (via Mache), applies the BTC patches, and produces a runnable Paperclip server jar.

### Prerequisites
- **JDK 25** — `java -version` must report **25** ([Temurin 25](https://adoptium.net/) recommended). Everything targets Java 25; older JDKs fail at configuration.
- **Git** and **Python 3** (Python is only needed for the NMS hook script in step 2).
- **~10 GB free RAM** — the Gradle build runs with `-Xmx10g` (see `gradle.properties`).
- **Internet on the first build** — it downloads Paper, the Mache decompiler and dependencies. `--offline` only works *after* one successful online build.

### Build steps

> On Windows, use `gradlew.bat` instead of `./gradlew`.

```bash
git clone https://github.com/RenaudRl/BTC-CORE-Fork.git
cd BTC-CORE-Fork
git checkout dev/26.2

# 1. Decompile Paper (Mache) + apply patches + Access Transformers   (first run: ~10-15 min)
./gradlew applyAllPatches

# 2. Apply the 32 BTC-CORE NMS hooks into the decompiled overlay (idempotent)
python scripts/apply-btccore-patches.py

# 3. Build the runnable server jar (compile + Mojmap reobf + Paperclip assembly)
./gradlew :aspaper-server:createPaperclipJar

# 4. (optional) Build every module — plugin, loaders, importer, bridge
./gradlew assemble
```

### Output artifacts
| Path | Description |
|------|-------------|
| `aspaper-server/build/libs/aspaper-paperclip-<version>.jar` | **Runnable server** (Paperclip) — deploy this |
| `plugin/build/libs/asp-plugin-<version>.jar` | ASP plugin (SlimeWorld management) |
| `importer/build/libs/importer-<version>.jar` | World importer |

### Run
```bash
java -Xms4G -Xmx6G -XX:+UseZGC -jar aspaper-paperclip-26.2.build.1-alpha.jar nogui
```

### Troubleshooting
- **`paperApiVersion` (`gradle.properties`)** must be a *real, published* paper-api build (e.g. `26.2.build.48-alpha`). It is intentionally **decoupled** from the fork's own `version`, which is a fork identity string, **not** a paper-api coordinate.
- **After editing Access Transformers (`build-data/aspaper.at`)**, the paperweight task cache does not detect the change. Delete `aspaper-server/.gradle/caches/paperweight/taskCache/mergeAspaperATs.at`, then re-run `./gradlew applyAllPatches` to re-apply the ATs to the decompiled source.
- **`--offline`** fails on a fresh clone — run one full online build first; subsequent builds can use it.
- **Editing generated `.patch` files by hand** (`aspaper-server/minecraft-patches/`) requires **CRLF** line endings and a single space `" "` for blank context lines; prefer fixing the source and running `./gradlew rebuildMinecraftSourcePatches` / `rebuildMinecraftFeaturePatches`.

## Developer API

### Gradle (Kotlin DSL)
```kotlin
repositories { maven("https://borntocraftstudio.net/public/repo/") }
dependencies { compileOnly("dev.btc.core:api:26.2.build.1-alpha") }
java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
```

### Maven
```xml
<repository><id>btcstudio</id><url>https://borntocraftstudio.net/public/repo/</url></repository>
<dependency>
    <groupId>dev.btc.core</groupId>
    <artifactId>api</artifactId>
    <version>26.2.build.1-alpha</version>
    <scope>provided</scope>
</dependency>
```

## Fork Heritage

Only **one** of these is a base — the rest are patch sources. Confusing the two is what turns a
version bump into a hunt across several repositories.

| Fork | Integration | Key Features |
|------|-------------|--------------|
| **AdvancedSlimePaper** | **Base** — this fork is a copy of it | Native SRF, database backends (MySQL/Redis/Mongo), instant instancing |
| **Paper** | Upstream of the base | Async chunk loading, modern API, performance patches |
| **Purpur** | Patch source (~17 injections) | WASD minecarts, configurable minecart speed, elytra kinetic damage toggle, no item cooldown in creative |
| **Leaf** | Patch source | Async entity tracker, async pathfinding |
| **Pufferfish** | Patch source | DEAR/DAB, suffocation optimization, inactive goal throttle |
| **Canvas** | Patch source | Priority-based loading, Moonrise executor |

> **Not Folia.** The base is single-region: there is not one Folia or regionised patch in this fork.
> The `dev.btc.core` code is *written* against the region scheduler API (`Bukkit.getRegionScheduler()`),
> so it stays portable, but the server does not run regionised threading today.

Declare every upstream in one command, and see how far behind each one we are:

```bash
python scripts/setup-upstreams.py --status
```

Read **[.docs/upstream-maintenance.md](.docs/upstream-maintenance.md)** before attempting a version
bump: it measures what a merge currently costs, and names the three deleted patches that make that
cost recur at every release.

## Design Philosophy

BTC-CORE follows a **"cherry-picking"** strategy — the best optimizations from each fork, adapted for **Folia's regionized threading**.

> [!WARNING]
> BTC-CORE introduces deep architectural changes. Standard Spigot/Paper plugins may not work. Use Folia-compatible plugins.

## Key Features

### Concurrency (Folia)
- Regionized Multithreading, Parallel World Ticking, Mid-Tick Task Execution
- All internal schedulers use Folia's `GlobalRegionScheduler` and `RegionScheduler`

### World Management (SlimeWorld)
- Native SRF, MySQL/Redis/Mongo/File backends, Instant Instancing, Game Rules Config, copperFade

### Performance — NMS Hooks (32 total via `apply-btccore-patches.py`)
- **Hopper Throttle**: Skip hopper processing every N ticks (configurable interval)
- **Collision Throttle**: Skip entity collision checks when nearby entity count exceeds threshold
- **Light Update Throttle**: Cap light updates per tick (atomic counter)
- **Redstone Throttle**: Per-chunk redstone update limit with automatic reset per tick
- **Suffocation Optimization**: Skip suffocation checks for entities far from players
- **Inactive Goal Selector Throttle**: Reduce goal selector tick frequency for distant entities
- **Projectile Chunk Loading Limits**: Per-tick and per-projectile chunk load caps
- **Batched Inventory Updates**: Queue slot packets per player, flush at end-of-tick
- **NBT Compression Cache**: LRU cache for compressed NBT data (thread-safe)
- **Scoreboard Optimization**: Filter scoreboard packet recipients by display slot
- **Chunk Prefetch**: Pre-load 5x5 chunk grid on player teleport
- **Per-World Tick Rate**: Reduce tick frequency for empty worlds
- **Vanilla Tick Suppression**: Disable AI/Brain/Sensors globally (per-world)
- **Projectile Pooling**: Track and cap active projectiles per world

### Performance — Dynamic (Bukkit Events)
- **DAB (Dynamic Activation of Brain)**: Periodic task (every 20 ticks) enables/disables entity AI based on player proximity
- **Particle/Sound/BetterHUD Culling**: Distance-based packet filtering

### Performance — Async Processing (Leaf Port)
- Async Entity Tracker (configurable thread pool)
- Async Pathfinding (configurable thread pool, reject policy)
- Async Mob Spawning (delegated to Paper's per-player-mob-spawn system)

### Gameplay (Purpur)
- Controllable Minecarts (WASD, configurable speed), Elytra kinetic damage toggle, no item cooldown in creative

  Silk-touch spawners are **not** provided here. The patch that claimed them targeted
  `net/minecraft/data/loot/packs/VanillaBlockLoot.java`, which is datagen and never runs at server
  boot, so it could not have had any effect; it has been removed. The feature is served by
  TypeWriter-EnchantmentCreatorExtension (`SpawnersSilkTouchActionEntry`).

### Security
- FreedomChat (chat reporting prevention), CPS Limiting, Combat Log, Reach Validation, Exploit Logging
- Native Sentinel Anticheat: async reach/velocity validation with ghost hitbox caching, MySQL logging support

### Quality of Life
- Maintenance Mode, Join Queue, Teleport Warmup, Vanish Levels, Player Data Backup

### Zero Features
Strip vanilla content or disable vanilla mechanics for performance.

- **Recipes** and **Advancements** load *zero vanilla content* while keeping the systems fully
  functional — custom recipes/advancements (plugins, non-`minecraft` datapacks) still work. Both act
  on a single server-wide registry, so they always apply **server-wide** and ignore the `worlds` list.
- **Stats** is a hard disable: nothing is tracked, loaded or saved.
- **Light Engine**, **Void Generator**, **Collisions**, **Cramming**, **Block Updates** and
  **Sleep Tick** support per-world pattern matching via the `worlds` list.

### BlockValueCache
Per-chunk cache for fast island level computation (BTC Sky integration). Uses registry key matching for block value lookup.

### Visual API
Packet-based display entities and virtual inventories. Dispatched via Folia's entity scheduler for thread-safe client-side visuals.
- `spawnDisplay()` — typed Text/Item/Block display with payload, styling, interpolation and optional TTL
- `updateDisplay()` — sparse metadata/position update using a viewer-bound `VirtualDisplayHandle`
- `destroyDisplay()` — idempotent viewer-scoped cleanup
- `spawnAsyncDisplayEntity()` — spawn fake display entity via packets (0 MSPT)
- `updateAsyncDisplayEntity()` — update position/transformation of spawned display entity
- `destroyAsyncDisplayEntity()` — remove fake display entity
- `sendAsyncVirtualInventory()` — send virtual inventory contents via packets

The typed API allocates collision-resistant virtual IDs, never registers an
entity in a world, and lets the Minecraft client perform transformation
interpolation. Legacy string-based methods remain available for existing
consumers during migration.

### Custom Events
- `PreDamageCalculationEvent` — fired before damage is calculated (before armor/enchant), cancellable, base damage modifiable
- `EntityTargetPlayerEvent` — fired when a mob targets a player, cancellable

## Configuration

All configuration files are generated on first server start; changes require a restart.

### `btccore.yml` (server root)
Central config for every BTC Core feature. Auto-generated on first run from a fully
**annotated template** — each option carries an inline English comment explaining what it does.
Grouped by category: `zero-features`, `slime-world`, `async`, `dab`, `performance`,
`security` (incl. Sentinel anti-cheat), `freedom-chat`, `spam-limiter`, `packet-limiter`,
`rpg`, `qol`, `join-queue`, `maintenance-mode`.

### `config/BTCCore/slimeworld-config.yml`
Default **GameRules applied automatically when a Slime World loads** — globally, per world, or
per pattern. Priority (later overrides earlier): `default` → pattern → exact world name.

```yaml
default:
  randomTickSpeed: 3          # applied to every slime world
worlds:
  spawn:                      # exact world name
    doMobSpawning: false
    keepInventory: true
  "lobby*":                   # prefix — worlds starting with "lobby"
    doDaylightCycle: false
  "*_pvp":                    # suffix — worlds ending with "_pvp"
    keepInventory: false
  "regex:^plot_[0-9]+$":      # full Java regex (prefix the pattern with "regex:")
    mobGriefing: false
```

World matching is case-insensitive. Rule names are the vanilla GameRule IDs
(`keepInventory`, `randomTickSpeed`, `doDaylightCycle`, `doMobSpawning`, `mobGriefing`, …);
boolean rules use `true`/`false`, numeric rules use a number.

### Other files
- `anticheat.yml` — Sentinel anti-cheat tuning (reach / velocity checks).
- `purpur.yml` — Purpur gameplay options.

## Commands

| Command | Permission | Description |
|---------|------------|-------------|
| `/btccore debug` | btccore.admin | Display all feature statuses |
| `/sentinel check <player>` | sentinel.admin | Check anticheat violations |
| `/sentinel notify` | sentinel.admin | Toggle real-time alerts |
| `/ping` | - | Check your ping |
| `/uptime` | - | Check server uptime |

## API

```java
// Zero Features
// true only when the whole recipe subsystem is off; the vanilla-only purge is
// btccore.yml -> vanilla-content.purge-recipes.
//
// That purge spares 31 recipes by default (vanilla-content.preserve-special-recipes):
// leather and wolf-armor dyeing, tipped arrows, fireworks, tool repair, book and banner
// duplication, map extending, shield decoration, decorated pots. Their behaviour lives in
// server code and the Bukkit API exposes no constructor for them, so dropping them is
// irreversible — no plugin can put them back. Add your own exceptions with
// vanilla-content.preserve-recipes.
boolean noRecipes = BTCCoreAPI.isZeroFeatureEnabledFor("recipes", worldName);

// Block Value Cache
double val = BTCCoreAPI.getChunkValue(level, chunkX, chunkZ);

// Combat
boolean inCombat = BTCCoreAPI.isInCombat(player);

// DAB Exemption — force entity to always tick AI
api.setEntityAlwaysTick(bossEntity);
boolean alwaysTicks = api.isEntityAlwaysTick(entity);

// MSPT — throttle async tasks under load
double mspt = api.getCurrentMspt();
if (mspt > 40.0) { // back off
    return;
}

// Visual API
BTCCoreVisualAPI.getInstance().spawnAsyncDisplayEntity(player, id, uuid, loc, "item", transform);
BTCCoreVisualAPI.getInstance().updateAsyncDisplayEntity(player, id, newLoc, newTransform);
BTCCoreVisualAPI.getInstance().destroyAsyncDisplayEntity(player, id);

// PreDamageCalculationEvent — intercept damage before armor calculation
@EventHandler(priority = EventPriority.LOW)
public void onPreDamage(PreDamageCalculationEvent event) {
    event.setBaseDamage(event.getBaseDamage() * 1.5); // 50% more damage
}
```

See [API.md](API.md) for full documentation.

## Structure

```
├── aspaper-server/    # Server fork + BTC Core code (dev.btc.core.*) + minecraft-patches
├── aspaper-api/       # Paper API fork (generated from paper-api + ASP patch)
├── api/               # Public API (BTCCoreAPI, BTCCoreVisualAPI, events)
├── core/              # SlimeWorld core logic (serialization, skeleton)
├── plugin/            # Bukkit plugin (listeners, commands, bootstrap)
├── loaders/           # SlimeWorld backends: MySQL, Redis, Mongo, File, API
├── importer/          # World importer
├── bridge-plugin/     # Cross-server bridge plugin
├── build-data/        # Access Transformers (aspaper.at)
├── scripts/           # apply-btccore-patches.py (32 NMS hooks)
└── buildSrc/          # Gradle build conventions
```

## Licence
GNU General Public License v3.0
