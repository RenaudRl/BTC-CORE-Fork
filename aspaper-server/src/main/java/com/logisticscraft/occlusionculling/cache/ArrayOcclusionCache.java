/*
 * MIT License
 *
 * Copyright (c) 2021 LogisticsCraft
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 * Copied unmodified from https://github.com/LogisticsCraft/OcclusionCulling (master branch);
 * this header is the only addition.
 */
package com.logisticscraft.occlusionculling.cache;

import java.util.Arrays;

public class ArrayOcclusionCache implements OcclusionCache {

    // Past this many touched entries a reset clears the whole array instead
    private static final int MAX_DIRTY = 8192;

    private final int reachX2;
    private final byte[] cache;
    private final int[] dirty = new int[MAX_DIRTY];
    private int dirtyCount;
    private boolean dirtyOverflow;
    private int positionKey;
    private int entry;
    private int offset;

    public ArrayOcclusionCache(int reach) {
        this.reachX2 = reach * 2;
        this.cache = new byte[(reachX2 * reachX2 * reachX2) / 4];
    }

    @Override
    public void resetCache() {
        if (dirtyOverflow) {
            Arrays.fill(cache, (byte) 0);
        } else {
            for (int i = 0; i < dirtyCount; i++) {
                cache[dirty[i]] = 0;
            }
        }
        dirtyCount = 0;
        dirtyOverflow = false;
    }

    @Override
    public void setVisible(int x, int y, int z) {
        positionKey = x + y * reachX2 + z * reachX2 * reachX2;
        entry = positionKey / 4;
        offset = (positionKey % 4) * 2;
        markDirty();
        cache[entry] |= 1 << offset;
    }

    @Override
    public void setHidden(int x, int y, int z) {
        positionKey = x + y * reachX2 + z * reachX2 * reachX2;
        entry = positionKey / 4;
        offset = (positionKey % 4) * 2;
        markDirty();
        cache[entry] |= 1 << offset + 1;
    }

    @Override
    public int getState(int x, int y, int z) {
        positionKey = x + y * reachX2 + z * reachX2 * reachX2;
        entry = positionKey / 4;
        offset = (positionKey % 4) * 2;
        return cache[entry] >> offset & 3;
    }

    @Override
    public void setLastVisible() {
        markDirty();
        cache[entry] |= 1 << offset;
    }

    @Override
    public void setLastHidden() {
        markDirty();
        cache[entry] |= 1 << offset + 1;
    }

    // Remembers the current entry for the next reset if it is still zero
    private void markDirty() {
        if (cache[entry] != 0 || dirtyOverflow) {
            return;
        }
        if (dirtyCount == MAX_DIRTY) {
            dirtyOverflow = true;
            return;
        }
        dirty[dirtyCount++] = entry;
    }

}
