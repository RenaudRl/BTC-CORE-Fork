package dev.btc.core.qol;

import dev.btc.core.config.BTCCoreConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;

import java.util.LinkedList;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * BTCCore: Join Queue System.
 * Manages a queue for players when the server is full.
 */
public final class JoinQueueManager {

    private static final Queue<UUID> joinQueue = new ConcurrentLinkedQueue<>();

    private JoinQueueManager() {}

    /**
     * Adds a player to the join queue.
     *
     * @param uuid The player's UUID
     * @return true if added, false if queue is full
     */
    public static boolean addToQueue(UUID uuid) {
        if (!BTCCoreConfig.joinQueueEnabled) {
            return false;
        }

        if (joinQueue.size() >= BTCCoreConfig.joinQueueMaxSize) {
            return false;
        }

        if (!joinQueue.contains(uuid)) {
            joinQueue.add(uuid);
        }
        return true;
    }

    /**
     * Removes a player from the join queue.
     *
     * @param uuid The player's UUID
     */
    public static void removeFromQueue(UUID uuid) {
        joinQueue.remove(uuid);
    }

    /**
     * Gets the next player in the queue.
     *
     * @return The next player's UUID, or null if queue is empty
     */
    public static UUID pollQueue() {
        return joinQueue.poll();
    }

    /**
     * Gets a player's position in the queue.
     *
     * @param uuid The player's UUID
     * @return The position (1-indexed), or -1 if not in queue
     */
    public static int getQueuePosition(UUID uuid) {
        int position = 1;
        for (UUID queued : joinQueue) {
            if (queued.equals(uuid)) {
                return position;
            }
            position++;
        }
        return -1;
    }

    /**
     * Gets the current queue size.
     *
     * @return The number of players in queue
     */
    public static int getQueueSize() {
        return joinQueue.size();
    }

    /**
     * Checks if the join queue system is enabled.
     *
     * @return true if enabled
     */
    public static boolean isEnabled() {
        return BTCCoreConfig.joinQueueEnabled;
    }
}

