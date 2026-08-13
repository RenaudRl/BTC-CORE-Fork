package dev.btc.core.redstone.compile;

/**
 * The outcome of a compilation attempt: either a circuit, or the reason the compiler walked away.
 *
 * <p>A bare {@code null} return told the caller nothing. Whether a real circuit is compilable can
 * only be answered by naming the block and the position that left the domain, so the refusal
 * travels back with the result instead of being thrown away.
 *
 * @param compilation the compiled circuit, or {@code null} when the attempt was refused
 * @param refusal     why the attempt was refused, or {@code null} when it succeeded
 */
public record CompileResult(Compilation compilation, String refusal) {

    public CompileResult {
        if ((compilation == null) == (refusal == null)) {
            throw new IllegalArgumentException("a compile result is either a compilation or a refusal");
        }
    }

    public static CompileResult compiled(final Compilation compilation) {
        return new CompileResult(compilation, null);
    }

    public static CompileResult refused(final String reason) {
        return new CompileResult(null, reason);
    }

    public boolean succeeded() {
        return this.compilation != null;
    }
}
