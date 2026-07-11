#!/usr/bin/env python3
"""BTC-CORE: Apply all custom modifications to the Paper-patched overlay.
Run after 'applyAllPatches' to re-apply BTCCore customizations.
Total: 32 hooks (8 BTC Zero + 5 Purpur + 2 Events + 1 Redstone + 13 Performance + 2 Async + 1 PreDamage)
"""

import os

OVERLAY = "aspaper-server/src/minecraft/java"
PAPER = "paper-server/src/main/java"

def patch(filepath, old, new):
    if not os.path.exists(filepath): return False
    with open(filepath, 'r', encoding='utf-8') as f: content = f.read()
    if new in content: return False
    if old not in content:
        print(f"  [WARN] Not found: {old[:60]}...")
        return False
    with open(filepath, 'w', encoding='utf-8') as f: f.write(content.replace(old, new, 1))
    return True

def patch_rthrottle(filepath):
    """Special redstone throttle patch for Level.java"""
    if not os.path.exists(filepath): return False
    with open(filepath) as f: content = f.read()
    if 'redstoneUpdateCount' in content: return False
    old = 'public void neighborChanged(final BlockPos pos, final Block changedBlock, final @Nullable Orientation orientation) {\n    }'
    new = '''private final java.util.Map<Long, Integer> redstoneUpdateCount = new java.util.HashMap<>();
    private int redstoneTickCounter = 0;

    public void neighborChanged(final BlockPos pos, final Block changedBlock, final @Nullable Orientation orientation) {
        if (dev.btc.core.config.BTCCoreConfig.redstoneThrottleEnabled) {
            int serverTick = this.getServer() != null ? this.getServer().getTickCount() : 0;
            if (serverTick != redstoneTickCounter) { redstoneTickCounter = serverTick; redstoneUpdateCount.clear(); }
            long chunkKey = ((long)(pos.getX() >> 4) << 32) | ((pos.getZ() >> 4) & 0xFFFFFFFFL);
            int count = redstoneUpdateCount.getOrDefault(chunkKey, 0);
            if (count >= dev.btc.core.config.BTCCoreConfig.redstoneThrottleMaxPerChunk) return;
            redstoneUpdateCount.put(chunkKey, count + 1);
        }
    }'''
    if old not in content: return False
    with open(filepath, 'w') as f: f.write(content.replace(old, new, 1))
    return True

print("=== BTC-CORE: Applying custom modifications ===")

O = OVERLAY
P = PAPER

# 0. Build.gradle.kts patches (applied before Java patches)
# 0a. aspaper-api/build.gradle.kts - Add ASP API dependency
f = 'aspaper-api/build.gradle.kts'
if os.path.exists(f):
    with open(f) as fh: c = fh.read()
    if 'api(project(":api"))' not in c:
        c = c.replace('dependencies {\n    // api dependencies are listed transitively to API consumers',
                       'dependencies {\n    api(project(":api")) //ASP\n    // api dependencies are listed transitively to API consumers')
        with open(f, 'w') as fh: fh.write(c)
        print("  [OK] aspaper-api/build.gradle.kts")

# 0b. aspaper-server/build.gradle.kts - Replace paper-api with aspaper-api, add core dependency
f = 'aspaper-server/build.gradle.kts'
if os.path.exists(f):
    with open(f) as fh: c = fh.read()
    if 'implementation(project(":aspaper-api"))' not in c:
        c = c.replace('implementation(project(":paper-api"))',
                       'implementation(project(":aspaper-api")) //ASP\n    implementation(project(":core")) //ASP')
        with open(f, 'w') as fh: fh.write(c)
        print("  [OK] aspaper-server/build.gradle.kts - dependencies")

