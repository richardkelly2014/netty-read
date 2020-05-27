package io.netty.util.concurrent;

import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.ThreadExecutorMap;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * 单 线程执行器
 * Created by jiangfei on 2020/5/25.
 */
@Slf4j
public abstract class SingleThreadEventExecutor extends AbstractScheduledEventExecutor implements OrderedEventExecutor {

    static final int DEFAULT_MAX_PENDING_EXECUTOR_TASKS = Math.max(16,
            SystemPropertyUtil.getInt("io.netty.eventexecutor.maxPendingTasks", Integer.MAX_VALUE));

    private static final int ST_NOT_STARTED = 1;
    private static final int ST_STARTED = 2;
    private static final int ST_SHUTTING_DOWN = 3;
    private static final int ST_SHUTDOWN = 4;
    private static final int ST_TERMINATED = 5;

    private static final Runnable NOOP_TASK = new Runnable() {
        @Override
        public void run() {
            // Do nothing.
        }
    };

    //状态
    private static final AtomicIntegerFieldUpdater<SingleThreadEventExecutor> STATE_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(SingleThreadEventExecutor.class, "state");
    private static final AtomicReferenceFieldUpdater<SingleThreadEventExecutor, ThreadProperties> PROPERTIES_UPDATER =
            AtomicReferenceFieldUpdater.newUpdater(
                    SingleThreadEventExecutor.class, ThreadProperties.class, "threadProperties");

    private final Queue<Runnable> taskQueue;

    private volatile Thread thread;
    private volatile ThreadProperties threadProperties;
    private final Executor executor;
    private volatile boolean interrupted;

    private final CountDownLatch threadLock = new CountDownLatch(1);
    private final Set<Runnable> shutdownHooks = new LinkedHashSet<Runnable>();
    private final boolean addTaskWakesUp;
    private final int maxPendingTasks;
    private final RejectedExecutionHandler rejectedExecutionHandler;

    private long lastExecutionTime;

    private volatile int state = ST_NOT_STARTED;

    private volatile long gracefulShutdownQuietPeriod;
    private volatile long gracefulShutdownTimeout;
    private long gracefulShutdownStartTime;

    private final Promise<?> terminationFuture = new DefaultPromise<Void>(GlobalEventExecutor.INSTANCE);

    protected SingleThreadEventExecutor(
            EventExecutorGroup parent, ThreadFactory threadFactory, boolean addTaskWakesUp) {
        this(parent, new ThreadPerTaskExecutor(threadFactory), addTaskWakesUp);
    }

    protected SingleThreadEventExecutor(
            EventExecutorGroup parent, ThreadFactory threadFactory,
            boolean addTaskWakesUp, int maxPendingTasks, RejectedExecutionHandler rejectedHandler) {
        this(parent, new ThreadPerTaskExecutor(threadFactory), addTaskWakesUp, maxPendingTasks, rejectedHandler);
    }

    protected SingleThreadEventExecutor(EventExecutorGroup parent, Executor executor, boolean addTaskWakesUp) {
        this(parent, executor, addTaskWakesUp, DEFAULT_MAX_PENDING_EXECUTOR_TASKS, RejectedExecutionHandlers.reject());
    }

    protected SingleThreadEventExecutor(EventExecutorGroup parent, Executor executor,
                                        boolean addTaskWakesUp, int maxPendingTasks,
                                        RejectedExecutionHandler rejectedHandler) {
        super(parent);
        this.addTaskWakesUp = addTaskWakesUp;
        this.maxPendingTasks = Math.max(16, maxPendingTasks);
        this.executor = ThreadExecutorMap.apply(executor, this);
        taskQueue = newTaskQueue(this.maxPendingTasks);
        rejectedExecutionHandler = ObjectUtil.checkNotNull(rejectedHandler, "rejectedHandler");
    }

