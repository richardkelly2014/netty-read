package io.netty.util.concurrent;

import java.util.Iterator;
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Created by jiangfei on 2020/5/24.
 */
public interface EventExecutorGroup  extends ScheduledExecutorService, Iterable<EventExecutor>  {

    boolean isShuttingDown();
    Future<?> shutdownGracefully();

    Future<?> shutdownGracefully(long quietPeriod, long timeout, TimeUnit unit);

    Future<?> terminationFuture();

    EventExecutor next();

    @Override
    Iterator<EventExecutor> iterator();

    @Override
    Future<?> submit(Runnable task);

    @Override
    <T> Future<T> submit(Runnable task, T result);

    @Override
    <T> Future<T> submit(Callable<T> task);

    @Override
    ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit);

    @Override
    <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit);

    @Override
    ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit);

    @Override
    ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit);
}