# 0c. aspaper-server/build.gradle.kts - Add aspaper fork config in paperweight block
f = 'aspaper-server/build.gradle.kts'
if os.path.exists(f):
    with open(f) as fh: c = fh.read()
    if 'val aspaper = forks.register' not in c:
        old_pw = '''    updatingMinecraft {
        // oldPaperCommit = "d4fe85375af18bfa88f44d7c1e6a61904ae550cc"
    }'''
        new_pw = '''    val aspaper = forks.register("aspaper") {
        upstream.patchDir("paperServer") {
            upstreamPath = "paper-server"
            excludes = setOf("src/minecraft", "patches", "build.gradle.kts")
            patchesDir = rootDirectory.dir("aspaper-server/paper-patches")
            outputDir = rootDirectory.dir("paper-server")
        }
    }

    activeFork = aspaper

//    updatingMinecraft {
//        oldPaperCommit = "d4fe85375af18bfa88f44d7c1e6a61904ae550cc"
//    }'''
        if old_pw in c:
            c = c.replace(old_pw, new_pw)
            with open(f, 'w') as fh: fh.write(c)
            print("  [OK] aspaper-server/build.gradle.kts - paperweight fork")

# 1. FreedomChat
if patch(f'{O}/net/minecraft/server/players/PlayerList.java',
    'this.server.enforceSecureProfile()',
    'this.server.enforceSecureProfile() && !dev.btc.core.config.BTCCoreConfig.freedomChatEnabled'):
    print("  [OK] FreedomChat")

# 2. CPS Limiting
if patch(f'{O}/net/minecraft/server/network/ServerGamePacketListenerImpl.java',
    'this.player.resetLastActionTime();\n        // CraftBukkit start - Raytrace',
    'this.player.resetLastActionTime();\n        if (dev.btc.core.config.BTCCoreConfig.cpsLimitEnabled && dev.btc.core.config.BTCCoreConfig.cpsLimitMax > 0) {\n            if (!dev.btc.core.security.ExploitLogger.checkClickAndBlock(this.player.getBukkitEntity())) return;\n        }\n        // CraftBukkit start - Raytrace'):
    print("  [OK] CPS Limiting")

# 3. Branding
f = f'{PAPER}/io/papermc/paper/ServerBuildInfoImpl.java'
if os.path.exists(f):
    with open(f) as fh: c = fh.read()
    if 'AdvancedSlimePaper' in c:
        with open(f, 'w') as fh: fh.write(c.replace('AdvancedSlimePaper', 'BTC Core'))
        print("  [OK] Branding")

# 4-7. Zero Features
patch(f'{O}/net/minecraft/stats/ServerStatsCounter.java',
    'public void setValue(final Player player, final Stat<?> stat, final int count) {\n        if (org.spigotmc.SpigotConfig.disableStatSaving) return;',
    'public void setValue(final Player player, final Stat<?> stat, final int count) {\n        if (dev.btc.core.config.BTCCoreConfig.isZeroFeatureEnabledFor("stats", player.level().getWorld().getName())) return;\n        if (org.spigotmc.SpigotConfig.disableStatSaving) return;')

patch(f'{O}/net/minecraft/server/PlayerAdvancements.java',
    'public boolean award(final AdvancementHolder holder, final String criterion) {',
    'public boolean award(final AdvancementHolder holder, final String criterion) {\n        if (dev.btc.core.config.BTCCoreConfig.isZeroFeatureEnabledFor("advancements", this.player.level().getWorld().getName())) return false;')

patch(f'{O}/net/minecraft/server/level/ThreadedLevelLightEngine.java',
    'final ServerLevel world = (ServerLevel)this.starlight$getLightEngine().getWorld();\n\n        final ChunkAccess center',
    'final ServerLevel world = (ServerLevel)this.starlight$getLightEngine().getWorld();\n        if (dev.btc.core.config.BTCCoreConfig.isZeroFeatureEnabledFor("light_engine", world.getWorld().getName())) return;\n        if (dev.btc.core.config.BTCCoreConfig.lightThrottleEnabled && !dev.btc.core.performance.PerformanceManager.shouldProcessLightUpdate()) return;\n\n        final ChunkAccess center')

patch(f'{O}/net/minecraft/server/level/ServerChunkCache.java',
    'public ChunkGenerator getGenerator() {\n        return this.chunkMap.generator();',
    'public ChunkGenerator getGenerator() {\n        if (dev.btc.core.config.BTCCoreConfig.isZeroFeatureEnabledFor("void_generator", level.getWorld().getName())) return new dev.btc.core.world.VoidChunkGenerator(this.chunkMap.generator().getBiomeSource());\n        return this.chunkMap.generator();')
