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
 * Written by Doug Lea, Bill Scherer, and Michael Scott with
 * assistance from members of JCP JSR-166 Expert Group and released to
 * the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */

package java.util.concurrent;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.*;
import java.util.Spliterator;
import java.util.Spliterators;

/**
 * A {@linkplain BlockingQueue blocking queue} in which each insert
 * operation must wait for a corresponding remove operation by another
 * thread, and vice versa.  A synchronous queue does not have any
 * internal capacity, not even a capacity of one.  You cannot
 * {@code peek} at a synchronous queue because an element is only
 * present when you try to remove it; you cannot insert an element
 * (using any method) unless another thread is trying to remove it;
 * you cannot iterate as there is nothing to iterate.  The
 * <em>head</em> of the queue is the element that the first queued
 * inserting thread is trying to add to the queue; if there is no such
 * queued thread then no element is available for removal and
 * {@code poll()} will return {@code null}.  For purposes of other
 * {@code Collection} methods (for example {@code contains}), a
 * {@code SynchronousQueue} acts as an empty collection.  This queue
 * does not permit {@code null} elements.
 *
 * <p>Synchronous queues are similar to rendezvous channels used in
 * CSP and Ada. They are well suited for handoff designs, in which an
 * object running in one thread must sync up with an object running
 * in another thread in order to hand it some information, event, or
 * task.
 *
 * <p>This class supports an optional fairness policy for ordering
 * waiting producer and consumer threads.  By default, this ordering
 * is not guaranteed. However, a queue constructed with fairness set
 * to {@code true} grants threads access in FIFO order.
 *
 * <p>This class and its iterator implement all of the
 * <em>optional</em> methods of the {@link Collection} and {@link
 * Iterator} interfaces.
 *
 * <p>This class is a member of the
 * <a href="{@docRoot}/../technotes/guides/collections/index.html">
 * Java Collections Framework</a>.
 *
 * SynchronousQueue是java里的无缓冲队列，但是它会给线程进行缓存排队, 用于在两个线程之间直接移交元素, 一般用于生产、消费的速度大致相当的情况.
 *
 * SynchronousQueue有两种实现方式，一种是公平（队列）方式，一种是非公平（栈）方式，队列与栈都是通过链表来实现的
 *  - 栈是同模式入栈一个节点, 不同模式匹配则先入栈一个节点, 再出栈两个匹配的节点, 后进先出
 *  - 队列是同模式在列表尾部入队一个节点, 不同模式匹配则在队头出队一个节点, 先进先出
 *
 * @since 1.5
 * @author Doug Lea and Bill Scherer and Michael Scott
 * @param <E> the type of elements held in this collection
 */
