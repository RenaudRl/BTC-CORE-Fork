#!/bin/bash
# BTC-CORE: Apply custom modifications to the Paper-patched overlay
# Run after 'applyAllPatches' to re-apply BTCCore customizations
set -e

OVERLAY="aspaper-server/src/minecraft/java"
PAPER="paper-server/src/main/java"

echo "=== BTC-CORE: Applying custom modifications ==="

# 1. FreedomChat - enforce secure profile
F="$OVERLAY/net/minecraft/server/players/PlayerList.java"
if ! grep -q "freedomChatEnforceSecureChat" "$F" 2>/dev/null; then
    sed -i 's|this.server.enforceSecureProfile()|this.server.enforceSecureProfile() \&\& !dev.btc.core.config.BTCCoreConfig.freedomChatEnabled|' "$F"
    echo "  [OK] FreedomChat"
else
    echo "  [SKIP] FreedomChat already applied"
fi

# 2. CPS Limiting
F="$OVERLAY/net/minecraft/server/network/ServerGamePacketListenerImpl.java"
if ! grep -q "CPS Limiting" "$F" 2>/dev/null; then
    sed -i '/this\.player\.resetLastActionTime();/a\        if (dev.btc.core.config.BTCCoreConfig.cpsLimitEnabled \&\& dev.btc.core.config.BTCCoreConfig.cpsLimitMax > 0) {\n            if (!dev.btc.core.security.ExploitLogger.checkClickAndBlock(this.player.getBukkitEntity())) return;\n        }' "$F"
    echo "  [OK] CPS Limiting"
else
    echo "  [SKIP] CPS already applied"
fi

# 3. Branding
F="$PAPER/io/papermc/paper/ServerBuildInfoImpl.java"
if [ -f "$F" ]; then
    sed -i 's/AdvancedSlimePaper/BTC Core/g' "$F"
    echo "  [OK] Branding"
fi

echo "=== BTC-CORE: Done ==="