print("  [OK] Zero Features (4)")

# 8-12. Purpur
patch(f'{O}/net/minecraft/data/loot/packs/VanillaBlockLoot.java',
    'this.add(Blocks.SPAWNER, block -> this.createSingleItemTable(Blocks.SPAWNER));',
    'this.add(Blocks.SPAWNER, block -> this.createSingleItemTableWithSilkTouch(block, Blocks.SPAWNER));')

patch(f'{O}/net/minecraft/world/item/EnderPearlItem.java',
    'player.getCooldowns().addCooldown(this, 20);',
    'if (!player.getAbilities().instabuild) player.getCooldowns().addCooldown(this, 20);')

patch(f'{O}/net/minecraft/world/level/Level.java',
    'public final org.bukkit.craftbukkit.CraftWorld getWorld()',
    'public org.purpurmc.purpur.PurpurConfig purpurConfig = org.purpurmc.purpur.PurpurConfig.config;\n\n    public final org.bukkit.craftbukkit.CraftWorld getWorld()')

patch(f'{O}/net/minecraft/world/entity/LivingEntity.java',
    'if (this.isFallFlying() && damageSource == this.damageSources().flyIntoWall()) {',
    'if (this.isFallFlying() && damageSource == this.damageSources().flyIntoWall() && org.purpurmc.purpur.PurpurConfig.elytraKineticDamage) {')

patch(f'{O}/net/minecraft/world/entity/vehicle/minecart/AbstractMinecart.java',
    'this.setMaxSpeed(0.4D);',
    'this.setMaxSpeed(org.purpurmc.purpur.PurpurConfig.minecartMaxSpeed);')
print("  [OK] Purpur (5)")

# 13. Rideables foundation
patch(f'{O}/net/minecraft/world/entity/Mob.java',
    'private final BodyRotationControl bodyRotationControl;',
    'private final BodyRotationControl bodyRotationControl;\n\n    public boolean isControllable() { return false; }\n    public boolean onSpacebar() { return false; }')
patch(f'{O}/net/minecraft/server/level/ServerLevel.java',
    'public boolean hasEntityMoveEvent;',
    'public boolean hasEntityMoveEvent;\n    public boolean hasRidableMoveEvent = false;')
print("  [OK] Rideables foundation")

# 14-15. Custom events
patch(f'{O}/net/minecraft/world/entity/LivingEntity.java',
    'ItemStack useItem = this.getUseItem();\n            float originAmount = amount;',
    'dev.btc.core.event.entity.PreDamageCalculationEvent preDamageEvent = new dev.btc.core.event.entity.PreDamageCalculationEvent(this.getBukkitEntity(), damageSource.getEntity() == null ? null : damageSource.getEntity().getBukkitEntity(), amount);\n            this.level().getCraftServer().getPluginManager().callEvent(preDamageEvent);\n            if (preDamageEvent.isCancelled()) return false;\n            amount = (float) preDamageEvent.getFinalDamage();\n\n            ItemStack useItem = this.getUseItem();\n            float originAmount = amount;')

patch(f'{O}/net/minecraft/world/entity/Mob.java',
    'org.bukkit.craftbukkit.entity.CraftLivingEntity ctarget = null;\n            if (target != null) {',
    'if (target instanceof net.minecraft.world.entity.player.Player nmsPlayer) {\n                dev.btc.core.event.entity.EntityTargetPlayerEvent playerEvent = new dev.btc.core.event.entity.EntityTargetPlayerEvent(\n                    this.getBukkitEntity(), (org.bukkit.entity.Player) nmsPlayer.getBukkitEntity(), EntityTargetPlayerEvent_reason(reason));\n                this.level().getCraftServer().getPluginManager().callEvent(playerEvent);\n                if (playerEvent.isCancelled()) return false;\n            }\n\n            org.bukkit.craftbukkit.entity.CraftLivingEntity ctarget = null;\n            if (target != null) {')
print("  [OK] Custom events (2)")

# 16. Redstone throttle
if patch_rthrottle(f'{O}/net/minecraft/world/level/Level.java'):
    print("  [OK] Redstone throttle")

