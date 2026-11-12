# Inventaire des hooks BTC-CORE

> Genere par `scripts/check-btccore-patches.py --markdown`. Ne pas editer a la main.

**333 hooks** repartis sur 142 fichiers.

Les hooks sont des patches Paperweight appliques par `git am`. Un hook qui ne s'applique plus fait echouer le build ; il n'y a plus de no-op silencieux.

## `aspaper-server/minecraft-patches/features/0003-BTC-CORE-hooks.patch`

| Fichier | Hunks | Options btccore.yml |
|---|---|---|
| `net/minecraft/network/protocol/game/ClientboundSetPassengersPacket.java` | 1 | — |
| `net/minecraft/network/protocol/game/ClientboundUpdateAttributesPacket.java` | 1 | server-driven digging: the client view, when set, is what the client gets |
| `net/minecraft/server/Main.java` | 1 | must be read before WorldLoader.load, which reads advancements and recipes |
| `net/minecraft/server/ReloadableServerRegistries.java` | 1 | vanilla-content.purge-loot |
| `net/minecraft/server/ServerAdvancementManager.java` | 1 | zero-features.advancements / vanilla-content.purge-advancements |
| `net/minecraft/server/level/ServerChunkCache.java` | 1 | zero-features.force-void-generator |
| `net/minecraft/server/level/ServerLevel.java` | 6 | redstone compiler |
| `net/minecraft/server/level/ServerPlayer.java` | 3 | batched inventory updates: drain this player's coalesced slot updates on, server-driven digging: a death ends the mode and its dig, server-driven digging: erase the crack the others see; no view to restore |
| `net/minecraft/server/level/ServerPlayerGameMode.java` | 12 | server-driven digging: the mode and the dig the server measures, per player,, server-driven digging: creative and spectator leave the mode, server-driven digging: enter, keep or leave the mode, server-driven digging, click limit: the server, not the swings, keeps the dig window open, server-driven digging: the server measures and finishes this dig, click limit: a START on a solid block in range opens the dig window, even for a block that breaks at once, server-driven digging: the decision of this START, set by the hook below, break speed: decide the speed for this exact block before the, server-driven digging: no dig starts, forget this START's decision, server-driven digging: a client in the mode predicted nothing,, server-driven digging: in the mode, the server measures every dig, server-driven digging: the server finishes the digs it measures; a, server-driven digging: erase the digger's own crack too, server-driven digging: a world change ends the mode, server-driven digging (design 6.G.10). Everything below runs on the thread that |
| `net/minecraft/server/level/ThreadedLevelLightEngine.java` | 1 | — |
| `net/minecraft/server/network/ServerCommonPacketListenerImpl.java` | 1 | batched inventory updates |
| `net/minecraft/server/network/ServerGamePacketListenerImpl.java` | 3 | server-driven digging: the client punches at every tick it digs; that is the, the swings of a dig are counted against the mining ceiling (a digging client sends one per tick, the normal ceiling), FreedomChat: deliver every player message as a disguised (unsigned) one,, FreedomChat: never register a chat session, so nothing the player says can be |
| `net/minecraft/server/players/PlayerList.java` | 1 | FreedomChat |
| `net/minecraft/stats/ServerRecipeBook.java` | 1 | a purge of our own is the expected reason for a stale entry, not an error. |
| `net/minecraft/stats/ServerStatsCounter.java` | 3 | zero-features.stats, zero-features.stats: the Bukkit statistics API (CraftStatistic) passes no player, counted as vanilla then |
| `net/minecraft/world/entity/LivingEntity.java` | 5 | PreDamageCalculationEvent, Purpur, collision throttle: pushable entities found by this entity's last completed, zero-features.collisions, collision throttle. Placed after the cheap early-outs, so only entities, collision throttle: record the real local density |
| `net/minecraft/world/entity/Mob.java` | 5 | performance.inactive-goal-selector-throttle, EntityTargetPlayerEvent |
| `net/minecraft/world/entity/ai/Brain.java` | 1 | — |
| `net/minecraft/world/entity/ai/attributes/AttributeInstance.java` | 1 | server-driven digging: a value shown to the client in place of the real one. |
| `net/minecraft/world/entity/ai/sensing/Sensor.java` | 1 | — |
| `net/minecraft/world/entity/player/Player.java` | 3 | one source for vanilla and the server-driven dig, server-driven digging: the part of the dig speed that does not depend on the tool, passed in by the caller |
| `net/minecraft/world/entity/projectile/throwableitemprojectile/ThrownEnderpearl.java` | 1 | refusing only skips the ticket, never removes the pearl |
| `net/minecraft/world/entity/vehicle/minecart/AbstractMinecart.java` | 1 | Purpur: per-entity override wins |
| `net/minecraft/world/item/ServerItemCooldowns.java` | 1 | Purpur: no item cooldown in creative |
| `net/minecraft/world/item/crafting/RecipeManager.java` | 1 | zero-features.recipes / vanilla-content.purge-recipes |
| `net/minecraft/world/level/Level.java` | 1 | collision throttle lives in LivingEntity#pushEntities, the sole caller: only |
| `net/minecraft/world/level/SignalGetter.java` | 3 | — |
| `net/minecraft/world/level/block/CartographyTableBlock.java` | 1 | workstations.block-cartography-table |
| `net/minecraft/world/level/block/ChangeOverTimeBlock.java` | 1 | copper_fade game rule: scales the oxidation odds from 0 to 100. |
| `net/minecraft/world/level/block/ComposterBlock.java` | 1 | workstations.block-composter |
| `net/minecraft/world/level/block/GrindstoneBlock.java` | 1 | workstations.block-grindstone |
| `net/minecraft/world/level/block/LoomBlock.java` | 1 | workstations.block-loom |
| `net/minecraft/world/level/block/entity/HopperBlockEntity.java` | 1 | hopper throttle. 26.2 narrowed the parameter to Level, which has no getServer(); |
| `net/minecraft/world/level/gamerules/GameRules.java` | 1 | copper oxidation rate, 100 = vanilla, 0 = never oxidises |
| `net/minecraft/world/level/material/FlowingFluid.java` | 1 | zero-features.block-updates |
| `net/minecraft/world/level/redstone/NeighborUpdater.java` | 2 | zero-features.block-updates, redstone compiler: the only funnel every neighbour update reaches. Hooking, redstone benchmark: this is where ALTERNATE_CURRENT spends its time. |
| `net/minecraft/world/level/storage/loot/LootTable.java` | 1 | drop API |

