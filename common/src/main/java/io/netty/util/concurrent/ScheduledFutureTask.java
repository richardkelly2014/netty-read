package io.netty.util.concurrent;

import io.netty.util.internal.PriorityQueueNode;

/**
 * Created by jiangfei on 2020/5/24.
 */
final class ScheduledFutureTask<V> extends PromiseTask<V> implements ScheduledFuture<V>, PriorityQueueNode {
}
