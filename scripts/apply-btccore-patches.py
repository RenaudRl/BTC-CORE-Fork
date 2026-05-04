#!/usr/bin/env python3
"""BTC-CORE: Apply custom modifications to the Paper-patched overlay.
Run after 'applyAllPatches' to re-apply BTCCore customizations.
More robust than sed for multi-line edits."""

import os, re, sys

OVERLAY = "aspaper-server/src/minecraft/java"
PAPER = "paper-server/src/main/java"

def apply_patch(filepath, replacements):
    """Apply a list of (old_text, new_text) replacements to a file."""
    if not os.path.exists(filepath):
        print(f"  [WARN] File not found: {filepath}")
        return False
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()
    modified = False
    for old, new in replacements:
        if old in content:
            content = content.replace(old, new, 1)
            modified = True
        elif new in content:
            pass  # Already applied
        else:
            print(f"  [WARN] Pattern not found in {filepath}: {old[:60]}...")
    if modified:
        with open(filepath, 'w', encoding='utf-8') as f:
            f.write(content)
    return modified

print("=== BTC-CORE: Applying custom modifications ===")

# 1. FreedomChat - enforce secure profile + chat rewrite
f = f"{OVERLAY}/net/minecraft/server/players/PlayerList.java"
if apply_patch(f, [
    # Enforce secure profile
    (
        'this.server.enforceSecureProfile()',
        'this.server.enforceSecureProfile() && !dev.btc.core.config.BTCCoreConfig.freedomChatEnabled'
    ),
]): print("  [OK] FreedomChat")

# 2. CPS Limiting
f = f"{OVERLAY}/net/minecraft/server/network/ServerGamePacketListenerImpl.java"
if apply_patch(f, [
    (
        'this.player.resetLastActionTime();\n        // CraftBukkit start - Raytrace',
        'this.player.resetLastActionTime();\n        // BTCCore start - CPS Limiting\n        if (dev.btc.core.config.BTCCoreConfig.cpsLimitEnabled && dev.btc.core.config.BTCCoreConfig.cpsLimitMax > 0) {\n            if (!dev.btc.core.security.ExploitLogger.checkClickAndBlock(this.player.getBukkitEntity())) return;\n        }\n        // BTCCore end\n        // CraftBukkit start - Raytrace'
    ),
]): print("  [OK] CPS Limiting")

# 3. Branding
f = f"{PAPER}/io/papermc/paper/ServerBuildInfoImpl.java"
if os.path.exists(f):
    apply_patch(f, [('AdvancedSlimePaper', 'BTC Core')])
    print("  [OK] Branding")

# 4. Zero Features - Light Engine
f = f"{OVERLAY}/net/minecraft/server/level/ThreadedLevelLightEngine.java"
apply_patch(f, [
    (
        'final ServerLevel world = (ServerLevel)this.starlight$getLightEngine().getWorld();\n        final ChunkAccess center',
        'final ServerLevel world = (ServerLevel)this.starlight$getLightEngine().getWorld();\n        // BTCCore - Zero Features: Light Engine\n        if (dev.btc.core.config.BTCCoreConfig.isZeroFeatureEnabledFor("light_engine", world.getWorld().getName())) {\n            return;\n        }\n        final ChunkAccess center'
    ),
])
print("  [OK] Zero Features - Light Engine")

# 5. Zero Features - Stats
f = f"{OVERLAY}/net/minecraft/stats/ServerStatsCounter.java"
apply_patch(f, [
    (
        'public void setValue(final Player player, final Stat<?> stat, final int count) {\n        if (org.spigotmc.SpigotConfig.disableStatSaving) return;',
        'public void setValue(final Player player, final Stat<?> stat, final int count) {\n        // BTCCore - Zero Features: Stats\n        if (dev.btc.core.config.BTCCoreConfig.isZeroFeatureEnabledFor("stats", player.level().getWorld().getName())) return;\n        if (org.spigotmc.SpigotConfig.disableStatSaving) return;'
    ),
])
print("  [OK] Zero Features - Stats")

# 6. Zero Features - Advancements
f = f"{OVERLAY}/net/minecraft/server/PlayerAdvancements.java"
apply_patch(f, [
    (
        'public boolean setOrDisplay(final ServerPlayer player, final AdvancementHolder advancement) {\n        boolean flag =',
        'public boolean setOrDisplay(final ServerPlayer player, final AdvancementHolder advancement) {\n        // BTCCore - Zero Features: Advancements\n        if (dev.btc.core.config.BTCCoreConfig.isZeroFeatureEnabledFor("advancements", this.player.level().getWorld().getName())) {\n            return false;\n        }\n        boolean flag ='
    ),
])
print("  [OK] Zero Features - Advancements")

# 7. Zero Features - Block Updates
f = f"{OVERLAY}/net/minecraft/world/level/Level.java"
apply_patch(f, [
    (
        'public void neighborChanged(final BlockPos pos, final Block block, @Nullable final Orientation orientation) {\n        if (this.isClientSide) {\n            return;\n        }',
        'public void neighborChanged(final BlockPos pos, final Block block, @Nullable final Orientation orientation) {\n        if (this.isClientSide) {\n            return;\n        }\n        // BTCCore - Zero Features: Block Updates\n        if (dev.btc.core.config.BTCCoreConfig.isZeroFeatureEnabledFor("block_updates", this.getWorld().getName())) {\n            return;\n        }'
    ),
])
print("  [OK] Zero Features - Block Updates")

print("=== BTC-CORE: Done ===")
