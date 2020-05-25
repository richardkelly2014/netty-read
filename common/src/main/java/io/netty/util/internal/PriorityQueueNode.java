package io.netty.util.internal;


public interface PriorityQueueNode {

    int INDEX_NOT_IN_QUEUE = -1;

    /**
     * get queue index
     *
     * @param queue
     * @return
     */
    int priorityQueueIndex(DefaultPriorityQueue<?> queue);

    /**
     * set queue index
     *
     * @param queue
     * @param i
     */
    void priorityQueueIndex(DefaultPriorityQueue<?> queue, int i);
}
