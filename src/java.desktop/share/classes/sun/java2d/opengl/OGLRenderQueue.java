/*
 * Copyright (c) 2005, 2012, Oracle and/or its affiliates. All rights reserved.
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

import sun.awt.util.ThreadGroupUtils;
import sun.java2d.pipe.DoubleBufferedRenderQueue;
import sun.java2d.pipe.RenderBuffer;
import sun.java2d.pipe.RenderQueue;
import sun.java2d.pipe.RenderQueueImpl;

import static sun.java2d.pipe.BufferedOpCodes.*;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * OGL-specific implementation of RenderQueue.  This class provides a
 * single (daemon) thread that is responsible for periodically flushing
 * the queue, thus ensuring that only one thread communicates with the native
 * OpenGL libraries for the entire process.
 */
public class OGLRenderQueue extends DoubleBufferedRenderQueue {

    private static OGLRenderQueue theInstance;
    private final QueueFlusher flusher;

    private OGLRenderQueue() {
        /*
         * The thread must be a member of a thread group
         * which will not get GCed before VM exit.
         */
      flusher = AccessController.doPrivileged((PrivilegedAction<QueueFlusher>) QueueFlusher::new);
    }

    /**
     * Returns the single OGLRenderQueue instance.  If it has not yet been
     * initialized, this method will first construct the single instance
     * before returning it.
     */
    public static synchronized OGLRenderQueue getInstance() {
        if (theInstance == null) {
            theInstance = new OGLRenderQueue();
        }
        return theInstance;
    }

    /**
     * Flushes the single OGLRenderQueue instance synchronously.  If an
     * OGLRenderQueue has not yet been instantiated, this method is a no-op.
     * This method is useful in the case of Toolkit.sync(), in which we want
     * to flush the OGL pipeline, but only if the OGL pipeline is currently
     * enabled.  Since this class has few external dependencies, callers need
     * not be concerned that calling this method will trigger initialization
     * of the OGL pipeline and related classes.
     */
    public static void sync() {
        if (theInstance != null) {
            theInstance.lock();
            try {
                theInstance.ensureCapacity(4);
                theInstance.getBuffer().putInt(SYNC);
                theInstance.flushNow();
            } finally {
                theInstance.unlock();
            }
        }
    }

    /**
     * Disposes the native memory associated with the given native
     * graphics config info pointer on the single queue flushing thread.
     */
    public static void disposeGraphicsConfig(long pConfigInfo) {
        OGLRenderQueue rq = getInstance();
        rq.lock();
        try {
            // make sure we make the context associated with the given
            // GraphicsConfig current before disposing the native resources
            OGLContext.setScratchSurface(pConfigInfo);

            rq.ensureCapacityAndAlignment(12, 4);
            RenderBuffer buf = rq.getBuffer();
            buf.putInt(DISPOSE_CONFIG);
            buf.putLong(pConfigInfo);

            // this call is expected to complete synchronously, so flush now
            rq.flushNow();
        } finally {
            rq.unlock();
        }
    }

    /**
     * Returns true if the current thread is the OGL QueueFlusher thread.
     */
    public static boolean isQueueFlusherThread() {
        return (Thread.currentThread() == getInstance().flusher.thread);
    }

  @Override
  public RenderQueue createRenderQueueImpl() {
    return new RenderQueueImpl() {
      @Override
      public void flushNow(boolean sync) {
        OGLRenderQueue.this.flushNow(sync);
      }

      @Override
      public void flushAndInvokeNow(Runnable task) {
        OGLRenderQueue.this.flushAndInvokeNow(task);
      }
    };
  }

  public void flushNow(boolean sync) {
        // assert lock.isHeldByCurrentThread();
        try {
            flusher.flushNow(sync);
        } catch (Exception e) {
            System.err.println("exception in flushNow:");
            e.printStackTrace();
        }
    }

    public void flushAndInvokeNow(Runnable r) {
        // assert lock.isHeldByCurrentThread();
        try {
            flusher.flushAndInvokeNow(r);
        } catch (Exception e) {
            System.err.println("exception in flushAndInvokeNow:");
            e.printStackTrace();
        }
    }

    private native void flushBuffer(long buf, int limit);

    private class QueueFlusher implements Runnable {
        private boolean needsFlush;
        private boolean sync;
        private Runnable task;
        private Error error;
        private final Thread thread;

