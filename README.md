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
git checkout main

# 1. Register the aspaper fork in the generated build files (idempotent, must run first)
python scripts/register-aspaper-fork.py

# 2. Decompile Paper (Mache) + apply every patch, BTC hooks included   (first run: ~10-15 min)
./gradlew applyAllPatches

# 3. Build the runnable server jar (compile + Mojmap reobf + Paperclip assembly)
./gradlew :aspaper-server:createPaperclipJar

# 4. (optional) Build every module — plugin, loaders, importer, bridge
./gradlew assemble
```

### Output artifacts
| Path | Description |
|------|-------------|
| `aspaper-server/build/libs/aspaper-paperclip-<version>.jar` | **Runnable server** (Paperclip) — deploy this |
| `plugin/build/libs/btccore-plugin-<version>.jar` | **BTCCore plugin** — SlimeWorld runtime, BTC Core modules and the BTCVelocity bridge. Deploy this alongside the paperclip |
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

## Performance — Measured

All figures below come from the same local bench, not from estimates: a Ryzen 5 5600 (6 cores),
50 SoulFire bots on 50 separate SlimeWorld islands, MSPT sampled over RCON. Every number is a
**median over 30+ samples**; the campaign scripts live outside this repo.

### Load: 50 players on 50 islands

| Stage | MSPT (median) | What changed |
|---|---:|---|
| Baseline (2026-08-15) | ~220 | — |
| After redstone write-back fix | 147–182 | see redstone table below |
| **Locator bar disabled** | **~117** | `locator_bar` is O(players²) per world |
| **MiniMessage + world-lookup caches** | **33.8** | see below |

Reference points: 50 ms is the 20 TPS ceiling, 40 ms is this server's `mspt-threshold`.
The bench sits **under both**, with 50 players online.

Two optimisations account for the last step, both in the Typewriter engine rather than in the
server core:

- **MiniPlaceholders resolvers cached per tick.** Every text parse rebuilt the whole `TagResolver`
  by walking all registered expansions — ~14% of the server thread. The cache expires each tick, so
  a reload still takes effect within one tick.
- **World lookup by name.** `Position → Location` called `UUID.fromString` on a world *name*
  (throwing an exception every time) and then scanned the world list with `equalsIgnoreCase`.
  With ~50 loaded worlds this was the hottest leaf of the entire tick (~7%). Name→uid is now
  memoised, while the world itself is still resolved by the server, so an unloaded world still
  resolves to null.

Combined effect, measured back-to-back on identical fresh restarts: **57.0 → 33.8 ms (−41%)**.

### Redstone: BTC-CORE compiler vs Alternate Current

Bench: 167-node circuit, lever driven at 2.5 Hz, 3 × 2000 ticks.

| | Cost per tick | Neighbour updates |
|---|---:|---:|
| BTC-CORE compiler (before fix) | 0.1008 ms | 461 |
| Alternate Current | 0.0480 ms | — |
| **BTC-CORE compiler** | **0.0100 ms** | **0** |

**≈4.8× faster than Alternate Current** (per-pass range 4.3–7.0× — Alternate Current is the noisy
side, so treat the range as the result, not a single figure). The control point did not move
(AC measured 0.0472 before and 0.0480 after), so the reversal is not bench drift.

The original defect was that the compiler's write-back used `Level#setBlock` without
`UPDATE_KNOWN_SHAPE`, paying full neighbour/shape updates on every dust block rewritten — it was
saving 184× the updates while paying for its writes on a far heavier path.

### Per-island cost (no players)

50 deliberately heavy islands (225 chunks each, full wheat cover, 480 hoppers + 480 chests,
60 hopper ping-pong pairs, 225 persistent mobs) tick at **37.9 ms total** — ~0.80 ms per island,
3.3 µs per entity-ticking chunk, scaling linearly. Cost tracks the **number of ticketed chunks**,
not what is inside them.

> [!NOTE]
> No vanilla or stock-Paper baseline was measured for the load scenario: a 50-world skyblock has no
> meaningful vanilla equivalent, and quoting one would be an invention. The Alternate Current
> comparison above *is* a like-for-like measurement on the same bench. Numbers were taken on fresh
> restarts; a long-running server with matured crops and fully activated islands will read higher.

## What BTC-CORE Adds

