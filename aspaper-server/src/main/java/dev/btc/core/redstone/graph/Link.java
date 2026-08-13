package dev.btc.core.redstone.graph;

/**
 * A compiled connection from one node to another.
 *
 * <p>The whole point of compiling is that this path is walked <em>once</em>, at compile time: the
 * breadth-first search through redstone dust is replaced by a single weight. At runtime, reading an
 * input costs one array lookup and one subtraction instead of a world traversal.
 *
 * @param side   {@code true} when the link feeds a comparator's side input rather than its back
 * @param source index of the source node inside {@link CompiledGraph#nodes()}
 * @param weight signal strength lost along the path; 0 for a direct component-to-component link,
 *               otherwise the number of dust blocks travelled
 */
public record Link(boolean side, int source, int weight) {

    public Link {
        if (source < 0) {
            throw new IllegalArgumentException("source index must be positive, got " + source);
        }
        if (weight < 0) {
            throw new IllegalArgumentException("weight must be positive, got " + weight);
        }
    }

    /** Signal strength arriving through this link for a source currently emitting {@code strength}. */
    public int apply(int strength) {
        return Math.max(0, strength - this.weight);
    }
}
