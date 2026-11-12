package dev.btc.core.performance;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VarIntWritesTest {

    private static final long SEED = 20261112L;
    private static final int RANDOM_VALUES = 20_000;
    private static final int FIRST_WIDE_VALUE = 1 << 14;
    private static final int DATA_BITS = 7;

    /** The vanilla loop: seven bits per byte, the high bit set while more follows. */
    private static String reference(int value) {
        ByteBuf expected = Unpooled.buffer();
        while ((value & -128) != 0) {
            expected.writeByte(value & 0x7F | 0x80);
            value >>>= DATA_BITS;
        }
        expected.writeByte(value);
        return ByteBufUtil.hexDump(expected);
    }

    private static String written(int value) {
        ByteBuf actual = Unpooled.buffer();
        VarIntWrites.writeWide(actual, value);
        return ByteBufUtil.hexDump(actual);
    }

    @Test
    @DisplayName("each size boundary from three bytes up gives the bytes of the vanilla loop")
    void boundariesMatchTheLoop() {
        int[] boundaries = {
            FIRST_WIDE_VALUE, FIRST_WIDE_VALUE + 1,
            (1 << 21) - 1, 1 << 21, (1 << 21) + 1,
            (1 << 28) - 1, 1 << 28, (1 << 28) + 1,
            Integer.MAX_VALUE, Integer.MIN_VALUE, -1, -2, -128, -(1 << 21),
        };
        for (int value : boundaries) {
            assertEquals(reference(value), written(value), "value " + value);
        }
    }

    @Test
    @DisplayName("random values beyond 14 bits give the bytes of the vanilla loop")
    void randomValuesMatchTheLoop() {
        Random random = new Random(SEED);
        for (int i = 0; i < RANDOM_VALUES; i++) {
            int value = random.nextInt();
            if ((value & -FIRST_WIDE_VALUE) == 0) {
                value |= FIRST_WIDE_VALUE;
            }
            assertEquals(reference(value), written(value), "value " + value);
        }
    }
}
