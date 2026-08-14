# Inventaire des hooks BTC-CORE

> Genere par `scripts/check-btccore-patches.py --markdown`. Ne pas editer a la main.

**57 hooks** repartis sur 37 fichiers.

Les hooks sont des patches Paperweight appliques par `git am`. Un hook qui ne s'applique plus fait echouer le build ; il n'y a plus de no-op silencieux.

## `aspaper-server/minecraft-patches/features/0003-BTC-CORE-hooks.patch`

| Fichier | Hunks | Options btccore.yml |
|---|---|---|
| `net/minecraft/network/protocol/game/ClientboundSetPassengersPacket.java` | 1 | — |
| `net/minecraft/server/Main.java` | 1 | must be read before WorldLoader.load, which reads advancements and recipes |
| `net/minecraft/server/ReloadableServerRegistries.java` | 1 | vanilla-content.purge-loot |
| `net/minecraft/server/ServerAdvancementManager.java` | 1 | zero-features.advancements / vanilla-content.purge-advancements |
| `net/minecraft/server/level/ChunkMap.java` | 1 | — |
| `net/minecraft/server/level/ServerChunkCache.java` | 1 | zero-features.force-void-generator |
| `net/minecraft/server/level/ServerLevel.java` | 6 | redstone compiler |
| `net/minecraft/server/level/ThreadedLevelLightEngine.java` | 1 | — |
| `net/minecraft/server/network/ServerCommonPacketListenerImpl.java` | 1 | batched inventory updates |
| `net/minecraft/server/network/ServerGamePacketListenerImpl.java` | 3 | FreedomChat: deliver every player message as a disguised (unsigned) one,, FreedomChat: never register a chat session, so nothing the player says can be |
| `net/minecraft/server/players/PlayerList.java` | 1 | FreedomChat |
| `net/minecraft/stats/ServerRecipeBook.java` | 1 | a purge of our own is the expected reason for a stale entry, not an error. |
| `net/minecraft/stats/ServerStatsCounter.java` | 3 | zero-features.stats |
| `net/minecraft/world/entity/LivingEntity.java` | 4 | PreDamageCalculationEvent, Purpur, zero-features.collisions |
| `net/minecraft/world/entity/Mob.java` | 5 | performance.inactive-goal-selector-throttle, EntityTargetPlayerEvent |
| `net/minecraft/world/entity/ai/Brain.java` | 1 | — |
| `net/minecraft/world/entity/ai/navigation/PathNavigation.java` | 1 | — |
| `net/minecraft/world/entity/ai/sensing/Sensor.java` | 1 | — |
| `net/minecraft/world/entity/projectile/Projectile.java` | 1 | — |
| `net/minecraft/world/entity/vehicle/minecart/AbstractMinecart.java` | 1 | Purpur: per-entity override wins |
| `net/minecraft/world/item/ServerItemCooldowns.java` | 1 | Purpur: no item cooldown in creative |
| `net/minecraft/world/item/crafting/RecipeManager.java` | 1 | zero-features.recipes / vanilla-content.purge-recipes |
| `net/minecraft/world/level/Level.java` | 1 | collision throttle. Level.getEntities() returns a LevelEntityGetter, which has |
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

## `aspaper-server/paper-patches/features/0007-BTC-CORE-hooks.patch`

| Fichier | Hunks | Options btccore.yml |
|---|---|---|
| `src/main/java/org/bukkit/craftbukkit/CraftServer.java` | 2 | world.overworld-only, world.overworld-only: refuse any nether/end world, whoever asks for it |

## `aspaper-api/paper-patches/features/0002-BTC-CORE-hooks.patch`

| Fichier | Hunks | Options btccore.yml |
|---|---|---|
| `src/main/java/org/bukkit/GameRules.java` | 1 | copper oxidation rate |
| `src/main/java/org/bukkit/UnsafeValues.java` | 1 | — |

