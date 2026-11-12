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
package com.logisticscraft.occlusionculling;

import com.logisticscraft.occlusionculling.util.Vec3d;

public interface DataProvider {

    /**
     * Prepares the requested chunk. Returns true if the chunk is ready, false when
     * not loaded. Should not reload the chunk when the x and y are the same as the
     * last request!
     *
     * @param chunkX
     * @param chunkZ
     * @return
     */
    boolean prepareChunk(int chunkX, int chunkZ);

    /**
     * Location is inside the chunk.
     *
     * @param x
     * @param y
     * @param z
     * @return
     */
    boolean isOpaqueFullCube(int x, int y, int z);

    default void cleanup() {
    }

    default void checkingPosition(Vec3d[] targetPoints, int size, Vec3d viewerPosition) {
    }

}
