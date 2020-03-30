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

import java.util.AbstractQueue;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;

/**
 * An unbounded {@link TransferQueue} based on linked nodes.
 * This queue orders elements FIFO (first-in-first-out) with respect
 * to any given producer.  The <em>head</em> of the queue is that
 * element that has been on the queue the longest time for some
 * producer.  The <em>tail</em> of the queue is that element that has
 * been on the queue the shortest time for some producer.
 *
 * <p>Beware that, unlike in most collections, the {@code size} method
 * is <em>NOT</em> a constant-time operation. Because of the
 * asynchronous nature of these queues, determining the current number
 * of elements requires a traversal of the elements, and so may report
 * inaccurate results if this collection is modified during traversal.
 * Additionally, the bulk operations {@code addAll},
 * {@code removeAll}, {@code retainAll}, {@code containsAll},
 * {@code equals}, and {@code toArray} are <em>not</em> guaranteed
 * to be performed atomically. For example, an iterator operating
 * concurrently with an {@code addAll} operation might view only some
 * of the added elements.
 *
 * <p>This class and its iterator implement all of the
 * <em>optional</em> methods of the {@link Collection} and {@link
 * Iterator} interfaces.
 *
 * <p>Memory consistency effects: As with other concurrent
 * collections, actions in a thread prior to placing an object into a
 * {@code LinkedTransferQueue}
 * <a href="package-summary.html#MemoryVisibility"><i>happen-before</i></a>
 * actions subsequent to the access or removal of that element from
 * the {@code LinkedTransferQueue} in another thread.
 *
 * <p>This class is a member of the
 * <a href="{@docRoot}/../technotes/guides/collections/index.html">
 * Java Collections Framework</a>.
 *
 * LinkedTransferQueue是LinkedBlockingQueue、SynchronousQueue（公平模式）、ConcurrentLinkedQueue三者的集合体，
 * 它综合了这三者的方法，并且提供了更加高效的实现方式。
 *
 * LinkedTransferQueue使用了松弛型双重队列，双重的意思可以理解为两种类型的节点.
 * 在双重队列中为了减少CAS的开销，加入了Slack（松弛度）的处理方式，在节点被匹配（被删除）之后，不会立即更新 head/tail，
 * 而是当 head/tail 节点和最近一个未匹配的节点之间的距离超过一个阈值之后才会更新，在LinkedTransferQueue中松弛度值设置为2，
 * 这是一个经验值，不多深究。同时为了避免匹配节点在队列中的堆积，在CAS更新head时，会把已匹配的head的next引用指向自己。
 * 当我们进行遍历时，遇到这种节点，表示当前线程已经落后于其他线程，需要重新获取head来进行遍历
 *
 * （1）LinkedTransferQueue 可以看作LinkedBlockingQueue、SynchronousQueue（公平模式）、ConcurrentLinkedQueue三者的集合体；
 * （2）LinkedTransferQueue的实现方式是使用一种叫做双重队列的数据结构；
 * （3）不管是取元素还是放元素都会入队；
 * （4）先尝试跟头节点比较，如果二者模式不一样，就匹配它们，组成CP，然后返回对方的值；
 * （5）如果二者模式一样，就入队，并自旋或阻塞等待被唤醒；
 * （6）至于是否入队及阻塞有四种模式，NOW、ASYNC、SYNC、TIMED；
 * （7）LinkedTransferQueue全程都没有使用synchronized、重入锁等比较重的锁，基本是通过 自旋+CAS 实现；
 * （8）对于入队之后，先自旋一定次数后再调用LockSupport.park()或LockSupport.parkNanos阻塞；
 *
 * SynchronousQueue 与 LinkedTransferQueue 区别 ?
 *  1. SynchronousQueue 无论 put 还是 take , 只要没有匹配的节点都会阻塞,
 *     LinkedTransferQueue 的 put , 不会阻塞, 没有匹配节点则异步添加到队列中, take 如果没匹配节点会阻塞, poll 会立刻返回
 *  2. SynchronousQueue 没有容量, LinkedTransferQueue 有容量
 *
 * @since 1.7
 * @author Doug Lea
 * @param <E> the type of elements held in this collection
 */