Every row below is a change this fork makes on top of its AdvancedSlimePaper base. The **Origin**
column says where the work comes from: **BTC** means it was written here, the others are ports
whose upstream is credited in [Fork Heritage](#fork-heritage) and in [NOTICE.md](NOTICE.md).
Everything listed is configurable in `btccore.yml` unless the row says otherwise.

### Threading — read this first

| | |
|---|---|
| Base threading | **Single-region.** There is not one Folia or regionised patch in this fork. |
| `dev.btc.core` code | Written against the region scheduler API (`Bukkit.getRegionScheduler()`) so it stays portable, but the server does **not** run regionised threading today. |
| Async work | Entity tracker, pathfinding and mob spawning run off the main thread (see below). That is thread offloading, not regionisation. |

### Performance — NMS hooks

Shipped as Paperweight feature patches (57 hunks), so they survive an upstream bump as patches
rather than as edits to decompiled sources.

| Hook | Origin | What it does |
|---|---|---|
| Hopper throttle | BTC | Skips hopper processing every N ticks. The phase is offset per hopper, so a server full of hoppers does not tick them all on the same tick. |
| Collision throttle | BTC | Spreads an entity's collision scan over several ticks once its **local** pushable-entity density passes a threshold. Not a server-wide or per-world entity count. |
| Light update throttle | BTC | Caps light updates per tick with an atomic counter. |
| Redstone throttle | BTC | Per-chunk redstone update limit, reset each tick. |
| Redstone compiler | BTC | Compiles a circuit once and replays it. Measured **≈4.8× faster than Alternate Current** with zero neighbour updates — see [Performance](#redstone-btc-core-compiler-vs-alternate-current). |
| Suffocation optimisation | Pufferfish | Skips suffocation checks for entities far from any player. |
| Inactive goal selector throttle | Pufferfish | Lowers goal-selector tick frequency for distant entities. |
| Projectile chunk-load limits | BTC | Per-tick and per-projectile caps on chunk loads. |
| Projectile pooling | BTC | Tracks and caps active projectiles per world. |
| Batched inventory updates | BTC | Coalesces slot packets per `(container, slot)` for one player tick. **Off by default** — the previous implementation dropped slot packets outright, this one stays opt-in until validated in game. |
| NBT compression cache | BTC | Thread-safe LRU cache over compressed NBT. |
| Scoreboard optimisation | BTC | Filters scoreboard packet recipients by display slot. |
| Chunk prefetch | BTC | Pre-loads a 5×5 chunk grid on player teleport. |
| Per-world tick rate | BTC | Reduces tick frequency for empty worlds. The phase is hashed from the world name, so fifty empty islands do not all come due on the same tick. |
| Vanilla tick suppression | BTC | Disables AI / Brain / Sensors, per world. |
| Batched recipe finalisation | BTC | Finalises recipes in one pass and honours the resend flag instead of resending the whole table. |

### Performance — dynamic and async

| Feature | Origin | What it does |
|---|---|---|
| DAB (Dynamic Activation of Brain) | Pufferfish | A 20-tick task enables/disables entity AI by player proximity. |
| Particle / sound / BetterHUD culling | BTC | Distance-based packet filtering. |
| Async entity tracker | Leaf | Configurable thread pool. |
| Async pathfinding | Leaf | Configurable thread pool and reject policy. |
| Async mob spawning | Leaf | Delegated to Paper's per-player-mob-spawn system. |

Measured effect of the whole stack under load is in [Performance — Measured](#performance--measured):
**220 → 33.8 ms MSPT** with 50 players on 50 islands.

### Zero Features

Strip vanilla content or disable vanilla mechanics outright. All BTC.

| Feature | Scope | Behaviour |
|---|---|---|
| Recipes | **Server-wide** — ignores the `worlds` list | Loads zero vanilla recipes, system stays functional. Plugin and non-`minecraft` datapack recipes still work. |
| Advancements | **Server-wide** — ignores the `worlds` list | Same contract as Recipes. |
| Stats | Server-wide | Hard disable: nothing tracked, loaded or saved. |
| Light engine | Per-world (`worlds` patterns) | — |
| Void generator | Per-world | — |
| Collisions | Per-world | — |
| Cramming | Per-world | — |
| Block updates | Per-world | — |
| Sleep tick | Per-world | — |

> Recipes and Advancements act on a single server-wide registry. Listing worlds for them has no
> effect — that is the registry's shape, not a bug.

### World management, gameplay, security

| Area | Origin | Contents |
|---|---|---|
| SlimeWorld | AdvancedSlimePaper | Native SRF, MySQL / Redis / Mongo / File backends, instant instancing, per-world game rules, `copperFade`. |
| Game rules | BTC | Rules are applied with the owning `ServerLevel`, so callbacks like `locator_bar` actually fire. Passing `null` writes the value but leaves cached rules on their old setting. |
| Controllable minecarts | Purpur | WASD control, configurable speed. |
| Elytra kinetic damage toggle | Purpur | — |
| No item cooldown in creative | Purpur | — |
| Sentinel anticheat | BTC | Async reach/velocity validation, ghost hitbox caching, optional MySQL logging. |
| FreedomChat | Port | Chat reporting prevention. |
| CPS limiting, combat log, reach validation, exploit logging | BTC | — |
| Maintenance mode, join queue, teleport warmup, vanish levels, player data backup | BTC | — |

> **Silk-touch spawners are not provided here.** The patch that claimed them targeted
> `net/minecraft/data/loot/packs/VanillaBlockLoot.java`, which is datagen and never runs at server
> boot — it could not have had any effect, and has been removed. The feature is served by
> TypeWriter-EnchantmentCreatorExtension (`SpawnersSilkTouchActionEntry`).

### BTC APIs

All BTC. These are the reason `dev.btc.core:api` is published — see [Developer API](#developer-api).

| API | What it gives a plugin |
|---|---|
| **Visual API** | Packet-based display entities and virtual inventories, dispatched via the entity scheduler. `spawnDisplay` / `updateDisplay` / `destroyDisplay` for the typed handle-based path; `spawnAsyncDisplayEntity` / `updateAsyncDisplayEntity` / `destroyAsyncDisplayEntity` and `sendAsyncVirtualInventory` for the raw packet path (0 MSPT). The typed API allocates collision-resistant virtual IDs, never registers an entity in a world, and lets the client interpolate transformations. Legacy string-based methods remain for migration. |
| **Island catch-up** | `activateIslandAsync` resumes a loaded island off the region thread, then schedules each owned chunk on its owning region thread. `resumeIslandChunkAsync` resumes a single chunk with a persisted per-chunk cursor — the window is never widened and neighbours are never loaded. Both are `default` methods: a plugin built against the older API still resolves. |
| **BlockValueCache** | Per-chunk cache for fast island level computation (BTC Sky), with registry-key block value lookup. |
| **Custom events** | `PreDamageCalculationEvent` (before armour/enchant, cancellable, base damage modifiable) and `EntityTargetPlayerEvent` (cancellable). |
| **Runtime contract** | `BTCCoreBuild` exposes the server build identity; `BTCCoreContractCheck` fails loudly at bootstrap if the plugin is running on a BTC-CORE it was not built against. |

### The BTCCore plugin

One runtime plugin, one lifecycle. The former `bridge-plugin` module (`BTCBridge`) has been folded
into `:plugin`, which now ships as **`btccore-plugin`**.

| | |
|---|---|
| Plugin name | `BTCCore` (was `ASPaperPlugin`) — look it up by that name |
| Jar | `plugin/build/libs/btccore-plugin-<version>.jar` |
| Replaces | `asp-plugin-*.jar` **and** `BTCBridge*.jar` — remove both, keeping them loads the same runtime twice |
| Contains | SlimeWorld runtime, BTC Core modules, the BTCVelocity `btc:bridge` client |
| Config | `plugins/BTCCore/config.yml`, created on first start |

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

// MSPT — throttle async tasks under load (threshold comes from btccore.yml)
if (api.getCurrentMspt() > api.getMsptThreshold()) { // back off
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
├── scripts/           # register-aspaper-fork.py, check-btccore-patches.py, setup-upstreams.py
└── buildSrc/          # Gradle build conventions
```

## Licence

**GNU General Public License v3.0** — see [LICENSE](LICENSE). Inherited from Paper and
AdvancedSlimePaper; it is not a choice this fork could make differently.

| | |
|---|---|
| You may | Run it, modify it, redistribute it — **including commercially**. GPLv3 §4 explicitly allows charging for a copy. |
| You must | Ship the complete corresponding source under GPLv3, preserve the copyright notices, and **state that you modified it and when** (§5(a)). |
| You may not | Relicense under stricter terms, distribute closed source, or strip the attribution and present this work as your own — that terminates your rights under §8. |
| Marks | **"Born To Craft", "BTC Studio", "BTC-CORE", "BTCCore"** and the associated logos are **not** covered by the GPL. Fork the code freely, but rebrand your fork. |

Full attribution, the statement of modifications required by §5(a), and the trademark reservation
are in **[NOTICE.md](NOTICE.md)**. Read it before redistributing.

> The decompiled Minecraft sources produced during the build (`aspaper-server/src/minecraft/`) are
> Mojang's, governed by the [Minecraft EULA](https://www.minecraft.net/eula), and are never
> redistributed by this repository.