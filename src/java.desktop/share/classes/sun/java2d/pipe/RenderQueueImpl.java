/*
 * Copyright (c) 2005, Oracle and/or its affiliates. All rights reserved.
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

package sun.java2d.pipe;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import sun.awt.SunToolkit;

/**
 * The RenderQueue class encapsulates a RenderBuffer on which rendering
 * operations are enqueued.  Note that the RenderQueue lock must be acquired
 * before performing any operations on the queue (e.g. enqueuing an operation
 * or flushing the queue).  A sample usage scenario follows:
 *
 *     public void drawSomething(...) {
 *         rq.lock();
 *         try {
 *             ctx.validate(...);
 *             rq.ensureCapacity(4);
 *             rq.getBuffer().putInt(DRAW_SOMETHING);
 *             ...
 *         } finally {
 *             rq.unlock();
 *         }
 *     }
 *
 * If you are enqueuing an operation that involves 8-byte parameters (i.e.
 * long or double values), it is imperative that you ensure proper
 * alignment of the underlying RenderBuffer.  This can be accomplished
 * simply by providing an offset to the first 8-byte parameter in your
 * operation to the ensureCapacityAndAlignment() method.  For example:
 *
 *     public void drawStuff(...) {
 *         rq.lock();
 *         try {
 *             RenderBuffer buf = rq.getBuffer();
 *             ctx.validate(...);
 *             // 28 total bytes in the operation, 12 bytes to the first long
 *             rq.ensureCapacityAndAlignment(28, 12);
 *             buf.putInt(DRAW_STUFF);
 *             buf.putInt(x).putInt(y);
 *             buf.putLong(addr1);
 *             buf.putLong(addr2);
 *         } finally {
 *             rq.unlock();
 *         }
 *     }
 */
public abstract class RenderQueueImpl implements RenderQueue {

    /** The size of the underlying buffer, in bytes. */
    private static final int BUFFER_SIZE = 32768;

    /** The underlying buffer for this queue. */
    protected RenderBuffer buf;

    /**
     * A Set containing hard references to Objects that must stay alive until
     * the queue has been completely flushed.
     */
    protected Set<Object> refSet;

    protected RenderQueueImpl() {
      refSet = new HashSet<>();
      buf = RenderBuffer.allocate(BUFFER_SIZE);
    }

  @Override
  public Collection<Object> copyAndClearReferences() {
    ArrayList<Object> refList = new ArrayList<>(refSet.size());
    refList.addAll(refSet);
    refSet.clear();
    return refList;
  }

  /**
     * Adds the given Object to the set of hard references, which will
     * prevent that Object from being disposed until the queue has been
     * flushed completely.  This is useful in cases where some enqueued
     * data could become invalid if the reference Object were garbage
     * collected before the queue could be processed.  (For example, keeping
     * a hard reference to a FontStrike will prevent any enqueued glyph
     * images associated with that strike from becoming invalid before the
     * queue is flushed.)  The reference set will be cleared immediately
     * after the queue is flushed each time.
     */
    public void addReference(Object ref) {
        refSet.add(ref);
    }

    /**
     * Returns the encapsulated RenderBuffer object.
     */
    public RenderBuffer getBuffer() {
        return buf;
    }

    /**
     * Ensures that there will be enough room on the underlying buffer
     * for the following operation.  If the operation will not fit given
     * the remaining space, the buffer will be flushed immediately, leaving
     * an empty buffer for the impending operation.
     *
     * @param opsize size (in bytes) of the following operation
     */
    public void ensureCapacity(int opsize) {
        if (buf.remaining() < opsize) {
            flushNow(false);
        }

        /*if(buf.position() > 32000) {
          System.out.println("Flushing with position: " + buf.position());
          flushNow(false);
        }*/
    }

    /**
     * Convenience method that is equivalent to calling ensureCapacity()
     * followed by ensureAlignment().  The ensureCapacity() call allows for an
     * extra 4 bytes of space in case the ensureAlignment() method needs to
     * insert a NOOP token on the buffer.
     *
     * @param opsize size (in bytes) of the following operation
     * @param first8ByteValueOffset offset (in bytes) from the current
     * position to the first 8-byte value used in the following operation
     */
    public void ensureCapacityAndAlignment(int opsize,
                                                 int first8ByteValueOffset)
    {
        ensureCapacity(opsize + 4);
        ensureAlignment(first8ByteValueOffset);
    }

    /**
     * Inserts a 4-byte NOOP token when necessary to ensure that all 8-byte
     * parameters for the following operation are added to the underlying
     * buffer with an 8-byte memory alignment.
     *
     * @param first8ByteValueOffset offset (in bytes) from the current
     * position to the first 8-byte value used in the following operation
     */
    public void ensureAlignment(int first8ByteValueOffset) {
        int first8ByteValuePosition = buf.position() + first8ByteValueOffset;
        if ((first8ByteValuePosition & 7) != 0) {
            buf.putInt(BufferedOpCodes.NOOP);
        }
    }

    /**
     * Updates the current position of the underlying buffer, and then
     * flushes the queue immediately.  This method is useful when native code
     * has added data to the queue and needs to flush immediately.
     */
    public void flushNow(int position, boolean sync) {
      Thread.dumpStack();
        buf.position(position);
        flushNow(sync);
    }
}
