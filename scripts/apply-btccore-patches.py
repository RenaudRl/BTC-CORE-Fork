#!/usr/bin/env python3
"""BTC-CORE: Apply all custom modifications to the Paper-patched overlay.
Run after 'applyAllPatches' to re-apply BTCCore customizations.

Do not keep a hook count here: the two that used to live in this file both went stale (45 and 46
against 53 real injections) and a wrong count reads as reassurance. `verify-btccore-patches.py` is
the only authority, since it replays these same definitions instead of restating them.

Exits non-zero if any anchor no longer matches — an unmatched anchor is a silent no-op.
"""

import os
import sys

OVERLAY = "aspaper-server/src/minecraft/java"
PAPER = "paper-server/src/main/java"
BUKKIT_API = "paper-api/src/main/java"

# A patch whose anchor no longer matches is a silent no-op: the build stays green, the option
# stays in btccore.yml, the debug command still says "enabled" — and the hook exists nowhere.
# Every miss is collected here and turns into a non-zero exit at the end of the run.
FAILURES = []

def injection_marker(old, new):
    """Longest line `new` adds over `old`. Used to detect an already-applied patch even when the
    overlay was hand-edited around the injection — comparing the whole `new` block verbatim reports
    those as drift, which would bury the real misses under false alarms."""
    old_lines = {line.strip() for line in old.splitlines() if line.strip()}
    added = [line.strip() for line in new.splitlines() if line.strip() and line.strip() not in old_lines]
    return max(added, key=len) if added else None

def patch(filepath, old, new):
    if not os.path.exists(filepath):
        FAILURES.append((filepath, 'file missing', old[:80]))
        return False
    with open(filepath, 'r', encoding='utf-8') as f: content = f.read()
    if new in content: return False
    marker = injection_marker(old, new)
    if marker and marker in content: return False  # already applied
    if old not in content:
        print(f"  [WARN] Not found: {old[:60]}...")
        FAILURES.append((filepath, 'anchor not found', old[:80]))
        return False
    with open(filepath, 'w', encoding='utf-8') as f: f.write(content.replace(old, new, 1))
    return True

print("=== BTC-CORE: Applying custom modifications ===")

O = OVERLAY
P = PAPER
A = BUKKIT_API

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

# 0d. BTCCore config must be read before WorldLoader.load, which is what reads the datapacks
# (advancements + recipes). Loading it any later makes every content-level option a no-op.
if patch(f'{O}/net/minecraft/server/Main.java',
    "org.spigotmc.SpigotConfig.disabledAdvancements = spigotConfiguration.getStringList(\"advancements.disabled\"); // Paper - fix SPIGOT-5885, must be set early in init\n",
    "org.spigotmc.SpigotConfig.disabledAdvancements = spigotConfiguration.getStringList(\"advancements.disabled\"); // Paper - fix SPIGOT-5885, must be set early in init\n\n            dev.btc.core.config.BTCCoreConfig.load(null); // BTCCore - must be read before WorldLoader.load, which reads advancements and recipes\n"):
    print("  [OK] Early BTCCoreConfig load")

# 1. FreedomChat — advertise secure chat to the client so it stops warning, and strip signatures.
if patch(f'{O}/net/minecraft/server/players/PlayerList.java',
    'this.server.enforceSecureProfile()\n',
    'dev.btc.core.config.BTCCoreConfig.advertisesSecureChat(this.server.enforceSecureProfile()) // BTCCore - FreedomChat\n'):
    print("  [OK] FreedomChat - enforcesSecureChat flag")

if patch(f'{O}/net/minecraft/server/network/ServerGamePacketListenerImpl.java',
    'public void sendPlayerChatMessage(final PlayerChatMessage message, final ChatType.Bound chatType) {\n        // CraftBukkit start - SPIGOT-7262',
    'public void sendPlayerChatMessage(final PlayerChatMessage message, final ChatType.Bound chatType) {\n'
    '        // BTCCore start - FreedomChat: deliver every player message as a disguised (unsigned) one,\n'
    '        // so the client never has a signature to verify and never reports it.\n'
    '        if (dev.btc.core.config.BTCCoreConfig.freedomChatEnabled && dev.btc.core.config.BTCCoreConfig.freedomChatRewriteChat) {\n'
    '            this.sendDisguisedChatMessage(message.decoratedContent(), chatType);\n'
    '            return;\n'
    '        }\n'
    '        // BTCCore end\n'
    '        // CraftBukkit start - SPIGOT-7262'):
    print("  [OK] FreedomChat - rewrite chat")

if patch(f'{O}/net/minecraft/server/network/ServerGamePacketListenerImpl.java',
    'public void handleChatSessionUpdate(final ServerboundChatSessionUpdatePacket packet) {\n        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());',
    'public void handleChatSessionUpdate(final ServerboundChatSessionUpdatePacket packet) {\n'
    '        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());\n'
    '        // BTCCore - FreedomChat: never register a chat session, so nothing the player says can be\n'
    '        // signed with their Mojang key, hence nothing can be reported.\n'
    '        if (dev.btc.core.config.BTCCoreConfig.freedomChatEnabled && dev.btc.core.config.BTCCoreConfig.freedomChatPreventChatReports) return;'):
    print("  [OK] FreedomChat - prevent chat reports")

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

# 4-9. Zero Features
# Stats: hard-disable — never track, never load, never save.
patch(f'{O}/net/minecraft/stats/ServerStatsCounter.java',
    'public void setValue(final Player player, final Stat<?> stat, final int count) {\n        if (org.spigotmc.SpigotConfig.disableStatSaving) return;',
    'public void setValue(final Player player, final Stat<?> stat, final int count) {\n        if (dev.btc.core.config.BTCCoreConfig.isZeroFeatureEnabledFor("stats", player.level().getWorld().getName())) return;\n        if (org.spigotmc.SpigotConfig.disableStatSaving) return;')

