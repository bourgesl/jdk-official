package sun.java2d.pipe;

import sun.java2d.opengl.OGLRenderQueue;

import java.util.Collection;

public abstract class DoubleBufferedRenderQueue implements RenderQueue {
  RenderQueue nativeRenderQueue;
  RenderQueue javaRenderQueue;

  public DoubleBufferedRenderQueue() {
    nativeRenderQueue = createRenderQueueImpl();
    javaRenderQueue = createRenderQueueImpl();
  }

  public abstract RenderQueue createRenderQueueImpl();

  @Override
  public Collection<Object> copyAndClearReferences() {
    return javaRenderQueue.copyAndClearReferences();
  }

  @Override
  public void addReference(Object ref) {
    javaRenderQueue.addReference(ref);
  }

  @Override
  public RenderBuffer getBuffer() {
    return javaRenderQueue.getBuffer();
  }

  @Override
  public void ensureCapacity(int opsize) {
    javaRenderQueue.ensureCapacity(opsize);
  }

  @Override
  public void ensureCapacityAndAlignment(int opsize, int first8ByteValueOffset) {
    javaRenderQueue.ensureCapacityAndAlignment(opsize, first8ByteValueOffset);
  }

  @Override
  public void ensureAlignment(int first8ByteValueOffset) {
    javaRenderQueue.ensureAlignment(first8ByteValueOffset);
  }

  public void flushNow(int position, boolean sync) {
    Thread.dumpStack();
    javaRenderQueue.getBuffer().position(position);
    flushNow(sync);
  }

  public void togglePipelines() {
    RenderQueue tmp = nativeRenderQueue;
    nativeRenderQueue = javaRenderQueue;
    javaRenderQueue = tmp;
  }

}
