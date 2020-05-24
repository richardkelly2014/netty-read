package io.netty.util.concurrent;

import java.util.EventListener;

/**
 * Created by jiangfei on 2020/5/24.
 */
public interface GenericFutureListener<F extends Future<?>> extends EventListener {

    void operationComplete(F future) throws Exception;
}