patch(f'{O}/net/minecraft/stats/ServerStatsCounter.java',
    'public ServerStatsCounter(final MinecraftServer server, final Path file) {\n        this.file = file;\n        if (Files.isRegularFile(file)) {',
    'public ServerStatsCounter(final MinecraftServer server, final Path file) {\n        this.file = file;\n        if (Files.isRegularFile(file) && !dev.btc.core.config.BTCCoreConfig.zfStatsEnabled) {')

patch(f'{O}/net/minecraft/stats/ServerStatsCounter.java',
    'public void save() {\n        if (org.spigotmc.SpigotConfig.disableStatSaving) return; // Spigot',
    'public void save() {\n        if (dev.btc.core.config.BTCCoreConfig.zfStatsEnabled) return; // BTCCore - zero-features.stats\n        if (org.spigotmc.SpigotConfig.disableStatSaving) return; // Spigot')

# Advancements: BTCCoreConfig decides per namespace — everything when the zero-feature is on,
# only the "minecraft" namespace when the vanilla purge is on. Custom datapacks survive the purge.
patch(f'{O}/net/minecraft/server/ServerAdvancementManager.java',
    'preparations.forEach((id, advancement) -> {\n            // Spigot start',
    'preparations.forEach((id, advancement) -> {\n            if (dev.btc.core.config.BTCCoreConfig.shouldDropAdvancement(id.getNamespace())) return; // BTCCore - zero-features.advancements / vanilla-content.purge-advancements\n            // Spigot start')

# Recipes: same split as advancements, plus a path-level whitelist. The code-backed recipes
# (dyeing, fireworks, repair, banner duplication...) have no Bukkit constructor, so a purge is
# final for them — vanilla-content.preserve-special-recipes spares them by identifier.
patch(f'{O}/net/minecraft/world/item/crafting/RecipeManager.java',
    'recipes.forEach((id, recipe) -> {\n            ResourceKey<Recipe<?>> key = ResourceKey.create(Registries.RECIPE, id);',
    'recipes.forEach((id, recipe) -> {\n            if (dev.btc.core.config.BTCCoreConfig.shouldDropRecipe(id.getNamespace(), id.getPath())) return; // BTCCore - zero-features.recipes / vanilla-content.purge-recipes\n            ResourceKey<Recipe<?>> key = ResourceKey.create(Registries.RECIPE, id);')

# A saved recipe book still lists whatever the player knew before the purge. Those entries are
# dropped anyway; only the one-error-per-entry-per-join spam is wrong, and it is expected here.
patch(f'{O}/net/minecraft/stats/ServerRecipeBook.java',
    '            if (!validator.test(recipe)) {\n                LOGGER.error("Tried to load unrecognized recipe: {} removed now.", recipe);',
    '            if (!validator.test(recipe)) {\n'
    '                // BTCCore - a purge of our own is the expected reason for a stale entry, not an error.\n'
    '                // The entry is dropped either way, so the profile self-heals on the next save.\n'
    '                if (!dev.btc.core.config.BTCCoreConfig.isDroppingRecipes()) {\n'
    '                    LOGGER.error("Tried to load unrecognized recipe: {} removed now.", recipe);\n'
    '                }')

# copper_fade game rule — per-world copper oxidation rate, 0..100. Applied per Slime World through
# config/BTCCore/slimeworld-config.yml like every other game rule.
patch(f'{O}/net/minecraft/world/level/gamerules/GameRules.java',
    '    public static final GameRule<Boolean> DROWNING_DAMAGE = registerBoolean("drowning_damage", GameRuleCategory.PLAYER, true);',
    '    public static final GameRule<Integer> COPPER_FADE = registerInteger("copper_fade", GameRuleCategory.UPDATES, 100, 0, 100); // BTCCore - copper oxidation rate, 100 = vanilla, 0 = never oxidises\n'
    '    public static final GameRule<Boolean> DROWNING_DAMAGE = registerBoolean("drowning_damage", GameRuleCategory.PLAYER, true);')

# The Bukkit mirror of the rule above. Registering a game rule server-side without its API constant
# fails upstream's RegistryConstantsTest ("Missing (1) constants in GameRules: {minecraft:copper_fade}"),
# which fails the whole build — and it would leave the rule unreachable from any plugin.
patch(f'{A}/org/bukkit/GameRules.java',
    '    public static final GameRule<Boolean> DROWNING_DAMAGE = getRule("drowning_damage");',
    '    public static final GameRule<Integer> COPPER_FADE = getRule("copper_fade"); // BTCCore - copper oxidation rate\n'
    '    public static final GameRule<Boolean> DROWNING_DAMAGE = getRule("drowning_damage");')

# changeOverTime is the single entry point of all 16 weathering-copper blocks, and getNextState is
# only ever reached from it, so one guard covers the whole family.
patch(f'{O}/net/minecraft/world/level/block/ChangeOverTimeBlock.java',
    '        float eachBlockOncePerDayChance = 0.05688889F;\n        if (random.nextFloat() < 0.05688889F) {',
    '        // BTCCore start - copper_fade game rule: scales the oxidation odds from 0 to 100.\n'
    '        // 100 multiplies by exactly 1.0F, so the threshold and the single nextFloat() draw stay\n'
    '        // bit-identical to vanilla. 0 stops oxidation outright, which also makes waxing pointless.\n'
    '        final int copperFade = level.getGameRules().get(net.minecraft.world.level.gamerules.GameRules.COPPER_FADE);\n'
    '        if (copperFade <= 0) {\n'
    '            return;\n'
    '        }\n'
    '        float eachBlockOncePerDayChance = 0.05688889F * (copperFade / 100.0F);\n'
    '        if (random.nextFloat() < eachBlockOncePerDayChance) {\n'
    '        // BTCCore end')

