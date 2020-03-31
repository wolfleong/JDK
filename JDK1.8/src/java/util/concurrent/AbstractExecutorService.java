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
import java.util.*;

/**
 * Provides default implementations of {@link ExecutorService}
 * execution methods. This class implements the {@code submit},
 * {@code invokeAny} and {@code invokeAll} methods using a
 * {@link RunnableFuture} returned by {@code newTaskFor}, which defaults
 * to the {@link FutureTask} class provided in this package.  For example,
 * the implementation of {@code submit(Runnable)} creates an
 * associated {@code RunnableFuture} that is executed and
 * returned. Subclasses may override the {@code newTaskFor} methods
 * to return {@code RunnableFuture} implementations other than
 * {@code FutureTask}.
 *
 * <p><b>Extension example</b>. Here is a sketch of a class
 * that customizes {@link ThreadPoolExecutor} to use
 * a {@code CustomTask} class instead of the default {@code FutureTask}:
 *  <pre> {@code
 * public class CustomThreadPoolExecutor extends ThreadPoolExecutor {
 *
 *   static class CustomTask<V> implements RunnableFuture<V> {...}
 *
 *   protected <V> RunnableFuture<V> newTaskFor(Callable<V> c) {
 *       return new CustomTask<V>(c);
 *   }
 *   protected <V> RunnableFuture<V> newTaskFor(Runnable r, V v) {
 *       return new CustomTask<V>(r, v);
 *   }
 *   // ... add constructors, etc.
 * }}</pre>
 *
 * AbstractExecutorService 抽象类派生自 ExecutorService 接口，然后在其基础上实现了几个实用的方法，这些方法提供给子类进行调用。
 * - 这实现了通用的方法, 如: submit、invokeAny、invokeAll 等方法
 *
 * @since 1.5
 * @author Doug Lea
 */
public abstract class AbstractExecutorService implements ExecutorService {

    /**
     * 根据 Runnable 和 返回值创建 RunnableFuture, 默认是创建 FutureTask
     * Returns a {@code RunnableFuture} for the given runnable and default
     * value.
     *
     * @param runnable the runnable task being wrapped
     * @param value the default value for the returned future
     * @param <T> the type of the given value
     * @return a {@code RunnableFuture} which, when run, will run the
     * underlying runnable and which, as a {@code Future}, will yield
     * the given value as its result and provide for cancellation of
     * the underlying task
     * @since 1.6
     */
    protected <T> RunnableFuture<T> newTaskFor(Runnable runnable, T value) {
        return new FutureTask<T>(runnable, value);
    }

    /**
     * 根据 Callable 创建 RunnableFuture, 默认是创建 FutureTask
     * Returns a {@code RunnableFuture} for the given callable task.
     *
     * @param callable the callable task being wrapped
     * @param <T> the type of the callable's result
     * @return a {@code RunnableFuture} which, when run, will call the
     * underlying callable and which, as a {@code Future}, will yield
     * the callable's result as its result and provide for
     * cancellation of the underlying task
     * @since 1.6
     */
    protected <T> RunnableFuture<T> newTaskFor(Callable<T> callable) {
        return new FutureTask<T>(callable);
    }

    /**
     * 提交任务
     *
     * @throws RejectedExecutionException {@inheritDoc}
     * @throws NullPointerException       {@inheritDoc}
     */
    public Future<?> submit(Runnable task) {
        //任务不能为 null
        if (task == null) throw new NullPointerException();
        // 将任务包装成 RunnableFuture
        RunnableFuture<Void> ftask = newTaskFor(task, null);
        //交给执行器执行，execute 方法由具体的子类来实现
        execute(ftask);
        //返回 ftask
        return ftask;
    }

    /**
     * @throws RejectedExecutionException {@inheritDoc}
     * @throws NullPointerException       {@inheritDoc}
     */
    public <T> Future<T> submit(Runnable task, T result) {
        //判断非空
        if (task == null) throw new NullPointerException();
        RunnableFuture<T> ftask = newTaskFor(task, result);
        execute(ftask);
        return ftask;
    }

    /**
     * @throws RejectedExecutionException {@inheritDoc}
     * @throws NullPointerException       {@inheritDoc}
     */
    public <T> Future<T> submit(Callable<T> task) {
        if (task == null) throw new NullPointerException();
        RunnableFuture<T> ftask = newTaskFor(task);
        execute(ftask);
        return ftask;
    }

