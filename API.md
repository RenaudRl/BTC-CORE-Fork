# BTC Core Public API

## Overview

BTC Core provides a public API for external plugins to access its features. The API is split into three parts:

- **`dev.btc.core.api.BTCCoreAPI`** — General features (Zero Features, combat, vanish, performance, etc.)
  - Interface in the `api` module, implementation resolved at runtime via `Services.service()`
- **`com.infernalsuite.asp.api.BTCCoreVisualAPI`** — Packet-based display entities and virtual inventories
- **`com.infernalsuite.asp.api.BTCCoreAPI`** (interface) — SlimeWorld management (load, clone, migrate)

## Maven Dependency

```xml
<repository>
    <id>btcstudio</id>
    <url>https://borntocraftstudio.net/public/repo/</url>
</repository>

<dependency>
    <groupId>dev.btc.core</groupId>
    <artifactId>api</artifactId>
    <version>26.2.build.1-alpha</version>
    <scope>provided</scope>
</dependency>
```

## BTCCoreAPI (dev.btc.core.api)

### Getting the API instance

```java
BTCCoreAPI api = BTCCoreAPI.instance();
```

### Zero Features

```java
boolean noRecipes = api.isZeroFeatureEnabledFor("recipes", worldName);
boolean noAdvancements = api.isZeroFeatureEnabledFor("advancements", worldName);
boolean noCollisions = api.isZeroFeatureEnabledFor("collisions", worldName);
```

### Block Value Cache (Island Level)

```java
// Get cached chunk value (uses world name, not NMS level)
double value = api.getChunkValue(worldName, chunkX, chunkZ);

// Update cache
api.setChunkValue(worldName, chunkX, chunkZ, newValue);
api.addToChunkValue(worldName, chunkX, chunkZ, delta);
api.invalidateChunk(worldName, chunkX, chunkZ);
```

### Combat System

```java
boolean inCombat = api.isInCombat(player);
int remaining = api.getRemainingCombatTime(player);
api.tagCombat(player);
api.untagCombat(player);
```

### Vanish

```java
int level = api.getVanishLevel(player);
boolean vanished = api.isVanished(player);
```

### Performance

```java
boolean shouldCollide = api.shouldCalculateCollision(entity, nearbyCount);
boolean shouldSend = api.shouldSendParticle(player, location);
boolean shouldSound = api.shouldSendSound(player, location);
```

### Cross-World Entity Transfer

```java
List<Entity> transferred = api.transferOwnedEntities(player, destination);
List<Entity> owned = api.findOwnedEntities(player, 16.0);
boolean owned = api.isOwnedBy(entity, playerUUID);
```

### CPS

```java
int cps = api.getPlayerCPS(player);
```

### DAB Exemption

Force an entity to always tick its AI, bypassing DAB (Dynamic Activation of Brain).
Useful for boss mobs that must remain active regardless of player proximity.

```java
api.setEntityAlwaysTick(bossEntity);
boolean alwaysTicks = api.isEntityAlwaysTick(entity);
```

### MSPT Monitoring

Get the current server MSPT (milliseconds per tick). Plugins can throttle
async tasks when the server is under load.

```java
double mspt = api.getCurrentMspt();
if (mspt > 40.0) {
    // Server is lagging — back off async work
}
```

### Maintenance Mode

```java
boolean maintenance = api.isMaintenanceMode();
```

### Join Queue

```java
int position = api.getQueuePosition(uuid);
int size = api.getQueueSize();
```

## BTCCoreVisualAPI (com.infernalsuite.asp.api)

Thread-safe packet dispatch via Folia's region scheduler. All methods can be called from any thread.

### Virtual Inventory

```java
BTCCoreVisualAPI api = BTCCoreVisualAPI.getInstance();
api.sendAsyncVirtualInventory(player, containerId, stateId, contents);
```

### Display Entities

```java
VirtualDisplaySpec spec = VirtualDisplaySpec
    .builder(VirtualDisplayType.TEXT, location)
    .text(Component.text("42"))
    .billboard(Display.Billboard.CENTER)
    .interpolationDuration(10)
    .lifetimeTicks(20)
    .build();

VirtualDisplayHandle handle = api.spawnDisplay(player, spec);
api.updateDisplay(
    handle,
    VirtualDisplayUpdate.transform(newLocation, newTransformation, 10)
);
api.destroyDisplay(handle);
```

The handle is bound to its viewer. Updates are sparse, display interpolation
runs client-side, and a positive `lifetimeTicks` automatically removes the
display through the viewer's Folia entity scheduler.

The legacy `spawnAsyncDisplayEntity`, `updateAsyncDisplayEntity`, and
`destroyAsyncDisplayEntity` methods remain temporarily available for existing
consumers.

## SlimeWorld API (com.infernalsuite.asp.api.BTCCoreAPI)

```java
BTCCoreAPI slimeAPI = BTCCoreAPI.instance();

// Read a world from a loader
SlimeWorld world = slimeAPI.readWorld(loader, "worldName", false, propertyMap);

// Load it into the server
SlimeWorldInstance instance = slimeAPI.loadWorld(world, true);

// Clone an unloaded world (for island templates)
SlimeWorld clone = slimeAPI.cloneUnloadedWorld("template", "player_island", loader, null);

// Save and migrate
slimeAPI.saveWorld(world);
slimeAPI.migrateWorld("worldName", oldLoader, newLoader);
```

## Custom Events

### EntityTargetPlayerEvent

Fired when an entity targets a player. Can be cancelled.

```java
@EventHandler
public void onTarget(EntityTargetPlayerEvent event) {
    Player target = event.getTarget();
    EntityTargetPlayerEvent.TargetReason reason = event.getReason();
    event.setCancelled(true); // Cancel targeting
}
```

### PreDamageCalculationEvent

Fired BEFORE vanilla damage calculation (armor, enchant, resistance).
Plugins can read, modify, or cancel the base damage before any reduction is applied.
NMS hook #32 in `LivingEntity.hurt()` — fires before `actuallyHurt()` is called.

```java
@EventHandler(priority = EventPriority.LOW)
public void onPreDamage(PreDamageCalculationEvent event) {
    Entity damagee = event.getDamagee();
    Entity damager = event.getDamager();
    double baseDamage = event.getBaseDamage();

    // Modify damage before armor calculation
    event.setBaseDamage(baseDamage * 1.5); // 50% more damage

    // Or cancel entirely
    event.setCancelled(true);
}
```