## `aspaper-server/minecraft-patches/features/0009-BTC-CORE-hooks-parallel-entity-tracker.patch`

| Fichier | Hunks | Options btccore.yml |
|---|---|---|
| `net/minecraft/server/MinecraftServer.java` | 1 | async.parallel-tracker |
| `net/minecraft/server/level/ChunkMap.java` | 3 | async.parallel-tracker, async.parallel-tracker - the test moved below, unchanged, so the read-only scan shares it |

## `aspaper-server/minecraft-patches/features/0010-BTC-CORE-hooks-async-pathfinding.patch`

| Fichier | Hunks | Options btccore.yml |
|---|---|---|
| `net/minecraft/server/MinecraftServer.java` | 1 | async.async-pathfinding |
| `net/minecraft/world/entity/ai/behavior/MoveToTargetSink.java` | 5 | async.async-pathfinding - whether the requested path has been read, async.async-pathfinding - reachability is only known once the search is back, async.async-pathfinding, async.async-pathfinding - wait for the search, async.async-pathfinding - request the path, tick() reads it once searched, async.async-pathfinding - the vanilla flow split around the search (Kaiiju/Petal) |
| `net/minecraft/world/entity/ai/goal/DoorInteractGoal.java` | 1 | async.async-pathfinding - not while it is still searched |
| `net/minecraft/world/entity/ai/navigation/FlyingPathNavigation.java` | 2 | async.async-pathfinding - null while the option is off, async.async-pathfinding - wait for the search |
| `net/minecraft/world/entity/ai/navigation/GroundPathNavigation.java` | 1 | async.async-pathfinding - null while the option is off |
| `net/minecraft/world/entity/ai/navigation/PathNavigation.java` | 6 | async.async-pathfinding - the same positions are already being searched: keep that request, async.async-pathfinding, async.async-pathfinding - trim once the search is applied, async.async-pathfinding - nothing to trim yet, async.async-pathfinding - not known yet, async.async-pathfinding - wait for the search; this is also where its result gets applied, async.async-pathfinding - nothing to recompute while it is still searched |
| `net/minecraft/world/entity/ai/navigation/WaterBoundPathNavigation.java` | 1 | async.async-pathfinding - null while the option is off |
| `net/minecraft/world/entity/animal/bee/Bee.java` | 2 | async.async-pathfinding - do not judge the hive unreachable on a path that is still searched, async.async-pathfinding, async.async-pathfinding - not while it is still searched |
| `net/minecraft/world/entity/animal/rabbit/Rabbit.java` | 3 | async.async-pathfinding - not while it is still searched, async.async-pathfinding - no movement decision on a path that is still searched, async.async-pathfinding |
| `net/minecraft/world/entity/monster/Strider.java` | 1 | async.async-pathfinding - null while the option is off |
| `net/minecraft/world/entity/monster/warden/Warden.java` | 1 | async.async-pathfinding - null while the option is off |
| `net/minecraft/world/entity/monster/zombie/Drowned.java` | 1 | async.async-pathfinding - not while it is still searched |
| `net/minecraft/world/level/PathNavigationRegion.java` | 1 | async.async-pathfinding, async.async-pathfinding - never the chunk's block entity maps off the tick thread |
| `net/minecraft/world/level/pathfinder/NodeEvaluator.java` | 1 | async.async-pathfinding |
| `net/minecraft/world/level/pathfinder/Path.java` | 6 | async.async-pathfinding - AsyncPath extends it, async.async-pathfinding - filled in once the search is applied, async.async-pathfinding, async.async-pathfinding - an unprocessed path only equals itself, async.async-pathfinding - AsyncPath keeps the debug data of its result, async.async-pathfinding - resolves an AsyncPath before its nodes and target are read |
| `net/minecraft/world/level/pathfinder/PathFinder.java` | 6 | async.async-pathfinding - null = always search on the calling thread, async.async-pathfinding, async.async-pathfinding - the request is prepared here, on the entity's thread, with an evaluator of its own, async.async-pathfinding - only the A* below leaves this thread; its result is applied back here, async.async-pathfinding - same search, on the heap, neighbours and evaluator it is handed |
| `net/minecraft/world/level/pathfinder/PathfindingContext.java` | 1 | async.async-pathfinding - dropped before a search leaves the tick thread |
| `net/minecraft/world/level/pathfinder/SwimNodeEvaluator.java` | 1 | async.async-pathfinding - part of the pooled evaluator's key |
| `net/minecraft/world/level/pathfinder/WalkNodeEvaluator.java` | 4 | async.async-pathfinding - read once on the tick thread, async.async-pathfinding |