# Add EntityTargetPlayerEvent helper to Mob.java
f = f'{O}/net/minecraft/world/entity/Mob.java'
if os.path.exists(f):
    with open(f) as fh: c = fh.read()
    if 'EntityTargetPlayerEvent_reason' in c and 'private static dev.btc.core.event.entity.EntityTargetPlayerEvent.TargetReason' not in c:
        helper = '''
    private static dev.btc.core.event.entity.EntityTargetPlayerEvent.TargetReason EntityTargetPlayerEvent_reason(org.bukkit.event.entity.EntityTargetEvent.TargetReason reason) {
        if (reason == null) return dev.btc.core.event.entity.EntityTargetPlayerEvent.TargetReason.CUSTOM;
        return switch (reason) {
            case CLOSEST_PLAYER -> dev.btc.core.event.entity.EntityTargetPlayerEvent.TargetReason.CLOSEST_PLAYER;
            case TARGET_ATTACKED_ENTITY, TARGET_ATTACKED_NEARBY_ENTITY, TARGET_ATTACKED_OWNER, OWNER_ATTACKED_TARGET -> dev.btc.core.event.entity.EntityTargetPlayerEvent.TargetReason.ATTACKED_BY;
            case COLLISION -> dev.btc.core.event.entity.EntityTargetPlayerEvent.TargetReason.COLLISION;
            case RANDOM_TARGET -> dev.btc.core.event.entity.EntityTargetPlayerEvent.TargetReason.RANDOM;
            default -> dev.btc.core.event.entity.EntityTargetPlayerEvent.TargetReason.CUSTOM;
        };
    }
'''
        c = c[:c.rfind('}')] + helper + '\n}'
        with open(f, 'w') as fh: fh.write(c)
        print("  [OK] EntityTargetPlayerEvent helper method")

# ==================== PERFORMANCE HOOKS (17-29) ====================

# 17. Hopper throttle — skip hopper tick processing every N ticks
if patch(f'{O}/net/minecraft/world/level/block/entity/HopperBlockEntity.java',
    'public static void pushItemsTick(ServerLevel level, BlockPos pos, BlockState state, HopperBlockEntity blockEntity, boolean overridden) {',
    'public static void pushItemsTick(ServerLevel level, BlockPos pos, BlockState state, HopperBlockEntity blockEntity, boolean overridden) {\n        if (dev.btc.core.config.BTCCoreConfig.hopperThrottlingEnabled) {\n            int tick = level.getServer().getTickCount();\n            if (tick % dev.btc.core.config.BTCCoreConfig.hopperThrottlingTicks != 0) return;\n        }'):
    print("  [OK] Hopper throttle")

# 18. Collision throttle — inject PerformanceManager check in EntityGetter.getEntities
# Uses a simple entity count from the world's entity list, no recursive call.
if patch(f'{O}/net/minecraft/world/level/EntityGetter.java',
    'default List<Entity> getEntities(@Nullable Entity entity, AABB area, Predicate<? super Entity> predicate) {',
    'default List<Entity> getEntities(@Nullable Entity entity, AABB area, Predicate<? super Entity> predicate) {\n        if (dev.btc.core.config.BTCCoreConfig.collisionThrottleEnabled && entity != null && this instanceof net.minecraft.world.level.Level level) {\n            int nearby = level.getEntities().size();\n            if (!dev.btc.core.performance.PerformanceManager.shouldCalculateCollision(entity.getBukkitEntity(), nearby)) return java.util.List.of();\n        }'):
    print("  [OK] Collision throttle")

# 19. Suffocation optimization — skip suffocation check for entities far from players
if patch(f'{O}/net/minecraft/world/entity/LivingEntity.java',
    'public boolean isInWall() {',
    'public boolean isInWall() {\n        if (dev.btc.core.config.BTCCoreConfig.suffocationOptimization) {\n            boolean nearPlayer = this.level().hasNearbyAlivePlayer(this.getX(), this.getY(), this.getZ(), 64.0);\n            if (!nearPlayer) return false;\n        }'):
    print("  [OK] Suffocation optimization")

