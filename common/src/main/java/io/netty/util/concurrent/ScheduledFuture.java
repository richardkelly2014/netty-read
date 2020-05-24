package io.netty.util.concurrent;

/**
 * 定时
 * Created by jiangfei on 2020/5/24.
 */
public interface ScheduledFuture<V> extends Future<V>, java.util.concurrent.ScheduledFuture<V> {
}