    protected SingleThreadEventExecutor(EventExecutorGroup parent, Executor executor,
                                        boolean addTaskWakesUp, Queue<Runnable> taskQueue,
                                        RejectedExecutionHandler rejectedHandler) {
        super(parent);
        this.addTaskWakesUp = addTaskWakesUp;
        this.maxPendingTasks = DEFAULT_MAX_PENDING_EXECUTOR_TASKS;
        this.executor = ThreadExecutorMap.apply(executor, this);
        this.taskQueue = ObjectUtil.checkNotNull(taskQueue, "taskQueue");
        this.rejectedExecutionHandler = ObjectUtil.checkNotNull(rejectedHandler, "rejectedHandler");
    }

    protected Queue<Runnable> newTaskQueue(int maxPendingTasks) {
        return new LinkedBlockingQueue<Runnable>(maxPendingTasks);
    }

    protected Runnable pollTask() {
        assert inEventLoop();
        return pollTaskFrom(taskQueue);
    }

    protected static Runnable pollTaskFrom(Queue<Runnable> taskQueue) {
        for (; ; ) {
            Runnable task = taskQueue.poll();
            if (task != WAKEUP_TASK) {
                return task;
            }
        }
    }

    //取出任务
    //必须在工作线程中操作
    protected Runnable takeTask() {
        assert inEventLoop();
        if (!(taskQueue instanceof BlockingQueue)) {
            throw new UnsupportedOperationException();
        }

        BlockingQueue<Runnable> taskQueue = (BlockingQueue<Runnable>) this.taskQueue;

        for (; ; ) {
            //取一个延迟任务
            ScheduledFutureTask<?> scheduledTask = peekScheduledTask();
            if (scheduledTask == null) {
                Runnable task = null;
                try {
                    task = taskQueue.take();
                    if (task == WAKEUP_TASK) {
                        task = null;
                    }
                } catch (InterruptedException e) {
                    // Ignore
                }
                return task;
            } else {
                long delayNanos = scheduledTask.delayNanos();
                Runnable task = null;
                if (delayNanos > 0) {
                    try {
                        task = taskQueue.poll(delayNanos, TimeUnit.NANOSECONDS);
                    } catch (InterruptedException e) {
                        // Waken up.
                        return null;
                    }
                }
                if (task == null) {
                    fetchFromScheduledTaskQueue();
                    task = taskQueue.poll();
                }

                if (task != null) {
                    return task;
                }
            }
        }
    }

    //从延迟任务队列取，放入本地queue
    private boolean fetchFromScheduledTaskQueue() {
        if (scheduledTaskQueue == null || scheduledTaskQueue.isEmpty()) {
            return true;
        }
        long nanoTime = AbstractScheduledEventExecutor.nanoTime();
        for (; ; ) {
            Runnable scheduledTask = pollScheduledTask(nanoTime);
            if (scheduledTask == null) {
                return true;
            }
            if (!taskQueue.offer(scheduledTask)) {
                // No space left in the task queue add it back to the scheduledTaskQueue so we pick it up again.
                scheduledTaskQueue.add((ScheduledFutureTask<?>) scheduledTask);
                return false;
            }
        }
    }

    //必须在工作线程中操作
    protected Runnable peekTask() {
        assert inEventLoop();
        return taskQueue.peek();
    }

    //必须在工作线程中操作
    protected boolean hasTasks() {
        assert inEventLoop();
        return !taskQueue.isEmpty();
    }

    public int pendingTasks() {
        return taskQueue.size();
    }

    protected void addTask(Runnable task) {
        ObjectUtil.checkNotNull(task, "task");
        if (!offerTask(task)) {
            reject(task);
        }
    }

    final boolean offerTask(Runnable task) {
        if (isShutdown()) {
            reject();
        }
        return taskQueue.offer(task);
    }

    protected boolean removeTask(Runnable task) {
        return taskQueue.remove(ObjectUtil.checkNotNull(task, "task"));
    }

    //执行所有任务
    //必须在工作线程中操作
    protected boolean runAllTasks() {
        assert inEventLoop();
        boolean fetchedAll;
        boolean ranAtLeastOne = false;

        do {
            fetchedAll = fetchFromScheduledTaskQueue();
            if (runAllTasksFrom(taskQueue)) {
                ranAtLeastOne = true;
            }
        } while (!fetchedAll); // keep on processing until we fetched all scheduled tasks.

        if (ranAtLeastOne) {
            lastExecutionTime = ScheduledFutureTask.nanoTime();
        }
        afterRunningAllTasks();
        return ranAtLeastOne;
    }