## `aspaper-server/minecraft-patches/features/0011-BTC-CORE-hooks-player-data.patch`

| Fichier | Hunks | Options btccore.yml |
|---|---|---|
| `net/minecraft/server/MinecraftServer.java` | 1 | async.player-data-save, qol.player-data-backup: queued player saves and copies land before the level is closed |
| `net/minecraft/server/network/config/PrepareSpawnTask.java` | 3 | one .dat read per connection, one .dat read per connection: kept for the spawn, one .dat read per connection: the tag read at start() is the file's content while the file has not changed |
| `net/minecraft/server/players/PlayerList.java` | 1 | one .dat read per connection |
| `net/minecraft/world/level/storage/PlayerDataStorage.java` | 2 | async.player-data-save: the player is encoded here, the file is written off this thread, in order for this player (Nitori, via Leaf), async.player-data-save, one .dat read per connection, async.player-data-save: a save queued since must show on the file before it is compared, async.player-data-save: before the stamp, so the stamp is of the file that gets read, async.player-data-save: a save still queued for this player lands before the file is read |

## `aspaper-server/minecraft-patches/features/0012-BTC-CORE-hooks-stats-sql.patch`

| Fichier | Hunks | Options btccore.yml |
|---|---|---|
| `net/minecraft/server/MinecraftServer.java` | 1 | stats-sql: queued statistics are stored and the leases given back, or kept in their files past the timeout |
| `net/minecraft/server/dedicated/DedicatedServer.java` | 1 | stats-sql: no-op unless stats-sql.enabled |
| `net/minecraft/server/network/config/PrepareSpawnTask.java` | 5 | stats-sql: null while the layer is closed, stats-sql, stats-sql: the statistics are read off this thread while the spawn is prepared, stats-sql: the player waits for its statistics, at most load-timeout-ms; never blocks, stats-sql: the connection closed before the player was in the game, its lease is given back, stats-sql: here and not in the counter's constructor, a player made for a legacy login event exists before the statistics were asked for |
| `net/minecraft/server/players/PlayerList.java` | 2 | stats-sql: after the last save, the snapshot is stored ahead of the batch and the lease given back, stats-sql: private -> public, the login asks for the file before the player exists |
| `net/minecraft/stats/ServerStatsCounter.java` | 2 | stats-sql: the player comes in with what its login loaded from the database, stats-sql, stats-sql: a session of the layer hands its document over; any other counter marks its file before writing it, stats-sql: only a changed counter is handed over |

