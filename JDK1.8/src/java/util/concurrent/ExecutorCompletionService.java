/*
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */

/*
 *
 *
 *
 *
 *
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */

package java.util.concurrent;

/**
 * A {@link CompletionService} that uses a supplied {@link Executor}
 * to execute tasks.  This class arranges that submitted tasks are,
 * upon completion, placed on a queue accessible using {@code take}.
 * The class is lightweight enough to be suitable for transient use
 * when processing groups of tasks.
 *
 * <p>
 *
 * <b>Usage Examples.</b>
 *
 * Suppose you have a set of solvers for a certain problem, each
 * returning a value of some type {@code Result}, and would like to
 * run them concurrently, processing the results of each of them that
 * return a non-null value, in some method {@code use(Result r)}. You
 * could write this as:
 *
 * <pre> {@code
 * void solve(Executor e,
 *            Collection<Callable<Result>> solvers)
 *     throws InterruptedException, ExecutionException {
 *     CompletionService<Result> ecs
 *         = new ExecutorCompletionService<Result>(e);
 *     for (Callable<Result> s : solvers)
 *         ecs.submit(s);
 *     int n = solvers.size();
 *     for (int i = 0; i < n; ++i) {
 *         Result r = ecs.take().get();
 *         if (r != null)
 *             use(r);
 *     }
 * }}</pre>
 *
 * Suppose instead that you would like to use the first non-null result
 * of the set of tasks, ignoring any that encounter exceptions,
 * and cancelling all other tasks when the first one is ready:
 *
 * <pre> {@code
 * void solve(Executor e,
 *            Collection<Callable<Result>> solvers)
 *     throws InterruptedException {
 *     CompletionService<Result> ecs
 *         = new ExecutorCompletionService<Result>(e);
 *     int n = solvers.size();
 *     List<Future<Result>> futures
 *         = new ArrayList<Future<Result>>(n);
 *     Result result = null;
 *     try {
 *         for (Callable<Result> s : solvers)
 *             futures.add(ecs.submit(s));
 *         for (int i = 0; i < n; ++i) {
 *             try {
 *                 Result r = ecs.take().get();
 *                 if (r != null) {
 *                     result = r;
 *                     break;
 *                 }
 *             } catch (ExecutionException ignore) {}
 *         }
 *     }
 *     finally {
 *         for (Future<Result> f : futures)
 *             f.cancel(true);
 *     }
 *
 *     if (result != null)
 *         use(result);
 * }}</pre>
 *
 * CompletionService 的默认实现, 内部实现由线程池和队列组合而成
 *
 */
public class ExecutorCompletionService<V> implements CompletionService<V> {
    /**
     * 线程池，用于执行任务
     */
    private final Executor executor;
    /**
     * 如果在构造函数传入进来的 executor 是 AbstractExecutorService 的子类，会将 executor 强转并赋值给该属性，
     * 该类主要用来将Callable和Runnable任务包装成FutureTask任务，下面会进行介绍
     */
    private final AbstractExecutorService aes;
    /**
     * 存放执行完成的任务，如果任务执行出现异常，不会存放在队列中，队列如果没有自定义，在构造函数传入进来，
     * ExecutorCompletionService会创建一个无限的链表队列，如果没有及时从队列获取执行完成的任务，有可能会导致内存溢出
     */
    private final BlockingQueue<Future<V>> completionQueue;

    /**
     * QueueingFuture 是 FutureTask 的子类
     * 重写了FutureTask中done方法，done方法在FutureTask中是个空方法，模板方法，子类可以进行重写
     * FutureTask extension to enqueue upon completion
     */
    private class QueueingFuture extends FutureTask<Void> {
        QueueingFuture(RunnableFuture<V> task) {
            super(task, null);
            this.task = task;
        }

        /**
         * 重写了 FutureTask 的 done 方法, 在 FutureTask 完成时添加到队列中
         */
        protected void done() { completionQueue.add(task); }

