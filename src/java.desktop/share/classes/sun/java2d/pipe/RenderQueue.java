package sun.java2d.pipe;

import sun.awt.SunToolkit;

import java.util.Collection;
import java.util.concurrent.TimeUnit;

public interface RenderQueue {

  /**
   * Locks the queue for read/write access.
   */
  default public void lock() {
    /*
     * Implementation note: In theory we should have two separate locks:
     * one lock to synchronize access to the RenderQueue, and then a
     * separate lock (the AWT lock) that only needs to be acquired when
     * we are about to flush the queue (using native windowing system
     * operations).  In practice it has been difficult to enforce the
     * correct lock ordering; sometimes AWT will have already acquired
     * the AWT lock before grabbing the RQ lock (see 6253009), while the
     * expected order should be RQ lock and then AWT lock.  Due to this
     * issue, using two separate locks is prone to deadlocks.  Therefore,
     * to solve this issue we have decided to eliminate the separate RQ
     * lock and instead just acquire the AWT lock here.  (Someday it might
     * be nice to go back to the old two-lock system, but that would
     * require potentially risky changes to AWT to ensure that it never
     * acquires the AWT lock before calling into 2D code that wants to
     * acquire the RQ lock.)
     */
    SunToolkit.awtLock();
  }

  /**
   * Attempts to lock the queue.  If successful, this method returns true,
   * indicating that the caller is responsible for calling
   * {@code unlock}; otherwise this method returns false.
   */
  default public boolean tryLock() {
    return SunToolkit.awtTryLock();
  }

  default public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
    return SunToolkit.awtTryLock(time, unit);
  }

  /**
   * Unlocks the queue.
   */
  default public void unlock() {
    SunToolkit.awtUnlock();
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
  public void addReference(Object ref);

  public Collection<Object> copyAndClearReferences();

  /**
   * Returns the encapsulated RenderBuffer object.
   */
  public RenderBuffer getBuffer();

  /**
   * Ensures that there will be enough room on the underlying buffer
   * for the following operation.  If the operation will not fit given
   * the remaining space, the buffer will be flushed immediately, leaving
   * an empty buffer for the impending operation.
   *
   * @param opsize size (in bytes) of the following operation
   */
  public void ensureCapacity(int opsize);

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
                                         int first8ByteValueOffset);

  /**
   * Inserts a 4-byte NOOP token when necessary to ensure that all 8-byte
   * parameters for the following operation are added to the underlying
   * buffer with an 8-byte memory alignment.
   *
   * @param first8ByteValueOffset offset (in bytes) from the current
   * position to the first 8-byte value used in the following operation
   */
  public void ensureAlignment(int first8ByteValueOffset);

  /**
   * Immediately processes each operation currently pending on the buffer.
   * This method will block until the entire buffer has been flushed.  The
   * queue lock must be acquired before calling this method.
   */
  public void flushNow(boolean sync);

  default public void flushNow() {
    flushNow(true);
  }

  /**
   * Immediately processes each operation currently pending on the buffer,
   * and then invokes the provided task.  This method will block until the
   * entire buffer has been flushed and the provided task has been executed.
   * The queue lock must be acquired before calling this method.
   */
  public void flushAndInvokeNow(Runnable task);

  /**
   * Updates the current position of the underlying buffer, and then
   * flushes the queue immediately.  This method is useful when native code
   * has added data to the queue and needs to flush immediately.
   */
  public void flushNow(int position, boolean async);

  default public void flushNow(int position) {
   flushNow(position, true);
  }
}
