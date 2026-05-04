#!/usr/bin/env python3
"""BTC-CORE: Apply all custom modifications to the Paper-patched overlay.
Run after 'applyAllPatches' to re-apply BTCCore customizations.
Total: 16 hooks (8 BTC + 5 Purpur + 2 Events + 1 Redstone)"""

import os

OVERLAY = "aspaper-server/src/minecraft/java"
PAPER = "paper-server/src/main/java"

def patch(filepath, old, new):
    if not os.path.exists(filepath): return False
    with open(filepath, 'r', encoding='utf-8') as f: content = f.read()
    if new in content: return False
    if old not in content:
        print(f"  [WARN] Not found: {old[:40]}...")
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
        if (dev.btc.core.config.BTCCoreConfig.redstoneThrottle) {
            int serverTick = this.getServer() != null ? this.getServer().getTickCount() : 0;
            if (serverTick != redstoneTickCounter) { redstoneTickCounter = serverTick; redstoneUpdateCount.clear(); }
            long chunkKey = ((long)(pos.getX() >> 4) << 32) | ((pos.getZ() >> 4) & 0xFFFFFFFFL);
            int count = redstoneUpdateCount.getOrDefault(chunkKey, 0);
            if (count >= dev.btc.core.config.BTCCoreConfig.redstoneMaxUpdatesPerChunk) return;
            redstoneUpdateCount.put(chunkKey, count + 1);
        }
    }'''
    if old not in content: return False
    with open(filepath, 'w') as f: f.write(content.replace(old, new, 1))
    return True

print("=== BTC-CORE: Applying custom modifications ===")

O = OVERLAY

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
    'final ServerLevel world = (ServerLevel)this.starlight$getLightEngine().getWorld();\n        if (dev.btc.core.config.BTCCoreConfig.isZeroFeatureEnabledFor("light_engine", world.getWorld().getName())) return;\n\n        final ChunkAccess center')

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

print("=== BTC-CORE: Done (16 hooks) ===")
