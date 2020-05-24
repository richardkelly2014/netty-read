package io.netty.util.concurrent;

/**
 * Created by jiangfei on 2020/5/24.
 */
public interface EventExecutor extends EventExecutorGroup {

    @Override
    EventExecutor next();

    EventExecutorGroup parent();

    boolean inEventLoop();

    boolean inEventLoop(Thread thread);

    <V> Promise<V> newPromise();

    <V> ProgressivePromise<V> newProgressivePromise();

    <V> Future<V> newSucceededFuture(V result);

    <V> Future<V> newFailedFuture(Throwable cause);
}
