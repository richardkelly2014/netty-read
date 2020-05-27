package io.netty.util.concurrent;

public interface EventExecutorChooserFactory {

    EventExecutorChooser newChooser(EventExecutor[] executors);

    interface EventExecutorChooser {

        /**
         * Returns the new {@link EventExecutor} to use.
         */
        EventExecutor next();
    }
}