    protected final boolean runAllTasksFrom(Queue<Runnable> taskQueue) {
        Runnable task = pollTaskFrom(taskQueue);
        if (task == null) {
            return false;
        }
        for (; ; ) {
            //执行
            safeExecute(task);
            task = pollTaskFrom(taskQueue);
            if (task == null) {
                return true;
            }
        }
    }

    protected void afterRunningAllTasks() {
    }


    protected void updateLastExecutionTime() {
        //最新执行时间
        lastExecutionTime = ScheduledFutureTask.nanoTime();
    }

    /**
     * Run the tasks in the {@link #taskQueue}
     */
    protected abstract void run();

    /**
     * Do nothing, sub-classes may override
     */
    protected void cleanup() {
        // NOOP
    }

    protected void wakeup(boolean inEventLoop) {
        if (!inEventLoop) {
            // Use offer as we actually only need this to unblock the thread and if offer fails we do not care as there
            // is already something in the queue.
            taskQueue.offer(WAKEUP_TASK);
        }
    }

    @Override
    public boolean inEventLoop(Thread thread) {
        return thread == this.thread;
    }

    public void addShutdownHook(final Runnable task) {
        if (inEventLoop()) {
            shutdownHooks.add(task);
        } else {
            execute(new Runnable() {
                @Override
                public void run() {
                    shutdownHooks.add(task);
                }
            });
        }
    }

    public void removeShutdownHook(final Runnable task) {
        if (inEventLoop()) {
            shutdownHooks.remove(task);
        } else {
            execute(new Runnable() {
                @Override
                public void run() {
                    shutdownHooks.remove(task);
                }
            });
        }
    }

    private boolean runShutdownHooks() {
        boolean ran = false;
        // Note shutdown hooks can add / remove shutdown hooks.
        while (!shutdownHooks.isEmpty()) {
            List<Runnable> copy = new ArrayList<Runnable>(shutdownHooks);
            shutdownHooks.clear();
            for (Runnable task : copy) {
                try {
                    task.run();
                } catch (Throwable t) {
                    log.warn("Shutdown hook raised an exception.", t);
                } finally {
                    ran = true;
                }
            }
        }

        if (ran) {
            lastExecutionTime = ScheduledFutureTask.nanoTime();
        }

        return ran;
    }

    @Override
    public Future<?> shutdownGracefully(long quietPeriod, long timeout, TimeUnit unit) {
        ObjectUtil.checkPositiveOrZero(quietPeriod, "quietPeriod");
        if (timeout < quietPeriod) {
            throw new IllegalArgumentException(
                    "timeout: " + timeout + " (expected >= quietPeriod (" + quietPeriod + "))");
        }
        ObjectUtil.checkNotNull(unit, "unit");

        if (isShuttingDown()) {
            return terminationFuture();
        }

        boolean inEventLoop = inEventLoop();
        boolean wakeup;
        int oldState;
        for (; ; ) {
            if (isShuttingDown()) {
                return terminationFuture();
            }
            int newState;
            wakeup = true;
            oldState = state;
            if (inEventLoop) {
                newState = ST_SHUTTING_DOWN;
            } else {
                switch (oldState) {
                    case ST_NOT_STARTED:
                    case ST_STARTED:
                        newState = ST_SHUTTING_DOWN;
                        break;
                    default:
                        newState = oldState;
                        wakeup = false;
                }
            }
            if (STATE_UPDATER.compareAndSet(this, oldState, newState)) {
                break;
            }
        }
        gracefulShutdownQuietPeriod = unit.toNanos(quietPeriod);
        gracefulShutdownTimeout = unit.toNanos(timeout);

        if (ensureThreadStarted(oldState)) {
            return terminationFuture;
        }

        if (wakeup) {
            taskQueue.offer(WAKEUP_TASK);
            if (!addTaskWakesUp) {
                wakeup(inEventLoop);
            }
        }

        return terminationFuture();
    }

    @Override
    public Future<?> terminationFuture() {
        return terminationFuture;
    }