public class SynchronousQueue<E> extends AbstractQueue<E>
    implements BlockingQueue<E>, java.io.Serializable {
    private static final long serialVersionUID = -3223113410248163686L;

    /*
     * This class implements extensions of the dual stack and dual
     * queue algorithms described in "Nonblocking Concurrent Objects
     * with Condition Synchronization", by W. N. Scherer III and
     * M. L. Scott.  18th Annual Conf. on Distributed Computing,
     * Oct. 2004 (see also
     * http://www.cs.rochester.edu/u/scott/synchronization/pseudocode/duals.html).
     * The (Lifo) stack is used for non-fair mode, and the (Fifo)
     * queue for fair mode. The performance of the two is generally
     * similar. Fifo usually supports higher throughput under
     * contention but Lifo maintains higher thread locality in common
     * applications.
     *
     * A dual queue (and similarly stack) is one that at any given
     * time either holds "data" -- items provided by put operations,
     * or "requests" -- slots representing take operations, or is
     * empty. A call to "fulfill" (i.e., a call requesting an item
     * from a queue holding data or vice versa) dequeues a
     * complementary node.  The most interesting feature of these
     * queues is that any operation can figure out which mode the
     * queue is in, and act accordingly without needing locks.
     *
     * Both the queue and stack extend abstract class Transferer
     * defining the single method transfer that does a put or a
     * take. These are unified into a single method because in dual
     * data structures, the put and take operations are symmetrical,
     * so nearly all code can be combined. The resulting transfer
     * methods are on the long side, but are easier to follow than
     * they would be if broken up into nearly-duplicated parts.
     *
     * The queue and stack data structures share many conceptual
     * similarities but very few concrete details. For simplicity,
     * they are kept distinct so that they can later evolve
     * separately.
     *
     * The algorithms here differ from the versions in the above paper
     * in extending them for use in synchronous queues, as well as
     * dealing with cancellation. The main differences include:
     *
     *  1. The original algorithms used bit-marked pointers, but
     *     the ones here use mode bits in nodes, leading to a number
     *     of further adaptations.
     *  2. SynchronousQueues must block threads waiting to become
     *     fulfilled.
     *  3. Support for cancellation via timeout and interrupts,
     *     including cleaning out cancelled nodes/threads
     *     from lists to avoid garbage retention and memory depletion.
     *
     * Blocking is mainly accomplished using LockSupport park/unpark,
     * except that nodes that appear to be the next ones to become
     * fulfilled first spin a bit (on multiprocessors only). On very
     * busy synchronous queues, spinning can dramatically improve
     * throughput. And on less busy ones, the amount of spinning is
     * small enough not to be noticeable.
     *
     * Cleaning is done in different ways in queues vs stacks.  For
     * queues, we can almost always remove a node immediately in O(1)
     * time (modulo retries for consistency checks) when it is
     * cancelled. But if it may be pinned as the current tail, it must
     * wait until some subsequent cancellation. For stacks, we need a
     * potentially O(n) traversal to be sure that we can remove the
     * node, but this can run concurrently with other threads
     * accessing the stack.
     *
     * While garbage collection takes care of most node reclamation
     * issues that otherwise complicate nonblocking algorithms, care
     * is taken to "forget" references to data, other nodes, and
     * threads that might be held on to long-term by blocked
     * threads. In cases where setting to null would otherwise
     * conflict with main algorithms, this is done by changing a
     * node's link to now point to the node itself. This doesn't arise
     * much for Stack nodes (because blocked threads do not hang on to
     * old head pointers), but references in Queue nodes must be
     * aggressively forgotten to avoid reachability of everything any
     * node has ever referred to since arrival.
     */

    /**
     * Transferer 抽象类，主要定义了一个transfer方法用来传输元素
     * Shared internal API for dual stacks and queues.
     */
    abstract static class Transferer<E> {
        /**
         * Performs a put or take.
         *
         * @param e if non-null, the item to be handed to a consumer;
         *          if null, requests that transfer return an item
         *          offered by producer.
         * @param timed if this operation should timeout
         * @param nanos the timeout, in nanoseconds
         * @return if non-null, the item provided or received; if null,
         *         the operation failed due to timeout or interrupt --
         *         the caller can distinguish which of these occurred
         *         by checking Thread.interrupted.
         */
        abstract E transfer(E e, boolean timed, long nanos);
    }

    // CPU的数量
    /** The number of CPUs, for spin control */
    static final int NCPUS = Runtime.getRuntime().availableProcessors();

    /**
     * 有超时的情况自旋多少次，当CPU数量小于2的时候不自旋
     * The number of times to spin before blocking in timed waits.
     * The value is empirically derived -- it works well across a
     * variety of processors and OSes. Empirically, the best value
     * seems not to vary with number of CPUs (beyond 2) so is just
     * a constant.
     */
    static final int maxTimedSpins = (NCPUS < 2) ? 0 : 32;

    /**
     * 没有超时的情况自旋多少次
     * The number of times to spin before blocking in untimed waits.
     * This is greater than timed value because untimed waits spin
     * faster since they don't need to check times on each spin.
     */
    static final int maxUntimedSpins = maxTimedSpins * 16;

    /**
     * 针对有超时的情况，自旋了多少次后，如果剩余时间大于1000纳秒就使用带时间的LockSupport.parkNanos()这个方法
     * The number of nanoseconds for which it is faster to spin
     * rather than to use timed park. A rough estimate suffices.
     */
    static final long spinForTimeoutThreshold = 1000L;

    //以栈方式实现的 Transferer
    /** Dual stack */
    static final class TransferStack<E> extends Transferer<E> {
        /*
         * This extends Scherer-Scott dual stack algorithm, differing,
         * among other ways, by using "covering" nodes rather than
         * bit-marked pointers: Fulfilling operations push on marker
         * nodes (with FULFILLING bit set in mode) to reserve a spot
         * to match a waiting node.
         */

        //栈中节点的几种类型：
        //1. 消费者（请求数据的）
        /* Modes for SNodes, ORed together in node fields */
        /** Node represents an unfulfilled consumer */
        static final int REQUEST    = 0;
        // 2. 生产者（提供数据的）
        /** Node represents an unfulfilled producer */
        static final int DATA       = 1;
        // 3. 二者正在撮合中
        /** Node is fulfilling another unfulfilled DATA or REQUEST */
        static final int FULFILLING = 2;

        //表示是否包含FULFILLING标记。
        /** Returns true if m has fulfilling bit set. */
        static boolean isFulfilling(int m) { return (m & FULFILLING) != 0; }

        // 栈中的节点
        /** Node class for TransferStacks. */
        static final class SNode {
            // 下一个节点
            volatile SNode next;        // next node in stack
            // 匹配者
            volatile SNode match;       // the node matched to this
            // 等待着的线程
            volatile Thread waiter;     // to control park/unpark
            // 元素
            Object item;                // data; or null for REQUESTs
            // 模式，也就是节点的类型，是消费者，是生产者，还是正在撮合中
            int mode;
            // Note: item and mode fields don't need to be volatile
            // since they are always written before, and read after,
            // other volatile/atomic operations.

            SNode(Object item) {
                this.item = item;
            }

            /**
             * 替换 next
             */
            boolean casNext(SNode cmp, SNode val) {
                return cmp == next &&
                    UNSAFE.compareAndSwapObject(this, nextOffset, cmp, val);
            }

            /**
             * 尝试撮合
             * Tries to match node s to this node, if so, waking up thread.
             * Fulfillers call tryMatch to identify their waiters.
             * Waiters block until they have been matched.
             *
             * @param s the node to match
             * @return true if successfully matched to s
             */
            boolean tryMatch(SNode s) {
                // 本结点的match域为null并且比较并替换match域成功
                if (match == null &&
                    UNSAFE.compareAndSwapObject(this, matchOffset, null, s)) {
                    // 获取本节点的等待线程
                    Thread w = waiter;
                    // 存在等待的线程
                    if (w != null) {    // waiters need at most one unpark
                        // 将本结点的等待线程重新置为null
                        waiter = null;
                        // unpark等待线程
                        LockSupport.unpark(w);
                    }
                    return true;
                }
                // 如果match不为null或者CAS设置失败，则比较match域是否等于s结点，若相等，则表示已经完成匹配，匹配成功
                return match == s;
            }

            /**
             * 取消节点, 将 match 设置成当前对象
             * Tries to cancel a wait by matching node to itself.
             */
            void tryCancel() {
                UNSAFE.compareAndSwapObject(this, matchOffset, null, this);
            }

            /**
             * 判断是否取消
             */
            boolean isCancelled() {
                return match == this;
            }

            // Unsafe mechanics
            private static final sun.misc.Unsafe UNSAFE;
            private static final long matchOffset;
            private static final long nextOffset;

            static {
                try {
                    UNSAFE = sun.misc.Unsafe.getUnsafe();
                    Class<?> k = SNode.class;
                    matchOffset = UNSAFE.objectFieldOffset
                        (k.getDeclaredField("match"));
                    nextOffset = UNSAFE.objectFieldOffset
                        (k.getDeclaredField("next"));
                } catch (Exception e) {
                    throw new Error(e);
                }
            }
        }

        // 栈的头节点
        /** The head (top) of the stack */
        volatile SNode head;

        /**
         * 替换头点节
         * @param h 头节点
         * @param nh 下一下头节点
         */
        boolean casHead(SNode h, SNode nh) {
            return h == head &&
                UNSAFE.compareAndSwapObject(this, headOffset, h, nh);
        }

        /**
         * 创建 SNode 节点
         * Creates or resets fields of a node. Called only from transfer
         * where the node to push on stack is lazily created and
         * reused when possible to help reduce intervals between reads
         * and CASes of head and to avoid surges of garbage when CASes
         * to push nodes fail due to contention.
         */
        static SNode snode(SNode s, Object e, SNode next, int mode) {
            if (s == null) s = new SNode(e);
            s.mode = mode;
            s.next = next;
            return s;
        }

        /**
         * 返回的是获取的数据
         * Puts or takes an item.
         */
        @SuppressWarnings("unchecked")
        E transfer(E e, boolean timed, long nanos) {
            /*
             * Basic algorithm is to loop trying one of three actions:
             *
             * 1. If apparently empty or already containing nodes of same
             *    mode, try to push node on stack and wait for a match,
             *    returning it, or null if cancelled.
             *
             * 2. If apparently containing node of complementary mode,
             *    try to push a fulfilling node on to stack, match
             *    with corresponding waiting node, pop both from
             *    stack, and return matched item. The matching or
             *    unlinking might not actually be necessary because of
             *    other threads performing action 3:
             *
             * 3. If top of stack already holds another fulfilling node,
             *    help it out by doing its match and/or pop
             *    operations, and then continue. The code for helping
             *    is essentially the same as for fulfilling, except
             *    that it doesn't return the item.
             */

            SNode s = null; // constructed/reused as needed
            // 根据 e 是否为 null 决定是生产者还是消费者
            int mode = (e == null) ? REQUEST : DATA;

            // 自旋+CAS
            for (;;) {
                // 获栈顶元素
                SNode h = head;
                // 栈顶没有元素，或者栈顶元素跟当前元素是一个模式的
                // 也就是都是生产者节点或者都是消费者节点
                if (h == null || h.mode == mode) {  // empty or same-mode
                    // 如果有超时而且已到期, 也就是不等待
                    if (timed && nanos <= 0) {      // can't wait
                        // 如果头节点不为空且是取消状态
                        if (h != null && h.isCancelled())
                            // 就把头节点弹出，并进入下一次循环
                            casHead(h, h.next);     // pop cancelled node
                        else
                            //如果头节点是 null 或者没有被取消, 不用处理, 直接返回
                            return null;
                        //生成一个SNode结点, 入栈（因为是模式相同的，所以只能入栈）
                    } else if (casHead(h, s = snode(s, e, h, mode))) {
                        // 调用awaitFulfill()方法
                        // 自旋+阻塞 当前入栈的线程并等待被匹配到
                        SNode m = awaitFulfill(s, timed, nanos);
                        //线程中断和超时会设置取消
                        // 如果m等于s，说明取消了，那么就把它清除掉，并返回null
                        if (m == s) {               // wait was cancelled
                            clean(s);
                            // 被取消了返回null
                            return null;
                        }
                        // 到这里说明匹配到元素了
                        // 因为从awaitFulfill()里面出来要不被取消了要不就匹配到了

                        // 如果头节点不为空，并且头节点的下一个节点是s
                        // 就把头节点换成s的下一个节点
                        // 也就是把h和s都弹出了
                        // 也就是把栈顶两个元素都弹出了
                        if ((h = head) != null && h.next == s)
                            casHead(h, s.next);     // help s's fulfiller
                        // 根据当前节点的模式判断返回m还是s中的值
                        return (E) ((mode == REQUEST) ? m.item : s.item);
                    }
                    // 没有 FULFILLING 标记，尝试匹配
                } else if (!isFulfilling(h.mode)) { // try to fulfill
                    // 到这里说明头节点和当前节点模式不一样
                    // 如果头节点不是正在撮合中

                    // 如果头节点已经取消了，就把它弹出栈
                    if (h.isCancelled())            // already cancelled
                        casHead(h, h.next);         // pop and retry
                    // 生成一个SNode结点；将原来的head头结点设置为该结点的next结点；将head头结点设置为该结点
                    else if (casHead(h, s=snode(s, e, h, FULFILLING|mode))) {
                        // 头节点没有在撮合中，就让当前节点先入队，再让他们尝试匹配
                        // 且s成为了新的头节点，它的状态是正在撮合中
                        for (;;) { // loop until matched or waiters disappear
                            // 保存s的next结点
                            SNode m = s.next;       // m is s's match
                            // 如果m为null，说明除了s节点外的节点都被其它线程先一步撮合掉了, 也就是只剩下 s 这个节点了
                            if (m == null) {        // all waiters are gone
                                // 就清空栈并跳出内部循环
                                casHead(s, null);   // pop fulfill node
                                //s 置 null
                                s = null;           // use new node next time
                                //断开当前循环, 并且继续外层循环生新来尝试
                                break;              // restart main loop
                            }
                            SNode mn = m.next;
                            // 如果m和s尝试撮合成功，就弹出栈顶的两个元素m和s
                            if (m.tryMatch(s)) {
                                casHead(s, mn);     // pop both s and m
                                // 返回撮合结果
                                return (E) ((mode == REQUEST) ? m.item : s.item);
                            } else                  // lost match
                                // 尝试撮合失败，说明m已经先一步被其它线程撮合了
                                // m 只有一种情况会撮合失败, m 被取消了, m 的 match 节点被设置成自己
                                // 就协助清除它
                                // 然后下一次循环再次尝试
                                s.casNext(m, mn);   // help unlink
                        }
                    }
                } else {                            // help a fulfiller
                    // 到这里说明当前节点和头节点模式不一样
                    // 且头节点是正在撮合中

                    SNode m = h.next;               // m is h's match
                    if (m == null)                  // waiter is gone
                        //如果m为null，说明除了s节点外的节点都被其它线程先一步撮合掉了, 也就是只剩下 s 这个节点了
                        casHead(h, null);           // pop fulfilling node
                    else {
                        SNode mn = m.next;
                        // 协助匹配，如果m和s尝试撮合成功，就弹出栈顶的两个元素m和s
                        if (m.tryMatch(h))          // help match
                            // 将栈顶的两个元素弹出后，再让s重新入栈
                            casHead(h, mn);         // pop both h and m
                        else                        // lost match
                            // 尝试撮合失败，说明m已经先一步被其它线程撮合了
                            // m 只有一种情况会撮合失败, m 被取消了, m 的 match 节点被设置成自己
                            // 就协助清除它
                            //然后下一次循环再次尝试
                            h.casNext(m, mn);       // help unlink
                    }
                }
            }
        }

        /**
         * 自旋或阻塞, 直接被匹配
         * Spins/blocks until node s is matched by a fulfill operation.
         *
         * @param s the waiting node
         * @param timed true if timed wait
         * @param nanos timeout value
         * @return matched node, or s if cancelled
         */
        SNode awaitFulfill(SNode s, boolean timed, long nanos) {
            /*
             * When a node/thread is about to block, it sets its waiter
             * field and then rechecks state at least one more time
             * before actually parking, thus covering race vs
             * fulfiller noticing that waiter is non-null so should be
             * woken.
             *
             * When invoked by nodes that appear at the point of call
             * to be at the head of the stack, calls to park are
             * preceded by spins to avoid blocking when producers and
             * consumers are arriving very close in time.  This can
             * happen enough to bother only on multiprocessors.
             *
             * The order of checks for returning out of main loop
             * reflects fact that interrupts have precedence over
             * normal returns, which have precedence over
             * timeouts. (So, on timeout, one last check for match is
             * done before giving up.) Except that calls from untimed
             * SynchronousQueue.{poll/offer} don't check interrupts
             * and don't wait at all, so are trapped in transfer
             * method rather than calling awaitFulfill.
             */
            //如果有超时时间, 则计算出超时的 deadline
            final long deadline = timed ? System.nanoTime() + nanos : 0L;
            //获取当前线程
            Thread w = Thread.currentThread();
            // 根据 s 确定空旋的次数
            int spins = (shouldSpin(s) ?
                         (timed ? maxTimedSpins : maxUntimedSpins) : 0);
            //自旋
            for (;;) {
                // 当前线程被中断
                if (w.isInterrupted())
                    // 取消s结点
                    s.tryCancel();
                // 获取s结点的match域
                SNode m = s.match;
                // m不为null，存在匹配结点
                if (m != null)
                    // 返回m结点
                    return m;
                // 设置了 timed
                if (timed) {
                    // 确定继续等待的时间
                    nanos = deadline - System.nanoTime();
                    // 继续等待的时间小于等于0，等待超时
                    if (nanos <= 0L) {
                        // 取消s结点
                        s.tryCancel();
                        // 跳过后面的部分，继续
                        continue;
                    }
                }
                // 空旋等待的时间大于0
                if (spins > 0)
                    // 确实是否还需要继续空旋等待? 自旋次数 - 1, 否则直接置 0
                    spins = shouldSpin(s) ? (spins-1) : 0;
                // 等待线程为null
                else if (s.waiter == null)
                    // 设置waiter线程为当前线程
                    s.waiter = w; // establish waiter so can park next iter
                // 没有设置timed标识
                else if (!timed)
                    // 禁用当前线程并设置了阻塞者
                    LockSupport.park(this);
                // 继续等待的时间大于阈值
                else if (nanos > spinForTimeoutThreshold)
                    // 禁用当前线程，最多等待指定的等待时间，除非许可可用
                    LockSupport.parkNanos(this, nanos);
            }
        }

        /**
         * 此函数表示是当前结点所包含的线程（当前线程）进行空旋等待，有如下情况需要进行空旋等待:
         *  ① 当前结点为头结点
         *  ② 头结点为null
         *  ③ 头结点正在匹配中
         *
         * 觉得很快就能匹配到, 所以先自旋等一下
         *
         * Returns true if node s is at head or there is an active
         * fulfiller.
         */
        boolean shouldSpin(SNode s) {
            // 获取头结点
            SNode h = head;
            // 当前结点 s 为头结点
            // 头结点为 null
            // 头结点正在匹配中
            return (h == s || h == null || isFulfilling(h.mode));
        }

        /**
         * 清除 SNode 节点
         *  -  这个方法是可以处理已经被其他线程删除了 SNode 的情况的, 可以看下面英文注释了解情况
         * Unlinks s from the stack.
         */
        void clean(SNode s) {
            //清空引用, 回收
            s.item = null;   // forget item
            //清空线程
            s.waiter = null; // forget thread

            /*
             * At worst we may need to traverse entire stack to unlink
             * s. If there are multiple concurrent calls to clean, we
             * might not see s if another thread has already removed
             * it. But we can stop when we see any node known to
             * follow s. We use s.next unless it too is cancelled, in
             * which case we try the node one past. We don't check any
             * further because we don't want to doubly traverse just to
             * find sentinel.
             */

            //获取下一个节点
            SNode past = s.next;
            // next域不为null并且next域被取消, 再取下个, 也就是 past 有可能是 s 的子节点或孙节点
            //最坏的情况是有可能遍历完整个链表的, 当 past 已经不在链表中的时候, 下面会全链表遍历
            if (past != null && past.isCancelled())
                past = past.next;

            //只有保证清空了 past 前面的所有取消节点才算清除了 s 节点

            // Absorb cancelled nodes at head
            SNode p;
            // 从栈顶头结点开始清除直到 past 结点（不包括)或者遇到非取消节点
            // 如果没有遍历到 past , 就代表没将 s 节点清除, 所以需要下面一个 while 保证
            while ((p = head) != null && p != past && p.isCancelled())
                // 比较并替换 head 域（弹出取消的结点）
                casHead(p, p.next);

            // p != past 表示上一个 while 没有遍历到 past , 也就是没有清除 s
            // p 是 head, 从 p 继续往下遍历, 直到 past
            // Unsplice embedded nodes
            while (p != null && p != past) {
                // 获取p的next域
                SNode n = p.next;
                // n不为null并且n被取消
                if (n != null && n.isCancelled())
                    // 比较并替换next域
                    p.casNext(n, n.next);
                else
                    //跳过节点
                    p = n;
            }

            //todo wolfleong 为什么不将 s.next 置 null 呢, s.next 或 s.next.next 也就是 past 是有可能继续在链表中

            //并发情况下, 别的线程清除了 s,  然后 s 的线程进来 clean 方法
            //1. 如果把 s.next 设置为 null, s 的线程进来就肯定要全遍历链表, 因为 past 为 null, 遍历完整个链表都找不到 past
            //2. 如果不清除的话, 分两种情况
            //   1) 如果 past 不在链表, 全遍历链表
            //   2) 如果 past 在链表的话, 只需要遍历 past 前面的一部分就可以了

        }

        // Unsafe mechanics
        private static final sun.misc.Unsafe UNSAFE;
        private static final long headOffset;
        static {
            try {
                UNSAFE = sun.misc.Unsafe.getUnsafe();
                Class<?> k = TransferStack.class;
                headOffset = UNSAFE.objectFieldOffset
                    (k.getDeclaredField("head"));
            } catch (Exception e) {
                throw new Error(e);
            }
        }
    }

    // 以队列方式实现的Transferer
    /** Dual Queue */
    static final class TransferQueue<E> extends Transferer<E> {
        /*
         * This extends Scherer-Scott dual queue algorithm, differing,
         * among other ways, by using modes within nodes rather than
         * marked pointers. The algorithm is a little simpler than
         * that for stacks because fulfillers do not need explicit
         * nodes, and matching is done by CAS'ing QNode.item field
         * from non-null to null (for put) or vice versa (for take).
         */

        // 队列中的节点
        /** Node class for TransferQueue. */
        static final class QNode {
            // 下一个节点
            volatile QNode next;          // next node in queue
            // 存储的元素
            volatile Object item;         // CAS'ed to or from null
            // 等待着的线程
            volatile Thread waiter;       // to control park/unpark
            // 是否是数据节点
            final boolean isData;

            QNode(Object item, boolean isData) {
                this.item = item;
                this.isData = isData;
            }

            /**
             *  比较并替换next域
             */
            boolean casNext(QNode cmp, QNode val) {
                return next == cmp &&
                    UNSAFE.compareAndSwapObject(this, nextOffset, cmp, val);
            }

            /**
             * 比较并替换item域
             */
            boolean casItem(Object cmp, Object val) {
                return item == cmp &&
                    UNSAFE.compareAndSwapObject(this, itemOffset, cmp, val);
            }

            /**
             * 取消本结点，将item域设置为自身
             * Tries to cancel by CAS'ing ref to this as item.
             */
            void tryCancel(Object cmp) {
                UNSAFE.compareAndSwapObject(this, itemOffset, cmp, this);
            }

            /**
             * 是否被取消
             */
            boolean isCancelled() {
                //item域是否等于自身
                return item == this;
            }

            /**
             * 是否不在队列中
             * Returns true if this node is known to be off the queue
             * because its next pointer has been forgotten due to
             * an advanceHead operation.
             */
            boolean isOffList() {
                //next与是否等于自身
                return next == this;
            }

            // Unsafe mechanics
            private static final sun.misc.Unsafe UNSAFE;
            private static final long itemOffset;
            private static final long nextOffset;

            static {
                try {
                    UNSAFE = sun.misc.Unsafe.getUnsafe();
                    Class<?> k = QNode.class;
                    itemOffset = UNSAFE.objectFieldOffset
                        (k.getDeclaredField("item"));
                    nextOffset = UNSAFE.objectFieldOffset
                        (k.getDeclaredField("next"));
                } catch (Exception e) {
                    throw new Error(e);
                }
            }
        }

        // 队列的头节点
        /** Head of queue */
        transient volatile QNode head;
        // 队列的尾节点
        /** Tail of queue */
        transient volatile QNode tail;
        /**
         *  对应 中断或超时的 前继节点,这个节点存在的意义是标记, 它的下个节点要删除
         *  何时使用:
         *       当你要删除 节点 node, 若节点 node 是队列的末尾, 则开始用这个节点,
         *  为什么呢？
         *       大家知道 删除一个节点 直接 A.CASNext(B, B.next) 就可以,但是当  节点 B 是整个队列中的末尾元素时,
         *       一个线程删除节点B, 一个线程在节点B之后插入节点 这样操作容易致使插入的节点丢失, 这个cleanMe很像
         *       ConcurrentSkipListMap 中的 删除添加的 marker 节点, 他们都是起着相同的作用
         *
         * Reference to a cancelled node that might not yet have been
         * unlinked from queue because it was the last inserted node
         * when it was cancelled.
         */
        transient volatile QNode cleanMe;

        /**
         * 该构造函数用于初始化一个队列，并且初始化了一个哨兵结点，头结点与尾节点均指向该哨兵结点。
         */
        TransferQueue() {
            // 初始化一个哨兵结点
            QNode h = new QNode(null, false); // initialize to dummy node.
            // 设置头结点
            head = h;
            // 设置尾结点
            tail = h;
        }

        /**
         * 重新设置头节点
         * Tries to cas nh as new head; if successful, unlink
         * old head's next node to avoid garbage retention.
         */
        void advanceHead(QNode h, QNode nh) {
            if (h == head &&
                UNSAFE.compareAndSwapObject(this, headOffset, h, nh))
                //如果头节点退出成功, 则将 next 设置成 this
                h.next = h; // forget old next
        }

        /**
         * 重新设置尾节点
         * Tries to cas nt as new tail.
         */
        void advanceTail(QNode t, QNode nt) {
            if (tail == t)
                UNSAFE.compareAndSwapObject(this, tailOffset, t, nt);
        }

        /**
         * Tries to CAS cleanMe slot.
         */
        boolean casCleanMe(QNode cmp, QNode val) {
            return cleanMe == cmp &&
                UNSAFE.compareAndSwapObject(this, cleanMeOffset, cmp, val);
        }

        /**
         * Puts or takes an item.
         */
        @SuppressWarnings("unchecked")
        E transfer(E e, boolean timed, long nanos) {
            /* Basic algorithm is to loop trying to take either of
             * two actions:
             *
             * 1. If queue apparently empty or holding same-mode nodes,
             *    try to add node to queue of waiters, wait to be
             *    fulfilled (or cancelled) and return matching item.
             *
             * 2. If queue apparently contains waiting items, and this
             *    call is of complementary mode, try to fulfill by CAS'ing
             *    item field of waiting node and dequeuing it, and then
             *    returning matching item.
             *
             * In each case, along the way, check for and try to help
             * advance head and tail on behalf of other stalled/slow
             * threads.
             *
             * The loop starts off with a null check guarding against
             * seeing uninitialized head or tail values. This never
             * happens in current SynchronousQueue, but could if
             * callers held non-volatile/final ref to the
             * transferer. The check is here anyway because it places
             * null checks at top of loop, which is usually faster
             * than having them implicitly interspersed.
             */

            //QNode 如果被取消, 会将设置 item = this, QNode 如果被移出队列, 则设置 next = this

            QNode s = null; // constructed/reused as needed
            // 确定此次转移的类型（put or take）, e 不为 null 表示数据节点 put
            boolean isData = (e != null);

            // 无限循环，确保操作成功
            for (;;) {
                // 获取尾结点
                QNode t = tail;
                // 获取头结点
                QNode h = head;
                // 如果并发导致未"来得及"初始化, 构造器执行的时候指令重排序会出现这情况
                if (t == null || h == null)         // saw uninitialized value
                    //重来
                    continue;                       // spin

                // 头结点与尾结点相等或者尾结点的模式与当前结点模式相同
                if (h == t || t.isData == isData) { // empty or same-mode
                    // 获取尾结点的next域
                    QNode tn = t.next;
                    // 如果 t 和 tail 不一样，说明，tail 被其他的线程改了，重来
                    if (t != tail)                  // inconsistent read
                        continue;
                    // tn不为null，有其他线程添加了tn结点
                    if (tn != null) {               // lagging tail
                        // 更新的尾结点为 tn
                        advanceTail(t, tn);
                        // 重来
                        continue;
                    }
                    // 设置了timed并且等待时间小于等于0，表示不能等待，需要立即操作
                    if (timed && nanos <= 0)        // can't wait
                        // 返回null
                        return null;
                    // s为null
                    if (s == null)
                        // 新生一个结点并赋值给s
                        s = new QNode(e, isData);
                    // 设置t结点的next域不成功
                    if (!t.casNext(null, s))        // failed to link in
                        // 跳过后面的部分，继续
                        continue;

                    // 上面成功设置 tail => s
                    // 设置更新 s 为新的尾结点
                    advanceTail(t, s);              // swing tail and wait
                    // 空旋或者阻塞直到s结点被匹配
                    Object x = awaitFulfill(s, e, timed, nanos);
                    // x 与 s 相等，表示已经取消
                    if (x == s) {                   // wait was cancelled
                        // 清除 s 节点
                        clean(t, s);
                        // 返回null
                        return null;
                    }

                    // 能下来这里, 表明 s 已经被匹配成功了, 也就当前这个线程在一定的自旋或挂起之后再出来的
                    // s 能匹配成功, 也表明 s 肯定是当前链表中的 head 或 在 head.next 中

                    // s 结点还没离开队列 todo wolfleong 感觉没有这个方法也可能工作, 这个方法的意义在那里
                    if (!s.isOffList()) {           // not already unlinked
                        // 这个 t 肯定不是 tail 了, 但 t 肯定是 s 的前一个节点, t.next 肯定是 s
                        // 如果 s 在 head 中, 那么 t 就已经在链表外, 如果 s 在 head.next 中, 那么 t 就肯定在链表的 head 中

                        // 设置新的头结点
                        // 这里有个疑问的是,todo wolfleong 这个 advanceHead 是否多此一举, 因为在前面匹配线程已经有一次 advanceHead 了
                        advanceHead(t, s);          // unlink if head
                        // x不为null, 表示 s 为 take 节点, 也就是 s 的 item 原来为 null
                        if (x != null)              // and forget fields
                            // 设置s结点的item todo wolfleong 这里为什么要改 item = this 呢, 后面都没机会用到, 直接置 null 不是更好吗
                            s.item = s;
                        // 设置s结点的waiter域为null
                        s.waiter = null;
                    }
                    return (x != null) ? (E)x : e;

                } else {                            // complementary-mode
                    // 模式互补

                    // 获取头结点的next域（匹配的结点）
                    QNode m = h.next;               // node to fulfill
                    // t不为尾结点或者m为null或者h不为头结点（不一致）
                    // 当另一线程正在清理 h => c(取消节点) , 然后这线程进来就能获取到 next 的 m 为 null
                    if (t != tail || m == null || h != head)
                        // 跳过后面的部分，继续
                        continue;                   // inconsistent read

                    // 获取m结点的元素域
                    Object x = m.item;
                    // h 是一个没用的空节点,  m 是 h 的下一个节点, m 一旦被匹配成功, 就是变成新的头节点, 也就是空节点
                    // 能进队列排队的都是相同模式的, 不是 take 就是 put , 所以 m 没被匹配之前都是跟 tail 是同一模式
                    // 可以这么理解, 队尾判断, 队头匹配
                    if (isData == (x != null) ||    // m already fulfilled //m 结点的模式与当前的相同, 表示它被别的线程匹配走了, 也有可能是 m 被取消了
                        x == m ||                   // m cancelled         // m 结点被取消
                        !m.casItem(x, e)) {         // lost CAS            // CAS操作失败, 也就是匹配失败, m 结点可能被取消或被别的线程匹配走了
                        //这个推进 head 是必要的, 因为发现 m 被取消的话, 其他地方没机会主动推 m 为 head
                        //还有一个原因是, 这里尝试一下将 m 推到 head , 可以更快地进入尝试
                        advanceHead(h, m);          // dequeue and retry   // 队列头结点出队列，并重试
                        continue;
                    }

                    // 匹配成功，设置新的头结点
                    advanceHead(h, m);              // successfully fulfilled
                    // unpark m结点对应的等待线程
                    LockSupport.unpark(m.waiter);
                    //如果 x 不为 null 表示 m 是 put , 也就是当前线程是take , 将 x 返回给当前线程
                    //如果 x 为 null, 表示 m 是 take , 当前线程是 put , 则将 e 返回
                    return (x != null) ? (E)x : e;
                }
            }
        }

        /**
         * Spins/blocks until node s is fulfilled.
         *
         * @param s the waiting node
         * @param e the comparison value for checking match
         * @param timed true if timed wait
         * @param nanos timeout value
         * @return matched item, or s if cancelled
         */
        Object awaitFulfill(QNode s, E e, boolean timed, long nanos) {
            /* Same idea as TransferStack.awaitFulfill */
            // 根据timed标识计算截止时间
            final long deadline = timed ? System.nanoTime() + nanos : 0L;
            // 获取当前线程
            Thread w = Thread.currentThread();
            // 计算空旋次数
            int spins = ((head.next == s) ?
                         (timed ? maxTimedSpins : maxUntimedSpins) : 0);
            // 无限循环，确保操作成功
            for (;;) {
                // 当前线程被中断
                if (w.isInterrupted())
                    // 取消
                    s.tryCancel(e);
                // 获取s的 item
                Object x = s.item;
                // 元素不为 e, 则返回
                if (x != e)
                    return x;
                // 设置了timed
                if (timed) {
                    // 计算继续等待的时间
                    nanos = deadline - System.nanoTime();
                    //超时
                    if (nanos <= 0L) {
                        // 取消
                        s.tryCancel(e);
                        // 跳过后面的部分，继续
                        continue;
                    }
                }
                // 空旋次数大于0
                if (spins > 0)
                    // 减少空旋时间
                    --spins;
                // 等待线程为null
                else if (s.waiter == null)
                    // 设置等待线程
                    s.waiter = w;
                // 没有设置timed标识
                else if (!timed)
                    //挂起当前线程
                    LockSupport.park(this);
                // 继续等待的时间大于阈值
                else if (nanos > spinForTimeoutThreshold)
                    //有超时地挂起线程
                    LockSupport.parkNanos(this, nanos);
            }
        }

        /**
         * 对 中断的 或 等待超时的 节点进行清除操作
         * Gets rid of cancelled node s with original predecessor pred.
         */
        void clean(QNode pred, QNode s) {
            // 1. 清除掉 thread 引用
            s.waiter = null; // forget thread
            /*
             * At any given time, exactly one node on list cannot be
             * deleted -- the last inserted node. To accommodate this,
             * if we cannot delete s, we save its predecessor as
             * "cleanMe", deleting the previously saved version
             * first. At least one of node s or the node previously
             * saved can always be deleted, so this always terminates.
             */
            //在任何时候，最后插入的结点不能删除，为了满足这个条件
            //如果不能删除s结点，我们将s结点的前驱设置为cleanMe结点
            //删除之前保存的版本，至少s结点或者之前保存的结点能够被删除
            //所以最后总是会结束

            // 2. 判断 pred.next == s, 下面的 步骤2 可能导致 pred.next = next
            while (pred.next == s) { // Return early if already unlinked
                // 获取头结点
                QNode h = head;
                // 获取头结点的next域
                QNode hn = h.next;   // Absorb cancelled first node as head
                // 3. hn  中断或者超时, 则推进 head 指针, 若这时 h 是 pred 则 loop 中的条件 "pred.next == s" 不满足, 退出 loop
                if (hn != null && hn.isCancelled()) {
                    // 设置新的头结点
                    advanceHead(h, hn);
                    // 跳过后面的部分，继续
                    continue;
                }
                // 获取尾结点，保证对尾结点的读一致性
                QNode t = tail;      // Ensure consistent read for tail
                // 尾结点为头结点，表示队列为空
                if (t == h)
                    // 返回
                    return;
                // 获取尾结点的next域
                QNode tn = t.next;
                // t不为尾结点，不一致，重试
                if (t != tail)
                    continue;
                // tn不为null
                if (tn != null) {
                    // 设置新的尾结点
                    advanceTail(t, tn);
                    // 跳过后面的部分，继续
                    continue;
                }

                //如果 s 是尾节点, 则不能直接移除的, 因为在删除的瞬间有新的节点插入到 s 后面, 就会多删了正确定的节点
                // s 不为尾结点, 可以直接移除 s
                if (s != t) {        // If not tail, try to unsplice
                    //获取 s 的 next
                    QNode sn = s.next;
                    //如果 sn == s , 表示 s 已经踢出链表了, 所以可以直接返回
                    //如果 sn != s , 则将 s 踢出链表
                    if (sn == s || pred.casNext(s, sn))
                        //返回
                        return;
                }

                //下面表示 s 是队尾, 则不需要删除, 但是要清除前面的删除状态节点或添加删除状态标记

                // 获取 cleanMe 结点
                QNode dp = cleanMe;
                // dp 不为 null ，断开前面被取消的结点
                if (dp != null) {    // Try unlinking previous cancelled node
                    // cleanMe 不为 null, 表示 cleanMe 后面有一个删除节点
                    //cleanMe 不为 null, 进行删除删一次的 s节点, 也就是这里的节点d
                    QNode d = dp.next;
                    QNode dn;

                    //1. d == null , 表示上一个 s 节点也就是 d 已经被删除, 后面还没有节点(啥时候出现这情况呢, 刚好别线程清理完只剩下 dp 一个节点的时候 )
                    //2. d == dp , 相当于 next == this,  表示 cleanMe 节点已经被踢出链表
                    //3. !d.isCancelled() 表示上一个 s 节点, 也就是 d 已经被删除了, 但同时也接上新的非取消节点
                    // 上面三种情况, 直接执行 casCleanMe(dp, null) 将 cleanMe 清空

                    //如果 d 不是尾节点且 dn 不为 null 且 dn != d (d 没有被清除), 则将 d 清除掉
                    if (d == null ||               // d is gone or
                        d == dp ||                 // d is off list or
                        !d.isCancelled() ||        // d not cancelled or
                        (d != t &&                 // d not tail and
                         (dn = d.next) != null &&  //   has successor  //这里要判断 next 为 null 的情况也是因为 d 有可能在这种情况刚好是最后一个节点
                         dn != d &&                //   that is on list
                         dp.casNext(d, dn)))       // d unspliced
                        //清掉 cleanMe
                        casCleanMe(dp, null);

                    //如果 dp == pred , 表示上面取消的 s 已经处理了, 否则继续处理
                    if (dp == pred)
                        return;      // s is already saved node
                    //将前一个节点设置成 cleanMe
                } else if (casCleanMe(null, pred))
                    return;          // Postpone cleaning s
            }
        }

        private static final sun.misc.Unsafe UNSAFE;
        private static final long headOffset;
        private static final long tailOffset;
        private static final long cleanMeOffset;
        static {
            try {
                UNSAFE = sun.misc.Unsafe.getUnsafe();
                Class<?> k = TransferQueue.class;
                headOffset = UNSAFE.objectFieldOffset
                    (k.getDeclaredField("head"));
                tailOffset = UNSAFE.objectFieldOffset
                    (k.getDeclaredField("tail"));
                cleanMeOffset = UNSAFE.objectFieldOffset
                    (k.getDeclaredField("cleanMe"));
            } catch (Exception e) {
                throw new Error(e);
            }
        }
    }

    /**
     * 传输器，即两个线程交换元素使用的东西
     * The transferer. Set only in constructor, but cannot be declared
     * as final without further complicating serialization.  Since
     * this is accessed only at most once per public method, there
     * isn't a noticeable performance penalty for using volatile
     * instead of final here.
     */
    private transient volatile Transferer<E> transferer;

    /**
     * Creates a {@code SynchronousQueue} with nonfair access policy.
     */
    public SynchronousQueue() {
        // 默认非公平模式
        this(false);
    }

    /**
     * Creates a {@code SynchronousQueue} with the specified fairness policy.
     *
     * @param fair if true, waiting threads contend in FIFO order for
     *        access; otherwise the order is unspecified.
     */
    public SynchronousQueue(boolean fair) {
        // 如果是公平模式就使用队列，如果是非公平模式就使用栈
        transferer = fair ? new TransferQueue<E>() : new TransferStack<E>();
    }

    /**
     * Adds the specified element to this queue, waiting if necessary for
     * another thread to receive it.
     *
     * @throws InterruptedException {@inheritDoc}
     * @throws NullPointerException {@inheritDoc}
     */
    public void put(E e) throws InterruptedException {
        // 元素不可为空
        if (e == null) throw new NullPointerException();
        // 直接调用传输器的 transfer() 方法
        // 三个参数分别是：传输的元素，是否需要超时，超时的时间
        if (transferer.transfer(e, false, 0) == null) {
            // 如果传输失败，直接让线程中断并抛出中断异常
            Thread.interrupted();
            throw new InterruptedException();
        }
    }

    /**
     * Inserts the specified element into this queue, waiting if necessary
     * up to the specified wait time for another thread to receive it.
     *
     * @return {@code true} if successful, or {@code false} if the
     *         specified waiting time elapses before a consumer appears
     * @throws InterruptedException {@inheritDoc}
     * @throws NullPointerException {@inheritDoc}
     */
    public boolean offer(E e, long timeout, TimeUnit unit)
        throws InterruptedException {
        if (e == null) throw new NullPointerException();
        if (transferer.transfer(e, true, unit.toNanos(timeout)) != null)
            return true;
        if (!Thread.interrupted())
            return false;
        throw new InterruptedException();
    }

    /**
     * Inserts the specified element into this queue, if another thread is
     * waiting to receive it.
     *
     * @param e the element to add
     * @return {@code true} if the element was added to this queue, else
     *         {@code false}
     * @throws NullPointerException if the specified element is null
     */
    public boolean offer(E e) {
        if (e == null) throw new NullPointerException();
        return transferer.transfer(e, true, 0) != null;
    }

    /**
     * Retrieves and removes the head of this queue, waiting if necessary
     * for another thread to insert it.
     *
     * @return the head of this queue
     * @throws InterruptedException {@inheritDoc}
     */
    public E take() throws InterruptedException {
        // 直接调用传输器的transfer()方法
        // 三个参数分别是：null，是否需要超时，超时的时间
        // 第一个参数为 null 表示是消费者，要取元素
        E e = transferer.transfer(null, false, 0);
        // 如果取到了元素就返回
        if (e != null)
            return e;
        //e 为 null 则表示线程中断, 因为没有设置超时
        //否则让线程中断并抛出中断异常
        Thread.interrupted();
        throw new InterruptedException();
    }

    /**
     * Retrieves and removes the head of this queue, waiting
     * if necessary up to the specified wait time, for another thread
     * to insert it.
     *
     * @return the head of this queue, or {@code null} if the
     *         specified waiting time elapses before an element is present
     * @throws InterruptedException {@inheritDoc}
     */
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        E e = transferer.transfer(null, true, unit.toNanos(timeout));
        if (e != null || !Thread.interrupted())
            return e;
        throw new InterruptedException();
    }

    /**
     * Retrieves and removes the head of this queue, if another thread
     * is currently making an element available.
     *
     * @return the head of this queue, or {@code null} if no
     *         element is available
     */
    public E poll() {
        return transferer.transfer(null, true, 0);
    }

    /**
     * Always returns {@code true}.
     * A {@code SynchronousQueue} has no internal capacity.
     *
     * @return {@code true}
     */
    public boolean isEmpty() {
        return true;
    }

    /**
     * Always returns zero.
     * A {@code SynchronousQueue} has no internal capacity.
     *
     * @return zero
     */
    public int size() {
        return 0;
    }

    /**
     * Always returns zero.
     * A {@code SynchronousQueue} has no internal capacity.
     *
     * @return zero
     */
    public int remainingCapacity() {
        return 0;
    }

    /**
     * Does nothing.
     * A {@code SynchronousQueue} has no internal capacity.
     */
    public void clear() {
    }

    /**
     * Always returns {@code false}.
     * A {@code SynchronousQueue} has no internal capacity.
     *
     * @param o the element
     * @return {@code false}
     */
    public boolean contains(Object o) {
        return false;
    }

    /**
     * Always returns {@code false}.
     * A {@code SynchronousQueue} has no internal capacity.
     *
     * @param o the element to remove
     * @return {@code false}
     */
    public boolean remove(Object o) {
        return false;
    }

    /**
     * Returns {@code false} unless the given collection is empty.
     * A {@code SynchronousQueue} has no internal capacity.
     *
     * @param c the collection
     * @return {@code false} unless given collection is empty
     */
    public boolean containsAll(Collection<?> c) {
        return c.isEmpty();
    }

    /**
     * Always returns {@code false}.
     * A {@code SynchronousQueue} has no internal capacity.
     *
     * @param c the collection
     * @return {@code false}
     */
    public boolean removeAll(Collection<?> c) {
        return false;
    }

    /**
     * Always returns {@code false}.
     * A {@code SynchronousQueue} has no internal capacity.
     *
     * @param c the collection
     * @return {@code false}
     */
    public boolean retainAll(Collection<?> c) {
        return false;
    }

    /**
     * Always returns {@code null}.
     * A {@code SynchronousQueue} does not return elements
     * unless actively waited on.
     *
     * @return {@code null}
     */
    public E peek() {
        return null;
    }

    /**
     * Returns an empty iterator in which {@code hasNext} always returns
     * {@code false}.
     *
     * @return an empty iterator
     */
    public Iterator<E> iterator() {
        return Collections.emptyIterator();
    }

    /**
     * Returns an empty spliterator in which calls to
     * {@link java.util.Spliterator#trySplit()} always return {@code null}.
     *
     * @return an empty spliterator
     * @since 1.8
     */
    public Spliterator<E> spliterator() {
        return Spliterators.emptySpliterator();
    }

    /**
     * Returns a zero-length array.
     * @return a zero-length array
     */
    public Object[] toArray() {
        return new Object[0];
    }

    /**
     * Sets the zeroeth element of the specified array to {@code null}
     * (if the array has non-zero length) and returns it.
     *
     * @param a the array
     * @return the specified array
     * @throws NullPointerException if the specified array is null
     */
    public <T> T[] toArray(T[] a) {
        if (a.length > 0)
            a[0] = null;
        return a;
    }

    /**
     * @throws UnsupportedOperationException {@inheritDoc}
     * @throws ClassCastException            {@inheritDoc}
     * @throws NullPointerException          {@inheritDoc}
     * @throws IllegalArgumentException      {@inheritDoc}
     */
    public int drainTo(Collection<? super E> c) {
        if (c == null)
            throw new NullPointerException();
        if (c == this)
            throw new IllegalArgumentException();
        int n = 0;
        for (E e; (e = poll()) != null;) {
            c.add(e);
            ++n;
        }
        return n;
    }

    /**
     * @throws UnsupportedOperationException {@inheritDoc}
     * @throws ClassCastException            {@inheritDoc}
     * @throws NullPointerException          {@inheritDoc}
     * @throws IllegalArgumentException      {@inheritDoc}
     */
    public int drainTo(Collection<? super E> c, int maxElements) {
        if (c == null)
            throw new NullPointerException();
        if (c == this)
            throw new IllegalArgumentException();
        int n = 0;
        for (E e; n < maxElements && (e = poll()) != null;) {
            c.add(e);
            ++n;
        }
        return n;
    }

    /*
     * To cope with serialization strategy in the 1.5 version of
     * SynchronousQueue, we declare some unused classes and fields
     * that exist solely to enable serializability across versions.
     * These fields are never used, so are initialized only if this
     * object is ever serialized or deserialized.
     */

    @SuppressWarnings("serial")
    static class WaitQueue implements java.io.Serializable { }
    static class LifoWaitQueue extends WaitQueue {
        private static final long serialVersionUID = -3633113410248163686L;
    }
    static class FifoWaitQueue extends WaitQueue {
        private static final long serialVersionUID = -3623113410248163686L;
    }
    private ReentrantLock qlock;
    private WaitQueue waitingProducers;
    private WaitQueue waitingConsumers;

    /**
     * Saves this queue to a stream (that is, serializes it).
     * @param s the stream
     * @throws java.io.IOException if an I/O error occurs
     */
    private void writeObject(java.io.ObjectOutputStream s)
        throws java.io.IOException {
        boolean fair = transferer instanceof TransferQueue;
        if (fair) {
            qlock = new ReentrantLock(true);
            waitingProducers = new FifoWaitQueue();
            waitingConsumers = new FifoWaitQueue();
        }
        else {
            qlock = new ReentrantLock();
            waitingProducers = new LifoWaitQueue();
            waitingConsumers = new LifoWaitQueue();
        }
        s.defaultWriteObject();
    }

    /**
     * Reconstitutes this queue from a stream (that is, deserializes it).
     * @param s the stream
     * @throws ClassNotFoundException if the class of a serialized object
     *         could not be found
     * @throws java.io.IOException if an I/O error occurs
     */
    private void readObject(java.io.ObjectInputStream s)
        throws java.io.IOException, ClassNotFoundException {
        s.defaultReadObject();
        if (waitingProducers instanceof FifoWaitQueue)
            transferer = new TransferQueue<E>();
        else
            transferer = new TransferStack<E>();
    }

    // Unsafe mechanics
    static long objectFieldOffset(sun.misc.Unsafe UNSAFE,
                                  String field, Class<?> klazz) {
        try {
            return UNSAFE.objectFieldOffset(klazz.getDeclaredField(field));
        } catch (NoSuchFieldException e) {
            // Convert Exception to corresponding Error
            NoSuchFieldError error = new NoSuchFieldError(field);
            error.initCause(e);
            throw error;
        }
    }

}