        /**
         * 包装的 Future 对象
         */
        private final Future<V> task;
    }

    /**
     * 将传入进来的Callable类型的任务封装成RunnableFuture任务
     */
    private RunnableFuture<V> newTaskFor(Callable<V> task) {
        //如果传入进来的线程池对象executor是AbstractExecutorService的子类，aes就赋值为线程池对象
        //如果aes为空
        if (aes == null)
            //直接使用FutureTask的构造函数将task封装成FutureTask
            return new FutureTask<V>(task);
        else
            //调用aes.newTaskFor方法将传入进来的task封装成FutureTask，
            // newTaskFor方法内部也是使用FutureTask的构造函数将task封装成FutureTask
            return aes.newTaskFor(task);
    }

    /**
     * //将传入进来的Runnable类型的任务封装成RunnableFuture任务
     */
    private RunnableFuture<V> newTaskFor(Runnable task, V result) {
        if (aes == null)
            return new FutureTask<V>(task, result);
        else
            return aes.newTaskFor(task, result);
    }

    /**
     * 用 Executor 构造 ExecutorCompletionService
     * Creates an ExecutorCompletionService using the supplied
     * executor for base task execution and a
     * {@link LinkedBlockingQueue} as a completion queue.
     *
     * @param executor the executor to use
     * @throws NullPointerException if executor is {@code null}
     */
    public ExecutorCompletionService(Executor executor) {
        //executor 不能为 null
        if (executor == null)
            throw new NullPointerException();
        //保存
        this.executor = executor;
        //如果 executor 是 AbstractExecutorService 类型, 则强转缓存, 否则给 null
        this.aes = (executor instanceof AbstractExecutorService) ?
            (AbstractExecutorService) executor : null;
        //创建 LinkedBlockingQueue , LinkedBlockingQueue 默认容量是 Integer 的最大值
        this.completionQueue = new LinkedBlockingQueue<Future<V>>();
    }

    /**
     * 给定线程池和阻塞队列构造 ExecutorCompletionService 实例
     * Creates an ExecutorCompletionService using the supplied
     * executor for base task execution and the supplied queue as its
     * completion queue.
     *
     * @param executor the executor to use
     * @param completionQueue the queue to use as the completion queue
     *        normally one dedicated for use by this service. This
     *        queue is treated as unbounded -- failed attempted
     *        {@code Queue.add} operations for completed tasks cause
     *        them not to be retrievable.
     * @throws NullPointerException if executor or completionQueue are {@code null}
     */
    public ExecutorCompletionService(Executor executor,
                                     BlockingQueue<Future<V>> completionQueue) {
        //保证 executor 和 队列都不为 null
        if (executor == null || completionQueue == null)
            throw new NullPointerException();
        this.executor = executor;
        //如果类型对的话, 强转并保存到 aes
        this.aes = (executor instanceof AbstractExecutorService) ?
            (AbstractExecutorService) executor : null;
        //保存队列
        this.completionQueue = completionQueue;
    }

    public Future<V> submit(Callable<V> task) {
        if (task == null) throw new NullPointerException();
        RunnableFuture<V> f = newTaskFor(task);
        executor.execute(new QueueingFuture(f));
        return f;
    }

    public Future<V> submit(Runnable task, V result) {
        //任务不能为 null
        if (task == null) throw new NullPointerException();
        //创建 RunnableFuture
        RunnableFuture<V> f = newTaskFor(task, result);
        //用 executor 执行
        executor.execute(new QueueingFuture(f));
        return f;
    }

    public Future<V> take() throws InterruptedException {
        //从队列中获取, 没有已经完成的任务, 则等待
        return completionQueue.take();
    }

    public Future<V> poll() {
        return completionQueue.poll();
    }

    public Future<V> poll(long timeout, TimeUnit unit)
            throws InterruptedException {
        return completionQueue.poll(timeout, unit);
    }

}