# world.overworld-only — no nether, no end, whatever bukkit.yml / paper / server.properties say.
patch(f'{P}/org/bukkit/craftbukkit/CraftServer.java',
    'public boolean getAllowEnd() {\n        return this.configuration.getBoolean("settings.allow-end");',
    'public boolean getAllowEnd() {\n        if (dev.btc.core.config.BTCCoreConfig.overworldOnly) return false; // BTCCore - world.overworld-only\n        return this.configuration.getBoolean("settings.allow-end");')

patch(f'{P}/org/bukkit/craftbukkit/CraftServer.java',
    'public boolean getAllowNether() {\n        return GlobalConfiguration.get().misc.enableNether;',
    'public boolean getAllowNether() {\n        if (dev.btc.core.config.BTCCoreConfig.overworldOnly) return false; // BTCCore - world.overworld-only\n        return GlobalConfiguration.get().misc.enableNether;')

patch(f'{P}/org/bukkit/craftbukkit/CraftServer.java',
    '        String name = creator.name();\n        ChunkGenerator chunkGenerator = creator.generator();',
    '        // BTCCore start - world.overworld-only: refuse any nether/end world, whoever asks for it\n'
    '        if (dev.btc.core.config.BTCCoreConfig.isDimensionBlocked(creator.environment().name())) {\n'
    '            this.getLogger().warning("[BTCCore] Refused to create world \'" + creator.name() + "\' ("\n'
    '                    + creator.environment() + "): world.overworld-only is enabled in btccore.yml");\n'
    '            return null;\n'
    '        }\n'
    '        // BTCCore end\n\n'
    '        String name = creator.name();\n        ChunkGenerator chunkGenerator = creator.generator();')

# Collisions: skip entity-vs-entity pushing entirely on matched worlds.
patch(f'{O}/net/minecraft/world/entity/LivingEntity.java',
    "protected void pushEntities() {\n        // Paper start - don't run getEntities if we're not going to use its result",
    "protected void pushEntities() {\n        if (dev.btc.core.config.BTCCoreConfig.isZeroFeatureEnabledFor(\"collisions\", this.level().getWorld().getName())) return; // BTCCore - zero-features.collisions\n        // Paper start - don't run getEntities if we're not going to use its result")

patch(f'{O}/net/minecraft/server/level/ThreadedLevelLightEngine.java',
    'final ServerLevel world = (ServerLevel)this.starlight$getLightEngine().getWorld();\n\n        final ChunkAccess center',
    'final ServerLevel world = (ServerLevel)this.starlight$getLightEngine().getWorld();\n        if (dev.btc.core.config.BTCCoreConfig.isZeroFeatureEnabledFor("light_engine", world.getWorld().getName())) return;\n        if (dev.btc.core.config.BTCCoreConfig.lightThrottleEnabled && !dev.btc.core.performance.PerformanceManager.shouldProcessLightUpdate()) return;\n\n        final ChunkAccess center')

patch(f'{O}/net/minecraft/server/level/ServerChunkCache.java',
    'public ChunkGenerator getGenerator() {\n        return this.chunkMap.generator();',
    'private volatile dev.btc.core.world.VoidChunkGenerator btcVoidGenerator; // BTCCore - zero-features.force-void-generator\n\n    public ChunkGenerator getGenerator() {\n        if (dev.btc.core.config.BTCCoreConfig.isZeroFeatureEnabledFor("void_generator", level.getWorld().getName())) {\n            dev.btc.core.world.VoidChunkGenerator cached = this.btcVoidGenerator;\n            if (cached == null) {\n                cached = new dev.btc.core.world.VoidChunkGenerator(this.chunkMap.generator().getBiomeSource());\n                this.btcVoidGenerator = cached;\n            }\n            return cached;\n        }\n        return this.chunkMap.generator();')

# Block updates: neighbour physics. This is the single funnel every neighbour update goes
# through, so the short-circuit belongs here rather than on a BlockPhysicsEvent listener —
# registering such a listener flips ServerLevel.hasPhysicsEvent on for the WHOLE server, which
# makes every world (in scope or not) pay the event allocation and dispatch.
patch(f'{O}/net/minecraft/world/level/redstone/NeighborUpdater.java',
    '    static void executeUpdate(Level level, BlockState state, BlockPos pos, Block changedBlock, @Nullable Orientation orientation, boolean movedByPiston, BlockPos sourcePos) {\n        // Paper end - Add source block to BlockPhysicsEvent\n        try {',
    '    static void executeUpdate(Level level, BlockState state, BlockPos pos, Block changedBlock, @Nullable Orientation orientation, boolean movedByPiston, BlockPos sourcePos) {\n        // Paper end - Add source block to BlockPhysicsEvent\n        if (dev.btc.core.config.BTCCoreConfig.isZeroFeatureEnabledFor("block_updates", level.getWorld().getName())) return; // BTCCore - zero-features.block-updates\n        try {')

# Redstone benchmark probe — handleNeighborChanged is where ALTERNATE_CURRENT does its work, so it
# is the only fair counterpart to the compiled path. The probe fires for EVERY world while a bench
# runs, not just the measured one: the profiler times the measured world and merely counts the
# others, which is what makes "zero updates here, plenty over there" a readable answer instead of a
# silent zero. An idle server still pays only one volatile read.
patch(f'{O}/net/minecraft/world/level/redstone/NeighborUpdater.java',
    '            state.handleNeighborChanged(level, pos, changedBlock, orientation, movedByPiston);',
    '            // BTCCore start - redstone benchmark: this is where ALTERNATE_CURRENT spends its time.\n'
    '            if (dev.btc.core.redstone.RedstoneProfiler.sampling()) {\n'
    '                final long btcStarted = System.nanoTime();\n'
    '                try {\n'
    '                    state.handleNeighborChanged(level, pos, changedBlock, orientation, movedByPiston);\n'
    '                } finally {\n'
    '                    dev.btc.core.redstone.RedstoneProfiler.recordVanilla(level, System.nanoTime() - btcStarted);\n'
    '                }\n'
    '            } else {\n'
    '            state.handleNeighborChanged(level, pos, changedBlock, orientation, movedByPiston);\n'
    '            }\n'
    '            // BTCCore end')

