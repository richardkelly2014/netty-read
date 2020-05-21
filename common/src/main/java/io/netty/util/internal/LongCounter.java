
package io.netty.util.internal;

/**
 * Counter for long.
 */
public interface LongCounter {
    void add(long delta);
    void increment();
    void decrement();
    long value();
}