## `aspaper-server/minecraft-patches/features/0013-BTC-CORE-hooks-parallel-world-ticking.patch`

| Fichier | Hunks | Options btccore.yml |
|---|---|---|
| `ca/spottedleaf/moonrise/patches/chunk_system/scheduling/ChunkHolderManager.java` | 4 | parallel-world-ticking: thrown whatever the hard-throw setting, a world closed mid-tick is not a warning, parallel-world-ticking |
| `io/papermc/paper/entity/activation/ActivationRange.java` | 1 | parallel-world-ticking |
| `io/papermc/paper/redstone/RedstoneWireTurbo.java` | 1 | parallel-world-ticking |
| `net/minecraft/core/dispenser/DispenseItemBehavior.java` | 1 | parallel-world-ticking |
| `net/minecraft/server/MinecraftServer.java` | 6 | parallel-world-ticking: a world thread runs the chunk tasks of its own world, with its own back-off, parallel-world-ticking: asked on the thread of a world (/save-all of a command block), every world and every player is saved by the main thread once the worlds have ticked, parallel-world-ticking, parallel-world-ticking, world.quarantine: the worlds tick behind the coordinator; both off = the loop below, untouched, world tick events, parallel-world-ticking: its tick thread stops with it |
| `net/minecraft/server/PlayerAdvancements.java` | 2 | parallel-world-ticking |
| `net/minecraft/server/ServerScoreboard.java` | 10 | parallel-world-ticking, parallel-world-ticking: the player may be in another world than the one this thread ticks, parallel-world-ticking: the waypoints of every world, asked on the thread of one |
| `net/minecraft/server/commands/TeleportCommand.java` | 1 | parallel-world-ticking: run by a command block of another world than the entity's or the target's, the whole teleport - with the look-at and the stop that follow it - is made by the main thread once every world has ticked, parallel-world-ticking |
| `net/minecraft/server/level/ServerChunkCache.java` | 1 | parallel-world-ticking |
| `net/minecraft/server/level/ServerLevel.java` | 4 | parallel-world-ticking, world.metrics: what this world costs, null with the option off, parallel-world-ticking: asked by the thread of another world (world.save() of a plugin), the main thread saves it once every world has ticked |
| `net/minecraft/server/level/ServerPlayer.java` | 11 | parallel-world-ticking: no menu before the first tick in a world, parallel-world-ticking: a pearl in another world registers itself from the thread of that world, parallel-world-ticking, parallel-world-ticking: a journey to a world this thread does not tick is made by the main thread, after the barrier, parallel-world-ticking: told once, parallel-world-ticking: the event is fired once, parallel-world-ticking: a listener changed the destination to a world this thread does not tick - before the player leaves its world, the journey is handed to the main thread, which does not fire the event again, parallel-world-ticking: asked by the thread of another world, the main thread opens it once every world has ticked, parallel-world-ticking: asked before the player ticked in the world it just entered, the menu may belong to the one it left, parallel-world-ticking: asked by the thread of another world, the main thread closes it once every world has ticked |
| `net/minecraft/server/players/PlayerList.java` | 2 | parallel-world-ticking: a respawn takes the player out of a world and into another, and rewrites the player list: asked by a world thread, the main thread makes it once every world has ticked, parallel-world-ticking, parallel-world-ticking and world.quarantine: no respawn into a world that is quarantined or left the server - a bed there, a listener that chose it - but at the spawn of the server, or in the overworld when the world of that spawn is refused too, parallel-world-ticking: the same player object comes back, in a world it has not ticked in |
| `net/minecraft/server/waypoints/ServerWaypointManager.java` | 8 | parallel-world-ticking |
| `net/minecraft/world/Container.java` | 1 | parallel-world-ticking: the block entity of another world is not read from the player's thread, the menu closes |
| `net/minecraft/world/entity/Entity.java` | 5 | parallel-world-ticking: finding where a portal leads reads, and may build in, another world, parallel-world-ticking, parallel-world-ticking: the end of handlePortal, on the main thread once every world has ticked, parallel-world-ticking: a journey to a world this thread does not tick is made by the main thread, after the barrier, parallel-world-ticking: the events are fired once, parallel-world-ticking: a listener of the events above changed the destination to a world this thread does not tick - before the entity leaves its world, the journey is handed to the main thread, which does not fire them again |
| `net/minecraft/world/entity/projectile/throwableitemprojectile/ThrownEnderpearl.java` | 2 | parallel-world-ticking: the owner is in another world, and the hit reads it, moves it here and hurts it, parallel-world-ticking, parallel-world-ticking: the owner is in another world, its advancement is granted by the main thread once every world has ticked |
| `net/minecraft/world/inventory/AbstractContainerMenu.java` | 1 | parallel-world-ticking: the block of another world is not read from the player's thread, the menu closes |
| `net/minecraft/world/inventory/ItemCombinerMenu.java` | 1 | parallel-world-ticking: the block of another world is not read from the player's thread, the menu closes |
| `net/minecraft/world/item/ItemStack.java` | 1 | parallel-world-ticking |
| `net/minecraft/world/item/trading/Merchant.java` | 1 | parallel-world-ticking: asked by the thread of another world, the main thread opens the menu and sends the offers once every world has ticked - a menu put off alone answers with no id, and the offers would never be sent |
| `net/minecraft/world/level/Level.java` | 5 | parallel-world-ticking |
| `net/minecraft/world/level/block/MushroomBlock.java` | 1 | parallel-world-ticking |
| `net/minecraft/world/level/block/NetherFungusBlock.java` | 1 | parallel-world-ticking |
| `net/minecraft/world/level/block/RedstoneWireBlock.java` | 6 | parallel-world-ticking |
| `net/minecraft/world/level/block/SaplingBlock.java` | 2 | parallel-world-ticking |
| `net/minecraft/world/level/block/entity/BaseContainerBlockEntity.java` | 1 | parallel-world-ticking: the thread of the player's world would run a container that is another world's |
| `net/minecraft/world/level/block/entity/BlockEntity.java` | 2 | parallel-world-ticking |
| `net/minecraft/world/level/block/entity/HopperBlockEntity.java` | 10 | parallel-world-ticking |
| `net/minecraft/world/level/block/entity/SculkCatalystBlockEntity.java` | 1 | parallel-world-ticking |
| `net/minecraft/world/level/block/grower/TreeGrower.java` | 1 | parallel-world-ticking |
| `net/minecraft/world/level/chunk/LevelChunk.java` | 1 | parallel-world-ticking |
| `net/minecraft/world/level/entity/EntityTickList.java` | 2 | parallel-world-ticking |
| `net/minecraft/world/level/saveddata/maps/MapIndex.java` | 1 | parallel-world-ticking |
| `net/minecraft/world/scores/PlayerTeam.java` | 2 | parallel-world-ticking: copied under the scoreboard lock while the worlds tick, parallel-world-ticking: a copy under the scoreboard lock while the worlds tick, for the scoreboard of a plugin as for that of the server - two worlds may read and write either; the scoreboard changing this team under that lock, and everyone with the option off, get the live set |
| `net/minecraft/world/scores/Scoreboard.java` | 13 | parallel-world-ticking, parallel-world-ticking: a copy under the lock while the worlds tick, the live view otherwise |

