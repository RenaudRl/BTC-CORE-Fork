# NOTICE — BTC-CORE

BTC-CORE is a fork of [AdvancedSlimePaper](https://github.com/InfernalSuite/AdvancedSlimePaper),
itself a fork of [Paper](https://github.com/PaperMC/Paper). It is distributed under the
**GNU General Public License v3.0** — see [LICENSE](LICENSE).

This file exists to satisfy GPLv3 §5(a) and §5(b): it states who modified this software, what was
modified, and which notices must be preserved when it is redistributed.

---

## 1. Copyright

| Scope | Holder |
|---|---|
| Modifications and original code introduced by this fork | **Copyright © 2026 Born To Craft Studio** |
| AdvancedSlimePaper base | Copyright © InfernalSuite and contributors |
| Paper base | Copyright © PaperMC and contributors |
| Patches ported from Purpur, Leaf, Pufferfish, DivineMC, Petal, Kaiiju | Copyright © their respective authors — all GPL-3.0, credited in the header of each ported file and patch |
| Decompiled Minecraft sources (`aspaper-server/src/minecraft/`) | Copyright © Mojang AB — **not** covered by the GPL, governed by the [Minecraft EULA](https://www.minecraft.net/eula). Never redistributed by this repository. |

Original code introduced by Born To Craft Studio lives under:

- `api/src/main/java/dev/btc/core/`
- `aspaper-server/src/main/java/dev/btc/core/`
- `plugin/src/main/java/dev/btc/core/`
- `aspaper-server/minecraft-patches/features/000{3,4,5}-*.patch`
- `aspaper-server/paper-patches/features/0008-*.patch`
- `aspaper-server/minecraft-patches/features/0011-*.patch` (all but its `PlayerDataStorage` hunks,
  listed below) and `aspaper-server/minecraft-patches/features/0012-*.patch`

Derived from other forks, with their notices kept:

- `aspaper-server/src/main/java/dev/btc/core/async/ParallelTracker.java` and
  `aspaper-server/minecraft-patches/features/0009-*.patch` — DivineMC "Parallel entity tracker"
  (NONPLAYT, BX Team, GPL-3.0), itself derived from Petal's multithreaded tracker (peaches94,
  GPL-3.0).
- `aspaper-server/src/main/java/dev/btc/core/async/path/` (all but `PathfindingCallbacks.java`, a BTC
  addition), `aspaper-server/minecraft-patches/features/0010-*.patch` and its API hook
  `aspaper-server/paper-patches/features/0010-*.patch` — Petal "Async Pathfinding" (peaches94,
  GPL-3.0-only), fixed and updated by Kaiiju (GPL-3.0-only), as carried by Leaf (HaHaWTH,
  Taiyou06, Altiami, MrlingXD, hayanesuru; GPL-3.0).
- `aspaper-server/src/main/java/dev/btc/core/playerdata/PlayerFileWrite.java` and the
  `PlayerDataStorage` hunks of `aspaper-server/minecraft-patches/features/0011-*.patch` — Nitori
  "Async playerdata saving" (Gensokyo Reimagined, GPL-3.0-only), as carried by Leaf (Dreeam;
  GPL-3.0). Leaf's async `level.dat` save is not ported.
- `aspaper-server/src/main/java/dev/btc/core/parallel/` (`WorldTickCoordinator`, `WorldOwnership`,
  `MidTickBudget`, `WorldArrival`), `aspaper-server/src/main/java/dev/btc/core/world/WorldTickLoop.java`,
  `aspaper-server/minecraft-patches/features/0013-*.patch` and
  `aspaper-server/paper-patches/features/0011-*.patch` — DivineMC "Parallel world ticking"
  (NONPLAYT, BX Team, GPL-3.0), itself derived from SparklyPaper (SparklyPower). The mid-tick
  back-off figures are Moonrise's (Spottedleaf, GPL-3.0), already part of the Paper base.
  The per-world guards of those two patches (blocks, entities, chunks, waypoints, `CraftWorld`,
  `CraftBlock`, `CraftBlockState`) come from the same DivineMC patch, as do the redstone turbo of
  each world, the synchronised map index and the concurrent `progressChanged` of the advancements;
  the lock on entity activation and the watchdog dump of the world threads are Leaf's
  (hayanesuru, GPL-3.0). The lock on the scoreboard shared by every world is a BTC addition, as are the
  copies it hands out in place of its live views (`SharedView`).
  `HopperFlags` (hopper flags per world) and
  `ThreadOverride` (tree type, sculk source block, redstone `shouldSignal`, ignored block entity
  updates, one per thread) are ideas of SparklyPaper, rewritten for the reason given below.
  `WorldQuarantine` and
  `WorldTickSteps`, in the same package, the main-thread-only guard on closing a world and the
  refusal to wait for a chunk of another world are BTC additions.
  `WorldArrival` (no menu before a player's first tick in a world) and the hand-over of portals and
  ender pearls come from the same DivineMC patch: BTC-CORE puts the menu off where DivineMC drops
  it, and hands every journey between two worlds to the main thread (`CrossWorldQueue`, a BTC
  addition) where DivineMC uses the entity scheduler. The refusal to open the container of another
  world is an idea of SparklyPaper, rewritten. `ChunkStore` and `WorldLifecycleGate` are BTC
  additions, as is the hand-over to the main thread of a menu asked for a player of another world.
  The concurrent set of the pearls of a player and the gateway advancement of a pearl granted by
  the main thread are BTC additions: neither DivineMC nor SparklyPaper guards a flying pearl.
- `aspaper-server/src/main/java/dev/btc/core/event/world/` — `WorldTickStartEvent` and
  `WorldTickEndEvent` are an idea of SparklyPaper (SparklyPower), rewritten: SparklyPaper carries no
  licence file, so none of its text is copied.
- `aspaper-server/src/main/java/dev/btc/core/performance/TrackingRangeOverride.java` and
  `aspaper-server/minecraft-patches/features/0014-*.patch` — the per-entity tracking range is an idea
  of SparklyPaper ("Add per-entity tracking range overrides", MrPowerGamerBR, SparklyPower),
  rewritten for the same reason; the entity is not removed from the tracker and added again.

## 2. Statement of modifications

Required by GPLv3 §5(a). A summary; the authoritative record is the git history of this repository.

| Area | Modification |
|---|---|
| NMS hooks | 290 hunks over 117 files (`scripts/check-btccore-patches.py`) shipped as Paperweight feature patches: hopper / collision / light / redstone throttles, redstone compiler, projectile limits and pooling, batched inventory updates, chunk prefetch, per-world tick rate, vanilla tick suppression, batched recipe finalisation. |
| Zero Features | Vanilla recipes, advancements and stats loading suppressed; per-world switches for light engine, void generator, collisions, cramming, block updates and sleep tick. |
| BTC APIs | Visual API (packet display entities, virtual inventories), island catch-up API, BlockValueCache, `PreDamageCalculationEvent`, `EntityTargetPlayerEvent`, `WorldTickStartEvent` / `WorldTickEndEvent`, build-identity contract check. |
| Runtime plugin | `ASPaperPlugin` renamed `BTCCore`; the separate `BTCBridge` plugin folded into it; ships as `btccore-plugin`. |
| Game rules | Rules applied with the owning `ServerLevel` so change callbacks fire. |
| World quarantine | Opt-in: a Slime world whose tick throws is unloaded (crash report, players moved out, one save) instead of stopping the server. |
| World diagnostics | Opt-in: the tick cost of each world over a window (`/btccore worlds`), and the unloaded worlds still in memory (`/btccore leaks`). |
| Security | Sentinel anticheat, CPS limiting, combat log, reach validation, exploit logging. |
| Ports | Suffocation optimisation and inactive-goal throttle (Pufferfish), async mob spawning (Leaf), parallel entity tracker narrowed to a read-only scan (DivineMC), async pathfinding with the path applied on the tick thread (Petal, Kaiiju, via Leaf), async player data save with per-player ordering and a forced-to-disk temporary file (Nitori, via Leaf), parallel world ticking — scheduling, thread ownership, per-world guards, the statics shared between worlds, the scoreboard lock, journeys between worlds handed to the main thread and the Slime world storage, with a barrier that reports every failing world (DivineMC, from SparklyPaper), controllable minecarts and gameplay toggles (Purpur). |

Removed from the upstream base: a silk-touch-spawner patch that targeted datagen
(`VanillaBlockLoot.java`) and therefore never executed at server boot.

## 3. What the GPL does and does not allow

Stated plainly, because it is often misread in both directions.

**You may**, under GPLv3:

- run BTC-CORE for any purpose, including commercially;
- study and modify it;
- redistribute it, modified or not, **including for a fee**;
- run a paid Minecraft server with it.

**You must**, when you redistribute it, modified or not:

- license the whole work under GPLv3 and provide the **complete corresponding source**;
- preserve every copyright notice, this NOTICE file, and the LICENSE file;
- **state prominently that you modified it, and on what date** (§5(a));
- keep the attribution to Born To Craft Studio, InfernalSuite and PaperMC intact.

**You may not**:

- relicense this work, or any derivative, under terms more restrictive than GPLv3 — including a
  "no resale" clause. GPLv3 does not permit it, and neither do we;
- distribute it as closed source;
- strip or rewrite the attribution above and present this work as your own. That is not merely
  impolite — it is a licence violation, and it terminates your rights under §8;
- use the Born To Craft Studio names or marks on a redistribution (see §4).

## 4. Names and marks — reserved

**"Born To Craft", "Born To Craft Studio", "BTC Studio", "BTC-CORE", "BTCCore", "BTCVelocity",
"BTCSky"**, the associated logos, and the `borntocraftstudio.net` domain are marks of Born To Craft
Studio. **They are not licensed under the GPL** — copyright licences do not grant trademark rights,
and this one grants none.

Concretely: you are free to fork this code and even to sell your fork, but you must **rebrand it**.
You may state factually that your work is derived from BTC-CORE. You may not name it BTC-CORE,
publish it under our marks, or present it in a way that suggests it comes from or is endorsed by
Born To Craft Studio.

## 5. Third-party components

Dependencies retain their own licences (Adventure, Netty, HikariCP, Lettuce, Cloud, Log4j, and the
others declared in `gradle/libs.versions.toml` / the module build files). Nothing in this NOTICE
alters them.

## 6. Reporting a licence violation

Open an issue at <https://github.com/RenaudRl/BTC-CORE-Fork/issues> or contact Born To Craft Studio
through <https://borntocraftstudio.net>.
