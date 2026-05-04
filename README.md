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
python scripts/apply-btccore-patches.py
./gradlew :aspaper-server:createPaperclipJar --offline
```

Deploy: `java -Xms4G -Xmx6G -XX:+UseZGC -jar aspaper-paperclip-26.1.2.build.19-alpha.jar nogui`

## 🧱 Developer API

### 🐘 Gradle (Kotlin DSL)
```kotlin
repositories { maven("https://borntocraftstudio.net/repo/") }
dependencies { compileOnly("dev.btc.core:api:26.1.2.build.19-alpha") }
java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
```

### 📦 Maven
```xml
<repository><id>btcstudio</id><url>https://borntocraftstudio.net/repo/</url></repository>
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
| **AdvancedSlimePaper** | ✅ World Management | Native SRF, database backends (MySQL/Redis/Mongo), instant instancing |
| **Purpur** | ✅ Gameplay | WASD minecarts, elytra physics, silk-touch spawners |
| **Pufferfish** | ✅ Entity Optimization | SIMD vectorization, async mob spawning, DEAR/DAB |
| **Canvas** | ✅ Chunk System | Priority-based loading, Moonrise executor |

## 🎯 Design Philosophy

BTC-CORE follows a **"cherry-picking"** strategy — the best optimizations from each fork, adapted for **Folia's regionized threading**.

> [!WARNING]
> BTC-CORE introduces deep architectural changes. Standard Spigot/Paper plugins may not work. Use Folia-compatible plugins.

## 🚀 Key Features

### ⚡ Concurrency (Folia)
- Regionized Multithreading, Parallel World Ticking, Mid-Tick Task Execution

### 🌍 World Management (SlimeWorld)
- Native SRF, MySQL/Redis/Mongo/File backends, Instant Instancing, Game Rules Config, copperFade

### 🛠 Performance
- Async Entity Tracker, Async Pathfinding, Async Mob Spawning
- Hopper/Collision/Redstone Throttle, Scoreboard Optimization, Light Update Throttle
- NBT Compression Cache, Chunk Prefetch, Batched Inventory Updates

### 🎮 Gameplay (Purpur)
- Controllable Minecarts (WASD, configurable speed), Elytra Physics, Silk-Touch Spawners, Ender Pearl no-cooldown creative

### 🛡 Security
- FreedomChat (chat reporting prevention), CPS Limiting, Combat Log, Reach Validation, Exploit Logging
- Anticheat: anticheat.yml with CPS/reach/movement/combat/auto-ban

### ✨ QoL
- Maintenance Mode, Join Queue, Teleport Warmup, Vanish Levels, Player Data Backup

### 🔬 Zero Features
Disable vanilla mechanics for performance: Stats, Advancements, Light Engine, Void Generator, Collisions, Cramming, Block Updates, Sleep Tick

### 📊 BlockValueCache
Per-chunk cache for fast island level computation (BTC Sky integration).

## 📚 API
```java
BTCCoreAPI.instance().createWorld(loader, name, properties);
double val = BlockValueCache.getChunkValue(level, chunkX, chunkZ);
```

## 📂 Structure
```
├── aspaper-server/    # Server fork + 31 modules owned
├── api/               # Public API
├── plugin/            # Bukkit plugin
├── loaders/           # MySQL, Redis, Mongo, File, API
├── scripts/           # apply-btccore-patches.py
└── buildSrc/          # Gradle configuration
```

## 📄 Licence
GNU General Public License v3.0