    /**
     * // 此方法目的：将 tasks 集合中的任务提交到线程池执行，任意一个线程执行完后就可以结束了
     * // 第二个参数 timed 代表是否设置超时机制，超时时间为第三个参数，
     * // 如果 timed 为 true，同时超时了还没有一个线程返回结果，那么抛出 TimeoutException 异常
     * the main mechanics of invokeAny.
     */
    private <T> T doInvokeAny(Collection<? extends Callable<T>> tasks,
                              boolean timed, long nanos)
        throws InterruptedException, ExecutionException, TimeoutException {
        //任务列表非空
        if (tasks == null)
            throw new NullPointerException();
        //任务数
        int ntasks = tasks.size();
        //任务列表不能为空
        if (ntasks == 0)
            throw new IllegalArgumentException();
        //创建任务结果列表
        ArrayList<Future<T>> futures = new ArrayList<Future<T>>(ntasks);
        //用当前 Executor 创建 ExecutorCompletionService , 可以获取已经完成的任务结果
        ExecutorCompletionService<T> ecs =
            new ExecutorCompletionService<T>(this);

        // For efficiency, especially in executors with limited
        // parallelism, check to see if previously submitted tasks are
        // done before submitting more of them. This interleaving
        // plus the exception mechanics account for messiness of main
        // loop.

        try {
            // Record exceptions so that if we fail to obtain any
            // result, we can throw the last exception we got.
            // 用于保存异常信息，此方法如果没有得到任何有效的结果，那么我们可以抛出最后得到的一个异常
            ExecutionException ee = null;
            //如果有给定时间, 则计算等待结束的时间
            final long deadline = timed ? System.nanoTime() + nanos : 0L;
            //获取提交的任务的迭代器
            Iterator<? extends Callable<T>> it = tasks.iterator();

            //用 ExecutorCompletionService 提交任务, 并且记录异步结果到 futures
            // Start one task for sure; the rest incrementally
            futures.add(ecs.submit(it.next()));
            //提交了一个任务，所以任务数量减 1
            --ntasks;
            //正在执行的任务数(提交的时候 +1，任务结束的时候 -1)
            int active = 1;

            //自旋
            for (;;) {
                //获取任务异步结果, 这个方法不阻塞
                Future<T> f = ecs.poll();
                //为 null，说明刚刚提交的第一个线程还没有执行完成
                //在前面先提交一个任务，加上这里做一次检查，也是为了提高性能
                if (f == null) {
                    //如果还有剩下的任务
                    if (ntasks > 0) {
                        --ntasks;
                        //继续加入
                        futures.add(ecs.submit(it.next()));
                        ++active;
                    }
                    //能下来, 表示已经没有任务了
                    //这里的 active == 0，说明所有的任务都执行失败，那么这里是 for 循环出口
                    else if (active == 0)
                        break;
                    // 这里也是 else if。这里说的是，没有任务了，但是设置了超时时间，这里检测是否超时
                    else if (timed) {
                        // 调用带等待的 poll 方法来获取异步结果
                        f = ecs.poll(nanos, TimeUnit.NANOSECONDS);
                        //如果已经超时，抛出 TimeoutException 异常，这整个方法就结束了
                        if (f == null)
                            throw new TimeoutException();
                        nanos = deadline - System.nanoTime();
                    }
                    // 这里是 else。说明，没有任务需要提交，但是池中的任务没有完成，还没有超时(如果设置了超时)
                    // take() 方法会阻塞，直到有元素返回，说明有任务结束了
                    else
                        f = ecs.take();
                }

                /*
                 * 我感觉上面这一段并不是很好理解，这里简单说下。
                 * 1. 首先，这在一个 for 循环中，我们设想每一个任务都没那么快结束，
                 *     那么，每一次都会进到第一个分支，进行提交任务，直到将所有的任务都提交了
                 * 2. 任务都提交完成后，如果设置了超时，那么 for 循环其实进入了“一直检测是否超时”
                       这件事情上
                 * 3. 如果没有设置超时机制，那么不必要检测超时，那就会阻塞在 ecs.take() 方法上，
                       等待获取第一个执行结果
                 * 4. 如果所有的任务都执行失败，也就是说 future 都返回了，
                       但是 f.get() 抛出异常，那么从 active == 0 分支出去
                         // 当然，这个需要看下面的 if 分支。
                 */

                // 有任务结束了
                if (f != null) {
                    //正在执行的任务数减 1
                    --active;
                    try {
                        // 返回执行结果，如果有异常，都包装成 ExecutionException
                        return f.get();
                    } catch (ExecutionException eex) {
                        ee = eex;
                    } catch (RuntimeException rex) {
                        ee = new ExecutionException(rex);
                    }
                }
            }

            //如果没有异常, 则创建异常并抛出
            if (ee == null)
                ee = new ExecutionException();
            throw ee;

        } finally {
            //方法退出之前，取消其他的任务
            for (int i = 0, size = futures.size(); i < size; i++)
                futures.get(i).cancel(true);
        }
    }

