package dev.btc.core.integrity;

import dev.btc.core.api.integrity.IntegrityAPI.CheckId;
import dev.btc.core.api.integrity.IntegrityAPI.ViolationEvent;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Task 9.2: a violation raised off the owning region is republished on that region's scheduler.
 *
 * <p>The property is pinned by order, not by threads: the listener must not have run when
 * {@code publish} returns, and must have run once the hop's delivery is executed. Delivering
 * synchronously — the bug this exists to catch — makes the first assertion fail.
 */
class ViolationBusTest {

    private static final UUID PLAYER = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final CheckId CHECK = new CheckId("test", "reach");

    /** Records each hop instead of scheduling it; the test runs the delivery when it decides to. */
    private static final class RecordingRepublisher implements ViolationBus.Republisher {
        final List<Plugin> hosts = new ArrayList<>();
        final List<Player> players = new ArrayList<>();
        final List<Runnable> deliveries = new ArrayList<>();

        @Override
        public void onOwningThread(Plugin host, Player player, Runnable delivery) {
            hosts.add(host);
            players.add(player);
            deliveries.add(delivery);
        }
    }

    private final Plugin host = Mockito.mock(Plugin.class);
    private final Plugin owner = Mockito.mock(Plugin.class);
    private final Player player = Mockito.mock(Player.class);
    private final RecordingRepublisher republisher = new RecordingRepublisher();
    private Plugin resolvedHost;
    private ViolationBus bus;

    @BeforeEach
    void setUp() {
        Mockito.when(owner.getName()).thenReturn("owner");
        resolvedHost = host;
        bus = new ViolationBus(() -> resolvedHost, republisher);
    }

    @Test
    @DisplayName("a published violation reaches the listener on the hop, not on the caller")
    void deliveryHopsToTheOwningRegion() {
        List<ViolationEvent> received = new ArrayList<>();
        bus.subscribe(owner, event -> {
            received.add(event);
            return false;
        });
        ViolationEvent event = violation();

        bus.publish(player, event);

        assertEquals(List.of(), received, "nothing is delivered from the publishing thread");
        assertEquals(1, republisher.deliveries.size(), "one hop was requested");
        republisher.deliveries.get(0).run();
        assertEquals(List.of(event), received, "the hop delivers it");
    }

    @Test
    @DisplayName("the hop is anchored on the player and carries the host plugin")
    void theHopIsAnchoredOnThePlayer() {
        bus.subscribe(owner, event -> false);
        bus.publish(player, violation());

        assertSame(player, republisher.players.get(0));
        assertSame(host, republisher.hosts.get(0));
    }

    @Test
    @DisplayName("without a host plugin the notification is dropped, never delivered from the wrong thread")
    void noHostMeansNoDelivery() {
        resolvedHost = null;
        List<ViolationEvent> received = new ArrayList<>();
        bus.subscribe(owner, event -> {
            received.add(event);
            return false;
        });

        assertFalse(bus.publish(player, violation()));

        assertEquals(List.of(), republisher.deliveries, "no hop without a host");
        assertEquals(List.of(), received);
    }

    @Test
    @DisplayName("a listener that throws does not silence the next one")
    void aThrowingListenerDoesNotStopTheOthers() {
        List<String> seen = new ArrayList<>();
        bus.subscribe(owner, event -> {
            throw new IllegalStateException("a listener with a bug");
        });
        bus.subscribe(owner, event -> {
            seen.add("second");
            return false;
        });
        bus.publish(player, violation());

        republisher.deliveries.get(0).run();
        assertEquals(List.of("second"), seen);
    }

    @Test
    @DisplayName("an absorbing listener is seen as cancelled by the ones after it, and not before")
    void absorptionIsVisibleDownstream() {
        List<Boolean> cancelledAsSeen = new ArrayList<>();
        bus.subscribe(owner, event -> {
            cancelledAsSeen.add(event.cancelled());
            return true;
        });
        bus.subscribe(owner, event -> {
            cancelledAsSeen.add(event.cancelled());
            return false;
        });
        bus.publish(player, violation());

        republisher.deliveries.get(0).run();
        assertEquals(List.of(false, true), cancelledAsSeen);
    }

    @Test
    @DisplayName("with no subscriber, nothing is scheduled")
    void noSubscriberNoHop() {
        assertFalse(bus.publish(player, violation()));
        assertEquals(List.of(), republisher.deliveries);
    }

    @Test
    @DisplayName("a subscription closed, or its owner disabled, is not delivered to")
    void closedSubscriptionsAreNotDelivered() {
        List<String> seen = new ArrayList<>();
        var closed = bus.subscribe(owner, event -> {
            seen.add("closed");
            return false;
        });
        Plugin other = Mockito.mock(Plugin.class);
        bus.subscribe(other, event -> {
            seen.add("disabled");
            return false;
        });
        bus.subscribe(owner, event -> {
            seen.add("live");
            return false;
        });
        closed.close();
        bus.clearOwner(other);

        bus.publish(player, violation());
        republisher.deliveries.get(0).run();
        assertEquals(List.of("live"), seen);
    }

    private static ViolationEvent violation() {
        return new ViolationEvent(PLAYER, CHECK, 1.0, "detail", false);
    }
}
