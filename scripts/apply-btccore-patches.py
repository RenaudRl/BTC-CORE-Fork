#!/usr/bin/env python3
"""BTC-CORE: Apply all custom modifications to the Paper-patched overlay.
Run after 'applyAllPatches' to re-apply BTCCore customizations."""

import os

OVERLAY = "aspaper-server/src/minecraft/java"
PAPER = "paper-server/src/main/java"

def patch(filepath, old, new):
    """Replace old with new in file. Returns True if applied."""
    if not os.path.exists(filepath):
        print(f"  [WARN] Not found: {filepath}")
        return False
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()
    if new in content:
        return False  # Already applied
    if old not in content:
        print(f"  [WARN] Pattern not found in {os.path.basename(filepath)}")
        return False
    with open(filepath, 'w', encoding='utf-8') as f:
        f.write(content.replace(old, new, 1))
    return True

print("=== BTC-CORE: Applying custom modifications ===")

# 1. FreedomChat
if patch(f'{OVERLAY}/net/minecraft/server/players/PlayerList.java',
    'this.server.enforceSecureProfile()',
    'this.server.enforceSecureProfile() && !dev.btc.core.config.BTCCoreConfig.freedomChatEnabled'):
    print("  [OK] FreedomChat")
else:
    print("  [SKIP] FreedomChat")

# 2. CPS Limiting
if patch(f'{OVERLAY}/net/minecraft/server/network/ServerGamePacketListenerImpl.java',
    'this.player.resetLastActionTime();\n        // CraftBukkit start - Raytrace',
    'this.player.resetLastActionTime();\n        if (dev.btc.core.config.BTCCoreConfig.cpsLimitEnabled && dev.btc.core.config.BTCCoreConfig.cpsLimitMax > 0) {\n            if (!dev.btc.core.security.ExploitLogger.checkClickAndBlock(this.player.getBukkitEntity())) return;\n        }\n        // CraftBukkit start - Raytrace'):
    print("  [OK] CPS Limiting")
else:
    print("  [SKIP] CPS Limiting")

# 3. Branding
f = f'{PAPER}/io/papermc/paper/ServerBuildInfoImpl.java'
if os.path.exists(f):
    with open(f) as fh: c = fh.read()
    if 'AdvancedSlimePaper' in c:
        with open(f, 'w') as fh: fh.write(c.replace('AdvancedSlimePaper', 'BTC Core'))
        print("  [OK] Branding")

# 4. Zero Feature: Stats
if patch(f'{OVERLAY}/net/minecraft/stats/ServerStatsCounter.java',
    'public void setValue(final Player player, final Stat<?> stat, final int count) {\n        if (org.spigotmc.SpigotConfig.disableStatSaving) return;',
    'public void setValue(final Player player, final Stat<?> stat, final int count) {\n        if (dev.btc.core.config.BTCCoreConfig.isZeroFeatureEnabledFor("stats", player.level().getWorld().getName())) return;\n        if (org.spigotmc.SpigotConfig.disableStatSaving) return;'):
    print("  [OK] Zero: Stats")

# 5. Zero Feature: Advancements
if patch(f'{OVERLAY}/net/minecraft/server/PlayerAdvancements.java',
    'public boolean award(final AdvancementHolder holder, final String criterion) {',
    'public boolean award(final AdvancementHolder holder, final String criterion) {\n        if (dev.btc.core.config.BTCCoreConfig.isZeroFeatureEnabledFor("advancements", this.player.level().getWorld().getName())) return false;'):
    print("  [OK] Zero: Advancements")

# 6. Zero Feature: Light Engine
if patch(f'{OVERLAY}/net/minecraft/server/level/ThreadedLevelLightEngine.java',
    'final ServerLevel world = (ServerLevel)this.starlight$getLightEngine().getWorld();\n\n        final ChunkAccess center',
    'final ServerLevel world = (ServerLevel)this.starlight$getLightEngine().getWorld();\n        if (dev.btc.core.config.BTCCoreConfig.isZeroFeatureEnabledFor("light_engine", world.getWorld().getName())) return;\n\n        final ChunkAccess center'):
    print("  [OK] Zero: Light Engine")

# 7. Zero Feature: Void Generator
if patch(f'{OVERLAY}/net/minecraft/server/level/ServerChunkCache.java',
    'public ChunkGenerator getGenerator() {\n        return this.chunkMap.generator();',
    'public ChunkGenerator getGenerator() {\n        if (dev.btc.core.config.BTCCoreConfig.isZeroFeatureEnabledFor("void_generator", level.getWorld().getName())) return new dev.btc.core.world.VoidChunkGenerator(this.chunkMap.generator().getBiomeSource());\n        return this.chunkMap.generator();'):
    print("  [OK] Zero: Void Generator")

# TODO: Block Updates, Sleep Tick, Vanilla Tick Suppression
# These need pattern adjustments - patterns vary between Paper versions

print("=== BTC-CORE: Done ===")
