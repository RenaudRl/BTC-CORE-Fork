#!/bin/bash
# BTC-CORE: Apply BTCCore modifications to the Paper-patched overlay
# Run after 'applyAllPatches' to re-apply customizations

OVERLAY="aspaper-server/src/minecraft/java"
PAPER="paper-server/src/main/java"

echo "=== BTC-CORE: Applying custom modifications ==="

# 1. FreedomChat - enforce secure profile
FILE="$OVERLAY/net/minecraft/server/players/PlayerList.java"
if ! grep -q "freedomChatEnforceSecureChat" "$FILE" 2>/dev/null; then
    sed -i 's|this.server.enforceSecureProfile()|this.server.enforceSecureProfile() || (dev.btc.core.config.BTCCoreConfig.freedomChatEnabled \&\& dev.btc.core.config.BTCCoreConfig.freedomChatEnforceSecureChat)|' "$FILE"
    echo "  [OK] FreedomChat - enforceSecureProfile"
else
    echo "  [SKIP] FreedomChat already applied"
fi

# 2. CPS Limiting
FILE="$OVERLAY/net/minecraft/server/network/ServerGamePacketListenerImpl.java"
if ! grep -q "CPS Limiting" "$FILE" 2>/dev/null; then
    sed -i '/this.player.resetLastActionTime();.*handleAnimate/,/\/\/ CraftBukkit start - Raytrace/{
        s|this.player.resetLastActionTime();|this.player.resetLastActionTime();\n        // BTCCore start - CPS Limiting\n        if (dev.btc.core.config.BTCCoreConfig.cpsLimitEnabled \&\& dev.btc.core.config.BTCCoreConfig.cpsLimitMax > 0) {\n            if (!dev.btc.core.security.ExploitLogger.checkClickAndBlock(this.player.getBukkitEntity())) return;\n        }\n        // BTCCore end|
    }' "$FILE"
    echo "  [OK] CPS Limiting"
else
    echo "  [SKIP] CPS already applied"
fi

# 3. Branding
FILE="$PAPER/io/papermc/paper/ServerBuildInfoImpl.java"
if [ -f "$FILE" ]; then
    sed -i 's/AdvancedSlimePaper/BTC Core/g' "$FILE"
    echo "  [OK] Branding - ServerBuildInfo"
fi
FILE="$PAPER/com/destroystokyo/paper/PaperVersionFetcher.java"
if [ -f "$FILE" ]; then
    sed -i 's|InfernalSuite/AdvancedSlimePaper|BTC Studio/BTC Core|g' "$FILE"
    echo "  [OK] Branding - PaperVersionFetcher"
fi

echo "=== BTC-CORE: Done ==="
