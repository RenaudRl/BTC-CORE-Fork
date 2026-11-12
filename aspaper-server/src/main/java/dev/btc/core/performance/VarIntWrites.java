package dev.btc.core.performance;

import io.netty.buffer.ByteBuf;

/**
 * VarInts of three bytes and more written in one or two buffer calls
 * ({@code performance.network.fast-varint}); Paper already does it for one and two bytes.
 *
 * <p>From Velocity's "Optimize varint writing" (Andrew Steinborn, GPL-3.0-only),
 * https://github.com/PaperMC/Velocity, as carried by Gale (Martijn Muijsers, GPL-3.0-only) and Leaf.
 * The bytes written are the ones of the vanilla loop.
 */
public final class VarIntWrites {

    private static final int FITS_THREE_BYTES = 0xFFE00000;
    private static final int FITS_FOUR_BYTES = 0xF0000000;

    private VarIntWrites() {}

    /** Writes a value that needs at least three bytes, that is one beyond 14 bits. */
    public static void writeWide(ByteBuf output, int value) {
        if ((value & FITS_THREE_BYTES) == 0) {
            output.writeMedium((value & 0x7F) << 16
                | (value & 0x3F80) << 1
                | (value >>> 14)
                | 0x00808000);
        } else if ((value & FITS_FOUR_BYTES) == 0) {
            output.writeInt((value & 0x7F) << 24
                | (value & 0x3F80) << 9
                | (value & 0x1FC000) >> 6
                | (value >>> 21)
                | 0x80808000);
        } else {
            output.writeInt((value & 0x7F) << 24
                | (value & 0x3F80) << 9
                | (value & 0x1FC000) >> 6
                | ((value >>> 21) & 0x7F)
                | 0x80808080);
            output.writeByte(value >>> 28);
        }
    }
}
