package io.netty.util.concurrent;

/**
 * Created by jiangfei on 2020/5/24.
 */
public interface GenericProgressiveFutureListener<F extends ProgressiveFuture<?>> extends GenericFutureListener<F> {

    void operationProgressed(F future, long progress, long total) throws Exception;
}
