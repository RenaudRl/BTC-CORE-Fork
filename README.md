# BTC-CORE

![Java Version](https://img.shields.io/badge/Java-25-orange)
![Build Status](https://img.shields.io/badge/build-passing-brightgreen)
![Target](https://img.shields.io/badge/Target-BTCCORE%2026.3-blue)
![Base](https://img.shields.io/badge/Base-AdvancedSlimePaper%2026.3-purple)
[![Wiki](https://img.shields.io/badge/Wiki-DeepWiki-blue)](https://deepwiki.com/RenaudRl/BTC-CORE-Fork)

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
java -Xms4G -Xmx6G -XX:+UseZGC -jar aspaper-paperclip-26.3.build.1.jar nogui
```

### Troubleshooting
- **`paperApiVersion` (`gradle.properties`)** must be a *real, published* paper-api build (e.g. `26.3.build.191-stable`). It is intentionally **decoupled** from the fork's own `version`, which is a fork identity string, **not** a paper-api coordinate.
- **After editing Access Transformers (`build-data/aspaper.at`)**, the paperweight task cache does not detect the change. Delete `aspaper-server/.gradle/caches/paperweight/taskCache/mergeAspaperATs.at`, then re-run `./gradlew applyAllPatches` to re-apply the ATs to the decompiled source.
- **`--offline`** fails on a fresh clone — run one full online build first; subsequent builds can use it.
- **Editing generated `.patch` files by hand** (`aspaper-server/minecraft-patches/`) requires **CRLF** line endings and a single space `" "` for blank context lines; prefer fixing the source and running `./gradlew rebuildMinecraftSourcePatches` / `rebuildMinecraftFeaturePatches`.

## Developer API

### Gradle (Kotlin DSL)
```kotlin
repositories { maven("https://borntocraftstudio.net/repo/") }
dependencies { compileOnly("dev.btc.core:api:26.3.build.2-alpha") }
java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
```

### Maven
```xml
<repository><id>btcstudio</id><url>https://borntocraftstudio.net/repo/</url></repository>
<dependency>
    <groupId>dev.btc.core</groupId>
    <artifactId>api</artifactId>
    <version>26.3.build.2-alpha</version>
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
| **Leaf** | Patch source | Async mob spawning toggle; async pathfinding (opt-in, from Petal and Kaiiju as Leaf carries it). The first async entity tracker and pathfinding hooks were removed as unsafe |
| **DivineMC** | Patch source | Parallel entity tracker (opt-in, narrowed to a read-only scan); parallel world ticking (opt-in, experimental, being ported in steps) |
| **Pufferfish** | Patch source | DEAR/DAB, suffocation optimization, inactive goal throttle |

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

## Work in Progress

These parts ship in the jar but are **not finished**. Do not rely on them as complete protections yet.

| Area | Status |
|---|---|
| **Sentinel** (anti-cheat, movement envelope, reach, integrity) | In progress, **observation mode only**: violations are logged, not acted on. Checks must be re-proven on Paper 26.3, false positives reviewed, then actions armed one check at a time (cancel → setback → restriction). |
| **CloudNet integration** (service identity, proxy bridge v2) | In progress: the signed bridge and clean shutdown work; network-wide orchestration is still being completed and validated on a test network. |
| **26.3 migration** | Done: the fleet runs on 26.3 and batched recipe registration is validated on the bench (2,161 recipes in 740 ms). One check left: Anti-Xray enabled on Slime worlds. |

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
| Async work | Mob spawning is delegated to Paper's per-player-mob-spawn system (see below). The former async entity tracker and pathfinding hooks are gone: both touched entity and world state off their owning thread. Their replacements are opt-in (`async.parallel-tracker`, `async.async-pathfinding`): pool threads write to nothing in the world, and every change to an entity, its tracker, its path or a chunk is made on the tick thread. The tracker pool reads while the tick thread waits for it. The pathfinding pool reads while the tick goes on — live chunk sections (block and fluid states) and the mob's own state, without a lock — so a path can be stale by the time it is applied, and a search that fails gives the mob an empty path, after which it asks again. It never reads a block entity (that read promotes and removes entries in the chunk): to such a search a shulker box is a full block. |
| Parallel world ticking | `parallel-world-ticking.enabled`, **off by default, experimental, bench only**. On: each world ticks on its own thread (`TickThread.ServerLevelTickThread`), at most `threads` at once; the main thread is parked until every world is done, makes the journeys between worlds they left it, then runs connections, players and the plugin schedulers as before. A synchronous event of a world is therefore fired on that world's thread, for several worlds at the same time. `TickThread.isTickThreadFor(world, …)` answers "main thread, or the thread ticking this world". What a world thread may and may not do is listed [below](#parallel-world-ticking--rules-for-a-plugin). Not proven on a running server yet. Off = the vanilla loop, untouched. |

#### Parallel world ticking — rules for a plugin

All of this applies only with `parallel-world-ticking.enabled: true`; with it off nothing below runs.

| On the thread of a world | What happens |
|---|---|
| Blocks, entities, chunks of **another** world | Refused: logged with the stack, then `IllegalStateException` (`-Dbtccore.pwt.disable-hard-throw=true` keeps the log and drops the exception). Hop with `Bukkit.getRegionScheduler().execute(plugin, location, task)`. |
| `entity.teleport(location)` to another world, a vanilla portal, an ender pearl whose owner is in another world | Never thrown, never run there: the journey is queued and made by the main thread once every world has ticked — the same server tick — if the entity is still there and the target world is still loaded and not quarantined. `teleport` answers `true` without knowing, and says so once per call site in the log; **`teleportAsync` is the call to use**, its future completes with the real outcome. A journey made at once — from the main thread, a command, `teleportAsync` — into a world that is quarantined or left the server is refused the same way. A listener of `PlayerTeleportEvent`, `EntityTeleportEvent` or `EntityPortalExitEvent` that changes the destination to another world gets the same hand-over, before the entity leaves its world: the events of that teleport are then fired a second time, by the main thread. A `/tp` run by a command block of another world than the entity's or the target's is put off whole — the move, the facing and the stop of the entity — and announces its success before the move, as vanilla does whatever the outcome. While it flies, a pearl in another world than its owner's renews its chunk ticket from its own thread: the owner's set of pearls is a concurrent one, the pearl only reads whether its owner is alive, and the advancement of an end gateway it crosses is granted to the owner by the main thread at the end of the tick. |
| Opening a menu | A player that just entered a world (login, respawn, change of world) gets no menu until it has ticked there: the request is put off, one tick at a time. The container of a block of another world than the player's is not opened at all. A menu asked for a player of **another** world (`player.openInventory(…)`, `openWorkbench`, `openMerchant`…, or `ServerPlayer.openMenu`) is opened by the main thread once every world has ticked, if the player is still online: the call answers `null` without a view, and says so once per call site in the log. Ask from the player's own scheduler (`player.getScheduler()`) to get the view. A menu bound to a block of another world than the player's (crafting table, anvil, enchanting table, ender chest, lectern… opened through a location or a menu builder of that world) is closed at the player's next tick instead of reading that block from the player's thread; a menu opened with `force` checks no block and stays open. Vanilla already closes the open menu of a player who leaves a world. |
| `player.spigot().respawn()`, or any respawn asked on the thread of a world (a death listener that respawns at once) | A respawn takes the player out of a world, into another, and rewrites the player list: it is made by the main thread once every world has ticked, if the player is still dead and online. The call returns before the player has respawned. |
| Team and waypoint updates of the scoreboard | Changed under the scoreboard lock on the calling thread; the waypoints of the other worlds are refreshed by the main thread at the end of the tick. |
| Reading the objectives, the teams or the members of a team (`getObjectives()`, `getTeams()`, `team.getEntries()`; NMS `Scoreboard.getObjectives()`, `getObjectiveNames()`, `getTeamNames()`, `getPlayerTeams()`, `PlayerTeam.getPlayers()`) | A copy taken under the scoreboard lock, not the live view: it can be walked while another world changes the scoreboard, does not follow later changes, and cannot be written (NMS code that added to `team.getPlayers()` must call `addPlayerToTeam`). |
| Creating, loading, unloading or closing a world (`createWorld`, `unloadWorld`, `loadWorld` of the Slime API) | Refused with the fix in the message — and from any thread that is not the main one: run it from `Bukkit.getGlobalRegionScheduler().execute(plugin, task)`. |
| Saving a Slime world from the thread of another world (`saveWorld`, `world.save()`), `/save-all` of a command block | Made by the main thread once every world has ticked: the call returns before the save, `/save-all` announces it before it is made, and what the save throws is only logged (the future of `SlimeLevelInstance.save()` still carries it). A world unloaded meanwhile is not saved again. |
| Cloning a Slime world from the thread of another world | Refused, as above. Clone it from its own thread, the main one or an async thread. |
| **Waiting for another world** — `otherWorld.getChunkAtAsync(x, z).join()` (or `.get()`) | **Deadlock, and nothing can catch it**: a chunk that is not loaded yet is finished by that world's thread while it still ticks, otherwise by the main thread — which is parked until this world's tick ends, the very tick that is waiting. Never block on a future of another world from a listener: chain it (`thenAccept`) and hop back with the region scheduler, or use `teleportAsync`, which already does. The synchronous `getChunkAt` of another world is refused instead of waiting. |

### Performance — NMS hooks

Shipped as Paperweight feature patches (304 hunks over 127 files, as counted by
`scripts/check-btccore-patches.py`; the list is `.docs/hooks-inventory.md`), so they survive an upstream bump as patches
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
| Chunk prefetch | BTC | Pre-loads a 5×5 chunk grid on player teleport. |
| Per-world tick rate | BTC | Reduces tick frequency for empty worlds. The phase is hashed from the world name, so fifty empty islands do not all come due on the same tick. |
| Vanilla tick suppression | BTC | Disables AI / Brain / Sensors, per world. |
| Batched recipe finalisation | BTC | Finalises recipes in one pass and honours the resend flag instead of resending the whole table. |
| Per-entity tracking range | SparklyPaper (idea, rewritten) | `performance.per-entity-tracking-range.enabled`, **off by default**. `BTCCoreAPI#setEntityTrackingRange(entity, blocks)` (1 to 512) tracks one entity at its own range in place of its type's; still scaled by `entity-broadcast-range-percentage` and capped by the viewer's view distance. Read at each tracker tick: the entity is never removed and sent again. Not saved with the entity. Off = Paper's range, the API stores nothing. |
| AI sensor loops | Leaf (MIT) | `performance.ai-sensor-loops.enabled`, **off by default**. The player, tempting, mob and golem sensors scan with plain loops (`SensorScans`) in place of streams: same players kept, same stable order by distance, same first match, so no behaviour changes. Off = Mojang's streams. Not ported because they change an observable result: Leaf's goal selector (priority order in place of insertion order), brain collections, entity activation, and Petal's reduced sensor work. |
| Simple farm checks | SparklyPaper (idea, rewritten) | `performance.simple-farm-checks.enabled`, **off by default — changes gameplay**. A crop, stem or pitcher crop reads the one block under it: `moist-growth-speed` (5.0) on moist soil, `dry-growth-speed` (2.0) otherwise, whatever grows around it. Moist farmland no longer searches for water on its own tick; it is looked at when the crop on it ripens or the stem bears a fruit, and dries in one step if unwatered. Dry farmland is moistened when watered and falls back to dirt when bare. Same Bukkit events; Paper's `tick-rates.dry-farmland` still applies. Off = Paper's ticks. |
| Network lot | Paper PR 10172, Velocity (via Gale), SparklyPaper (idea), Purpur, Canvas — all as carried by Leaf | `performance.network.*`, one key per element, **all off by default**: `lazy-non-flush-packets` (a packet sent without flush does not wake the Netty thread), `fast-varint` (VarInts of 3 bytes and more in one or two buffer calls, same bytes), `skip-unchanged-motion` (no distance computed when the velocity object is the one last sent, same packets), `skip-empty-entity-moves` (the relative move that moves and turns nothing is dropped), `filter-entity-motion` (velocity sent only for an explicit change, a glider, a moving item and the `always-send` types — **visual risk**, check custom projectiles first). The packet object stays shared and each connection encodes it: no encoded bytes are reused. |

### Performance — dynamic and async

| Feature | Origin | What it does |
|---|---|---|
| DAB (Dynamic Activation of Brain) | Pufferfish | A 20-tick task enables/disables entity AI by player proximity. |
| Particle / sound / BetterHUD culling | BTC | Distance-based packet filtering. |
| Async mob spawning | Leaf | Delegated to Paper's per-player-mob-spawn system. |
| Parallel entity tracker | DivineMC (from Petal) | `async.parallel-tracker.enabled`, **off by default**. A pool works out, read-only, which entities gained or lost a viewer; the tick thread waits for it, then runs Paper's own tracker code for those entities and sends every packet itself. Off = Paper's tracker, untouched. |
| Async pathfinding | Petal, Kaiiju (via Leaf) | `async.async-pathfinding.enabled`, **off by default**. The tick thread prepares the request and applies the path; only the A* search runs on a pool (`threads`, 0 = a quarter of the cores), and a request replaced in the meantime is dropped. A full pool, a plugin reading a `PathResult`, or a caller that cannot wait gets the search done on the spot. Frogs, turtles, axolotls, drowned, sniffers and creakings keep the vanilla search, as does any mob whose class redefines `onPathfindingStart` or `onPathfindingDone`. Off = vanilla search on the calling thread; the only difference is that a walking mob's fall distance and step height are read once when the search starts instead of during it. |
| Async player data save | Nitori (via Leaf) | `async.player-data-save.enabled`, **off by default**. The saving thread encodes the player; `<uuid>.dat` is written on two worker threads, in order for each player, as a temporary file forced to disk before it replaces the last good one (kept as `.dat_old`), so a save cut short never leaves a truncated file. A queued save lands before that player is read again, before a `player-data-backup` copy of the same file, and before the server stops (30 s drain). Off = vanilla save on the calling thread, untouched. |
| Parallel world ticking | DivineMC (from SparklyPaper) | `parallel-world-ticking.enabled`, **off by default, experimental** — see [Threading](#threading--read-this-first). One thread per world behind a barrier (`threads` worlds at once, 0 = half the cores); the tick still lasts as long as the slowest world, so it only pays when several worlds are expensive at the same time. Unlike DivineMC the barrier waits for every world and reports every one that threw, mid-tick chunk tasks keep running, each world thread serving its own world, and a journey between two worlds is made by the main thread after the barrier instead of on a world thread. Read once at startup. |
| World tick events | SparklyPaper (idea, rewritten) | `WorldTickStartEvent` / `WorldTickEndEvent` (`dev.btc.core.event.world`), fired around each world's tick on the thread that ticks it, whether parallel world ticking is on or off; nothing is created when nobody listens. |
| One player-file read per connection | BTC | Vanilla reads `<uuid>.dat` twice while a player connects (spawn position, then the player itself). The second read is skipped while the file is still the one read first (same modification time, size and file key); a file saved in between, a `.dat_old` fallback or an offline-UUID file is read again as before. No option: what is read and written is unchanged. |
| Vanilla statistics in PostgreSQL | BTC | `stats-sql.enabled`, **off by default** (restart to change). One JSON document per player in `<prefix>player_stats`, shared by the servers of a network, so a statistic follows the player from one server to the next. A player's row is held under a lease with a fencing token: the login waits for the previous server to let go (`handoff-wait-ms`, then takes the row over or stays on its file), and a server that lost its lease can no longer write the row. The database is only reached from the layer's own threads: read while the spawn is prepared (the player comes in on its local file after `load-timeout-ms`), written in batches (latest document of each player), flushed ahead of the batch when the player leaves, drained when the server stops. Database down: statistics go to `stats/<uuid>.json` with a `.pending` marker and are merged back, highest value of each counter, once it answers (a counter reset during the outage comes back). Credentials come from `BTC_STATS_DB_HOST/PORT/NAME/USER/PASSWORD` through `${env:NAME}`; an unset variable keeps the layer closed and is named in the log, a value never is. `/btccore stats-sql status` and `migrate [--merge]` (imports the `.json` files, which are only read). Not wired for offline players: Bukkit `OfflinePlayer` statistics read the local `.json` and write it with a marker, merged at that player's next login on that server. Off, or `zero-features.stats: true` = vanilla files, untouched. |

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
| World quarantine | BTC | `world.quarantine.enabled`, **off by default** (restart to change). A Slime world whose tick throws no longer stops the server: the other worlds finish their tick, the world gets its own crash report (`crash-reports/crash-<date>-world-<uid>.txt`), stops ticking, has its players moved to the default world (or kicked) and is unloaded — after one save (`save-before-unload`, on by default), never after an `Error`. The server still stops for the default world, a world that is not a Slime world, an out-of-memory or internal JVM error, more than `max-per-minute` quarantines in a minute, or a world that cannot be emptied or unloaded. A teleport into a world that is quarantined, or that left the server, is refused and logged (`teleport` answers `false`). Works with parallel world ticking on or off. Off = the first world that throws stops the server, as in vanilla. |
| World metrics | BTC | `world.metrics.enabled`, **off by default** (restart to change). On: each world times its own `level.tick` — average and worst of the last 600 ticks — and notes the chunks, entities and players it held at its last tick, without allocating; `/btccore worlds [count]` lists the costliest worlds first (`world.metrics.top`, 50 at most), with replies set in `world.metrics.messages`. Works with parallel world ticking on or off: each world is written by the thread that ticks it. Off = nothing is measured, one null check per world tick. |
| World leak diagnostic | BTC | `world.leak-diagnostic.enabled`, **off by default**. On: a world that leaves the server is followed through a weak reference, which keeps nothing alive; `check-after-seconds` (300) later, if it is still in memory, one warning is logged for it — once per world — with its age and the garbage collections run since its unload. `/btccore leaks` lists every unloaded world still in memory, with replies set in `world.leak-diagnostic.messages`. Still in memory is not a proven leak: no collection is forced, the collector may not have got to it; a heap dump tells what holds it. At most 512 unloaded worlds are followed. Off = nothing is followed. |
| Game rules | BTC | Rules are applied with the owning `ServerLevel`, so callbacks like `locator_bar` actually fire. Passing `null` writes the value but leaves cached rules on their old setting. |
| Controllable minecarts | Purpur | WASD control, configurable speed. |
| Elytra kinetic damage toggle | Purpur | — |
| No item cooldown in creative | Purpur | — |
| Sentinel anticheat *(in progress, observation mode)* | BTC | Async reach/velocity validation, ghost hitbox caching, optional MySQL logging. |
| FreedomChat | Port | Chat reporting prevention. |
| CPS limiting, combat log, reach validation, exploit logging | BTC | — |
| Maintenance mode, join queue, teleport warmup, vanish levels, player data backup | BTC | — |

> **Slime worlds — three changes that are not behind an option** (they fix defects that existed
> without parallel world ticking). A chunk that loads takes its own copy of the light of the stored
> chunk instead of sharing it; its extra data is one concurrent map, the same for the stored chunk
> and the loaded one, so that what a plugin writes there at any moment (CraftEngine through FAWE,
> while the chunk loads) is kept and never met half-written. `SlimeWorld.clone()` of a loaded world
> copies what a save would write, on the thread that owns the world (the main thread when called
> from an async one): the clone carries the world's current persistent data and none of the chunks a
> save prunes, and it is refused off the main thread while the server stops — a clone already
> waiting for the main thread when the stop begins gives up within a second instead of waiting for
> good, and one the main thread has not served after ten seconds is logged.
> `SlimeWorld.getChunkStorage()` returns a detached copy, no longer the live table. Also not behind
> an option: a Slime world whose close throws at unload still gives its lease back, and with
> `world.quarantine` on a refused save is an `IOException`, as the API declares.

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
| `/btccore stats-sql status` | btccore.admin + btccore.admin.stats-sql | State of the SQL statistics layer (never its credentials) |
| `/btccore stats-sql migrate [--merge]` | btccore.admin + btccore.admin.stats-sql | Import the local statistics files into PostgreSQL; one migration at a time |
| `/btccore worlds [count]` | btccore.admin | What each world costs, the costliest first (`world.metrics`) |
| `/btccore leaks` | btccore.admin | Unloaded worlds still in memory (`world.leak-diagnostic`) |
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