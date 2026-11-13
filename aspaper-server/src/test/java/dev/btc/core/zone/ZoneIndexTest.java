package dev.btc.core.zone;

import dev.btc.core.api.zone.Zone;
import dev.btc.core.api.zone.ZoneListener;
import dev.btc.core.world.LoadedWorlds;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The zones of {@code BTCCoreAPI#registerZone}: entered and left as the player changes block, teleports, changes world or quits. */
class ZoneIndexTest {

    private static final UUID WORLD = UUID.randomUUID();
    private static final UUID OTHER_WORLD = UUID.randomUUID();
    private static final int Y = 64;

    private final ZoneIndex index = new ZoneIndex(new LoadedWorlds());

    {
        this.index.worldLoaded(WORLD);
        this.index.worldLoaded(OTHER_WORLD);
    }
    private final Plugin plugin = plugin("Extension");
    private final Player player = player();
    private final List<String> calls = new ArrayList<>();

    private static Plugin plugin(String name) {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn(name);
        when(plugin.getLogger()).thenReturn(Logger.getLogger(name));
        when(plugin.isEnabled()).thenReturn(true);
        return plugin;
    }

    private static Player player() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn("Steve");
        return player;
    }

    /** A zone of the blocks from (x1, Y, z1) to (x2, Y + 3, z2), both included, that writes its calls down. */
    private Zone zone(String name, UUID world, int x1, int z1, int x2, int z2) {
        return this.zone(this.plugin, name, world, x1, z1, x2, z2);
    }

    private Zone zone(Plugin owner, String name, UUID world, int x1, int z1, int x2, int z2) {
        return this.index.register(owner, world, new BoundingBox(x1, Y, z1, x2 + 1, Y + 4, z2 + 1), new ZoneListener() {
            @Override
            public void onEnter(Player player, Zone zone) {
                ZoneIndexTest.this.calls.add("enter " + name);
            }

            @Override
            public void onLeave(Player player, Zone zone) {
                ZoneIndexTest.this.calls.add("leave " + name);
            }
        });
    }

    private List<String> at(UUID world, int x, int z) {
        this.calls.clear();
        this.index.at(this.player, world, x, Y, z);
        return List.copyOf(this.calls);
    }

    @Test
    @DisplayName("a player is entered once when it reaches the zone, never again while inside, and left when it steps out")
    void enterAndLeave() {
        this.zone("spawn", WORLD, 0, 0, 2, 2);

        assertEquals(List.of(), this.at(WORLD, -1, 0));
        assertEquals(List.of("enter spawn"), this.at(WORLD, 0, 0));
        assertEquals(List.of(), this.at(WORLD, 2, 2));
        assertEquals(List.of("leave spawn"), this.at(WORLD, 3, 2));
        assertEquals(List.of(), this.at(WORLD, 4, 2));
    }

    @Test
    @DisplayName("height counts: a block above or below the zone is outside it")
    void heightCounts() {
        this.zone("room", WORLD, 0, 0, 2, 2);
        this.calls.clear();

        this.index.at(this.player, WORLD, 1, Y + 3, 1);
        this.index.at(this.player, WORLD, 1, Y + 4, 1);
        this.index.at(this.player, WORLD, 1, Y - 1, 1);

        assertEquals(List.of("enter room", "leave room"), this.calls);
    }

    @Test
    @DisplayName("overlapping zones are entered and left each on its own, the leaves of a move before its enters")
    void overlappingZones() {
        this.zone("west", WORLD, 0, 0, 10, 10);
        this.zone("east", WORLD, 5, 0, 20, 10);

        assertEquals(List.of("enter west"), this.at(WORLD, 1, 1));
        assertEquals(List.of("enter east"), this.at(WORLD, 7, 1));
        assertEquals(List.of("leave west"), this.at(WORLD, 15, 1));
        assertEquals(Set.of("east"), this.names(this.index.zonesOf(this.player)));
        assertEquals(List.of("leave east", "enter west"), this.at(WORLD, 2, 1));
    }

    @Test
    @DisplayName("a teleport far away leaves the zone of the start and enters that of the arrival, a large one included")
    void teleport() {
        this.zone("start", WORLD, 0, 0, 2, 2);
        this.zone("arrival", WORLD, 10_000, 10_000, 10_002, 10_002);
        this.zone("region", WORLD, -50_000, -50_000, -1_000, -1_000); // too many columns for the grid: tested on every move

        this.at(WORLD, 1, 1);
        assertEquals(List.of("leave start", "enter arrival"), this.at(WORLD, 10_001, 10_001));
        assertEquals(List.of("leave arrival", "enter region"), this.at(WORLD, -20_000, -1_000));
        assertEquals(List.of("leave region"), this.at(WORLD, -999, -20_000));
    }

    @Test
    @DisplayName("a change of world leaves the zones of the old world, even at the same coordinates, and enters those of the new one")
    void changeOfWorld() {
        this.zone("here", WORLD, 0, 0, 2, 2);
        this.zone("there", OTHER_WORLD, 0, 0, 2, 2);

        this.at(WORLD, 1, 1);
        assertEquals(List.of("leave here", "enter there"), this.at(OTHER_WORLD, 1, 1));
        assertEquals(List.of("leave there"), this.at(UUID.randomUUID(), 1, 1));
    }

    @Test
    @DisplayName("a player who quits leaves the zones it stood in")
    void quit() {
        this.zone("spawn", WORLD, 0, 0, 2, 2);
        this.at(WORLD, 1, 1);
        this.calls.clear();

        this.index.quit(this.player);

        assertEquals(List.of("leave spawn"), this.calls);
        assertEquals(Set.of(), this.index.zonesOf(this.player));
        assertEquals(0, this.index.followed());
    }

    @Test
    @DisplayName("an unregistered zone drops its players without a leave, and is no longer entered")
    void unregister() {
        Zone spawn = this.zone("spawn", WORLD, 0, 0, 2, 2);
        this.at(WORLD, 1, 1);

        spawn.unregister();

        assertFalse(spawn.isRegistered());
        assertTrue(this.index.isEmpty());
        assertEquals(Set.of(), this.index.zonesOf(this.player));
        assertEquals(List.of(), this.at(WORLD, 5, 5));
        this.zone("other", WORLD, 100, 100, 101, 101);
        assertEquals(List.of(), this.at(WORLD, 1, 1));
        spawn.unregister(); // twice is nothing
        assertFalse(this.index.isEmpty());
    }

    @Test
    @DisplayName("a disabled plugin takes only its own zones, an unloaded world only the zones in it")
    void pluginAndWorldGoWithTheirZones() {
        Plugin other = plugin("Other");
        Zone mine = this.zone("mine", WORLD, 0, 0, 2, 2);
        Zone theirs = this.zone(other, "theirs", WORLD, 0, 0, 2, 2);
        Zone elsewhere = this.zone(other, "elsewhere", OTHER_WORLD, 0, 0, 2, 2);

        this.index.unregisterAll(this.plugin);
        assertFalse(mine.isRegistered());
        assertTrue(theirs.isRegistered());
        assertEquals(List.of("enter theirs"), this.at(WORLD, 1, 1));

        this.index.worldUnloaded(WORLD);
        assertFalse(theirs.isRegistered());
        assertTrue(elsewhere.isRegistered());
        assertEquals(List.of(), this.at(WORLD, 1, 1));
        elsewhere.unregister();
        assertTrue(this.index.isEmpty());
    }

    @Test
    @DisplayName("a listener that teleports its player is followed at once, and the calls of the old place that remain are skipped")
    void listenerThatTeleports() {
        this.index.register(this.plugin, WORLD, new BoundingBox(0, Y, 0, 3, Y + 4, 3), new ZoneListener() {
            @Override
            public void onEnter(Player who, Zone zone) {
                ZoneIndexTest.this.calls.add("enter trap");
                ZoneIndexTest.this.index.at(who, WORLD, 500, Y, 500);
            }

            @Override
            public void onLeave(Player who, Zone zone) {
                ZoneIndexTest.this.calls.add("leave trap");
            }
        });
        this.zone("beside", WORLD, 0, 0, 2, 2);
        this.zone("cell", WORLD, 500, 500, 500, 500);

        assertEquals(List.of("enter trap", "leave trap", "enter cell"), this.at(WORLD, 1, 1));
        assertEquals(Set.of("cell"), this.names(this.index.zonesOf(this.player)));
    }

    @Test
    @DisplayName("a failing listener is logged under its owner and the other zones are still told")
    void failingListener() {
        this.index.register(this.plugin, WORLD, new BoundingBox(0, Y, 0, 3, Y + 4, 3), new ZoneListener() {
            @Override
            public void onEnter(Player who, Zone zone) {
                throw new IllegalStateException("broken extension");
            }
        });
        this.zone("fine", WORLD, 0, 0, 2, 2);

        assertEquals(List.of("enter fine"), this.at(WORLD, 1, 1));
    }

    @Test
    @DisplayName("a box takes the blocks whose centre it holds: two corner blocks give both and everything between")
    void boxOfBlocks() {
        Zone zone = this.index.register(this.plugin, WORLD, new BoundingBox(-2, 0, -2, 1, 1, 1), new ZoneListener() {
        });

        assertEquals(new BoundingBox(-2, 0, -2, 1, 1, 1), zone.getBounds());
        assertEquals(new BoundingBox(0, 0, 0, 1, 1, 1),
            this.index.register(this.plugin, WORLD, new BoundingBox(0.2, 0.4, 0.5, 0.6, 0.6, 0.9), new ZoneListener() {
            }).getBounds());
        assertThrows(IllegalArgumentException.class,
            () -> this.index.register(this.plugin, WORLD, new BoundingBox(0.6, 0, 0, 0.9, 1, 1), new ZoneListener() {
            }));
        assertThrows(IllegalArgumentException.class,
            () -> this.index.register(this.plugin, WORLD, new BoundingBox(0, 0, 0, Double.POSITIVE_INFINITY, 1, 1), new ZoneListener() {
            }));
    }

    @Test
    @DisplayName("with no zone registered nothing is kept for a player that moves")
    void inertWithoutZones() {
        assertTrue(this.index.isEmpty());
        this.index.at(this.player, WORLD, 1, Y, 1);
        this.zone("far", OTHER_WORLD, 0, 0, 1, 1);
        this.index.at(this.player, WORLD, 1, Y, 1); // a world without zones: nothing kept either

        assertEquals(0, this.index.followed());
    }

    @Test
    @DisplayName("a zone gone by unregister, plugin disable or world unload is no longer held for a player still standing in it")
    void deadZonesAreNotKeptByThePlayersInThem() {
        Zone spawn = this.zone("spawn", WORLD, 0, 0, 2, 2);
        Plugin other = plugin("Other");
        this.zone(other, "theirs", WORLD, 0, 0, 2, 2);
        this.zone("there", OTHER_WORLD, 0, 0, 2, 2);
        Player second = player();
        this.at(WORLD, 1, 1);
        this.index.at(second, OTHER_WORLD, 1, Y, 1);
        assertEquals(2, this.index.held(this.player));

        spawn.unregister();
        assertEquals(1, this.index.held(this.player));
        this.index.unregisterAll(other);
        assertEquals(0, this.index.held(this.player));
        this.index.worldUnloaded(OTHER_WORLD);
        assertEquals(0, this.index.held(second));
    }

    @Test
    @DisplayName("a zone cannot be registered for a world not loaded, or by a disabled plugin")
    void registerRefusesWhatWouldNeverBeCleaned() {
        ZoneIndex unloaded = new ZoneIndex(new LoadedWorlds());
        BoundingBox box = new BoundingBox(0, Y, 0, 3, Y + 4, 3);
        assertThrows(IllegalStateException.class, () -> unloaded.register(this.plugin, WORLD, box, new ZoneListener() {
        }));
        assertTrue(unloaded.isEmpty());

        Plugin disabled = plugin("Disabled");
        when(disabled.isEnabled()).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> this.index.register(disabled, WORLD, box, new ZoneListener() {
        }));
        assertTrue(this.index.isEmpty());
    }

    @Test
    @DisplayName("a world unloaded while a zone is being registered in it takes nothing with it: the zone is refused")
    void worldUnloadedDuringRegistration() {
        ZoneIndex[] racing = new ZoneIndex[1];
        racing[0] = new ZoneIndex(new LoadedWorlds() {
            private boolean first = true;

            @Override
            public boolean contains(UUID world) {
                if (!this.first) return super.contains(world);
                this.first = false;
                racing[0].worldUnloaded(world); // the unload slips in after the world was looked up, before its lock
                return true;
            }
        });
        racing[0].worldLoaded(WORLD);

        assertThrows(IllegalStateException.class, () -> racing[0].register(this.plugin, WORLD, new BoundingBox(0, Y, 0, 3, Y + 4, 3), new ZoneListener() {
        }));
        assertTrue(racing[0].isEmpty());
        assertEquals(0, racing[0].worldsHeld());
    }

    @Test
    @DisplayName("between the unload event of a world and its removal from Bukkit a zone in it is refused, and accepted again once reloaded")
    void zoneRefusedFromTheUnloadEventOn() {
        BoundingBox box = new BoundingBox(0, Y, 0, 3, Y + 4, 3);
        this.index.worldUnloaded(WORLD); // the event: Bukkit still lists the world, its chunk system closes next

        assertThrows(IllegalStateException.class, () -> this.index.register(this.plugin, WORLD, box, new ZoneListener() {
        }));
        assertTrue(this.index.isEmpty());
        assertEquals(0, this.index.worldsHeld());

        this.index.worldLoaded(WORLD); // a Slime world loaded again under the same id
        assertTrue(this.index.register(this.plugin, WORLD, box, new ZoneListener() {
        }).isRegistered());
    }

    @Test
    @DisplayName("between the disable event of a plugin and its onDisable, another thread cannot register a zone for it; once enabled again it can")
    void zoneRefusedFromTheDisableEventOn() {
        BoundingBox box = new BoundingBox(0, Y, 0, 3, Y + 4, 3);
        this.index.pluginDisabled(this.plugin); // the event: the plugin still says it is enabled

        java.util.concurrent.CompletionException refused = assertThrows(java.util.concurrent.CompletionException.class,
            () -> java.util.concurrent.CompletableFuture.supplyAsync(() -> this.index.register(this.plugin, WORLD, box, new ZoneListener() {
            })).join());
        assertTrue(refused.getCause() instanceof IllegalStateException, refused.toString());
        assertTrue(this.index.isEmpty());

        this.index.pluginEnabled(this.plugin);
        assertTrue(java.util.concurrent.CompletableFuture.supplyAsync(() -> this.index.register(this.plugin, WORLD, box, new ZoneListener() {
        })).join().isRegistered());
    }

    @Test
    @DisplayName("a world whose last zone is gone, or whose zone was refused, is not kept")
    void noEmptyWorldIsKept() {
        Zone only = this.zone("only", WORLD, 0, 0, 2, 2);
        assertEquals(1, this.index.worldsHeld());

        only.unregister();
        assertEquals(0, this.index.worldsHeld());
    }

    @Test
    @DisplayName("a leave told at quit that teleports the player does not follow it again")
    void quitWithATeleportingLeave() {
        this.index.register(this.plugin, WORLD, new BoundingBox(0, Y, 0, 3, Y + 4, 3), new ZoneListener() {
            @Override
            public void onLeave(Player who, Zone zone) {
                ZoneIndexTest.this.index.at(who, WORLD, 500, Y, 500);
            }
        });
        this.zone("cell", WORLD, 500, 500, 500, 500);
        this.at(WORLD, 1, 1);
        this.calls.clear();

        this.index.quit(this.player);

        assertEquals(List.of(), this.calls);
        assertEquals(0, this.index.followed());
    }

    @Test
    @DisplayName("a listener failing on every move is logged once, then every thousandth time")
    void failingListenerIsLoggedSparingly() {
        Plugin noisy = plugin("Noisy" + UUID.randomUUID());
        List<java.util.logging.LogRecord> records = new ArrayList<>();
        noisy.getLogger().setUseParentHandlers(false);
        noisy.getLogger().addHandler(new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        this.index.register(noisy, WORLD, new BoundingBox(0, Y, 0, 3, Y + 4, 3), new ZoneListener() {
            @Override
            public void onEnter(Player who, Zone zone) {
                throw new IllegalStateException("broken extension");
            }
        });

        for (int i = 0; i < 5; i++) {
            this.at(WORLD, 1, 1);
            this.at(WORLD, 9, 9);
        }

        assertEquals(1, records.size());
    }

    private Set<String> names(Set<Zone> zones) {
        Set<String> names = new java.util.HashSet<>();
        for (Zone zone : zones) {
            this.calls.clear();
            ((ZoneIndex.ZoneImpl) zone).listener.onEnter(this.player, zone);
            names.add(this.calls.getFirst().substring("enter ".length()));
        }
        this.calls.clear();
        return names;
    }
}