# 20. Vanilla tick suppression — AI
if patch(f'{O}/net/minecraft/world/entity/Mob.java',
    'protected void serverAiStep() {',
    'protected void serverAiStep() {\n        if (dev.btc.core.config.BTCCoreConfig.vanillaTickSuppressionAi) return;'):
    print("  [OK] Vanilla tick suppression — AI")

# 21. Vanilla tick suppression — Brain
if patch(f'{O}/net/minecraft/world/entity/ai/Brain.java',
    'public void tick(WorldGenLevel level, Entity entity) {',
    'public void tick(WorldGenLevel level, Entity entity) {\n        if (dev.btc.core.config.BTCCoreConfig.vanillaTickSuppressionBrain) return;'):
    print("  [OK] Vanilla tick suppression — Brain")

# 22. Vanilla tick suppression — Sensors
# Patch the Sensor interface's default tick method
if patch(f'{O}/net/minecraft/world/entity/ai/sensing/Sensor.java',
    'default void tick(ServerLevel serverLevel, Entity entity) {',
    'default void tick(ServerLevel serverLevel, Entity entity) {\n        if (dev.btc.core.config.BTCCoreConfig.vanillaTickSuppressionSensors) return;'):
    print("  [OK] Vanilla tick suppression — Sensors")

# 23+28. Per-world tick rate + RPG spawn control (combined in one patch to avoid pattern conflict)
# Both hooks target tickCustomSpawners, so we combine them in a single patch.
if patch(f'{O}/net/minecraft/server/level/ServerLevel.java',
    'public void tickCustomSpawners(final boolean spawnAnimals, final boolean spawnMonsters) {',
    'private boolean shouldTickThisWorld() {\n        if (!dev.btc.core.config.BTCCoreConfig.perWorldTickRateEnabled) return true;\n        int players = this.players() != null ? this.players().size() : 0;\n        long tick = this.getServer() != null ? this.getServer().getTickCount() : 0;\n        return dev.btc.core.config.BTCCoreConfig.shouldTickWorld(this.getWorld().getName(), players, tick);\n    }\n\n    public void tickCustomSpawners(final boolean spawnAnimals, final boolean spawnMonsters) {\n        if (!dev.btc.core.config.BTCCoreConfig.rpgVanillaSpawnsEnabled) return;\n        if (!shouldTickThisWorld()) return;'):
    print("  [OK] Per-world tick rate + RPG spawn control")

# 24. Projectile chunk loading limits — prevent projectiles from loading too many chunks
if patch(f'{O}/net/minecraft/world/entity/Projectile.java',
    'public void tick() {',
    'public void tick() {\n        if (!dev.btc.core.performance.ProjectilePool.shouldLoadChunkThisTick()) {\n            this.discard(net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);\n            return;\n        }\n        if (!dev.btc.core.performance.ProjectilePool.shouldLoadChunk(this.getId())) {\n            this.discard(net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);\n            return;\n        }'):
    print("  [OK] Projectile chunk loading limits")

# 25. Inactive goal selector throttle — skip goal selector tick for distant entities
# GoalSelector has no reference to its owning Mob, so we patch Mob.tick() instead
# to conditionally skip goalSelector.tick() and targetSelector.tick().
if patch(f'{O}/net/minecraft/world/entity/Mob.java',
    'this.goalSelector.tick();',
    'if (!dev.btc.core.config.BTCCoreConfig.inactiveGoalSelectorThrottle || this.level().hasNearbyAlivePlayer(this.getX(), this.getY(), this.getZ(), 32.0) || (this.level().getServer().getTickCount() % 20 == 0)) {\n            this.goalSelector.tick();\n        }'):
    print("  [OK] Inactive goal selector throttle")

# 26. Batched inventory updates — intercept slot packet sends
if patch(f'{O}/net/minecraft/server/network/ServerGamePacketListenerImpl.java',
    'public void send(ClientboundContainerSetSlotPacket packet) {',
    'public void send(ClientboundContainerSetSlotPacket packet) {\n        if (dev.btc.core.config.BTCCoreConfig.batchedInventoryUpdates) {\n            dev.btc.core.performance.BatchedInventoryUpdates.queuePacket(this.player, packet);\n            return;\n        }'):
    print("  [OK] Batched inventory updates")

