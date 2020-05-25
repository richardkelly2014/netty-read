package io.netty.util.concurrent;

/**
 * Created by jiangfei on 2020/5/25.
 */
public interface RejectedExecutionHandler {

    void rejected(Runnable task, SingleThreadEventExecutor executor);
}
