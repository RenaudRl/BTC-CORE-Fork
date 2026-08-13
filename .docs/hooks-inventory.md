# Inventaire des hooks BTC-CORE

> Genere par `scripts/verify-btccore-patches.py --markdown`. Ne pas editer a la main.

**52 hooks** — APPLIED : 52

| Statut | Hook | Fichier cible | Detail |
|---|---|---|---|
| `APPLIED` | 0d. BTCCore config must be read before WorldLoader.load, which is what reads the datapacks | `aspaper-server/src/minecraft/java/net/minecraft/server/Main.java` | bloc complet |
| `APPLIED` | 1. FreedomChat — advertise secure chat to the client so it stops warning, and strip signatures. | `aspaper-server/src/minecraft/java/net/minecraft/server/players/PlayerList.java` | bloc complet |
| `APPLIED` | 1. FreedomChat — advertise secure chat to the client so it stops warning, and strip signatures. | `aspaper-server/src/minecraft/java/net/minecraft/server/network/ServerGamePacketListenerImpl.java` | bloc complet |
| `APPLIED` | 1. FreedomChat — advertise secure chat to the client so it stops warning, and strip signatures. | `aspaper-server/src/minecraft/java/net/minecraft/server/network/ServerGamePacketListenerImpl.java` | bloc complet |
| `APPLIED` | 2. CPS Limiting | `aspaper-server/src/minecraft/java/net/minecraft/server/network/ServerGamePacketListenerImpl.java` | bloc complet |
| `APPLIED` | 3. Branding | `aspaper-server/src/minecraft/java/net/minecraft/stats/ServerStatsCounter.java` | bloc complet |
| `APPLIED` | 3. Branding | `aspaper-server/src/minecraft/java/net/minecraft/stats/ServerStatsCounter.java` | bloc complet |
| `APPLIED` | 3. Branding | `aspaper-server/src/minecraft/java/net/minecraft/stats/ServerStatsCounter.java` | bloc complet |
| `APPLIED` | 3. Branding | `aspaper-server/src/minecraft/java/net/minecraft/server/ServerAdvancementManager.java` | bloc complet |
| `APPLIED` | 3. Branding | `aspaper-server/src/minecraft/java/net/minecraft/world/item/crafting/RecipeManager.java` | bloc complet |
| `APPLIED` | 3. Branding | `aspaper-server/src/minecraft/java/net/minecraft/stats/ServerRecipeBook.java` | bloc complet |
| `APPLIED` | 3. Branding | `aspaper-server/src/minecraft/java/net/minecraft/world/level/gamerules/GameRules.java` | bloc complet |
| `APPLIED` | 3. Branding | `aspaper-server/src/minecraft/java/net/minecraft/world/level/block/ChangeOverTimeBlock.java` | bloc complet |
| `APPLIED` | 3. Branding | `paper-server/src/main/java/org/bukkit/craftbukkit/CraftServer.java` | bloc complet |
| `APPLIED` | 3. Branding | `paper-server/src/main/java/org/bukkit/craftbukkit/CraftServer.java` | bloc complet |
| `APPLIED` | 3. Branding | `paper-server/src/main/java/org/bukkit/craftbukkit/CraftServer.java` | bloc complet |
| `APPLIED` | 3. Branding | `aspaper-server/src/minecraft/java/net/minecraft/world/entity/LivingEntity.java` | bloc complet |
| `APPLIED` | 3. Branding | `aspaper-server/src/minecraft/java/net/minecraft/server/level/ThreadedLevelLightEngine.java` | bloc complet |
| `APPLIED` | 3. Branding | `aspaper-server/src/minecraft/java/net/minecraft/server/level/ServerChunkCache.java` | bloc complet |
| `APPLIED` | 3. Branding | `aspaper-server/src/minecraft/java/net/minecraft/world/level/redstone/NeighborUpdater.java` | marqueur |
| `APPLIED` | 3. Branding | `aspaper-server/src/minecraft/java/net/minecraft/world/level/redstone/NeighborUpdater.java` | bloc complet |
| `APPLIED` | 3. Branding | `aspaper-server/src/minecraft/java/net/minecraft/world/level/material/FlowingFluid.java` | bloc complet |
| `APPLIED` | 3. Branding | `aspaper-server/src/minecraft/java/net/minecraft/world/item/ServerItemCooldowns.java` | bloc complet |
| `APPLIED` | 3. Branding | `aspaper-server/src/minecraft/java/net/minecraft/world/entity/LivingEntity.java` | bloc complet |
| `APPLIED` | 3. Branding | `aspaper-server/src/minecraft/java/net/minecraft/world/entity/vehicle/minecart/AbstractMinecart.java` | bloc complet |
| `APPLIED` | 13. Rideables foundation | `aspaper-server/src/minecraft/java/net/minecraft/world/entity/Mob.java` | bloc complet |
| `APPLIED` | 13. Rideables foundation | `aspaper-server/src/minecraft/java/net/minecraft/server/level/ServerLevel.java` | bloc complet |
| `APPLIED` | 13. Rideables foundation | `aspaper-server/src/minecraft/java/net/minecraft/world/entity/LivingEntity.java` | bloc complet |
| `APPLIED` | 13. Rideables foundation | `aspaper-server/src/minecraft/java/net/minecraft/world/entity/Mob.java` | bloc complet |
| `APPLIED` | 16. Redstone compiler: hand compiled circuits to the graph and keep vanilla off their blocks. | `aspaper-server/src/minecraft/java/net/minecraft/server/level/ServerLevel.java` | bloc complet |
| `APPLIED` | 16. Redstone compiler: hand compiled circuits to the graph and keep vanilla off their blocks. | `aspaper-server/src/minecraft/java/net/minecraft/server/level/ServerLevel.java` | bloc complet |
| `APPLIED` | 16. Redstone compiler: hand compiled circuits to the graph and keep vanilla off their blocks. | `aspaper-server/src/minecraft/java/net/minecraft/server/level/ServerLevel.java` | bloc complet |
| `APPLIED` | 16. Redstone compiler: hand compiled circuits to the graph and keep vanilla off their blocks. | `aspaper-server/src/minecraft/java/net/minecraft/world/level/redstone/NeighborUpdater.java` | bloc complet |
| `APPLIED` | 17. Hopper throttle — skip hopper tick processing every N ticks | `aspaper-server/src/minecraft/java/net/minecraft/world/level/block/entity/HopperBlockEntity.java` | bloc complet |
| `APPLIED` | 18. Collision throttle — PerformanceManager check on the entity lookup. | `aspaper-server/src/minecraft/java/net/minecraft/world/level/Level.java` | bloc complet |
| `APPLIED` | 19. Suffocation optimization — skip suffocation check for entities far from players | `aspaper-server/src/minecraft/java/net/minecraft/world/entity/LivingEntity.java` | bloc complet |
| `APPLIED` | 20. Vanilla tick suppression — AI | `aspaper-server/src/minecraft/java/net/minecraft/world/entity/Mob.java` | bloc complet |
| `APPLIED` | 21. Vanilla tick suppression — Brain | `aspaper-server/src/minecraft/java/net/minecraft/world/entity/ai/Brain.java` | bloc complet |
| `APPLIED` | 22. Vanilla tick suppression — Sensors | `aspaper-server/src/minecraft/java/net/minecraft/world/entity/ai/sensing/Sensor.java` | bloc complet |
| `APPLIED` | 22. Vanilla tick suppression — Sensors | `aspaper-server/src/minecraft/java/net/minecraft/server/level/ServerLevel.java` | bloc complet |
| `APPLIED` | 24. Projectile chunk loading limits — prevent projectiles from loading too many chunks | `aspaper-server/src/minecraft/java/net/minecraft/world/entity/projectile/Projectile.java` | bloc complet |
| `APPLIED` | 25. Inactive goal selector throttle — skip goal selector tick for distant entities | `aspaper-server/src/minecraft/java/net/minecraft/world/entity/Mob.java` | bloc complet |
| `APPLIED` | 26. Batched inventory updates — intercept slot packet sends. | `aspaper-server/src/minecraft/java/net/minecraft/server/network/ServerCommonPacketListenerImpl.java` | bloc complet |
| `APPLIED` | 29. RPG weather tick control | `aspaper-server/src/minecraft/java/net/minecraft/server/level/ServerLevel.java` | bloc complet |
| `APPLIED` | 30. Async entity tracker — offload entity tracker tick to async pool | `aspaper-server/src/minecraft/java/net/minecraft/server/level/ChunkMap.java` | bloc complet |
| `APPLIED` | 31. Async pathfinding — offload path computation to async pool | `aspaper-server/src/minecraft/java/net/minecraft/world/entity/ai/navigation/PathNavigation.java` | bloc complet |
| `APPLIED` | 33. Workstation blocking — grindstone, loom, cartography table, composter. | `aspaper-server/src/minecraft/java/net/minecraft/world/level/block/GrindstoneBlock.java` | bloc complet |
| `APPLIED` | 33. Workstation blocking — grindstone, loom, cartography table, composter. | `aspaper-server/src/minecraft/java/net/minecraft/world/level/block/LoomBlock.java` | bloc complet |
| `APPLIED` | 33. Workstation blocking — grindstone, loom, cartography table, composter. | `aspaper-server/src/minecraft/java/net/minecraft/world/level/block/CartographyTableBlock.java` | bloc complet |
| `APPLIED` | 33. Workstation blocking — grindstone, loom, cartography table, composter. | `aspaper-server/src/minecraft/java/net/minecraft/world/level/block/ComposterBlock.java` | bloc complet |
| `APPLIED` | 34. Drop API — one hook, every drop in the game. | `aspaper-server/src/minecraft/java/net/minecraft/world/level/storage/loot/LootTable.java` | bloc complet |
| `APPLIED` | 35. Vanilla loot purge — the mirror of the recipe purge, one registry later. | `aspaper-server/src/minecraft/java/net/minecraft/server/ReloadableServerRegistries.java` | bloc complet |