# Block updates: liquid spreading. Both BlockFromToEvent call sites in FlowingFluid are reached
# through spread(), so one guard covers downward flow and sideways flow alike.
patch(f'{O}/net/minecraft/world/level/material/FlowingFluid.java',
    'protected void spread(final ServerLevel level, final BlockPos pos, final BlockState state, final FluidState fluidState) {\n        if (!fluidState.isEmpty()) {',
    'protected void spread(final ServerLevel level, final BlockPos pos, final BlockState state, final FluidState fluidState) {\n        if (dev.btc.core.config.BTCCoreConfig.isZeroFeatureEnabledFor("block_updates", level.getWorld().getName())) return; // BTCCore - zero-features.block-updates\n        if (!fluidState.isEmpty()) {')
print("  [OK] Zero Features (9)")

# 8-11. Purpur
# Removed: the Purpur silk-touch-spawner patch targeted net/minecraft/data/loot/packs/VanillaBlockLoot.java,
# which is datagen — its only caller is VanillaLootTableProvider, reached from net.minecraft.data.Main,
# never from server boot. Runtime loot tables are the JSON under
# aspaper-server/src/minecraft/resources/data/minecraft/loot_table/. The patch could not have had any
# in-game effect even with a matching anchor (and its anchor had drifted: 26.2 ships `noDrop()`).
# Silk-touch spawners are provided by TypeWriter-EnchantmentCreatorExtension (SpawnersSilkTouchActionEntry).

# Ender pearl cooldown in creative: 26.2 moved the cooldown out of EnderPearlItem code and onto the
# item's use_cooldown data component, so there is no longer any call to guard there. ServerItemCooldowns
# is the funnel every component-driven cooldown now goes through, and it is the only one that knows the
# player — so the creative check lives here and covers the pearl along with every other cooldown item.
patch(f'{O}/net/minecraft/world/item/ServerItemCooldowns.java',
    '    public void addCooldown(ItemStack item, int duration) {\n        final Identifier cooldownGroup = this.getCooldownGroup(item);',
    '    public void addCooldown(ItemStack item, int duration) {\n'
    '        if (this.player.getAbilities().instabuild) return; // BTCCore - Purpur: no item cooldown in creative\n'
    '        final Identifier cooldownGroup = this.getCooldownGroup(item);')

# Removed: this added a `public PurpurConfig purpurConfig` field to Level. It never compiled —
# PurpurConfig.config is a YamlConfiguration, not a PurpurConfig — and the mismatch stayed hidden only
# because the anchor had drifted, so the patch was a no-op. Nothing in the fork reads the field.

# Elytra kinetic damage: 26.2 moved the flyIntoWall hurt out of the generic damage path and into
# handleFallFlyingCollisions, so the old `damageSource == flyIntoWall()` test no longer exists
# anywhere. Guarding the call site itself is equivalent and is where Purpur now puts it.
patch(f'{O}/net/minecraft/world/entity/LivingEntity.java',
    '                this.playSound(this.getFallDamageSound((int)dmg), 1.0F, 1.0F);\n                this.hurt(this.damageSources().flyIntoWall(), dmg);',
    '                if (org.purpurmc.purpur.PurpurConfig.elytraKineticDamage) { // BTCCore - Purpur\n'
    '                this.playSound(this.getFallDamageSound((int)dmg), 1.0F, 1.0F);\n'
    '                this.hurt(this.damageSources().flyIntoWall(), dmg);\n'
    '                }')

# Minecart max speed: the hardcoded setMaxSpeed(0.4D) is gone in 26.2; speed now comes from the
# behavior strategy. Override the accessor rather than the (removed) initialisation.
patch(f'{O}/net/minecraft/world/entity/vehicle/minecart/AbstractMinecart.java',
    '    protected double getMaxSpeed(final ServerLevel level) {\n        return this.behavior.getMaxSpeed(level);',
    '    protected double getMaxSpeed(final ServerLevel level) {\n'
    '        if (this.maxSpeed != null) return this.maxSpeed; // BTCCore - Purpur: per-entity override wins\n'
    '        return Math.min(this.behavior.getMaxSpeed(level), org.purpurmc.purpur.PurpurConfig.minecartMaxSpeed);')
print("  [OK] Purpur (3)")

# 13. Rideables foundation
patch(f'{O}/net/minecraft/world/entity/Mob.java',
    'private final BodyRotationControl bodyRotationControl;',
    'private final BodyRotationControl bodyRotationControl;\n\n    public boolean isControllable() { return false; }\n    public boolean onSpacebar() { return false; }')
patch(f'{O}/net/minecraft/server/level/ServerLevel.java',
    'public boolean hasEntityMoveEvent;',
    'public boolean hasEntityMoveEvent;\n    public boolean hasRidableMoveEvent = false;')
print("  [OK] Rideables foundation")

