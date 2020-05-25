package io.netty.util.concurrent;

/**
 * Expose details for a {@link Thread}.
 */
public interface ThreadProperties {
    /**
     * @see Thread#getState()
     */
    Thread.State state();

    /**
     * @see Thread#getPriority()
     */
    int priority();

    /**
     * @see Thread#isInterrupted()
     */
    boolean isInterrupted();

    /**
     * @see Thread#isDaemon()
     */
    boolean isDaemon();

    /**
     * @see Thread#getName()
     */
    String name();

    /**
     * @see Thread#getId()
     */
    long id();

    /**
     * @see Thread#getStackTrace()
     */
    StackTraceElement[] stackTrace();

    /**
     * @see Thread#isAlive()
     */
    boolean isAlive();
}