## `aspaper-server/minecraft-patches/features/0014-BTC-CORE-hooks-per-entity-tracking-range.patch`

| Fichier | Hunks | Options btccore.yml |
|---|---|---|
| `net/minecraft/server/level/ChunkMap.java` | 2 | performance.per-entity-tracking-range, performance.per-entity-tracking-range: a rider seen from further than its mount would ride nothing |
| `net/minecraft/world/entity/Entity.java` | 1 | performance.per-entity-tracking-range |

## `aspaper-server/minecraft-patches/features/0015-BTC-CORE-hooks-AI-sensor-loops.patch`

| Fichier | Hunks | Options btccore.yml |
|---|---|---|
| `net/minecraft/world/entity/ai/sensing/GolemSensor.java` | 1 | performance.ai-sensor-loops |
| `net/minecraft/world/entity/ai/sensing/MobSensor.java` | 1 | performance.ai-sensor-loops |
| `net/minecraft/world/entity/ai/sensing/PlayerSensor.java` | 2 | performance.ai-sensor-loops |
| `net/minecraft/world/entity/ai/sensing/TemptingSensor.java` | 1 | performance.ai-sensor-loops |

## `aspaper-server/minecraft-patches/features/0016-BTC-CORE-hooks-simple-farm-checks.patch`