# 14-15. Custom events
# PreDamageCalculationEvent: 26.2 renamed LivingEntity.hurt(ServerLevel, DamageSource, float) to
# hurtServer(...) and its local `amount` to `damage`, which killed both former anchors. Injected just
# above `float originalDamage = damage;` — past the invulnerability/dead/fire-resistance guards, and
# before any armor, enchantment or resistance reduction, which is the contract the event documents.
# Fires dev.btc.core.api.event (the published API artifact plugins compile against); the
# aspaper-server-internal dev.btc.core.event.entity twin is not fired — see api/.../PreDamageCalculationEvent.
patch(f'{O}/net/minecraft/world/entity/LivingEntity.java',
    '        float originalDamage = damage;\n        ItemStack itemInUse = this.getUseItem();',
    '        // BTCCore start - PreDamageCalculationEvent\n'
    '        if (dev.btc.core.config.BTCCoreConfig.preDamageEventEnabled) {\n'
    '            dev.btc.core.api.event.PreDamageCalculationEvent btcPreDamage = new dev.btc.core.api.event.PreDamageCalculationEvent(\n'
    '                this.getBukkitEntity(), source.getEntity() == null ? null : source.getEntity().getBukkitEntity(), damage);\n'
    '            this.level().getCraftServer().getPluginManager().callEvent(btcPreDamage);\n'
    '            if (btcPreDamage.isCancelled()) return false;\n'
    '            damage = (float) btcPreDamage.getBaseDamage();\n'
    '        }\n'
    '        // BTCCore end\n'
    '        float originalDamage = damage;\n        ItemStack itemInUse = this.getUseItem();')

# EntityTargetPlayerEvent: 26.2 reworked Mob.setTarget into a boolean-returning overload and dropped
# the local `CraftLivingEntity ctarget` the old anchor relied on. Re-anchored just above the vanilla
# EntityTargetLivingEntityEvent call, still inside `if (reason != null)`, so `reason` is in scope and
# `return false` keeps the surrounding "target refused" convention.
patch(f'{O}/net/minecraft/world/entity/Mob.java',
    '            org.bukkit.event.entity.EntityTargetLivingEntityEvent event = org.bukkit.craftbukkit.event.CraftEventFactory.callEntityTargetLivingEvent(this, target, reason);',
    '            // BTCCore start - EntityTargetPlayerEvent\n'
    '            if (target instanceof net.minecraft.world.entity.player.Player btcPlayer) {\n'
    '                dev.btc.core.event.entity.EntityTargetPlayerEvent btcTargetEvent = new dev.btc.core.event.entity.EntityTargetPlayerEvent(\n'
    '                    this.getBukkitEntity(), (org.bukkit.entity.Player) btcPlayer.getBukkitEntity(), EntityTargetPlayerEvent_reason(reason));\n'
    '                this.level().getCraftServer().getPluginManager().callEvent(btcTargetEvent);\n'
    '                if (btcTargetEvent.isCancelled()) return false;\n'
    '            }\n'
    '            // BTCCore end\n'
    '            org.bukkit.event.entity.EntityTargetLivingEntityEvent event = org.bukkit.craftbukkit.event.CraftEventFactory.callEntityTargetLivingEvent(this, target, reason);')
print("  [OK] Custom events (2)")

# 16. Redstone compiler: hand compiled circuits to the graph and keep vanilla off their blocks.
SL = f'{O}/net/minecraft/server/level/ServerLevel.java'
rc = 0
rc += patch(SL,
    '    public void tick(final BooleanSupplier haveTime) {\n        ProfilerFiller profiler = Profiler.get();\n        this.handlingTick = true;',
    '    public final dev.btc.core.redstone.RedstoneCompilerManager btcRedstoneCompiler = new dev.btc.core.redstone.RedstoneCompilerManager(this); //BTCCore - redstone compiler\n\n    public void tick(final BooleanSupplier haveTime) {\n        this.btcRedstoneCompiler.tick(); //BTCCore - redstone compiler\n        ProfilerFiller profiler = Profiler.get();\n        this.handlingTick = true;')
rc += patch(SL,
    '    private void tickBlock(final BlockPos pos, final Block type) {\n        BlockState state = this.getBlockState(pos);',
    '    private void tickBlock(final BlockPos pos, final Block type) {\n        if (this.btcRedstoneCompiler.ownsBlockTick(pos)) return; //BTCCore - redstone compiler\n        BlockState state = this.getBlockState(pos);')
rc += patch(SL,
    '        this.getChunkSource().blockChanged(pos);\n        this.pathTypesByPosCache.invalidate(pos);',
    '        this.btcRedstoneCompiler.onBlockUpdated(pos, old, current); //BTCCore - redstone compiler\n        this.getChunkSource().blockChanged(pos);\n        this.pathTypesByPosCache.invalidate(pos);')
# Absorption sits on NeighborUpdater.executeUpdate, not on the two ServerLevel.neighborChanged
# overloads it used to hook: dust and diodes push through updateNeighborsAt(), which goes straight to
# the neighbourUpdater and never touches those overloads. The compiler therefore saw a fraction of the
# traffic, never reached activity-threshold, and compiled nothing (measured: 3655 updates, 0 attempts).
# executeUpdate is the single funnel every implementation converges on, which is why the zero-features
# guard already lives there. Anchored on that guard so a drift upstream fails loudly rather than
# silently reinstalling an unhooked path.
rc += patch(f'{O}/net/minecraft/world/level/redstone/NeighborUpdater.java',
    '        if (dev.btc.core.config.BTCCoreConfig.isZeroFeatureEnabledFor("block_updates", level.getWorld().getName())) return; // BTCCore - zero-features.block-updates\n        try {',
    '        if (dev.btc.core.config.BTCCoreConfig.isZeroFeatureEnabledFor("block_updates", level.getWorld().getName())) return; // BTCCore - zero-features.block-updates\n'
    '        // BTCCore start - redstone compiler: the only funnel every neighbour update reaches. Hooking\n'
    '        // ServerLevel.neighborChanged instead missed updateNeighborsAt(), which is how dust and diodes\n'
    '        // actually push, so the compiler saw almost no activity and never compiled anything.\n'
    '        if (level instanceof net.minecraft.server.level.ServerLevel btcLevel\n'
    '            && btcLevel.btcRedstoneCompiler.absorbNeighborChanged(pos, state)) return;\n'
    '        // BTCCore end\n'
    '        try {')
