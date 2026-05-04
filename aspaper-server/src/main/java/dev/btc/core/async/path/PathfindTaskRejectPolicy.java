package dev.btc.core.async.path;

/**
 * Policy for handling pathfinding task rejection when the queue is full.
 * Based on Leaf's async pathfinding implementation.
 */
public enum PathfindTaskRejectPolicy {
    /**
     * Flush all pending tasks and accept the new one.
     * Best for high-performance servers with many entities.
     */
    FLUSH_ALL,
    
    /**
     * Run the task on the caller thread (blocking).
     * Safer but may cause lag spikes.
     */
    CALLER_RUNS,
    
    /**
     * Discard the new task silently.
     */
    DISCARD,
    
    /**
     * Discard the oldest task in the queue.
     */
    DISCARD_OLDEST;
    
    /**
     * Parse a string to a PathfindTaskRejectPolicy.
     * @param name The name of the policy (case-insensitive)
     * @return The policy, or FLUSH_ALL if not found
     */
    public static PathfindTaskRejectPolicy fromString(String name) {
        if (name == null || name.isEmpty()) {
            return FLUSH_ALL;
        }
        try {
            return valueOf(name.toUpperCase().replace("-", "_"));
        } catch (IllegalArgumentException e) {
            return FLUSH_ALL;
        }
    }
}