| Fichier | Hunks | Options btccore.yml |
|---|---|---|
| `net/minecraft/world/level/block/CropBlock.java` | 2 | performance.simple-farm-checks: keep the outcome, performance.simple-farm-checks: not for a growth a plugin cancelled, performance.simple-farm-checks |
| `net/minecraft/world/level/block/FarmlandBlock.java` | 2 | performance.simple-farm-checks |
| `net/minecraft/world/level/block/StemBlock.java` | 1 | performance.simple-farm-checks |

## `aspaper-server/minecraft-patches/features/0017-BTC-CORE-hooks-network-lot.patch`

| Fichier | Hunks | Options btccore.yml |
|---|---|---|
| `net/minecraft/network/Connection.java` | 1 | performance.network.lazy-non-flush-packets |
| `net/minecraft/network/VarInt.java` | 1 | performance.network.fast-varint |
| `net/minecraft/server/level/ServerEntity.java` | 2 | performance.network.skip-empty-entity-moves, performance.network.skip-unchanged-motion, performance.network.filter-entity-motion |

## `aspaper-server/minecraft-patches/features/0018-BTC-CORE-hooks-raytrace-culling.patch`

| Fichier | Hunks | Options btccore.yml |
|---|---|---|
| `net/minecraft/server/level/ChunkMap.java` | 1 | performance.raytrace-culling: a read, as the scan below needs |
| `net/minecraft/server/level/ServerLevel.java` | 2 | performance.raytrace-culling: the opaque blocks of this world for the culling threads, null with the option off, performance.raytrace-culling |
| `net/minecraft/server/level/ServerPlayer.java` | 1 | performance.raytrace-culling: what this player does not see, null until its first scan |
| `net/minecraft/world/level/chunk/LevelChunk.java` | 2 | performance.raytrace-culling, performance.raytrace-culling: the index holds the loaded chunks only |

## `aspaper-server/minecraft-patches/features/0019-BTC-CORE-hooks-spread-latency-updates.patch`

| Fichier | Hunks | Options btccore.yml |
|---|---|---|
| `net/minecraft/server/players/PlayerList.java` | 1 | performance.network.spread-latency-updates: each player gets the latencies as often as before, in the tick of its place in the list instead of all of them in one tick, performance.network.spread-latency-updates |

## `aspaper-server/minecraft-patches/features/0020-BTC-CORE-hooks-piston-shape-cache.patch`

| Fichier | Hunks | Options btccore.yml |
|---|---|---|
| `net/minecraft/world/level/block/piston/PistonMovingBlockEntity.java` | 1 | performance.piston-shape-cache: the base of a retracting piston joined with its head at one of the three offsets it stands at is one of 18 shapes, built once, performance.piston-shape-cache |

## `aspaper-server/minecraft-patches/features/0021-BTC-CORE-hooks-entity-census.patch`