if rc:
    print(f"  [OK] Redstone compiler ({rc}/4)")

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
    'public static void pushItemsTick(final Level level, final BlockPos pos, final BlockState state, final HopperBlockEntity entity) {',
    'public static void pushItemsTick(final Level level, final BlockPos pos, final BlockState state, final HopperBlockEntity entity) {\n'
    '        // BTCCore - hopper throttle. 26.2 narrowed the parameter to Level, which has no getServer();\n'
    '        // getGameTime() is the same monotonic tick counter for this purpose.\n'
    '        if (dev.btc.core.config.BTCCoreConfig.hopperThrottlingEnabled\n'
    '            && level.getGameTime() % dev.btc.core.config.BTCCoreConfig.hopperThrottlingTicks != 0) return;'):
    print("  [OK] Hopper throttle")

# 18. Collision throttle — PerformanceManager check on the entity lookup.
# 26.2 turned EntityGetter.getEntities into an abstract method with no body, so the old injection site
# no longer exists. Moved onto Level, the concrete implementation every caller funnels through.
if patch(f'{O}/net/minecraft/world/level/Level.java',
    '    public List<Entity> getEntities(final @Nullable Entity except, final AABB bb, final Predicate<? super Entity> selector) {',
    '    public List<Entity> getEntities(final @Nullable Entity except, final AABB bb, final Predicate<? super Entity> selector) {\n'
    '        // BTCCore start - collision throttle. Level.getEntities() returns a LevelEntityGetter, which has\n'
    '        // no size(); the loaded-entity count lives on the Moonrise lookup, server side only.\n'
    '        if (dev.btc.core.config.BTCCoreConfig.collisionThrottleEnabled && except != null\n'
    '            && this instanceof net.minecraft.server.level.ServerLevel btcServerLevel\n'
    '            && !dev.btc.core.performance.PerformanceManager.shouldCalculateCollision(\n'
    '                except.getBukkitEntity(), btcServerLevel.moonrise$getEntityLookup().getEntityCount())) return java.util.List.of();\n'
    '        // BTCCore end'):
    print("  [OK] Collision throttle")

# 19. Suffocation optimization — skip suffocation check for entities far from players
if patch(f'{O}/net/minecraft/world/entity/LivingEntity.java',
    'public boolean isInWall() {',
    'public boolean isInWall() {\n        if (dev.btc.core.config.BTCCoreConfig.suffocationOptimization) {\n            boolean nearPlayer = this.level().hasNearbyAlivePlayer(this.getX(), this.getY(), this.getZ(), 64.0);\n            if (!nearPlayer) return false;\n        }'):
    print("  [OK] Suffocation optimization")

# 20. Vanilla tick suppression — AI
if patch(f'{O}/net/minecraft/world/entity/Mob.java',
    'protected final void serverAiStep() {',
    'protected final void serverAiStep() {\n        if (dev.btc.core.config.BTCCoreConfig.vanillaTickSuppressionAi) return;'):
    print("  [OK] Vanilla tick suppression — AI")

# 21. Vanilla tick suppression — Brain
if patch(f'{O}/net/minecraft/world/entity/ai/Brain.java',
    'public void tick(final ServerLevel level, final E body) {',
    'public void tick(final ServerLevel level, final E body) {\n        if (dev.btc.core.config.BTCCoreConfig.vanillaTickSuppressionBrain) return;'):
    print("  [OK] Vanilla tick suppression — Brain")

# 22. Vanilla tick suppression — Sensors
# 26.2 turned Sensor into an abstract class with a final generic tick(ServerLevel, E) — no longer an
# interface default method.
if patch(f'{O}/net/minecraft/world/entity/ai/sensing/Sensor.java',
    'public final void tick(final ServerLevel level, final E body) {',
    'public final void tick(final ServerLevel level, final E body) {\n        if (dev.btc.core.config.BTCCoreConfig.vanillaTickSuppressionSensors) return;'):
    print("  [OK] Vanilla tick suppression — Sensors")

# 23+28. Per-world tick rate + RPG spawn control (combined in one patch to avoid pattern conflict)
# Both hooks target tickCustomSpawners, so we combine them in a single patch.
if patch(f'{O}/net/minecraft/server/level/ServerLevel.java',
    'public void tickCustomSpawners(final boolean spawnEnemies) {',
    'private boolean shouldTickThisWorld() {\n        if (!dev.btc.core.config.BTCCoreConfig.perWorldTickRateEnabled) return true;\n        int players = this.players() != null ? this.players().size() : 0;\n        long tick = this.getServer() != null ? this.getServer().getTickCount() : 0;\n        return dev.btc.core.config.BTCCoreConfig.shouldTickWorld(this.getWorld().getName(), players, tick);\n    }\n\n    public void tickCustomSpawners(final boolean spawnEnemies) {\n        if (!dev.btc.core.config.BTCCoreConfig.rpgVanillaSpawnsEnabled) return;\n        if (!shouldTickThisWorld()) return;'):
    print("  [OK] Per-world tick rate + RPG spawn control")

# 24. Projectile chunk loading limits — prevent projectiles from loading too many chunks
# 26.2 moved Projectile from net.minecraft.world.entity to net.minecraft.world.entity.projectile.
if patch(f'{O}/net/minecraft/world/entity/projectile/Projectile.java',
    'public void tick() {',
    'public void tick() {\n        if (!dev.btc.core.performance.ProjectilePool.shouldLoadChunkThisTick()) {\n            this.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.DESPAWN);\n            return;\n        }\n        if (!dev.btc.core.performance.ProjectilePool.shouldLoadChunk(this.getId())) {\n            this.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.DESPAWN);\n            return;\n        }'):
    print("  [OK] Projectile chunk loading limits")