    @Override
    @Deprecated
    public void shutdown() {
        if (isShutdown()) {
            return;
        }

        boolean inEventLoop = inEventLoop();
        boolean wakeup;
        int oldState;
        for (; ; ) {
            if (isShuttingDown()) {
                return;
            }
            int newState;
            wakeup = true;
            oldState = state;
            if (inEventLoop) {
                newState = ST_SHUTDOWN;
            } else {
                switch (oldState) {
                    case ST_NOT_STARTED:
                    case ST_STARTED:
                    case ST_SHUTTING_DOWN:
                        newState = ST_SHUTDOWN;
                        break;
                    default:
                        newState = oldState;
                        wakeup = false;
                }
            }
            if (STATE_UPDATER.compareAndSet(this, oldState, newState)) {
                break;
            }
        }

        if (ensureThreadStarted(oldState)) {
            return;
        }

        if (wakeup) {
            taskQueue.offer(WAKEUP_TASK);
            if (!addTaskWakesUp) {
                wakeup(inEventLoop);
            }
        }
    }

    @Override
    public boolean isShuttingDown() {
        return state >= ST_SHUTTING_DOWN;
    }

    @Override
    public boolean isShutdown() {
        return state >= ST_SHUTDOWN;
    }

    @Override
    public boolean isTerminated() {
        return state == ST_TERMINATED;
    }

    /**
     * 确认是否关闭
     * 必须在工作线程中操作
     *
     * @return
     */
    protected boolean confirmShutdown() {
        if (!isShuttingDown()) {
            return false;
        }

        if (!inEventLoop()) {
            throw new IllegalStateException("must be invoked from an event loop");
        }

        cancelScheduledTasks();

        if (gracefulShutdownStartTime == 0) {
            gracefulShutdownStartTime = ScheduledFutureTask.nanoTime();
        }

        if (runAllTasks() || runShutdownHooks()) {
            if (isShutdown()) {
                // Executor shut down - no new tasks anymore.
                return true;
            }

            // There were tasks in the queue. Wait a little bit more until no tasks are queued for the quiet period or
            // terminate if the quiet period is 0.
            // See https://github.com/netty/netty/issues/4241
            if (gracefulShutdownQuietPeriod == 0) {
                return true;
            }
            taskQueue.offer(WAKEUP_TASK);
            return false;
        }

        final long nanoTime = ScheduledFutureTask.nanoTime();

        if (isShutdown() || nanoTime - gracefulShutdownStartTime > gracefulShutdownTimeout) {
            return true;
        }

        if (nanoTime - lastExecutionTime <= gracefulShutdownQuietPeriod) {
            // Check if any tasks were added to the queue every 100ms.
            // TODO: Change the behavior of takeTask() so that it returns on timeout.
            taskQueue.offer(WAKEUP_TASK);
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                // Ignore
            }

            return false;
        }

