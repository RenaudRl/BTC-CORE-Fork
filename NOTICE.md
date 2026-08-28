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
| Patches ported from Purpur, Leaf, Pufferfish, Canvas | Copyright © their respective authors |
| Decompiled Minecraft sources (`aspaper-server/src/minecraft/`) | Copyright © Mojang AB — **not** covered by the GPL, governed by the [Minecraft EULA](https://www.minecraft.net/eula). Never redistributed by this repository. |

Original code introduced by Born To Craft Studio lives under:

- `api/src/main/java/dev/btc/core/`
- `aspaper-server/src/main/java/dev/btc/core/`
- `plugin/src/main/java/dev/btc/core/`
- `aspaper-server/minecraft-patches/features/000{3,4,5}-*.patch`
- `aspaper-server/paper-patches/features/0008-*.patch`

## 2. Statement of modifications

Required by GPLv3 §5(a). A summary; the authoritative record is the git history of this repository.

| Area | Modification |
|---|---|
| NMS performance hooks | 57 hunks shipped as Paperweight feature patches: hopper / collision / light / redstone throttles, redstone compiler, projectile limits and pooling, batched inventory updates, NBT compression cache, scoreboard filtering, chunk prefetch, per-world tick rate, vanilla tick suppression, batched recipe finalisation. |
| Zero Features | Vanilla recipes, advancements and stats loading suppressed; per-world switches for light engine, void generator, collisions, cramming, block updates and sleep tick. |
| BTC APIs | Visual API (packet display entities, virtual inventories), island catch-up API, BlockValueCache, `PreDamageCalculationEvent`, `EntityTargetPlayerEvent`, build-identity contract check. |
| Runtime plugin | `ASPaperPlugin` renamed `BTCCore`; the separate `BTCBridge` plugin folded into it; ships as `btccore-plugin`. |
| Game rules | Rules applied with the owning `ServerLevel` so change callbacks fire. |
| Security | Sentinel anticheat, CPS limiting, combat log, reach validation, exploit logging. |
| Ports | Suffocation optimisation and inactive-goal throttle (Pufferfish), async entity tracker / pathfinding / mob spawning (Leaf), controllable minecarts and gameplay toggles (Purpur), priority loading and Moonrise executor (Canvas). |

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