    public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
        throws InterruptedException, ExecutionException {
        try {
            return doInvokeAny(tasks, false, 0);
        } catch (TimeoutException cannotHappen) {
            assert false;
            return null;
        }
    }

    public <T> T invokeAny(Collection<? extends Callable<T>> tasks,
                           long timeout, TimeUnit unit)
        throws InterruptedException, ExecutionException, TimeoutException {
        return doInvokeAny(tasks, true, unit.toNanos(timeout));
    }

    /**
     * // 执行所有的任务，返回任务结果。
     * // 先不要看这个方法，我们先想想，其实我们自己提交任务到线程池，也是想要线程池执行所有的任务
     * // 只不过，我们是每次 submit 一个任务，这里以一个集合作为参数提交
     */
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks)
        throws InterruptedException {
        //任务列表不能为 null
        if (tasks == null)
            throw new NullPointerException();
        //创建异步结果列表
        ArrayList<Future<T>> futures = new ArrayList<Future<T>>(tasks.size());
        //未执行完成
        boolean done = false;
        try {
            //遍历所有要提交的任务
            for (Callable<T> t : tasks) {
                //为每个任务创建 RunnableFuture
                RunnableFuture<T> f = newTaskFor(t);
                //添加到异步结果列表中
                futures.add(f);
                //提交到 Executor 中执行
                execute(f);
            }
            //遍历异步结果列表
            for (int i = 0, size = futures.size(); i < size; i++) {
                Future<T> f = futures.get(i);
                //如果异步任务未完成
                if (!f.isDone()) {
                    try {
                        // 这是一个阻塞方法，直到获取到值，或抛出了异常
                        // 这里有个小细节，其实 get 方法签名上是会抛出 InterruptedException 的
                        // 可是这里没有进行处理，而是抛给外层去了。此异常发生于还没执行完的任务被取消了
                        f.get();
                    } catch (CancellationException ignore) {
                    } catch (ExecutionException ignore) {
                    }
                }
            }
            //标记已经完成
            done = true;
            //返回结果集
            return futures;
        } finally {
            //如果未完成
            // 什么时候未完成呢, InterruptedException 异常没有捕获
            if (!done)
                //取消任务的执行
                for (int i = 0, size = futures.size(); i < size; i++)
                    futures.get(i).cancel(true);
        }
    }

    /**
     * 带超时的 invokeAll
     */
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks,
                                         long timeout, TimeUnit unit)
        throws InterruptedException {
        if (tasks == null)
            throw new NullPointerException();
        //剩余时间
        long nanos = unit.toNanos(timeout);
        ArrayList<Future<T>> futures = new ArrayList<Future<T>>(tasks.size());
        boolean done = false;
        try {
            //先记录异步结果到结果列表中
            for (Callable<T> t : tasks)
                futures.add(newTaskFor(t));

            //计算 deadline
            final long deadline = System.nanoTime() + nanos;
            final int size = futures.size();

            // Interleave time checks and calls to execute in case
            // executor doesn't have any/much parallelism.
            // 每提交一个任务，检测一次是否超时
            for (int i = 0; i < size; i++) {
                execute((Runnable)futures.get(i));
                nanos = deadline - System.nanoTime();
                // 如果有超时, 直接返回结果
                if (nanos <= 0L)
                    return futures;
            }

            //下面代表还没有超时

            for (int i = 0; i < size; i++) {
                Future<T> f = futures.get(i);
                //任务未完成
                if (!f.isDone()) {
                    //有超时也直接返回结果
                    if (nanos <= 0L)
                        return futures;
                    try {
                        // 调用带超时的 get 方法，这里的参数 nanos 是剩余的时间，
                        // 因为上面其实已经用掉了一些时间了
                        f.get(nanos, TimeUnit.NANOSECONDS);
                    } catch (CancellationException ignore) {
                    } catch (ExecutionException ignore) {
                    } catch (TimeoutException toe) {
                        //有超时也返回
                        return futures;
                    }
                    //重新计算剩余时间
                    nanos = deadline - System.nanoTime();
                }
            }
            //记录全部已经完成
            done = true;
            //返回结果
            return futures;
        } finally {
            //超时, 或异常导致未完成所有任务, 则取消剩下任务
            if (!done)
                for (int i = 0, size = futures.size(); i < size; i++)
                    futures.get(i).cancel(true);
        }
    }

}