| Fichier | Hunks | Options btccore.yml |
|---|---|---|
| `net/minecraft/server/level/ServerLevel.java` | 4 | world.entity-census: the entities of this world by category and by type, null with the option off, world.entity-census: the category has reached the cap a plugin set - refused before the event, with the cleanup a cancelled event gets (CraftEventFactory#doEntityAddEventCalling), so no mount is left without its rider, no rider without its mount, no raider enrolled and never added; no reason is a journey between worlds, never refused, world.entity-census |
| `net/minecraft/world/entity/animal/Animal.java` | 1 | world.entity-census: the cap is reached - refused here as a cancelled EntityBreedEvent is, before the parents are aged, rewarded and given experience for a young that would not come |

## `aspaper-server/minecraft-patches/features/0022-BTC-CORE-hooks-player-cell-change.patch`

| Fichier | Hunks | Options btccore.yml |
|---|---|---|
| `net/minecraft/server/network/ServerGamePacketListenerImpl.java` | 2 | PlayerCellChangeEvent: the move that PlayerMoveEvent let through took the player into another block; nothing computed while no listener is registered |

## `aspaper-server/minecraft-patches/features/0023-BTC-CORE-hooks-shared-chunk-packets.patch`

| Fichier | Hunks | Options btccore.yml |
|---|---|---|
| `ca/spottedleaf/moonrise/patches/chunk_system/player/RegionizedPlayerChunkLoader.java` | 2 | performance.network.shared-chunk-packets: read once, the window opened is the window closed, performance.network.shared-chunk-packets: players sent the same chunk in this pass share its packet, performance.network.shared-chunk-packets, performance.network.shared-chunk-packets: nothing is kept past the pass, so a block changed later is never in a packet built earlier |
| `net/minecraft/server/level/ServerLevel.java` | 1 | performance.network.shared-chunk-packets: the chunk packets built during one pass of the player chunk loader, empty and never opened with the option off |
| `net/minecraft/server/network/PlayerChunkSender.java` | 1 | performance.network.shared-chunk-packets: never a packet Anti-Xray rewrites for this player; the window is only ever open with the option on |

## `aspaper-server/minecraft-patches/features/0024-BTC-CORE-hooks-world-freeze-at-rest.patch`

| Fichier | Hunks | Options btccore.yml |
|---|---|---|
| `net/minecraft/server/MinecraftServer.java` | 1 | world.freeze-at-rest: a world with no player, no ticket and no chunk loaded for long enough does not tick; off = one static boolean read |
| `net/minecraft/server/level/ServerLevel.java` | 1 | world.freeze-at-rest: how long this world has had nothing in it, never asked with the option off |

## `aspaper-server/paper-patches/features/0007-BTC-CORE-hooks.patch`

| Fichier | Hunks | Options btccore.yml |
|---|---|---|
| `src/main/java/org/bukkit/craftbukkit/CraftServer.java` | 3 | world.overworld-only, world.overworld-only: refuse any nether/end world, whoever asks for it, a close that throws is logged and the world leaves the server all the same: what follows must still run, it gives the lease of a Slime world back |

## `aspaper-server/paper-patches/features/0010-BTC-CORE-hooks-async-pathfinding-API-results.patch`

| Fichier | Hunks | Options btccore.yml |
|---|---|---|
| `src/main/java/com/destroystokyo/paper/entity/PaperPathfinder.java` | 1 | async.async-pathfinding - a plugin reads the result at once, never a path still being searched |

## `aspaper-server/paper-patches/features/0011-BTC-CORE-hooks-parallel-world-ticking-thread-ownersh.patch`

| Fichier | Hunks | Options btccore.yml |
|---|---|---|
| `src/main/java/ca/spottedleaf/moonrise/common/util/TickThread.java` | 3 | parallel-world-ticking |
| `src/main/java/org/bukkit/craftbukkit/CraftServer.java` | 2 | parallel-world-ticking, parallel-world-ticking: before the event, and before the catch below can swallow the refusal of close() |
| `src/main/java/org/bukkit/craftbukkit/CraftWorld.java` | 8 | parallel-world-ticking |
| `src/main/java/org/bukkit/craftbukkit/block/CraftBlock.java` | 12 | parallel-world-ticking |
| `src/main/java/org/bukkit/craftbukkit/block/CraftBlockState.java` | 1 | parallel-world-ticking |
| `src/main/java/org/bukkit/craftbukkit/entity/CraftEntity.java` | 1 | parallel-world-ticking: asked on the thread of a world for another one, the journey is made by the main thread after the barrier, parallel-world-ticking |
| `src/main/java/org/bukkit/craftbukkit/entity/CraftHumanEntity.java` | 6 | parallel-world-ticking: asked by the thread of another world, the main thread opens it once every world has ticked, parallel-world-ticking |
| `src/main/java/org/bukkit/craftbukkit/entity/CraftPlayer.java` | 1 | parallel-world-ticking: asked by a world thread, the main thread respawns the player once every world has ticked, if it is still dead and online then |
| `src/main/java/org/bukkit/craftbukkit/event/CraftEventFactory.java` | 2 | parallel-world-ticking |
| `src/main/java/org/spigotmc/WatchdogThread.java` | 1 | parallel-world-ticking |

## `aspaper-api/paper-patches/features/0002-BTC-CORE-hooks.patch`

| Fichier | Hunks | Options btccore.yml |
|---|---|---|
| `src/main/java/org/bukkit/GameRules.java` | 1 | copper oxidation rate |
| `src/main/java/org/bukkit/UnsafeValues.java` | 1 | — |

