#!/bin/bash
# BTC-Core: Pre-build script
# Removes incompatible Paper patches that conflict with current file format

PATCH_DIR=".gradle/caches/paperweight/upstreams/paper/paper-server/patches/resources/data/minecraft/loot_table/chests/trial_chambers"

# Remove the incompatible intersection_barrel.json patch
# This patch was created for an older Paper version that included "add": false
# in the JSON format. The current version doesn't have this field, so the patch
# can never apply. The change (removing set_damage from compass) is applied
# manually in the source file.
if [ -f "$PATCH_DIR/intersection_barrel.json.patch" ]; then
    rm -f "$PATCH_DIR/intersection_barrel.json.patch"
    echo "[BTCCore] Removed incompatible intersection_barrel.json.patch"
fi