        private final ReentrantLock lock = new ReentrantLock();
        private final Condition flushRequested = lock.newCondition();
        private final Condition flushDone = lock.newCondition();
       //  private final Condition condition = lock.newCondition();

        public QueueFlusher() {
            String name = "Java2D Queue Flusher";
            thread = new Thread(ThreadGroupUtils.getRootThreadGroup(),
                                this, name, 0, false);
            thread.setDaemon(true);
            thread.setPriority(Thread.MAX_PRIORITY);
            thread.start();
        }

        public void flushNow(boolean sync) {
         // sync = true;

            lock.lock();
            try {
              // wake up the flusher
              needsFlush = true;
              this.sync = sync;

         //     System.out.println("Queuing flush for: " + getBuffer().getAddress() +" pos: "+getBuffer().position());

              flushRequested.signal();
              //condition.signal();

              // wait for flush to complete
              while (needsFlush) {
                try {
               //   System.out.println("Before await");
                  flushDone.await();

                 // System.out.println("Await returned " + needsFlush + " flush for: " + getBuffer().getAddress() +" pos: "+getBuffer().position());

                  //  condition.await();
                } catch (InterruptedException e) {
                }
              }

            }finally {
              lock.unlock();
            }
            // re-throw any error that may have occurred during the flush
         /*   if (error != null) {
                throw error;
            }
            */
        }

        public void flushAndInvokeNow(Runnable task) {
            this.task = task;
            flushNow(true);
        }

        public void run() {
            boolean rqLock = false;

            while (true) {
              lock.lock();
                while (!needsFlush) {
                    try {
                      rqLock = false;
                        /*
                         * Wait until we're woken up with a flushNow() call,
                         * or the timeout period elapses (so that we can
                         * flush the queue periodically).
                         */
                      boolean wait = true;
                    /*  if( (rqLock = tryLock())) { //1, TimeUnit.MILLISECONDS
                        if(getBuffer().position() == 0) {
                          unlock();
                          rqLock = false;
                        } else {
                          wait = false;
                      //    System.out.println("no wait");
                        }
                      }*/

                      if(wait) {
                        flushRequested.await(250, TimeUnit.MILLISECONDS);
                      }

                    //  flushRequested.await();
                     // condition.await(100, TimeUnit.MILLISECONDS);
                        /*
                         * We will automatically flush the queue if the
                         * following conditions apply:
                         *   - the wait() timed out
                         *   - we can lock the queue (without blocking)
                         *   - there is something in the queue to flush
                         * Otherwise, just continue (we'll flush eventually).
                         */
                        if (!needsFlush && (rqLock || (rqLock = tryLock()))) {
                            if (getBuffer().position() > 0) {
                                needsFlush = true;
                                sync = false;
                            } else {
                                unlock();
                            }
                        }
                    } catch (InterruptedException e) {
                    }
                }
                boolean lockHeld = true;
                try {
                  {
                    RenderBuffer buf = getBuffer();
                    // assert lock.isHeldByCurrentThread();
                    int bufferPosition = buf.position();
                    long bufferAddress = buf.getAddress();
                    // reset the buffer position
                    buf.clear();

                    // will go out of scope after native flushBuffer is done
                    Collection<Object> refs = copyAndClearReferences();

                    togglePipelines();

                    if (!sync) {
                      if(rqLock) {
                       // System.out.println("async buffer swap");
                        unlock();
                        rqLock = false;
                      } else {
                        needsFlush = false;
                        flushDone.signal();
                        //condition.signal();
                      }

                        lock.unlock();
                        lockHeld = false;
                    }

                    if (bufferPosition > 0) {
                      // process the queue
                      //System.out.println("Flushing Buffer " + bufferAddress+" length: " + bufferPosition+" sync: "+sync);
                      flushBuffer(bufferAddress, bufferPosition);
                    }

                    if (!sync) {
                      if (task != null) {
                        System.out.println("Task is null during async!");
                      }
                      // assert task == null;
                    } else {
                      // if there's a task, invoke that now as well
                      if (task != null) {
                        task.run();
                      }
                    }
                  }
                } catch (Error e) {
                    error = e;
                } catch (Exception x) {
                    System.err.println("exception in QueueFlusher:");
                    x.printStackTrace();
                } finally {
                    if (rqLock) {
                        unlock();
                    }

                    task = null;

                  if(lockHeld) {
                    // allow the waiting thread to continue
                    needsFlush = false;
                    flushDone.signal();
                    //condition.signal();
                    lock.unlock();
                  }
                }
            }
        }
    }
}
