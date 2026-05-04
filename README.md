# BTC-CORE

![Java Version](https://img.shields.io/badge/Java-25-orange)
![Build Status](https://img.shields.io/badge/build-passing-brightgreen)
![Target](https://img.shields.io/badge/Target-BTCCORE%2026.1.2-blue)
![Base](https://img.shields.io/badge/Base-AdvancedSlimePaper%2026.1.2-purple)
[![Wiki](https://img.shields.io/badge/Wiki-DeepWiki-blue)](https://deepwiki.com/RenaudRl/BTC-CORE)

## 📖 Documentation

For detailed guides, API references, and internal logic explanations, visit our official Wiki:
👉 **[BTC-CORE Deep Wiki](https://deepwiki.com/RenaudRl/BTC-CORE)**

## 🛠 Building & Deployment

BTC-CORE uses **Paperweight v2 (Moonrise)**. Requires **Java 25** and Gradle 9.x.

```bash
git clone https://github.com/RenaudRl/BTC-CORE-Fork.git
cd BTC-CORE-Fork && git checkout dev/26.1.1
./gradlew applyAllPatches --offline
python scripts/apply-btccore-patches.py    # python, pas python3 !
./gradlew :aspaper-server:createPaperclipJar --offline
```

Deploy: `java -Xms4G -Xmx6G -XX:+UseZGC -jar aspaper-paperclip-26.1.2.build.19-alpha.jar nogui`

## 🧱 Developer API (Maven/Gradle)

### 🐘 Gradle (Kotlin DSL)
```kotlin
repositories {
    maven("https://borntocraftstudio.net/repo/")
}
dependencies {
    compileOnly("dev.btc.core:api:26.1.2.build.19-alpha")
}
java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}
```

### 📦 Maven
```xml
<repository>
    <id>btcstudio</id>
    <url>https://borntocraftstudio.net/repo/</url>
</repository>
<dependency>
    <groupId>dev.btc.core</groupId>
    <artifactId>api</artifactId>
    <version>26.1.2.build.19-alpha</version>
    <scope>provided</scope>
</dependency>
```

## 🧪 Fork Heritage

| Fork | Integration | Key Features |
|------|-------------|--------------|
| **Paper** | 🧩 Base | Async chunk loading, modern API, performance patches |
| **Folia** | ✅ Regionized Threading | Multi-threaded world regions, region schedulers |
| **AdvancedSlimePaper** | ✅ World Management | Native SRF support, database backends (MySQL/Redis/Mongo), instant instancing |
| **Purpur** | ✅ Gameplay Features | WASD minecarts, elytra physics, silk-touch spawners |
| **Pufferfish** | ✅ Entity Optimization | SIMD vectorization, async mob spawning, DEAR/DAB |
| **Canvas** | ✅ Chunk System | Rewritten chunk executor, priority-based loading (Moonrise) |

## 🎯 Design Philosophy

BTC-CORE follows a **"cherry-picking"** strategy:
- We actively select the best optimizations and features from each fork
- All features are adapted for **Folia's regionized threading** model

> [!WARNING]
> **DEVELOPER COMPATIBILITY NOTICE**
> BTC-CORE introduces deep architectural changes affecting plugin compatibility. Many standard Spigot/Paper plugins will not work out of the box. Use Folia-compatible plugins or adapt existing ones. Leverage the BTCCoreAPI for world management.

## 🚀 Key Features

### ⚡ Concurrency & Threading (Folia)
- **Regionized Multithreading**: Different world regions on separate threads
- **Parallel World Ticking**: Separate worlds tick concurrently on dedicated thread pool
- **Mid-Tick Task Execution**: Chunk-related tasks during idle mid-tick periods

### 🌍 World Management (SlimeWorld)
- **Native SRF Support**: Ultra-fast world loading and saving
- **Database Backends**: MySQL, Redis, MongoDB, File, API
- **Instant Instancing**: Create, clone, dispose temporary worlds without filesystem overhead
- **SlimeWorld Game Rules Config**: YAML configuration per world/pattern
- **Custom Game Rules**: `copperFade`, `randomTickSpeed` per-world

### 🛠 Core Optimizations
- **Async Entity Tracker** (Leaf): Entity tracking on separate threads
- **Async Pathfinding** (Leaf): Multi-threaded mob pathfinding
- **Async Mob Spawning** (Pufferfish): Prevents tick loss during spawn events
- **Hopper Optimization**: Throttles hoppers when destinations are full
- **Entity Collision Throttle**: Reduces collision checks in crowded areas
- **Redstone Optimization**: Alternate Current (Moonrise) + configurable throttle
- **Scoreboard Optimization**: Only sends updates to players with visible objectives
- **Light Update Throttle**: Limits light recalculations per tick (Zero Features)
- **NBT Compression Cache**: Caches compressed NBT for frequent items
- **Chunk Prefetch**: Pre-loads destination chunks before teleport
- **Batched Inventory Updates**: Combines inventory packets for efficiency

### 🎮 Gameplay (Purpur)
- **Controllable Minecarts**: WASD control, configurable speed
- **Advanced Elytra Physics**: Damage per second, kinetic toggle
- **Silk-Touch Spawners**: Mine spawners with silk touch
- **Ender Pearl Fixes**: No cooldown in creative mode

### 🛡 Security & Privacy
- **Native FreedomChat Integration**: Chat reporting prevention
- **CPS Limiting**: Configurable clicks-per-second detection
- **Combat Log Prevention**: Native combat tagging with kill-on-logout
- **Reach Validation**: Server-side attack distance validation
- **Exploit Logging**: Automatic logging of suspicious behavior
- **Anticheat**: anticheat.yml with CPS, reach, movement, combat, auto-ban

### ✨ Quality of Life
- **Maintenance Mode**: Whitelist with custom MOTD
- **Join Queue**: Rate-limited player joins
- **Teleport Warmup**: Configurable delay with cancel on move
- **Vanish Levels**: Tiered vanish with configurable visibility
- **Player Data Backup**: Automatic backup on join

### 🔬 Zero Features
Disable vanilla mechanics per world for performance:
- Stats, Advancements, Recipes
- Light Engine, Block Updates, Sleep Tick
- Collisions, Cramming
- Force Void Generator

### 📊 BlockValueCache
Per-chunk block value caching for fast island level computation (BTC Sky integration).

## 📚 API

```java
// World management
BTCCoreAPI.instance().createWorld(loader, name, properties);
BTCCoreAPI.instance().cloneWorld(name, newName);

// Island leveling
double val = BlockValueCache.getChunkValue(level, chunkX, chunkZ);
BlockValueCache.scanAndCacheChunk(chunk);

// Custom events
@EventHandler
public void onPreDamage(PreDamageCalculationEvent e) {
    e.setFinalDamage(e.getFinalDamage() * 1.5);
}
```

## 📝 Configuration

| File | Purpose |
|------|---------|
| `btccore.yml` | Performance, security, zero features, async |
| `purpur.yml` | Minecart, elytra, spawners, barrel, rideables |
| `anticheat.yml` | CPS, reach, movement, combat, auto-ban |

## 📂 Project Structure

```
BTC-CORE-Fork/
├── aspaper-server/          # Server fork (Minecraft patches + owned modules)
│   ├── src/main/java/dev/btc/core/   # 31 modules
│   └── src/main/java/org/purpurmc/   # Purpur integration
├── api/                     # Public API
├── plugin/                  # Bukkit plugin
├── loaders/                 # Database backends
├── scripts/                 # Build scripts
└── buildSrc/                # Gradle configuration
```

## 📄 Licence

GNU General Public License v3.0 — héritée d'AdvancedSlimePaper