public class LinkedTransferQueue<E> extends AbstractQueue<E>
    implements TransferQueue<E>, java.io.Serializable {
    private static final long serialVersionUID = -3223113410248163686L;

    /*
     * *** Overview of Dual Queues with Slack ***
     *
     * Dual Queues, introduced by Scherer and Scott
     * (http://www.cs.rice.edu/~wns1/papers/2004-DISC-DDS.pdf) are
     * (linked) queues in which nodes may represent either data or
     * requests.  When a thread tries to enqueue a data node, but
     * encounters a request node, it instead "matches" and removes it;
     * and vice versa for enqueuing requests. Blocking Dual Queues
     * arrange that threads enqueuing unmatched requests block until
     * other threads provide the match. Dual Synchronous Queues (see
     * Scherer, Lea, & Scott
     * http://www.cs.rochester.edu/u/scott/papers/2009_Scherer_CACM_SSQ.pdf)
     * additionally arrange that threads enqueuing unmatched data also
     * block.  Dual Transfer Queues support all of these modes, as
     * dictated by callers.
     *
     * A FIFO dual queue may be implemented using a variation of the
     * Michael & Scott (M&S) lock-free queue algorithm
     * (http://www.cs.rochester.edu/u/scott/papers/1996_PODC_queues.pdf).
     * It maintains two pointer fields, "head", pointing to a
     * (matched) node that in turn points to the first actual
     * (unmatched) queue node (or null if empty); and "tail" that
     * points to the last node on the queue (or again null if
     * empty). For example, here is a possible queue with four data
     * elements:
     *
     *  head                tail
     *    |                   |
     *    v                   v
     *    M -> U -> U -> U -> U
     *
     * The M&S queue algorithm is known to be prone to scalability and
     * overhead limitations when maintaining (via CAS) these head and
     * tail pointers. This has led to the development of
     * contention-reducing variants such as elimination arrays (see
     * Moir et al http://portal.acm.org/citation.cfm?id=1074013) and
     * optimistic back pointers (see Ladan-Mozes & Shavit
     * http://people.csail.mit.edu/edya/publications/OptimisticFIFOQueue-journal.pdf).
     * However, the nature of dual queues enables a simpler tactic for
     * improving M&S-style implementations when dual-ness is needed.
     *
     * In a dual queue, each node must atomically maintain its match
     * status. While there are other possible variants, we implement
     * this here as: for a data-mode node, matching entails CASing an
     * "item" field from a non-null data value to null upon match, and
     * vice-versa for request nodes, CASing from null to a data
     * value. (Note that the linearization properties of this style of
     * queue are easy to verify -- elements are made available by
     * linking, and unavailable by matching.) Compared to plain M&S
     * queues, this property of dual queues requires one additional
     * successful atomic operation per enq/deq pair. But it also
     * enables lower cost variants of queue maintenance mechanics. (A
     * variation of this idea applies even for non-dual queues that
     * support deletion of interior elements, such as
     * j.u.c.ConcurrentLinkedQueue.)
     *
     * Once a node is matched, its match status can never again
     * change.  We may thus arrange that the linked list of them
     * contain a prefix of zero or more matched nodes, followed by a
     * suffix of zero or more unmatched nodes. (Note that we allow
     * both the prefix and suffix to be zero length, which in turn
     * means that we do not use a dummy header.)  If we were not
     * concerned with either time or space efficiency, we could
     * correctly perform enqueue and dequeue operations by traversing
     * from a pointer to the initial node; CASing the item of the
     * first unmatched node on match and CASing the next field of the
     * trailing node on appends. (Plus some special-casing when
     * initially empty).  While this would be a terrible idea in
     * itself, it does have the benefit of not requiring ANY atomic
     * updates on head/tail fields.
     *
     * We introduce here an approach that lies between the extremes of
     * never versus always updating queue (head and tail) pointers.
     * This offers a tradeoff between sometimes requiring extra
     * traversal steps to locate the first and/or last unmatched
     * nodes, versus the reduced overhead and contention of fewer
     * updates to queue pointers. For example, a possible snapshot of
     * a queue is:
     *
     *  head           tail
     *    |              |
     *    v              v
     *    M -> M -> U -> U -> U -> U
     *
     * The best value for this "slack" (the targeted maximum distance
     * between the value of "head" and the first unmatched node, and
     * similarly for "tail") is an empirical matter. We have found
     * that using very small constants in the range of 1-3 work best
     * over a range of platforms. Larger values introduce increasing
     * costs of cache misses and risks of long traversal chains, while
     * smaller values increase CAS contention and overhead.
     *
     * Dual queues with slack differ from plain M&S dual queues by
     * virtue of only sometimes updating head or tail pointers when
     * matching, appending, or even traversing nodes; in order to
     * maintain a targeted slack.  The idea of "sometimes" may be
     * operationalized in several ways. The simplest is to use a
     * per-operation counter incremented on each traversal step, and
     * to try (via CAS) to update the associated queue pointer
     * whenever the count exceeds a threshold. Another, that requires
     * more overhead, is to use random number generators to update
     * with a given probability per traversal step.
     *
     * In any strategy along these lines, because CASes updating
     * fields may fail, the actual slack may exceed targeted
     * slack. However, they may be retried at any time to maintain
     * targets.  Even when using very small slack values, this
     * approach works well for dual queues because it allows all
     * operations up to the point of matching or appending an item
     * (hence potentially allowing progress by another thread) to be
     * read-only, thus not introducing any further contention. As
     * described below, we implement this by performing slack
     * maintenance retries only after these points.
     *
     * As an accompaniment to such techniques, traversal overhead can
     * be further reduced without increasing contention of head
     * pointer updates: Threads may sometimes shortcut the "next" link
     * path from the current "head" node to be closer to the currently
     * known first unmatched node, and similarly for tail. Again, this
     * may be triggered with using thresholds or randomization.
     *
     * These ideas must be further extended to avoid unbounded amounts
     * of costly-to-reclaim garbage caused by the sequential "next"
     * links of nodes starting at old forgotten head nodes: As first
     * described in detail by Boehm
     * (http://portal.acm.org/citation.cfm?doid=503272.503282) if a GC
     * delays noticing that any arbitrarily old node has become
     * garbage, all newer dead nodes will also be unreclaimed.
     * (Similar issues arise in non-GC environments.)  To cope with
     * this in our implementation, upon CASing to advance the head
     * pointer, we set the "next" link of the previous head to point
     * only to itself; thus limiting the length of connected dead lists.
     * (We also take similar care to wipe out possibly garbage
     * retaining values held in other Node fields.)  However, doing so
     * adds some further complexity to traversal: If any "next"
     * pointer links to itself, it indicates that the current thread
     * has lagged behind a head-update, and so the traversal must
     * continue from the "head".  Traversals trying to find the
     * current tail starting from "tail" may also encounter
     * self-links, in which case they also continue at "head".
     *
     * It is tempting in slack-based scheme to not even use CAS for
     * updates (similarly to Ladan-Mozes & Shavit). However, this
     * cannot be done for head updates under the above link-forgetting
     * mechanics because an update may leave head at a detached node.
     * And while direct writes are possible for tail updates, they
     * increase the risk of long retraversals, and hence long garbage
     * chains, which can be much more costly than is worthwhile
     * considering that the cost difference of performing a CAS vs
     * write is smaller when they are not triggered on each operation
     * (especially considering that writes and CASes equally require
     * additional GC bookkeeping ("write barriers") that are sometimes
     * more costly than the writes themselves because of contention).
     *
     * *** Overview of implementation ***
     *
     * We use a threshold-based approach to updates, with a slack
     * threshold of two -- that is, we update head/tail when the
     * current pointer appears to be two or more steps away from the
     * first/last node. The slack value is hard-wired: a path greater
     * than one is naturally implemented by checking equality of
     * traversal pointers except when the list has only one element,
     * in which case we keep slack threshold at one. Avoiding tracking
     * explicit counts across method calls slightly simplifies an
     * already-messy implementation. Using randomization would
     * probably work better if there were a low-quality dirt-cheap
     * per-thread one available, but even ThreadLocalRandom is too
     * heavy for these purposes.
     *
     * With such a small slack threshold value, it is not worthwhile
     * to augment this with path short-circuiting (i.e., unsplicing
     * interior nodes) except in the case of cancellation/removal (see
     * below).
     *
     * We allow both the head and tail fields to be null before any
     * nodes are enqueued; initializing upon first append.  This
     * simplifies some other logic, as well as providing more
     * efficient explicit control paths instead of letting JVMs insert
     * implicit NullPointerExceptions when they are null.  While not
     * currently fully implemented, we also leave open the possibility
     * of re-nulling these fields when empty (which is complicated to
     * arrange, for little benefit.)
     *
     * All enqueue/dequeue operations are handled by the single method
     * "xfer" with parameters indicating whether to act as some form
     * of offer, put, poll, take, or transfer (each possibly with
     * timeout). The relative complexity of using one monolithic
     * method outweighs the code bulk and maintenance problems of
     * using separate methods for each case.
     *
     * Operation consists of up to three phases. The first is
     * implemented within method xfer, the second in tryAppend, and
     * the third in method awaitMatch.
     *
     * 1. Try to match an existing node
     *
     *    Starting at head, skip already-matched nodes until finding
     *    an unmatched node of opposite mode, if one exists, in which
     *    case matching it and returning, also if necessary updating
     *    head to one past the matched node (or the node itself if the
     *    list has no other unmatched nodes). If the CAS misses, then
     *    a loop retries advancing head by two steps until either
     *    success or the slack is at most two. By requiring that each
     *    attempt advances head by two (if applicable), we ensure that
     *    the slack does not grow without bound. Traversals also check
     *    if the initial head is now off-list, in which case they
     *    start at the new head.
     *
     *    If no candidates are found and the call was untimed
     *    poll/offer, (argument "how" is NOW) return.
     *
     * 2. Try to append a new node (method tryAppend)
     *
     *    Starting at current tail pointer, find the actual last node
     *    and try to append a new node (or if head was null, establish
     *    the first node). Nodes can be appended only if their
     *    predecessors are either already matched or are of the same
     *    mode. If we detect otherwise, then a new node with opposite
     *    mode must have been appended during traversal, so we must
     *    restart at phase 1. The traversal and update steps are
     *    otherwise similar to phase 1: Retrying upon CAS misses and
     *    checking for staleness.  In particular, if a self-link is
     *    encountered, then we can safely jump to a node on the list
     *    by continuing the traversal at current head.
     *
     *    On successful append, if the call was ASYNC, return.
     *
     * 3. Await match or cancellation (method awaitMatch)
     *
     *    Wait for another thread to match node; instead cancelling if
     *    the current thread was interrupted or the wait timed out. On
     *    multiprocessors, we use front-of-queue spinning: If a node
     *    appears to be the first unmatched node in the queue, it
     *    spins a bit before blocking. In either case, before blocking
     *    it tries to unsplice any nodes between the current "head"
     *    and the first unmatched node.
     *
     *    Front-of-queue spinning vastly improves performance of
     *    heavily contended queues. And so long as it is relatively
     *    brief and "quiet", spinning does not much impact performance
     *    of less-contended queues.  During spins threads check their
     *    interrupt status and generate a thread-local random number
     *    to decide to occasionally perform a Thread.yield. While
     *    yield has underdefined specs, we assume that it might help,
     *    and will not hurt, in limiting impact of spinning on busy
     *    systems.  We also use smaller (1/2) spins for nodes that are
     *    not known to be front but whose predecessors have not
     *    blocked -- these "chained" spins avoid artifacts of
     *    front-of-queue rules which otherwise lead to alternating
     *    nodes spinning vs blocking. Further, front threads that
     *    represent phase changes (from data to request node or vice
     *    versa) compared to their predecessors receive additional
     *    chained spins, reflecting longer paths typically required to
     *    unblock threads during phase changes.
     *
     *
     * ** Unlinking removed interior nodes **
     *
     * In addition to minimizing garbage retention via self-linking
     * described above, we also unlink removed interior nodes. These
     * may arise due to timed out or interrupted waits, or calls to
     * remove(x) or Iterator.remove.  Normally, given a node that was
     * at one time known to be the predecessor of some node s that is
     * to be removed, we can unsplice s by CASing the next field of
     * its predecessor if it still points to s (otherwise s must
     * already have been removed or is now offlist). But there are two
     * situations in which we cannot guarantee to make node s
     * unreachable in this way: (1) If s is the trailing node of list
     * (i.e., with null next), then it is pinned as the target node
     * for appends, so can only be removed later after other nodes are
     * appended. (2) We cannot necessarily unlink s given a
     * predecessor node that is matched (including the case of being
     * cancelled): the predecessor may already be unspliced, in which
     * case some previous reachable node may still point to s.
     * (For further explanation see Herlihy & Shavit "The Art of
     * Multiprocessor Programming" chapter 9).  Although, in both
     * cases, we can rule out the need for further action if either s
     * or its predecessor are (or can be made to be) at, or fall off
     * from, the head of list.
     *
     * Without taking these into account, it would be possible for an
     * unbounded number of supposedly removed nodes to remain
     * reachable.  Situations leading to such buildup are uncommon but
     * can occur in practice; for example when a series of short timed
     * calls to poll repeatedly time out but never otherwise fall off
     * the list because of an untimed call to take at the front of the
     * queue.
     *
     * When these cases arise, rather than always retraversing the
     * entire list to find an actual predecessor to unlink (which
     * won't help for case (1) anyway), we record a conservative
     * estimate of possible unsplice failures (in "sweepVotes").
     * We trigger a full sweep when the estimate exceeds a threshold
     * ("SWEEP_THRESHOLD") indicating the maximum number of estimated
     * removal failures to tolerate before sweeping through, unlinking
     * cancelled nodes that were not unlinked upon initial removal.
     * We perform sweeps by the thread hitting threshold (rather than
     * background threads or by spreading work to other threads)
     * because in the main contexts in which removal occurs, the
     * caller is already timed-out, cancelled, or performing a
     * potentially O(n) operation (e.g. remove(x)), none of which are
     * time-critical enough to warrant the overhead that alternatives
     * would impose on other threads.
     *
     * Because the sweepVotes estimate is conservative, and because
     * nodes become unlinked "naturally" as they fall off the head of
     * the queue, and because we allow votes to accumulate even while
     * sweeps are in progress, there are typically significantly fewer
     * such nodes than estimated.  Choice of a threshold value
     * balances the likelihood of wasted effort and contention, versus
     * providing a worst-case bound on retention of interior nodes in
     * quiescent queues. The value defined below was chosen
     * empirically to balance these under various timeout scenarios.
     *
     * Note that we cannot self-link unlinked interior nodes during
     * sweeps. However, the associated garbage chains terminate when
     * some successor ultimately falls off the head of the list and is
     * self-linked.
     */

    //如果机器为多处理器则为true,MP为multiprocessor缩写
    /** True if on multiprocessor */
    private static final boolean MP =
        Runtime.getRuntime().availableProcessors() > 1;

    /**
     * 节点自旋等待的次数 128
     * The number of times to spin (with randomly interspersed calls
     * to Thread.yield) on multiprocessor before blocking when a node
     * is apparently the first waiter in the queue.  See above for
     * explanation. Must be a power of two. The value is empirically
     * derived -- it works pretty well across a variety of processors,
     * numbers of CPUs, and OSes.
     */
    private static final int FRONT_SPINS   = 1 << 7;

    /**
     * 前驱节点正在处理，当前节点需要自旋的次数, FRONT_SPINS >>> 1 = 64
     * The number of times to spin before blocking when a node is
     * preceded by another node that is apparently spinning.  Also
     * serves as an increment to FRONT_SPINS on phase changes, and as
     * base average frequency for yielding during spins. Must be a
     * power of two.
     */
    private static final int CHAINED_SPINS = FRONT_SPINS >>> 1;

    /**
     * sweepVotes的阈值，达到这个阈值上限则进行一次清理操作
     * The maximum number of estimated removal failures (sweepVotes)
     * to tolerate before sweeping through the queue unlinking
     * cancelled nodes that were not unlinked upon initial
     * removal. See above for explanation. The value must be at least
     * two to avoid useless sweeps when removing trailing nodes.
     */
    static final int SWEEP_THRESHOLD = 32;

    /**
     * 单向链表节点
     * Queue nodes. Uses Object, not E, for items to allow forgetting
     * them after use.  Relies heavily on Unsafe mechanics to minimize
     * unnecessary ordering constraints: Writes that are intrinsically
     * ordered wrt other accesses or CASes use simple relaxed forms.
     */
    static final class Node {
        /**
         * 数据节点和请求节点类型区分标识
         */
        final boolean isData;   // false if this is a request node
        /**
         * 数据节点保存数据，请求节点为null
         */
        volatile Object item;   // initially non-null if isData; CASed to match
        /**
         * 指向队列中下一个节点
         */
        volatile Node next;
        /**
         * 当前节点对应的等待线程
         */
        volatile Thread waiter; // null until waiting

        // CAS methods for fields
        final boolean casNext(Node cmp, Node val) {
            return UNSAFE.compareAndSwapObject(this, nextOffset, cmp, val);
        }

        final boolean casItem(Object cmp, Object val) {
            // assert cmp == null || cmp.getClass() != Node.class;
            return UNSAFE.compareAndSwapObject(this, itemOffset, cmp, val);
        }

        /**
         * 构造方法
         * Constructs a new node.  Uses relaxed write because item can
         * only be seen after publication via casNext.
         */
        Node(Object item, boolean isData) {
            UNSAFE.putObject(this, itemOffset, item); // relaxed write
            this.isData = isData;
        }

        /**
         * 将next指向自己，避免无用Node过长影响垃圾回收, 在cas更新head后调用
         * Links node to itself to avoid garbage retention.  Called
         * only after CASing head field, so uses relaxed write.
         */
        final void forgetNext() {
            UNSAFE.putObject(this, nextOffset, this);
        }

        /**
         * 匹配或者节点被取消的时候被调用，设置item指向自己，waiter为null
         * Sets item to self and waiter to null, to avoid garbage
         * retention after matching or cancelling. Uses relaxed writes
         * because order is already constrained in the only calling
         * contexts: item is forgotten only after volatile/atomic
         * mechanics that extract items.  Similarly, clearing waiter
         * follows either CAS or return from park (if ever parked;
         * else we don't care).
         */
        final void forgetContents() {
            UNSAFE.putObject(this, itemOffset, this);
            UNSAFE.putObject(this, waiterOffset, null);
        }

        /**
         * 如果此节点已匹配或者是被取消匹配的节点，则返回true
         * Returns true if this node has been matched, including the
         * case of artificial matches due to cancellation.
         */
        final boolean isMatched() {
            Object x = item;
            //x == this 调用了forgetContents, 或者是被取消了
            // x == null 相当于 !(x != null), 也就是相当于 !isData , !isData 表示最原始的 isData 取反
            //(x == null) == isData 表示请求节点匹配了数据节点（请求节点的item更新为数据节点的数据）
            // 或者数据节点匹配了请求节点（数据节点的item更新为null）
            return (x == this) || ((x == null) == isData);
        }

        /**
         * 当前节点是否是未匹配的请求(take)节点
         *  - !isData 请求节点, 也就是非数据节点
         *  - item == null 代表没被匹配
         * Returns true if this is an unmatched request node.
         */
        final boolean isUnmatchedRequest() {
            return !isData && item == null;
        }

        /**
         * 能否将指定的节点node（haveData类型）追加到当前节点后。如果node节点属性与当前节点相反，且当前节点还未进行匹配则不能追加
         * Returns true if a node with the given mode cannot be
         * appended to this node because this node is unmatched and
         * has opposite data mode.
         */
        final boolean cannotPrecede(boolean haveData) {
            boolean d = isData;
            Object x;
            //d != haveData 表示类型匹配
            // (x = item) != this 没有被取消且没有被匹配
            // (x != null) == d 表示还没有被匹配, x != null 表示最原始的 isData, d 表示现在的 isData,
            // 如果被匹配上, 最原始的 isData 肯定不等现在的 isData
            return d != haveData && (x = item) != this && (x != null) == d;
        }

        /**
         * 尝试人为匹配数据节点，匹配成功返回true，设置item为null(不用再匹配了) , 相当于移除当前数据节点，用在remove方法中
         * Tries to artificially match a data node -- used by remove.
         */
        final boolean tryMatchData() {
            // assert isData;
            //this 是个数据节点, item 肯定不为 null
            Object x = item;
            // x != null 表示数据节点
            // x != this 表示还没被匹配或没被取消
            // casItem(x, null) , cas 进行匹配
            if (x != null && x != this && casItem(x, null)) {
                //如果匹配成功, 则唤醒当前挂起的线程
                LockSupport.unpark(waiter);
                //return true
                return true;
            }
            //默认没匹配上
            return false;
        }

        private static final long serialVersionUID = -3375979862319811754L;

        // Unsafe mechanics
        private static final sun.misc.Unsafe UNSAFE;
        private static final long itemOffset;
        private static final long nextOffset;
        private static final long waiterOffset;
        static {
            try {
                UNSAFE = sun.misc.Unsafe.getUnsafe();
                Class<?> k = Node.class;
                itemOffset = UNSAFE.objectFieldOffset
                    (k.getDeclaredField("item"));
                nextOffset = UNSAFE.objectFieldOffset
                    (k.getDeclaredField("next"));
                waiterOffset = UNSAFE.objectFieldOffset
                    (k.getDeclaredField("waiter"));
            } catch (Exception e) {
                throw new Error(e);
            }
        }
    }

    // 头节点
    /** head of the queue; null until first enqueue */
    transient volatile Node head;

    // 尾节点
    /** tail of the queue; null until first append */
    private transient volatile Node tail;

    //解除删除节点关联失败的次数
    /** The number of apparent failures to unsplice removed nodes */
    private transient volatile int sweepVotes;

    // CAS methods for fields
    private boolean casTail(Node cmp, Node val) {
        return UNSAFE.compareAndSwapObject(this, tailOffset, cmp, val);
    }

    private boolean casHead(Node cmp, Node val) {
        return UNSAFE.compareAndSwapObject(this, headOffset, cmp, val);
    }

    private boolean casSweepVotes(int cmp, int val) {
        return UNSAFE.compareAndSwapInt(this, sweepVotesOffset, cmp, val);
    }

    /*
     * Possible values for "how" argument in xfer method.
     */
    // xfer方法how参数可能的取值类型, 队列操作统一方法根据类型进行不同的处理
    // 立即返回，用于非超时的poll()和tryTransfer()方法中
    private static final int NOW   = 0; // for untimed poll, tryTransfer
    // 异步，不会阻塞，用于放元素时，因为内部使用无界单链表存储元素，不会阻塞放元素的过程
    private static final int ASYNC = 1; // for offer, put, add
    // 同步，调用的时候如果没有匹配到会阻塞直到匹配到为止
    private static final int SYNC  = 2; // for transfer, take
    // 超时，用于有超时的poll()和tryTransfer()方法中
    private static final int TIMED = 3; // for timed poll, tryTransfer

    @SuppressWarnings("unchecked")
    static <E> E cast(Object item) {
        // assert item == null || item.getClass() != Node.class;
        return (E) item;
    }

    /**
     * LinkedTransferQueue核心方法，所有的操作最终都通过xfer实现，通过how参数的不同进行不同的处理，
     * 在匹配上时判断当前head的slack阈值，如果达到上限则进行head更新
     *
     * Implements all queuing methods. See above for explanation.
     *
     * @param e the item or null for take
     * @param haveData true if this is a put, else a take
     * @param how NOW, ASYNC, SYNC, or TIMED
     * @param nanos timeout in nanosecs, used only if mode is TIMED
     * @return an item if matched, else e
     * @throws NullPointerException if haveData mode but e is null
     */
    private E xfer(E e, boolean haveData, int how, long nanos) {
        //e 数据节点（e非空）或者请求节点（e为null）
        //haveData 数据节点为true，请求节点为false
        //how NOW, ASYNC, SYNC, or TIMED  4种类型，上面已经介绍过
        //nanos TIMED模式下设置的超时时间
        //@return 节点匹配上则返回对应的匹配项否则传入的参数e

        // 如果是数据节点则数据不能为 null
        if (haveData && (e == null))
            throw new NullPointerException();
        //如果需要的话, 缓存待新增的节点
        Node s = null;                        // the node to append, if needed

        // 外层循环，自旋，失败就重试
        retry:
        for (;;) {                            // restart on append race
            //从 head 开始遍历尝试匹配, 如果 head 为 null 则不进入 for 循环体, 则直接往下走
            for (Node h = head, p = h; p != null;) { // find & match first node
                // p 节点的节点类型
                boolean isData = p.isData;
                // 获取数据
                Object item = p.item;
                // item != p 表示当前节点没有取消或者没有被匹配上, 因为匹配上也会设置 item = this 的
                //(item != null) == isData 表示 p 是一个还未匹配的节点
                // item != null 相当于最原始的 isData, 这里可以理解成最原始的 isData 和现在的 isData 进行对比, 相等就是没匹配
                if (item != p && (item != null) == isData) { // unmatched
                    // 相同节点类型，说明和队列中所有节点相同类型，无需匹配，跳出这个循环根据类型继续接下来的操作
                    if (isData == haveData)   // can't match
                        break;

                    //执行到这说明p节点还未匹配上且与当前节点是相异类型，cas更新item成功则表示匹配上了
                    // 注意这里只更新了head指向的节点，因为本次线程的e节点到这里还未入队
                    // 这里将p的item指向为对应操作的节点e，表示p对应的节点已经与此次的e匹配上了
                    // 如果未更新成功，说明p已经被其他人匹配上，执行后面逻辑继续循环
                    if (p.casItem(item, e)) { // match
                        //进来, 表示 p 已经被匹配上了

                        // 如果 p 当前已经不是指向 h 了，说明p已经被循环 next 更新过了, 也就是: h -> q -> n 或 h -> a -> b -> q -> n
                        for (Node q = p; q != h;) {
                            //获取 p 的下一个节点
                            Node n = q.next;  // update by 2 unless singleton

                            // head == h 表示别的线程没有更改 head 节点的位置
                            //如果别的线程没有更改 head 的位置, 则尝试推进 head 节点
                            // 如果 n == null, 则表示 q 是链表的最后一个节点, 直接用q 做 head, 否则用 n 作为 head
                            // 松弛度等于2则更新head，h->q->n
                            if (head == h && casHead(h, n == null ? q : n)) {
                                //替换成功, 将h的next更新方便回收
                                h.forgetNext();
                                //退出循环
                                break;
                            }                 // advance and retry

                            //能跑下来表明, head 被别的线程更新了, 或 h 不是最新的 head

                            //有其它线程更新了头节点，再次判断 slack<2。 h -> q 如果 q.isMatched() 则可以将 q.next 设置为头节点
                            //更新 h 为最新的 head , 并且判断如果 head 为 null, 则 slack 为 0, 满足 slack<2 , 直接退出
                            //更新 q 为 next, 并且判断如果 h.next(q) 为 null, 也就是链表只有 h, 如果 h 是未匹配的, 则 stack 为 0, 如果 h 是已匹配的, 则 stack 是 1,  满足 slack < 2 , 也退出

                            //如果 head 不为 null, h.next(q) 也不为 null, 也就是 h -> q , 如果 q 是未匹配, 如果 h 是未匹配, 则 stack 是 0,
                            // 如果 h 是匹配的, 则 stack 是 1, 满足 stack < 2 , 直接退出.
                            // 相反, 如果q是已经匹配的, 则 h 肯定也是匹配的, 那么 stack 至少大于等于 2, 因为 q 后面可能还有已经匹配的节点, 所以不能直接退出
                            if ((h = head)   == null ||
                                (q = h.next) == null || !q.isMatched())
                                break;        // unless slack < 2
                        }
                        //唤醒匹配节点的挂起线程
                        LockSupport.unpark(p.waiter);
                        //返回数据对象
                        return LinkedTransferQueue.<E>cast(item);
                    }
                }
                //获取下一个节点
                Node n = p.next;
                p = (p != n) ? n : (h = head); // Use head if p offlist
            }

            // 到这里肯定是队列中存储的节点类型和自己一样
            // 或者队列中没有元素了
            // 就入队（不管放元素还是取元素都得入队）
            // 入队又分成四种情况：
            // NOW，立即返回，没有匹配到立即返回，不做入队操作
            // ASYNC，异步，元素入队但当前线程不会阻塞（相当于无界LinkedBlockingQueue的元素入队）
            // SYNC，同步，元素入队后当前线程阻塞，等待被匹配到
            // TIMED，有超时，元素入队后等待一段时间被匹配，时间到了还没匹配到就返回元素本身

            // 如果不是立即返回
            if (how != NOW) {                 // No matches available
                // 新建s节点
                if (s == null)
                    s = new Node(e, haveData);
                // 尝试添加节点s到队列尾部
                Node pred = tryAppend(s, haveData);
                // 入队失败, 表示可以匹配，重试
                if (pred == null)
                    continue retry;           // lost race vs opposite mode
                //执行到这里说明 pred 非 null，s添加到队列中了，pred代表的是s的前驱节点或者s本身
                // 处理SYNC/TIMED模式
                if (how != ASYNC)
                    return awaitMatch(s, pred, e, (how == TIMED), nanos);
            }
            return e; // not waiting
        }
    }

    /**
     * 尝试在尾部入队
     * Tries to append node s as tail.
     *
     * @param s the node to append
     * @param haveData true if appending in data mode
     * @return null on failure due to losing race with append in
     * different mode, else s's predecessor, or s itself if no
     * predecessor
     */
    private Node tryAppend(Node s, boolean haveData) {
        //从 tail 开始遍历，尝试把 s 放到链表尾端
        for (Node t = tail, p = t;;) {        // move p to last node and append
            Node n, u;                        // temps for reads of next & tail
            // 如果首尾都是null，说明链表中还没有元素, 则进行初始化
            if (p == null && (p = head) == null) {
                // 队列为空时尝试更新头节点为s即可，失败重新循环处理
                // 注意，这里插入第一个元素的时候tail指针并没有指向s
                if (casHead(null, s))
                    return s;                 // initialize
            }
            //节点s不能追加到p节点后。①p和s的模式不同且②p还未匹配
            else if (p.cannotPrecede(haveData))
                //两者可以匹配则返回null进行标记处理
                return null;                  // lost race vs opposite mode
            //如果 p.next 不为 null, 则表示 p 不是尾节点, 可能是被其他线程更新导致的
            else if ((n = p.next) != null)    // not last; keep traversing
                //如果 p == t , 表示当前的 t 是刚从 tail 获取的, tail 可能还没更新, 则只能往下遍历, 如果 n ==  p 的话, 表示 p 已经被删除, 则返回 null, 否则返回下一个节点 n
                //如果 p != t, 则表示 p 已经经过 p = p.next 的往下遍历了, 也就是 p 已经走在 t 后面了, 那么用 t != (u = tail) 判断一下 tail 是否已经更新过了, 如果更新过, 则返回 t, 否则根据p 是否被删除来返回 n 或 null
                p = p != t && t != (u = tail) ? (t = u) : // stale tail
                    (p != n) ? n : null;      // restart if off list
            //到这里, 表示 p 为最后一个节点, 则尝试添加当前节点到链表后面, 如果添加不成功, 则表明有其他线程在添加
            else if (!p.casNext(null, s))
                //更新p重新循环执行
                p = p.next;                   // re-read on CAS failure
            else {
                // 执行到这表明更新p的next为s成功
                //如果 p == t , 则 p(t) -> s , 此时 s 是尾节点, t 到 s 的距离为 1, 则 stack = 1, 所以不用更新 tail
                //如果 t != p, 整个节点关联为...-> t -> ... -> p -> s，t到s距离 >= 2 , 满足 stack >= 2, 要更新 tail
                if (p != t) {                 // update if slack now >= 2
                    //如果 tail == t, 则表明 tail 还没有被更新过, 则尝试用 casTail(t, s) 更新, 如果更新成功, 则退出循环
                    //如果 tail != t 或 casTail(t, s) 更新失败, 表示其他线程已经更新过 tail 的位置, 则继续判断是否需要更新 tail
                    //t.next.next != null , 则 t 到 s 的距离 >= 2, 满足 stack >= 2, 则需要重新更新 tail。
                    // s != t ??? todo wolfleong s != t 不明白作用是什么?
                    while ((tail != t || !casTail(t, s)) &&
                           (t = tail)   != null &&
                           (s = t.next) != null && // advance and retry
                           (s = s.next) != null && s != t);
                    //这里是不停循环地更新 tail 直到尾节点 , 而不是直接把 tail 指到尾节点
                }
                //添加成功, 返回
                return p;
            }
        }
    }

    /**
     * Spins(自旋)/yields(放弃cpu时间片)/blocks(阻塞) 直到s节点matched或canceled
     * Spins/yields/blocks until node s is matched or caller gives up.
     *
     * @param s the waiting node
     * @param pred the predecessor of s, or s itself if it has no
     * predecessor, or null if unknown (the null case does not occur
     * in any current calls but may in possible future extensions)
     * @param e the comparison value for checking match
     * @param timed if true, wait only until timeout elapses
     * @param nanos timeout in nanosecs, used only if timed is true
     * @return matched item, or e if unmatched on interrupt or timeout
     */
    private E awaitMatch(Node s, Node pred, E e, boolean timed, long nanos) {
        //pred: s的前驱节点，如果没有前驱节点则为s自己
        //s: 表示当前节点
        //e: s节点的原始值
        //timed: true时限时等待，false时无限等待

        //计算超时时间
        final long deadline = timed ? System.nanoTime() + nanos : 0L;
        //当前线程
        Thread w = Thread.currentThread();
        //默认自旋次数为 -1
        int spins = -1; // initialized after first item and cancel checks
        //用于控制 Thread.yield 概率的随机对象
        ThreadLocalRandom randomYields = null; // bound if needed

        for (;;) {
            //获取 s 节点的值
            Object item = s.item;
            //item已经被修改，说明匹配成功。返回匹配后的值
            if (item != e) {                  // matched
                // assert item != s;
                // 清除 s 的内容
                s.forgetContents();           // avoid garbage
                //返回匹配的值
                return LinkedTransferQueue.<E>cast(item);
            }
            //如果线程被中断或超时, 则将 s 设置成取消
            if ((w.isInterrupted() || (timed && nanos <= 0)) &&
                    s.casItem(e, s)) {        // cancel
                //踢出 s
                unsplice(pred, s);
                return e;
            }

            // 3. 设置自旋次数
            if (spins < 0) {                  // establish spins at/near front
                //计算并设置, 如果 spins > 0 , 则获取 ThreadLocalRandom
                if ((spins = spinsFor(pred, s.isData)) > 0)
                    randomYields = ThreadLocalRandom.current();
            }
            //自旋，有很小的概率调用 yield
            else if (spins > 0) {             // spin
                --spins;
                //随机 yield
                if (randomYields.nextInt(CHAINED_SPINS) == 0)
                    Thread.yield();           // occasionally yield
            }
            //如果线程引用为 null
            else if (s.waiter == null) {
                //设置当前线程
                s.waiter = w;                 // request unpark then recheck
            }
            //如果有设置时间, 则设置时间挂起
            else if (timed) {
                nanos = deadline - System.nanoTime();
                //没到超时时间, 则挂起
                if (nanos > 0L)
                    LockSupport.parkNanos(this, nanos);
            }
            else {
                //挂起, 直到被唤醒
                LockSupport.park(this);
            }
        }
    }

    /**
     * 通过pre节点计算自旋次数
     * Returns spin/yield value for a node with given predecessor and
     * data mode. See above for explanation.
     */
    private static int spinsFor(Node pred, boolean haveData) {
        //如果是多核cpu 且 前一个节点不为 null
        if (MP && pred != null) {
            //前驱和当前节点类型不同则自旋 FRONT_SPINS + CHAINED_SPINS ??? todo wolfleong 有机会出现这种情况吗?
            if (pred.isData != haveData)      // phase change
                return FRONT_SPINS + CHAINED_SPINS;
            // pre已经匹配了，那就可以少自旋一些
            if (pred.isMatched())             // probably at front
                return FRONT_SPINS;
            //pre节点在匹配中了，那可以再少自旋一点
            if (pred.waiter == null)          // pred apparently spinning
                return CHAINED_SPINS;
        }
        //单核CPU时不自旋
        return 0;
    }

    /* -------------- Traversal methods -------------- */

    /**
     * 返回p的后继节点，如果p.next指向p(p节点已经离队)，则返回head头节点. 因为 head 节点肯定在离队节点后面
     * Returns the successor of p, or the head node if p.next has been
     * linked to self, which will only be true if traversing with a
     * stale pointer that is now off the list.
     */
    final Node succ(Node p) {
        Node next = p.next;
        return (p == next) ? head : next;
    }

    /**
     * 找到第一个未匹配节点，数据类型一致则返回节点，不一致则返回null
     * Returns the first unmatched node of the given mode, or null if
     * none.  Used by methods isEmpty, hasWaitingConsumer.
     */
    private Node firstOfMode(boolean isData) {
        //遍历
        for (Node p = head; p != null; p = succ(p)) {
            //如果找到未匹配节点
            if (!p.isMatched())
                return (p.isData == isData) ? p : null;
        }
        return null;
    }

    /**
     * Version of firstOfMode used by Spliterator. Callers must
     * recheck if the returned node's item field is null or
     * self-linked before using.
     */
    final Node firstDataNode() {
        // 从头开始进行循环判断
        for (Node p = head; p != null;) {
            Object item = p.item;
            //如果是数据节点
            if (p.isData) {
                //如果没有被取消也没有被匹配, 则返回
                if (item != null && item != p)
                    return p;
            }
            // 头节点未被匹配同时非数据节点则队列中此刻应该只有请求节点不需要再循环判断下去了
            else if (item == null)
                break;
            //如果节点被删除, 则下一个从 head 开始
            if (p == (p = p.next))
                p = head;
        }
        return null;
    }

    /**
     * Returns the item in the first unmatched node with isData; or
     * null if none.  Used by peek.
     */
    private E firstDataItem() {
        for (Node p = head; p != null; p = succ(p)) {
            Object item = p.item;
            if (p.isData) {
                if (item != null && item != p)
                    return LinkedTransferQueue.<E>cast(item);
            }
            else if (item == null)
                return null;
        }
        return null;
    }

    /**
     * Traverses and counts unmatched nodes of the given mode.
     * Used by methods size and getWaitingConsumerCount.
     */
    private int countOfMode(boolean data) {
        int count = 0;
        for (Node p = head; p != null; ) {
            if (!p.isMatched()) {
                if (p.isData != data)
                    return 0;
                if (++count == Integer.MAX_VALUE) // saturated
                    break;
            }
            Node n = p.next;
            if (n != p)
                p = n;
            else {
                count = 0;
                p = head;
            }
        }
        return count;
    }

    final class Itr implements Iterator<E> {
        private Node nextNode;   // next node to return item for
        private E nextItem;      // the corresponding item
        private Node lastRet;    // last returned node, to support remove
        private Node lastPred;   // predecessor to unlink lastRet

        /**
         * Moves to next node after prev, or first node if prev null.
         */
        private void advance(Node prev) {
            /*
             * To track and avoid buildup of deleted nodes in the face
             * of calls to both Queue.remove and Itr.remove, we must
             * include variants of unsplice and sweep upon each
             * advance: Upon Itr.remove, we may need to catch up links
             * from lastPred, and upon other removes, we might need to
             * skip ahead from stale nodes and unsplice deleted ones
             * found while advancing.
             */

            Node r, b; // reset lastPred upon possible deletion of lastRet
            if ((r = lastRet) != null && !r.isMatched())
                lastPred = r;    // next lastPred is old lastRet
            else if ((b = lastPred) == null || b.isMatched())
                lastPred = null; // at start of list
            else {
                Node s, n;       // help with removal of lastPred.next
                while ((s = b.next) != null &&
                       s != b && s.isMatched() &&
                       (n = s.next) != null && n != s)
                    b.casNext(s, n);
            }

            this.lastRet = prev;

            for (Node p = prev, s, n;;) {
                s = (p == null) ? head : p.next;
                if (s == null)
                    break;
                else if (s == p) {
                    p = null;
                    continue;
                }
                Object item = s.item;
                if (s.isData) {
                    if (item != null && item != s) {
                        nextItem = LinkedTransferQueue.<E>cast(item);
                        nextNode = s;
                        return;
                    }
                }
                else if (item == null)
                    break;
                // assert s.isMatched();
                if (p == null)
                    p = s;
                else if ((n = s.next) == null)
                    break;
                else if (s == n)
                    p = null;
                else
                    p.casNext(s, n);
            }
            nextNode = null;
            nextItem = null;
        }

        Itr() {
            advance(null);
        }

        public final boolean hasNext() {
            return nextNode != null;
        }

        public final E next() {
            Node p = nextNode;
            if (p == null) throw new NoSuchElementException();
            E e = nextItem;
            advance(p);
            return e;
        }

        public final void remove() {
            final Node lastRet = this.lastRet;
            if (lastRet == null)
                throw new IllegalStateException();
            this.lastRet = null;
            if (lastRet.tryMatchData())
                unsplice(lastPred, lastRet);
        }
    }

    /** A customized variant of Spliterators.IteratorSpliterator */
    static final class LTQSpliterator<E> implements Spliterator<E> {
        static final int MAX_BATCH = 1 << 25;  // max batch array size;
        final LinkedTransferQueue<E> queue;
        Node current;    // current node; null until initialized
        int batch;          // batch size for splits
        boolean exhausted;  // true when no more nodes
        LTQSpliterator(LinkedTransferQueue<E> queue) {
            this.queue = queue;
        }

        public Spliterator<E> trySplit() {
            Node p;
            final LinkedTransferQueue<E> q = this.queue;
            int b = batch;
            int n = (b <= 0) ? 1 : (b >= MAX_BATCH) ? MAX_BATCH : b + 1;
            if (!exhausted &&
                ((p = current) != null || (p = q.firstDataNode()) != null) &&
                p.next != null) {
                Object[] a = new Object[n];
                int i = 0;
                do {
                    Object e = p.item;
                    if (e != p && (a[i] = e) != null)
                        ++i;
                    if (p == (p = p.next))
                        p = q.firstDataNode();
                } while (p != null && i < n && p.isData);
                if ((current = p) == null)
                    exhausted = true;
                if (i > 0) {
                    batch = i;
                    return Spliterators.spliterator
                        (a, 0, i, Spliterator.ORDERED | Spliterator.NONNULL |
                         Spliterator.CONCURRENT);
                }
            }
            return null;
        }

        @SuppressWarnings("unchecked")
        public void forEachRemaining(Consumer<? super E> action) {
            Node p;
            if (action == null) throw new NullPointerException();
            final LinkedTransferQueue<E> q = this.queue;
            if (!exhausted &&
                ((p = current) != null || (p = q.firstDataNode()) != null)) {
                exhausted = true;
                do {
                    Object e = p.item;
                    if (e != null && e != p)
                        action.accept((E)e);
                    if (p == (p = p.next))
                        p = q.firstDataNode();
                } while (p != null && p.isData);
            }
        }

        @SuppressWarnings("unchecked")
        public boolean tryAdvance(Consumer<? super E> action) {
            Node p;
            if (action == null) throw new NullPointerException();
            final LinkedTransferQueue<E> q = this.queue;
            if (!exhausted &&
                ((p = current) != null || (p = q.firstDataNode()) != null)) {
                Object e;
                do {
                    if ((e = p.item) == p)
                        e = null;
                    if (p == (p = p.next))
                        p = q.firstDataNode();
                } while (e == null && p != null && p.isData);
                if ((current = p) == null)
                    exhausted = true;
                if (e != null) {
                    action.accept((E)e);
                    return true;
                }
            }
            return false;
        }

        public long estimateSize() { return Long.MAX_VALUE; }

        public int characteristics() {
            return Spliterator.ORDERED | Spliterator.NONNULL |
                Spliterator.CONCURRENT;
        }
    }

    /**
     * Returns a {@link Spliterator} over the elements in this queue.
     *
     * <p>The returned spliterator is
     * <a href="package-summary.html#Weakly"><i>weakly consistent</i></a>.
     *
     * <p>The {@code Spliterator} reports {@link Spliterator#CONCURRENT},
     * {@link Spliterator#ORDERED}, and {@link Spliterator#NONNULL}.
     *
     * @implNote
     * The {@code Spliterator} implements {@code trySplit} to permit limited
     * parallelism.
     *
     * @return a {@code Spliterator} over the elements in this queue
     * @since 1.8
     */
    public Spliterator<E> spliterator() {
        return new LTQSpliterator<E>(this);
    }

    /* -------------- Removal methods -------------- */

    /**
     * 前驱节点与已删除或者取消状态的s节点取消连接，将两个节点取消关联
     * todo wolfleong 这个删除逻辑有点迷, 啥情况才需要 SWEEP 投票, 啥时候不需要
     * Unsplices (now or later) the given deleted/cancelled node with
     * the given predecessor.
     *
     * @param pred a node that was at one time known to be the
     * predecessor of s, or null or s itself if s is/was at head
     * @param s the node to be unspliced
     */
    final void unsplice(Node pred, Node s) {
        //pred s的前驱节点或者为null或者为s自己（当s为头节点时）
        //s 取消或删除的节点

        // 清理s节点变量
        s.forgetContents(); // forget unneeded fields
        /*
         * See above for rationale. Briefly: if pred still points to
         * s, try to unlink s.  If s cannot be unlinked, because it is
         * trailing node or pred might be unlinked, and neither pred
         * nor s are head or offlist, add to sweepVotes, and if enough
         * votes have accumulated, sweep.
         */
        // 确认 pred 的 next 指向 s 即两者之间还有关联才处理
        // 如果 s 是头节点, 那么 pred 就等于 s, 这种情况没法删除
        if (pred != null && pred != s && pred.next == s) {
            Node n = s.next;
            // s的next为空表示s为尾结点
            // 如果 n == s 表示 s 已经被删除了
            // s 没被删除且 cas 删除 s 成功且 pred 已经被匹配才会进入 if
            if (n == null ||
                (n != s && pred.casNext(s, n) && pred.isMatched())) {
                // 看是否是 head 或将要成为新的head，根据需要更新head指向第一个未匹配节点
                for (;;) {               // check if at, or could be, head
                    Node h = head;
                    // todo wolfleong 为什么 pred 是 h 或 s 是 h  就不需要投票 sweep 删除呢
                    if (h == pred || h == s || h == null)
                        return;          // at head or list empty
                    // 头节点未被匹配则跳出循环
                    if (!h.isMatched())
                        break;
                    // 到这说明h已经被匹配，需要更新head
                    Node hn = h.next;
                    // 头节点后继节点为空，验证队列为空
                    if (hn == null)
                        return;          // now empty
                    // 头节点后继节点非头节点并且尝试更新头节点为后继节点
                    if (hn != h && casHead(h, hn))
                        //替换成功, 则清理原头节点
                        h.forgetNext();  // advance head
                }
                //上面没办法保证 n 完全脱离链表, 所以才需要下面进一步的操作

                // 如果 pred 没有离队 且 s 也没有离队
                if (pred.next != pred && s.next != s) { // recheck if offlist
                    // 解除前后节点链接失败则统计阈值处理
                    // 根据SWEEP_THRESHOLD阈值进行判断处理
                    for (;;) {           // sweep now if enough votes
                        int v = sweepVotes;
                        // 小于阈值则尝试将阈值加1
                        if (v < SWEEP_THRESHOLD) {
                            if (casSweepVotes(v, v + 1))
                                break;
                        }
                        //如果 sweepVotes >= SWEEP_THRESHOLD
                        else if (casSweepVotes(v, 0)) {
                            //通过sweep方法进行清理
                            sweep();
                            break;
                        }
                    }
                }
            }
        }
    }

    /**
     * 从头节点开始遍历清理匹配节点（取消的节点）的节点关联关系
     * Unlinks matched (typically cancelled) nodes encountered in a
     * traversal from head.
     */
    private void sweep() {
        // 从头节点开始，p开始为头节点，s为p的后继节点
        for (Node p = head, s, n; p != null && (s = p.next) != null; ) {
            // s为未匹配的节点，开始遍历下一个
            if (!s.isMatched())
                // Unmatched nodes are never self-linked
                p = s;
            // s已经被匹配了，如果s为尾节点，遍历完了，终止
            else if ((n = s.next) == null) // trailing node is pinned
                break;
            //s的next指向自己，说明s已经离队
            else if (s == n)    // stale
                // 从头重新开始
                // No need to also check for p == s, since that implies s == n
                p = head;
            else
                // 更新p的next
                p.casNext(s, n);
        }
    }

    /**
     * Main implementation of remove(Object)
     */
    private boolean findAndRemove(Object e) {
        if (e != null) {
            for (Node pred = null, p = head; p != null; ) {
                Object item = p.item;
                if (p.isData) {
                    if (item != null && item != p && e.equals(item) &&
                        p.tryMatchData()) {
                        unsplice(pred, p);
                        return true;
                    }
                }
                else if (item == null)
                    break;
                pred = p;
                if ((p = p.next) == pred) { // stale
                    pred = null;
                    p = head;
                }
            }
        }
        return false;
    }

    /**
     * 默认构造器
     * Creates an initially empty {@code LinkedTransferQueue}.
     */
    public LinkedTransferQueue() {
    }

    /**
     * 从集合中创建的构造器
     * Creates a {@code LinkedTransferQueue}
     * initially containing the elements of the given collection,
     * added in traversal order of the collection's iterator.
     *
     * @param c the collection of elements to initially contain
     * @throws NullPointerException if the specified collection or any
     *         of its elements are null
     */
    public LinkedTransferQueue(Collection<? extends E> c) {
        this();
        addAll(c);
    }

    /**
     * Inserts the specified element at the tail of this queue.
     * As the queue is unbounded, this method will never block.
     *
     * @throws NullPointerException if the specified element is null
     */
    public void put(E e) {
        // 异步模式，不会阻塞，不会超时
        // 因为是放元素，单链表存储，会一直往后加
        xfer(e, true, ASYNC, 0);
    }

    /**
     * Inserts the specified element at the tail of this queue.
     * As the queue is unbounded, this method will never block or
     * return {@code false}.
     *
     * @return {@code true} (as specified by
     *  {@link java.util.concurrent.BlockingQueue#offer(Object,long,TimeUnit)
     *  BlockingQueue.offer})
     * @throws NullPointerException if the specified element is null
     */
    public boolean offer(E e, long timeout, TimeUnit unit) {
        xfer(e, true, ASYNC, 0);
        return true;
    }

    /**
     * Inserts the specified element at the tail of this queue.
     * As the queue is unbounded, this method will never return {@code false}.
     *
     * @return {@code true} (as specified by {@link Queue#offer})
     * @throws NullPointerException if the specified element is null
     */
    public boolean offer(E e) {
        xfer(e, true, ASYNC, 0);
        return true;
    }

    /**
     * Inserts the specified element at the tail of this queue.
     * As the queue is unbounded, this method will never throw
     * {@link IllegalStateException} or return {@code false}.
     *
     * @return {@code true} (as specified by {@link Collection#add})
     * @throws NullPointerException if the specified element is null
     */
    public boolean add(E e) {
        xfer(e, true, ASYNC, 0);
        return true;
    }

    /**
     * Transfers the element to a waiting consumer immediately, if possible.
     *
     * <p>More precisely, transfers the specified element immediately
     * if there exists a consumer already waiting to receive it (in
     * {@link #take} or timed {@link #poll(long,TimeUnit) poll}),
     * otherwise returning {@code false} without enqueuing the element.
     *
     * @throws NullPointerException if the specified element is null
     */
    public boolean tryTransfer(E e) {
        // 立刻尝试匹配返回，不进行任何等待操作，xfer源码部分有判断这个标识
        return xfer(e, true, NOW, 0) == null;
    }

    /**
     * Transfers the element to a consumer, waiting if necessary to do so.
     *
     * <p>More precisely, transfers the specified element immediately
     * if there exists a consumer already waiting to receive it (in
     * {@link #take} or timed {@link #poll(long,TimeUnit) poll}),
     * else inserts the specified element at the tail of this queue
     * and waits until the element is received by a consumer.
     *
     * @throws NullPointerException if the specified element is null
     */
    public void transfer(E e) throws InterruptedException {
        //由于中断操作导致失败会抛错
        if (xfer(e, true, SYNC, 0) != null) {
            Thread.interrupted(); // failure possible only due to interrupt
            throw new InterruptedException();
        }
    }

    /**
     * Transfers the element to a consumer if it is possible to do so
     * before the timeout elapses.
     *
     * <p>More precisely, transfers the specified element immediately
     * if there exists a consumer already waiting to receive it (in
     * {@link #take} or timed {@link #poll(long,TimeUnit) poll}),
     * else inserts the specified element at the tail of this queue
     * and waits until the element is received by a consumer,
     * returning {@code false} if the specified wait time elapses
     * before the element can be transferred.
     *
     * @throws NullPointerException if the specified element is null
     */
    public boolean tryTransfer(E e, long timeout, TimeUnit unit)
        throws InterruptedException {
        // 尝试匹配未匹配等待超时时间才返回，如被中断则抛错
        if (xfer(e, true, TIMED, unit.toNanos(timeout)) == null)
            return true;
        if (!Thread.interrupted())
            return false;
        throw new InterruptedException();
    }

    public E take() throws InterruptedException {
        // 同步模式，会阻塞直到取到元素
        E e = xfer(null, false, SYNC, 0);
        if (e != null)
            return e;
        Thread.interrupted();
        throw new InterruptedException();
    }

    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        E e = xfer(null, false, TIMED, unit.toNanos(timeout));
        if (e != null || !Thread.interrupted())
            return e;
        throw new InterruptedException();
    }

    public E poll() {
        return xfer(null, false, NOW, 0);
    }

    /**
     * @throws NullPointerException     {@inheritDoc}
     * @throws IllegalArgumentException {@inheritDoc}
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
     * @throws NullPointerException     {@inheritDoc}
     * @throws IllegalArgumentException {@inheritDoc}
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

    /**
     * Returns an iterator over the elements in this queue in proper sequence.
     * The elements will be returned in order from first (head) to last (tail).
     *
     * <p>The returned iterator is
     * <a href="package-summary.html#Weakly"><i>weakly consistent</i></a>.
     *
     * @return an iterator over the elements in this queue in proper sequence
     */
    public Iterator<E> iterator() {
        return new Itr();
    }

    public E peek() {
        return firstDataItem();
    }

    /**
     * Returns {@code true} if this queue contains no elements.
     *
     * @return {@code true} if this queue contains no elements
     */
    public boolean isEmpty() {
        for (Node p = head; p != null; p = succ(p)) {
            if (!p.isMatched())
                return !p.isData;
        }
        return true;
    }

    public boolean hasWaitingConsumer() {
        return firstOfMode(false) != null;
    }

    /**
     * Returns the number of elements in this queue.  If this queue
     * contains more than {@code Integer.MAX_VALUE} elements, returns
     * {@code Integer.MAX_VALUE}.
     *
     * <p>Beware that, unlike in most collections, this method is
     * <em>NOT</em> a constant-time operation. Because of the
     * asynchronous nature of these queues, determining the current
     * number of elements requires an O(n) traversal.
     *
     * @return the number of elements in this queue
     */
    public int size() {
        return countOfMode(true);
    }

    public int getWaitingConsumerCount() {
        return countOfMode(false);
    }

    /**
     * Removes a single instance of the specified element from this queue,
     * if it is present.  More formally, removes an element {@code e} such
     * that {@code o.equals(e)}, if this queue contains one or more such
     * elements.
     * Returns {@code true} if this queue contained the specified element
     * (or equivalently, if this queue changed as a result of the call).
     *
     * @param o element to be removed from this queue, if present
     * @return {@code true} if this queue changed as a result of the call
     */
    public boolean remove(Object o) {
        return findAndRemove(o);
    }

    /**
     * Returns {@code true} if this queue contains the specified element.
     * More formally, returns {@code true} if and only if this queue contains
     * at least one element {@code e} such that {@code o.equals(e)}.
     *
     * @param o object to be checked for containment in this queue
     * @return {@code true} if this queue contains the specified element
     */
    public boolean contains(Object o) {
        if (o == null) return false;
        for (Node p = head; p != null; p = succ(p)) {
            Object item = p.item;
            if (p.isData) {
                if (item != null && item != p && o.equals(item))
                    return true;
            }
            else if (item == null)
                break;
        }
        return false;
    }

    /**
     * Always returns {@code Integer.MAX_VALUE} because a
     * {@code LinkedTransferQueue} is not capacity constrained.
     *
     * @return {@code Integer.MAX_VALUE} (as specified by
     *         {@link java.util.concurrent.BlockingQueue#remainingCapacity()
     *         BlockingQueue.remainingCapacity})
     */
    public int remainingCapacity() {
        return Integer.MAX_VALUE;
    }

    /**
     * Saves this queue to a stream (that is, serializes it).
     *
     * @param s the stream
     * @throws java.io.IOException if an I/O error occurs
     * @serialData All of the elements (each an {@code E}) in
     * the proper order, followed by a null
     */
    private void writeObject(java.io.ObjectOutputStream s)
        throws java.io.IOException {
        s.defaultWriteObject();
        for (E e : this)
            s.writeObject(e);
        // Use trailing null as sentinel
        s.writeObject(null);
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
        for (;;) {
            @SuppressWarnings("unchecked")
            E item = (E) s.readObject();
            if (item == null)
                break;
            else
                offer(item);
        }
    }

    // Unsafe mechanics

    private static final sun.misc.Unsafe UNSAFE;
    private static final long headOffset;
    private static final long tailOffset;
    private static final long sweepVotesOffset;
    static {
        try {
            UNSAFE = sun.misc.Unsafe.getUnsafe();
            Class<?> k = LinkedTransferQueue.class;
            headOffset = UNSAFE.objectFieldOffset
                (k.getDeclaredField("head"));
            tailOffset = UNSAFE.objectFieldOffset
                (k.getDeclaredField("tail"));
            sweepVotesOffset = UNSAFE.objectFieldOffset
                (k.getDeclaredField("sweepVotes"));
        } catch (Exception e) {
            throw new Error(e);
        }
    }
}
