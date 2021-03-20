/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package sun.java2d.opengl;

import sun.java2d.pipe.RenderBuffer;
import sun.java2d.pipe.RenderQueue;
import sun.misc.Unsafe;

import static sun.java2d.pipe.BufferedOpCodes.MASK_BUFFER_FENCE;

public class OGLMaskBuffer {

    public static final int MASK_BUFFER_REGION_COUNT = 4;
    public static final int MASK_BUFFER_REGION_SIZE = 4 * 1024 * 1024; // LBO: 4Mb seems faster
    public static final int MASK_BUFFER_SIZE = MASK_BUFFER_REGION_SIZE * MASK_BUFFER_REGION_COUNT;

    static {
        System.out.println("MASK_BUFFER_REGION_COUNT: " + MASK_BUFFER_REGION_COUNT);
        System.out.println("MASK_BUFFER_REGION_SIZE: " + MASK_BUFFER_REGION_SIZE);
        System.out.println("MASK_BUFFER_SIZE: " + MASK_BUFFER_SIZE);
    }

    /**
     * Vertex-Data per Mask-Quad: 1 Vertex = 8*sizeof(float) = 32 byte
     * Per Quad: 32x4 = 128 byte
     * -> Size Vertex Buffers, so that Masks with 512 byte on average fit the vtx buffer
     */
    public static final int VERTEX_BUFFER_SIZE = MASK_BUFFER_SIZE / 4;

    long maskBufferBasePtr;
    long vertexBufferBasePtr;

    long tileDataOffset = VERTEX_BUFFER_SIZE / 2;

    int currentBufferOffset;

    int currentVtxPos;
    int lastVtxPos;

    private volatile static OGLMaskBuffer buffer;

    public static final Unsafe UNSAFE;

    private static int BUFFER_ARRAY_STRIDE = 1;

    private final static boolean pendingFences[] = new boolean[MASK_BUFFER_REGION_COUNT];

    static {
        UNSAFE = Unsafe.getUnsafe();
    }

    public static OGLMaskBuffer getInstance() {
        if (buffer == null) {
            synchronized (OGLMaskBuffer.class) {
                if (buffer == null) {
                    OGLRenderQueue.getInstance().flushAndInvokeNow(new Runnable() {
                        @Override
                        public void run() {
                            buffer = new OGLMaskBuffer();
                        }
                    });
                }
            }
        }

        return buffer;
    }

    public OGLMaskBuffer() {
        vertexBufferBasePtr = allocateVertexBufferPtr(VERTEX_BUFFER_SIZE);
        maskBufferBasePtr = allocateMaskBufferPtr(MASK_BUFFER_SIZE, MASK_BUFFER_REGION_SIZE);

        currentVtxPos = 0;
        lastVtxPos = 0;
        currentBufferOffset = 0;
    }

    public final int allocateMaskData(final RenderQueue queue, final int maskSize) {
        int offsetBefore = currentBufferOffset;

        // System.out.println("Requested " +maskSize +" offset before: " + offsetBefore);
        final int regionBefore = currentBufferOffset / MASK_BUFFER_REGION_SIZE;

        // LBO: fix offset before out of bound checks:
        // Align offset to multiple of 8
        currentBufferOffset = (currentBufferOffset + 7) & ~7;

        if (currentBufferOffset + maskSize >= MASK_BUFFER_SIZE) {
            offsetBefore = currentBufferOffset = 0;
        }

        currentBufferOffset += maskSize;

        int regionAfter = currentBufferOffset / MASK_BUFFER_REGION_SIZE;

        if (regionBefore != regionAfter) {
            // System.out.println("need another buffer region: " + regionAfter);

            offsetBefore = (MASK_BUFFER_REGION_SIZE * regionAfter);
            currentBufferOffset = offsetBefore + maskSize;

            queue.ensureCapacity(12);

            final RenderBuffer buffer = queue.getBuffer();
            buffer.putInt(MASK_BUFFER_FENCE);
            buffer.putInt(regionBefore);

            int waitRegion;
            boolean nextRegionPending;

            synchronized (pendingFences) {
                // Optimization: Queue the wait for a fence one region before we actually need the region to be available.
                // Therefore we might save a few flushNow() synchronizations,
                // because the wait for region we need has been queued before (and maybe flushed because other draw calls)
                // E.g.: region 3 was just filled up -> place fence for region 3
                // and queue a wait for region region 1.
                // region 0's wait we require now was placed earlier, and might have been processed without an explicit flushNow
                waitRegion = (regionBefore + 2) % MASK_BUFFER_REGION_COUNT;
                if (!pendingFences[waitRegion]) {
                    waitRegion = -1;
                }
                buffer.putInt(waitRegion);
                //System.out.println("BUF: placing fence for:"+regionBefore+" waiting for: "+waitRegion);

                // enable in case async flush is available
                //queue.flushNow(false);
                pendingFences[regionBefore] = true;

                // We need the next region now, so check, if it is already available (fence was waited on earlier)
                nextRegionPending = pendingFences[regionAfter];
            }

            // If fence was not met, we have to explicitly flush, the wait has been queued earlier,
            // but was not yet processed by the OpenGL queue thread.
            int fenceCounter = 0;
            while (nextRegionPending) {
                //System.out.println("explicit flush");
                queue.flushNow();

                synchronized (pendingFences) {
                    nextRegionPending = pendingFences[regionAfter];
                }

                // should not happen in sync flush mode
                if (fenceCounter > 0) {
                    throw new RuntimeException("Aborting fence stall");
                }
                fenceCounter++;
            }
        }

        return offsetBefore;
    }

    public final long getMaskBufferBasePtr() {
        return maskBufferBasePtr;
    }

    private static void setFenceAvailable(int fenceNum) {
        synchronized (pendingFences) {
            // System.out.println("Fence available: " + fenceNum);
            pendingFences[fenceNum] = false;
        }
    }

    private static native long allocateMaskBufferPtr(int size, int regionSize);

    private static native long allocateVertexBufferPtr(int size);
}