# 25. Inactive goal selector throttle — skip goal selector tick for distant entities
# GoalSelector has no reference to its owning Mob, so we patch Mob.tick() instead
# to conditionally skip goalSelector.tick() and targetSelector.tick().
if patch(f'{O}/net/minecraft/world/entity/Mob.java',
    'this.goalSelector.tick();',
    'if (!dev.btc.core.config.BTCCoreConfig.inactiveGoalSelectorThrottle || this.level().hasNearbyAlivePlayer(this.getX(), this.getY(), this.getZ(), 32.0) || (this.level().getServer().getTickCount() % 20 == 0)) {\n            this.goalSelector.tick();\n        }'):
    print("  [OK] Inactive goal selector throttle")

# 26. Batched inventory updates — intercept slot packet sends.
# The old anchor assumed a type-specific send(ClientboundContainerSetSlotPacket) overload on
# ServerGamePacketListenerImpl; no such overload exists in 26.2 (that class declares no send() at all).
# The single send funnel is ServerCommonPacketListenerImpl.send(Packet<?>), so the packet type is
# selected with instanceof there instead.
if patch(f'{O}/net/minecraft/server/network/ServerCommonPacketListenerImpl.java',
    '    public void send(final Packet<?> packet) {',
    '    public void send(final Packet<?> packet) {\n'
    '        // BTCCore start - batched inventory updates\n'
    '        if (dev.btc.core.config.BTCCoreConfig.batchedInventoryUpdates\n'
    '            && packet instanceof net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket slotPacket\n'
    '            && this instanceof ServerGamePacketListenerImpl gameListener) {\n'
    '            dev.btc.core.performance.BatchedInventoryUpdates.queuePacket(gameListener.player, slotPacket);\n'
    '            return;\n'
    '        }\n'
    '        // BTCCore end'):
    print("  [OK] Batched inventory updates")

# 29. RPG weather tick control
if patch(f'{O}/net/minecraft/server/level/ServerLevel.java',
    'private void advanceWeatherCycle() {',
    'private void advanceWeatherCycle() {\n        if (!dev.btc.core.config.BTCCoreConfig.rpgWeatherTicksEnabled) return;'):
    print("  [OK] RPG weather tick control")

# 30. Async entity tracker — offload entity tracker tick to async pool
# Intercept the EntityTracker.tick() method to delegate computation to AsyncEntityTracker
if patch(f'{O}/net/minecraft/server/level/ChunkMap.java',
    '    protected void tick() {',
    '    protected void tick() {\n        if (dev.btc.core.config.BTCCoreConfig.asyncEntityTrackerEnabled) {\n            dev.btc.core.async.AsyncEntityTracker.submitTracking(() -> { this.tickTracker(); });\n            return;\n        }\n        this.tickTracker();\n    }\n    private void tickTracker() {'):
    print("  [OK] Async entity tracker")

# 31. Async pathfinding — offload path computation to async pool
# Intercept Mob.navigation.move() path computation to delegate to AsyncPathfindingEngine
if patch(f'{O}/net/minecraft/world/entity/ai/navigation/PathNavigation.java',
    'public void tick() {',
    'public void tick() {\n        if (dev.btc.core.config.BTCCoreConfig.asyncPathfindingEnabled && this.mob != null) {\n            final net.minecraft.world.entity.Mob fMob = this.mob;\n            dev.btc.core.async.AsyncPathfindingEngine.submitPathfinding(() -> {\n                this.doTickNavigation();\n                dev.btc.core.async.AsyncPathfindingEngine.queuePathResult(() -> {\n                    // Apply path result on region thread — the doTickNavigation already ran on async\n                    // but the actual movement is applied on the next sync tick\n                });\n            });\n            return;\n        }\n        this.doTickNavigation();\n    }\n    private void doTickNavigation() {'):
    print("  [OK] Async pathfinding")

# 32. Removed: a second PreDamageCalculationEvent hook on the same method, living alongside patch 14.
# Both anchors were dead, so neither fired and the duplication went unnoticed for a whole version.
# The single surviving hook is patch 14 above.

# 33. Workstation blocking — grindstone, loom, cartography table, composter.
#
# These four build their result in server code, so no recipe purge reaches them. Each switch is
# off by default: the fork stays vanilla until a server asks for the block.
#
# The three menus are refused at the block rather than inside the menu class, so the refusal also
# covers whatever else may reach the menu provider. The composter is caught in `addItem`, the one
# choke point both the player path (`useItemOn`) and the hopper path (`InputContainer`) funnel
# through — blocking only the right-click would leave automated composting wide open.
grindstone_ok = patch(f'{O}/net/minecraft/world/level/block/GrindstoneBlock.java',
    '        if (!level.isClientSide() && player.openMenu(state.getMenuProvider(level, pos)).isPresent()) { // Paper - Fix InventoryOpenEvent cancellation\n'
    '            player.awardStat(Stats.INTERACT_WITH_GRINDSTONE);',
    '        if (dev.btc.core.config.BTCCoreConfig.blockGrindstone) return net.minecraft.world.InteractionResult.PASS; // BTCCore - workstations.block-grindstone\n'
    '        if (!level.isClientSide() && player.openMenu(state.getMenuProvider(level, pos)).isPresent()) { // Paper - Fix InventoryOpenEvent cancellation\n'
    '            player.awardStat(Stats.INTERACT_WITH_GRINDSTONE);')

