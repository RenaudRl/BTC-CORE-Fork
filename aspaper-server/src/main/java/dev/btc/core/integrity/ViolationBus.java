package dev.btc.core.integrity;

import dev.btc.core.api.integrity.IntegrityAPI.ViolationEvent;
import dev.btc.core.api.integrity.IntegrityAPI.ViolationListener;
import dev.btc.core.api.integrity.IntegrityAPI.ViolationSubscription;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Delivers violations to subscribers, always on the scheduler that owns the player.
 *
 * <p>Checks run wherever their data lives — a packet check on a Netty thread, a statistical check on
 * the scoring thread. Subscribers, on the other hand, are extension code that will reach for the world
 * the moment it is handed a player. Republishing on the owning region thread is therefore not a
 * nicety: without it, every listener becomes a race condition on Folia.
 *
 * <p>This is the same guard {@code BTCCoreAPIImpl#activateIslandAsync} already applies for island
 * events; the pattern is deliberately identical so there is one rule to remember, not two.
 */
public final class ViolationBus {

    /** Not {@code Bukkit.getLogger()}: the bus must log without a server, in tests as at early startup. */
    private static final Logger LOG = Logger.getLogger("Sentinel");

    /**
     * The hop to the thread that owns the player.
     *
     * <p>A seam rather than a direct call to {@link Bukkit#getRegionScheduler()} so that the hop is
     * testable without a server (9.2): the property that matters — a listener never runs on the
     * publishing thread — is pinned by a test that injects a recorder here.
     */
    @FunctionalInterface
    public interface Republisher {
        void onOwningThread(Plugin host, Player player, Runnable delivery);
    }

    private final CopyOnWriteArrayList<Subscription> subscriptions = new CopyOnWriteArrayList<>();
    private final Supplier<Plugin> host;
    private final Republisher republisher;

    /** The production bus: host plugin from the plugin manager, hop through the region scheduler. */
    public ViolationBus() {
        this(() -> Bukkit.getPluginManager().getPlugin("ASPaper"),
            (host, player, delivery) -> Bukkit.getRegionScheduler().run(host, player.getLocation(), task -> delivery.run()));
    }

    ViolationBus(Supplier<Plugin> host, Republisher republisher) {
        this.host = host;
        this.republisher = republisher;
    }

    /** Registers a listener owned by {@code owner}. */
    public ViolationSubscription subscribe(Plugin owner, ViolationListener listener) {
        if (owner == null || listener == null) {
            throw new IllegalArgumentException("a subscription needs an owner and a listener");
        }
        Subscription subscription = new Subscription(owner, listener);
        subscriptions.add(subscription);
        return subscription;
    }

    /** Drops every subscription held by a plugin being disabled. */
    public void clearOwner(Plugin owner) {
        subscriptions.removeIf(subscription -> subscription.owner.equals(owner));
    }

    /** Number of live subscriptions. Diagnostics only. */
    public int subscriberCount() {
        return subscriptions.size();
    }

    /**
     * Publishes a violation, hopping to the player's owning thread first.
     *
     * @param player the player concerned, used to resolve the owning region
     * @param event the violation to deliver
     * @return {@code true} when a listener absorbed the platform's default response
     */
    public boolean publish(Player player, ViolationEvent event) {
        if (subscriptions.isEmpty()) {
            return false;
        }
        Plugin host = this.host.get();
        if (host == null) {
            // Without a host plugin there is no scheduler to hop onto. Dropping the notification is
            // the safe failure: delivering it from this thread would hand extension code a player it
            // is not allowed to touch.
            LOG.warning(
                "[Sentinel] No host plugin available; violation notification for "
                    + event.check() + " was not delivered.");
            return false;
        }

        // Absorption cannot be answered synchronously from here: the listeners run later, on another
        // thread. The platform's default response is applied by the caller unless a listener has
        // already absorbed a violation for this check — hence the deliberate false.
        republisher.onOwningThread(host, player, () -> deliver(event));
        return false;
    }

    private void deliver(ViolationEvent event) {
        List<Subscription> snapshot = List.copyOf(subscriptions);
        boolean cancelled = event.cancelled();
        for (Subscription subscription : snapshot) {
            ViolationEvent view = cancelled == event.cancelled()
                ? event
                : new ViolationEvent(event.playerId(), event.check(), event.violationLevel(),
                    event.verboseDetail(), cancelled);
            try {
                // A listener that absorbs the event still lets later listeners observe it, flagged as
                // cancelled — the model GrimAC's flag bus uses, and the one that lets a logger sit
                // behind a handler without being silenced by it.
                cancelled |= subscription.listener.onViolation(view);
            } catch (RuntimeException | LinkageError failure) {
                // One extension's bug must not stop the others from being told, nor kill the check.
                LOG.log(Level.WARNING,
                    "[Sentinel] Violation listener from " + subscription.owner.getName() + " threw.",
                    failure);
            }
        }
    }

    private final class Subscription implements ViolationSubscription {
        private final Plugin owner;
        private final ViolationListener listener;

        Subscription(Plugin owner, ViolationListener listener) {
            this.owner = owner;
            this.listener = listener;
        }

        @Override
        public void close() {
            subscriptions.remove(this);
        }
    }
}