        // No tasks were added for last quiet period - hopefully safe to shut down.
        // (Hopefully because we really cannot make a guarantee that there will be no execute() calls by a user.)
        return true;
    }


    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        ObjectUtil.checkNotNull(unit, "unit");
        if (inEventLoop()) {
            throw new IllegalStateException("cannot await termination of the current thread");
        }

        threadLock.await(timeout, unit);

        return isTerminated();
    }

    @Override
    public void execute(Runnable task) {
        ObjectUtil.checkNotNull(task, "task");
        execute(task, !(task instanceof LazyRunnable) && wakesUpForTask(task));
    }

    @Override
    public void lazyExecute(Runnable task) {
        execute(ObjectUtil.checkNotNull(task, "task"), false);
    }

    private void execute(Runnable task, boolean immediate) {
        boolean inEventLoop = inEventLoop();
        addTask(task);
        if (!inEventLoop) {
            // 不在启动线程
            startThread();
            if (isShutdown()) {
                // 是否关闭
                boolean reject = false;
                try {
                    if (removeTask(task)) {
                        reject = true;
                    }
                } catch (UnsupportedOperationException e) {
                }
                if (reject) {
                    reject();
                }
            }
        }

        if (!addTaskWakesUp && immediate) {
            wakeup(inEventLoop);
        }
    }


    protected boolean wakesUpForTask(Runnable task) {
        return true;
    }

    /**
     * 拒绝
     */
    protected static void reject() {
        throw new RejectedExecutionException("event executor terminated");
    }

    protected final void reject(Runnable task) {
        rejectedExecutionHandler.rejected(task, this);
    }

    private static final long SCHEDULE_PURGE_INTERVAL = TimeUnit.SECONDS.toNanos(1);

    //启动
    private void startThread() {
        if (state == ST_NOT_STARTED) {
            if (STATE_UPDATER.compareAndSet(this, ST_NOT_STARTED, ST_STARTED)) {
                boolean success = false;
                try {
                    doStartThread();
                    success = true;
                } finally {
                    if (!success) {
                        STATE_UPDATER.compareAndSet(this, ST_STARTED, ST_NOT_STARTED);
                    }
                }
            }
        }
    }

    private boolean ensureThreadStarted(int oldState) {
        if (oldState == ST_NOT_STARTED) {
            try {
                doStartThread();
            } catch (Throwable cause) {
                STATE_UPDATER.set(this, ST_TERMINATED);
                terminationFuture.tryFailure(cause);

                if (!(cause instanceof Exception)) {
                    // Also rethrow as it may be an OOME for example
                    PlatformDependent.throwException(cause);
                }
                return true;
            }
        }
        return false;
    }

    private void doStartThread() {
        assert thread == null;
        //提交一个线程启动
        executor.execute(new Runnable() {
            @Override
            public void run() {
                //启动线程
                thread = Thread.currentThread();
                if (interrupted) {
                    thread.interrupt();
                }

                boolean success = false;
                updateLastExecutionTime();
                try {
                    //执行run 方法
                    SingleThreadEventExecutor.this.run();
                    success = true;
                } catch (Throwable t) {
                    log.warn("Unexpected exception from an event executor: ", t);
                } finally {
                    //执行结束一般关闭 executor
                    for (; ; ) {
                        int oldState = state;
                        if (oldState >= ST_SHUTTING_DOWN || STATE_UPDATER.compareAndSet(
                                SingleThreadEventExecutor.this, oldState, ST_SHUTTING_DOWN)) {
                            break;
                        }
                    }

                    try {
                        for (; ; ) {
                            if (confirmShutdown()) {
                                break;
                            }
                        }

                        for (; ; ) {
                            int oldState = state;
                            if (oldState >= ST_SHUTDOWN || STATE_UPDATER.compareAndSet(
                                    SingleThreadEventExecutor.this, oldState, ST_SHUTDOWN)) {
                                break;
                            }
                        }

                        confirmShutdown();
                    } finally {
                        try {
                            cleanup();
                        } finally {
                            FastThreadLocal.removeAll();

                            STATE_UPDATER.set(SingleThreadEventExecutor.this, ST_TERMINATED);
                            threadLock.countDown();
                            int numUserTasks = drainTasks();
                            terminationFuture.setSuccess(null);
                        }
                    }
                }
            }
        });
    }


    final int drainTasks() {
        int numTasks = 0;
        for (; ; ) {
            Runnable runnable = taskQueue.poll();
            if (runnable == null) {
                break;
            }
            // WAKEUP_TASK should be just discarded as these are added internally.
            // The important bit is that we not have any user tasks left.
            if (WAKEUP_TASK != runnable) {
                numTasks++;
            }
        }
        return numTasks;
    }

    private static final class DefaultThreadProperties implements ThreadProperties {
        private final Thread t;

        DefaultThreadProperties(Thread t) {
            this.t = t;
        }

        @Override
        public Thread.State state() {
            return t.getState();
        }

        @Override
        public int priority() {
            return t.getPriority();
        }

        @Override
        public boolean isInterrupted() {
            return t.isInterrupted();
        }

        @Override
        public boolean isDaemon() {
            return t.isDaemon();
        }

        @Override
        public String name() {
            return t.getName();
        }

        @Override
        public long id() {
            return t.getId();
        }

        @Override
        public StackTraceElement[] stackTrace() {
            return t.getStackTrace();
        }

        @Override
        public boolean isAlive() {
            return t.isAlive();
        }
    }
}