# 27. Async block updates — delegate neighbor updates to server executor (non-blocking on Folia)
if patch(f'{O}/net/minecraft/world/level/Level.java',
    'public void updateNeighborsAt(BlockPos pos, Block block) {',
    'public void updateNeighborsAt(BlockPos pos, Block block) {\n        if (dev.btc.core.config.BTCCoreConfig.asyncBlockUpdatesEnabled && !this.isClientSide() && this.getServer() != null) {\n            final BlockPos fPos = pos.immutable();\n            final Block fBlock = block;\n            this.getServer().executeIfPossible(() -> { doUpdateNeighborsAt(fPos, fBlock); });\n            return;\n        }\n        doUpdateNeighborsAt(pos, block);\n    }\n    private void doUpdateNeighborsAt(BlockPos pos, Block block) {'):
    print("  [OK] Async block updates")

# 29. RPG weather tick control
if patch(f'{O}/net/minecraft/server/level/ServerLevel.java',
    'public void advanceWeatherCycle() {',
    'public void advanceWeatherCycle() {\n        if (!dev.btc.core.config.BTCCoreConfig.rpgWeatherTicksEnabled) return;'):
    print("  [OK] RPG weather tick control")

# 30. Async entity tracker — offload entity tracker tick to async pool
# Intercept the EntityTracker.tick() method to delegate computation to AsyncEntityTracker
if patch(f'{O}/net/minecraft/server/level/ChunkMap.java',
    'public void tick() {',
    'public void tick() {\n        if (dev.btc.core.config.BTCCoreConfig.asyncEntityTrackerEnabled) {\n            dev.btc.core.async.AsyncEntityTracker.submitTracking(() -> { this.tickTracker(); });\n            return;\n        }\n        this.tickTracker();\n    }\n    private void tickTracker() {'):
    print("  [OK] Async entity tracker")

# 31. Async pathfinding — offload path computation to async pool
# Intercept Mob.navigation.move() path computation to delegate to AsyncPathfindingEngine
if patch(f'{O}/net/minecraft/world/entity/ai/navigation/PathNavigation.java',
    'public void tick() {',
    'public void tick() {\n        if (dev.btc.core.config.BTCCoreConfig.asyncPathfindingEnabled && this.mob != null) {\n            final net.minecraft.world.entity.Mob fMob = this.mob;\n            dev.btc.core.async.AsyncPathfindingEngine.submitPathfinding(() -> {\n                this.doTickNavigation();\n                dev.btc.core.async.AsyncPathfindingEngine.queuePathResult(() -> {\n                    // Apply path result on region thread — the doTickNavigation already ran on async\n                    // but the actual movement is applied on the next sync tick\n                });\n            });\n            return;\n        }\n        this.doTickNavigation();\n    }\n    private void doTickNavigation() {'):
    print("  [OK] Async pathfinding")

# 32. PreDamageCalculationEvent — fire event before vanilla damage calculation
# Patches LivingEntity.hurt() to fire PreDamageCalculationEvent before armor/enchant processing.
# Plugins can read, modify, or cancel damage before any reduction is applied.
if patch(f'{O}/net/minecraft/world/entity/LivingEntity.java',
    'public boolean hurt(ServerLevel level, DamageSource source, float amount) {',
    'public boolean hurt(ServerLevel level, DamageSource source, float amount) {\n'
    '        if (dev.btc.core.config.BTCCoreConfig.preDamageEventEnabled && source.getEntity() != null) {\n'
    '            dev.btc.core.api.event.PreDamageCalculationEvent preEvent = new dev.btc.core.api.event.PreDamageCalculationEvent(this.getBukkitEntity(), source.getEntity().getBukkitEntity(), amount);\n'
    '            org.bukkit.Bukkit.getPluginManager().callEvent(preEvent);\n'
    '            if (preEvent.isCancelled()) return false;\n'
    '            amount = (float) preEvent.getBaseDamage();\n'
    '        }'):
    print("  [OK] PreDamageCalculationEvent hook")

print("=== BTC-CORE: Done (32 hooks) ===")