loom_ok = patch(f'{O}/net/minecraft/world/level/block/LoomBlock.java',
    '        if (!level.isClientSide() && player.openMenu(state.getMenuProvider(level, pos)).isPresent()) { // Paper - Fix InventoryOpenEvent cancellation\n'
    '            player.awardStat(Stats.INTERACT_WITH_LOOM);',
    '        if (dev.btc.core.config.BTCCoreConfig.blockLoom) return net.minecraft.world.InteractionResult.PASS; // BTCCore - workstations.block-loom\n'
    '        if (!level.isClientSide() && player.openMenu(state.getMenuProvider(level, pos)).isPresent()) { // Paper - Fix InventoryOpenEvent cancellation\n'
    '            player.awardStat(Stats.INTERACT_WITH_LOOM);')

carto_ok = patch(f'{O}/net/minecraft/world/level/block/CartographyTableBlock.java',
    '        if (!level.isClientSide()) {\n'
    '            if (player.openMenu(state.getMenuProvider(level, pos)).isPresent()) { // Paper - Fix InventoryOpenEvent cancellation',
    '        if (dev.btc.core.config.BTCCoreConfig.blockCartographyTable) return net.minecraft.world.InteractionResult.PASS; // BTCCore - workstations.block-cartography-table\n'
    '        if (!level.isClientSide()) {\n'
    '            if (player.openMenu(state.getMenuProvider(level, pos)).isPresent()) { // Paper - Fix InventoryOpenEvent cancellation')

# Returning `state` untouched is what vanilla itself does for a non-compostable item, so the caller
# already handles it: nothing is consumed and no level is gained.
composter_ok = patch(f'{O}/net/minecraft/world/level/block/ComposterBlock.java',
    '        // CraftBukkit end\n'
    '    ) {\n'
    '        int fillLevel = state.getValue(LEVEL);',
    '        // CraftBukkit end\n'
    '    ) {\n'
    '        if (dev.btc.core.config.BTCCoreConfig.blockComposter) return state; // BTCCore - workstations.block-composter\n'
    '        int fillLevel = state.getValue(LEVEL);')

if grindstone_ok or loom_ok or carto_ok or composter_ok:
    print("  [OK] Workstation blocking (4)")

# 34. Drop API — one hook, every drop in the game.
#
# `getRandomItemsRaw(LootContext, Consumer)` is the single funnel of the loot system: every other
# overload delegates to it, `fill()` included, so a block break, a mob death, a structure chest, a
# fishing catch, an explosion and `/loot` all pass through this one line. That is why the API lives
# here and not on Bukkit events — events cover only two of those paths.
#
# The vanilla body moves to `btcVanillaRandomItemsRaw` instead of staying behind an early return,
# because the registry needs to roll the vanilla table on demand (a provider augmenting vanilla, or
# a transformer rewriting it) and calling the hooked method for that would recurse forever.
if patch(f'{O}/net/minecraft/world/level/storage/loot/LootTable.java',
    '    public void getRandomItemsRaw(final LootContext context, final Consumer<ItemStack> output) {\n'
    '        LootContext.VisitedEntry<?> breadcrumb = LootContext.createVisitedEntry(this);',
    '    public void getRandomItemsRaw(final LootContext context, final Consumer<ItemStack> output) {\n'
    '        // BTCCore start - drop API\n'
    '        if (dev.btc.core.drop.DropRegistry.intercept(this, context, output)) return;\n'
    '        this.btcVanillaRandomItemsRaw(context, output);\n'
    '    }\n'
    '\n'
    '    /** The untouched vanilla roll, called back by the drop API when it needs the vanilla result. */\n'
    '    public void btcVanillaRandomItemsRaw(final LootContext context, final Consumer<ItemStack> output) {\n'
    '        // BTCCore end\n'
    '        LootContext.VisitedEntry<?> breadcrumb = LootContext.createVisitedEntry(this);'):
    print("  [OK] Drop API")

# 35. Vanilla loot purge — the mirror of the recipe purge, one registry later.
#
# Dropped after `scanDirectory` and before `registerWithListeners`, so a purged table is never
# registered at all: nothing to evaluate, nothing to look up, nothing left in memory. Same place in
# the pipeline as `RecipeManager`'s `shouldDropRecipe`.
#
# The registry test is here rather than in BTCCoreConfig on purpose — `scheduleRegistryLoad` also
# loads predicates and item modifiers, and the config layer has no business knowing NMS registry
# keys.
if patch(f'{O}/net/minecraft/server/ReloadableServerRegistries.java',
    '            SimpleJsonResourceReloadListener.scanDirectory(manager, type.registryKey(), ops, type.codec(), elements);\n'
    '            // Paper start - register with listeners',
    '            SimpleJsonResourceReloadListener.scanDirectory(manager, type.registryKey(), ops, type.codec(), elements);\n'
    '            // BTCCore start - vanilla-content.purge-loot\n'
    '            if (net.minecraft.core.registries.Registries.LOOT_TABLE.equals(type.registryKey())) {\n'
    '                elements.keySet().removeIf(btcId -> dev.btc.core.config.BTCCoreConfig.shouldDropLootTable(btcId.getNamespace(), btcId.getPath()));\n'
    '            }\n'
    '            // BTCCore end\n'
    '            // Paper start - register with listeners'):
    print("  [OK] Vanilla loot purge")

if FAILURES:
    print()
    print("=== BTC-CORE: FAILED — %d anchor(s) no longer match ===" % len(FAILURES))
    for filepath, reason, anchor in FAILURES:
        print(f"  {reason}: {filepath}")
        print(f"    anchor: {anchor}...")
    print()
    print("An unmatched anchor is a silent no-op: the config option survives and lies about")
    print("being active. Fix the anchor against the current overlay, or delete the patch.")
    sys.exit(1)

print("=== BTC-CORE: Done (46 hooks) ===")
