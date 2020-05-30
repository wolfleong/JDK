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

package java.util.concurrent.locks;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.LockSupport;

/**
 * A capability-based lock with three modes for controlling read/write
 * access.  The state of a StampedLock consists of a version and mode.
 * Lock acquisition methods return a stamp that represents and
 * controls access with respect to a lock state; "try" versions of
 * these methods may instead return the special value zero to
 * represent failure to acquire access. Lock release and conversion
 * methods require stamps as arguments, and fail if they do not match
 * the state of the lock. The three modes are:
 *
 * <ul>
 *
 *  <li><b>Writing.</b> Method {@link #writeLock} possibly blocks
 *   waiting for exclusive access, returning a stamp that can be used
 *   in method {@link #unlockWrite} to release the lock. Untimed and
 *   timed versions of {@code tryWriteLock} are also provided. When
 *   the lock is held in write mode, no read locks may be obtained,
 *   and all optimistic read validations will fail.  </li>
 *
 *  <li><b>Reading.</b> Method {@link #readLock} possibly blocks
 *   waiting for non-exclusive access, returning a stamp that can be
 *   used in method {@link #unlockRead} to release the lock. Untimed
 *   and timed versions of {@code tryReadLock} are also provided. </li>
 *
 *  <li><b>Optimistic Reading.</b> Method {@link #tryOptimisticRead}
 *   returns a non-zero stamp only if the lock is not currently held
 *   in write mode. Method {@link #validate} returns true if the lock
 *   has not been acquired in write mode since obtaining a given
 *   stamp.  This mode can be thought of as an extremely weak version
 *   of a read-lock, that can be broken by a writer at any time.  The
 *   use of optimistic mode for short read-only code segments often
 *   reduces contention and improves throughput.  However, its use is
 *   inherently fragile.  Optimistic read sections should only read
 *   fields and hold them in local variables for later use after
 *   validation. Fields read while in optimistic mode may be wildly
 *   inconsistent, so usage applies only when you are familiar enough
 *   with data representations to check consistency and/or repeatedly
 *   invoke method {@code validate()}.  For example, such steps are
 *   typically required when first reading an object or array
 *   reference, and then accessing one of its fields, elements or
 *   methods. </li>
 *
 * </ul>
 *
 * <p>This class also supports methods that conditionally provide
 * conversions across the three modes. For example, method {@link
 * #tryConvertToWriteLock} attempts to "upgrade" a mode, returning
 * a valid write stamp if (1) already in writing mode (2) in reading
 * mode and there are no other readers or (3) in optimistic mode and
 * the lock is available. The forms of these methods are designed to
 * help reduce some of the code bloat that otherwise occurs in
 * retry-based designs.
 *
 * <p>StampedLocks are designed for use as internal utilities in the
 * development of thread-safe components. Their use relies on
 * knowledge of the internal properties of the data, objects, and
 * methods they are protecting.  They are not reentrant, so locked
 * bodies should not call other unknown methods that may try to
 * re-acquire locks (although you may pass a stamp to other methods
 * that can use or convert it).  The use of read lock modes relies on
 * the associated code sections being side-effect-free.  Unvalidated
 * optimistic read sections cannot call methods that are not known to
 * tolerate potential inconsistencies.  Stamps use finite
 * representations, and are not cryptographically secure (i.e., a
 * valid stamp may be guessable). Stamp values may recycle after (no
 * sooner than) one year of continuous operation. A stamp held without
 * use or validation for longer than this period may fail to validate
 * correctly.  StampedLocks are serializable, but always deserialize
 * into initial unlocked state, so they are not useful for remote
 * locking.
 *
 * <p>The scheduling policy of StampedLock does not consistently
 * prefer readers over writers or vice versa.  All "try" methods are
 * best-effort and do not necessarily conform to any scheduling or
 * fairness policy. A zero return from any "try" method for acquiring
 * or converting locks does not carry any information about the state
 * of the lock; a subsequent invocation may succeed.
 *
 * <p>Because it supports coordinated usage across multiple lock
 * modes, this class does not directly implement the {@link Lock} or
 * {@link ReadWriteLock} interfaces. However, a StampedLock may be
 * viewed {@link #asReadLock()}, {@link #asWriteLock()}, or {@link
 * #asReadWriteLock()} in applications requiring only the associated
 * set of functionality.
 *
 * <p><b>Sample Usage.</b> The following illustrates some usage idioms
 * in a class that maintains simple two-dimensional points. The sample
 * code illustrates some try/catch conventions even though they are
 * not strictly needed here because no exceptions can occur in their
 * bodies.<br>
 *
 *  <pre>{@code
 * class Point {
 *   private double x, y;
 *   private final StampedLock sl = new StampedLock();
 *
 *   void move(double deltaX, double deltaY) { // an exclusively locked method
 *     long stamp = sl.writeLock();
 *     try {
 *       x += deltaX;
 *       y += deltaY;
 *     } finally {
 *       sl.unlockWrite(stamp);
 *     }
 *   }
 *
 *   double distanceFromOrigin() { // A read-only method
 *     long stamp = sl.tryOptimisticRead();
 *     double currentX = x, currentY = y;
 *     if (!sl.validate(stamp)) {
 *        stamp = sl.readLock();
 *        try {
 *          currentX = x;
 *          currentY = y;
 *        } finally {
 *           sl.unlockRead(stamp);
 *        }
 *     }
 *     return Math.sqrt(currentX * currentX + currentY * currentY);
 *   }
 *
 *   void moveIfAtOrigin(double newX, double newY) { // upgrade
 *     // Could instead start with optimistic, not read mode
 *     long stamp = sl.readLock();
 *     try {
 *       while (x == 0.0 && y == 0.0) {
 *         long ws = sl.tryConvertToWriteLock(stamp);
 *         if (ws != 0L) {
 *           stamp = ws;
 *           x = newX;
 *           y = newY;
 *           break;
 *         }
 *         else {
 *           sl.unlockRead(stamp);
 *           stamp = sl.writeLock();
 *         }
 *       }
 *     } finally {
 *       sl.unlock(stamp);
 *     }
 *   }
 * }}</pre>
 *
 * ReentrantReadWriteLock 这种读写分离锁，它做到了读与读之间不用等待.
 * 这种读写分离锁的缺点是，只有读读操作不会竞争锁，即读与读操作是并行的，而读写、写写都会竞争锁。很明显读写其实大部分情况也都可以不竞争锁的，这就是后来StampedLock的优化点.
 *
 * ReentrantReadWriteLock 在沒有任何读写锁时，才可以取得写入锁，这可用于实现了悲观读取。然而，如果读取很多，写入很少的情况下，
 * 使用 ReentrantReadWriteLock 可能会使写入线程遭遇饥饿问题，也就是写入线程无法竞争到锁定而一直处于等待状态。
 *
 *  总结：
 *    （1）StampedLock也是一种读写锁，它不是基于AQS实现的；
 *    （2）StampedLock相较于ReentrantReadWriteLock多了一种乐观读的模式，以及读锁转化为写锁的方法；
 *    （3）StampedLock的state存储的是版本号，确切地说是高24位存储的是版本号，写锁的释放会增加其版本号，读锁不会；
 *    （4）StampedLock的低7位存储的读锁被获取的次数，第8位存储的是写锁被获取的次数；
 *    （5）StampedLock不是可重入锁，因为只有第8位标识写锁被获取了，并不能重复获取；
 *    （6）StampedLock中获取锁的过程使用了大量的自旋操作，对于短任务的执行会比较高效，长任务的执行会浪费大量CPU；
 *    （7）StampedLock不能实现条件锁；
 *
 *  StampedLock 与 ReentrantReadWriteLock的对比 ？
 *    （1）两者都有获取读锁、获取写锁、释放读锁、释放写锁的方法，这是相同点；
 *    （2）两者的结构基本类似，都是使用state + CLH 队列；
 *    （3）前者的state分成三段，高24位存储版本号、低7位存储读锁被获取的次数、第8位存储写锁被获取的次数；
 *    （4）后者的state分成两段，高16位存储读锁被获取的次数，低16位存储写锁被获取的次数；
 *    （5）前者的CLH队列可以看成是变异的CLH队列，连续的读线程只有首个节点存储在队列中，其它的节点存储的首个节点的cowait栈中；
 *    （6）后者的CLH队列是正常的CLH队列，所有的节点都在这个队列中；
 *    （7）前者获取锁的过程中有判断首尾节点是否相同，也就是是不是快轮到自己了，如果是则不断自旋，所以适合执行短任务；
 *    （8）后者获取锁的过程中非公平模式下会做有限次尝试；
 *    （9）前者只有非公平模式，一上来就尝试获取锁；
 *    （10）前者唤醒读锁是一次性唤醒连续的读锁的，而且其它线程还会协助唤醒；
 *    （11）后者是一个接着一个地唤醒的；
 *    （12）前者有乐观读的模式，乐观读的实现是通过判断state的高25位是否有变化来实现的；
 *    （13）前者各种模式可以互转，类似tryConvertToXxx()方法；
 *    （14）前者写锁不可重入，后者写锁可重入；
 *    （15）前者无法实现条件锁，后者可以实现条件锁；
 *
 *  StampedLock 这个类太多流程没搞懂了????? 难度有点大????
 *  1. cowaiter 上面的节点取消后, 是怎么移出链表 ?
 *  2. cowaiter 的主节点 p 取消后, cowaiter 后面的节点是如何接替换上的?
 *  3. 链表整个流转流程没搞懂
 *
 *  看得我心累, 求大神指导 ????
 *
 * @since 1.8
 * @author Doug Lea
 */
public class StampedLock implements java.io.Serializable {
    /*
     * Algorithmic notes:
     *
     * The design employs elements of Sequence locks
     * (as used in linux kernels; see Lameter's
     * http://www.lameter.com/gelato2005.pdf
     * and elsewhere; see
     * Boehm's http://www.hpl.hp.com/techreports/2012/HPL-2012-68.html)
     * and Ordered RW locks (see Shirako et al
     * http://dl.acm.org/citation.cfm?id=2312015)
     *
     * Conceptually, the primary state of the lock includes a sequence
     * number that is odd when write-locked and even otherwise.
     * However, this is offset by a reader count that is non-zero when
     * read-locked.  The read count is ignored when validating
     * "optimistic" seqlock-reader-style stamps.  Because we must use
     * a small finite number of bits (currently 7) for readers, a
     * supplementary reader overflow word is used when the number of
     * readers exceeds the count field. We do this by treating the max
     * reader count value (RBITS) as a spinlock protecting overflow
     * updates.
     *
     * Waiters use a modified form of CLH lock used in
     * AbstractQueuedSynchronizer (see its internal documentation for
     * a fuller account), where each node is tagged (field mode) as
     * either a reader or writer. Sets of waiting readers are grouped
     * (linked) under a common node (field cowait) so act as a single
     * node with respect to most CLH mechanics.  By virtue of the
     * queue structure, wait nodes need not actually carry sequence
     * numbers; we know each is greater than its predecessor.  This
     * simplifies the scheduling policy to a mainly-FIFO scheme that
     * incorporates elements of Phase-Fair locks (see Brandenburg &
     * Anderson, especially http://www.cs.unc.edu/~bbb/diss/).  In
     * particular, we use the phase-fair anti-barging rule: If an
     * incoming reader arrives while read lock is held but there is a
     * queued writer, this incoming reader is queued.  (This rule is
     * responsible for some of the complexity of method acquireRead,
     * but without it, the lock becomes highly unfair.) Method release
     * does not (and sometimes cannot) itself wake up cowaiters. This
     * is done by the primary thread, but helped by any other threads
     * with nothing better to do in methods acquireRead and
     * acquireWrite.
     *
     * These rules apply to threads actually queued. All tryLock forms
     * opportunistically try to acquire locks regardless of preference
     * rules, and so may "barge" their way in.  Randomized spinning is
     * used in the acquire methods to reduce (increasingly expensive)
     * context switching while also avoiding sustained memory
     * thrashing among many threads.  We limit spins to the head of
     * queue. A thread spin-waits up to SPINS times (where each
     * iteration decreases spin count with 50% probability) before
     * blocking. If, upon wakening it fails to obtain lock, and is
     * still (or becomes) the first waiting thread (which indicates
     * that some other thread barged and obtained lock), it escalates
     * spins (up to MAX_HEAD_SPINS) to reduce the likelihood of
     * continually losing to barging threads.
     *
     * Nearly all of these mechanics are carried out in methods
     * acquireWrite and acquireRead, that, as typical of such code,
     * sprawl out because actions and retries rely on consistent sets
     * of locally cached reads.
     *
     * As noted in Boehm's paper (above), sequence validation (mainly
     * method validate()) requires stricter ordering rules than apply
     * to normal volatile reads (of "state").  To force orderings of
     * reads before a validation and the validation itself in those
     * cases where this is not already forced, we use
     * Unsafe.loadFence.
     *
     * The memory layout keeps lock state and queue pointers together
     * (normally on the same cache line). This usually works well for
     * read-mostly loads. In most other cases, the natural tendency of
     * adaptive-spin CLH locks to reduce memory contention lessens
     * motivation to further spread out contended locations, but might
     * be subject to future improvements.
     */

    private static final long serialVersionUID = -6001602636862214147L;

    //cpu 核数
    /** Number of processors, for spin control */
    private static final int NCPU = Runtime.getRuntime().availableProcessors();

    //获取锁失败入队之前的最大自旋次数（实际运行时并不一定是这个数） , 多核 1 << 6 =  64, 单核为 0
    /** Maximum number of retries before enqueuing on acquisition */
    private static final int SPINS = (NCPU > 1) ? 1 << 6 : 0;

    //头节点获取锁的最大自旋次数, 多核 1 << 10 = 1024, 单核 0
    /** Maximum number of retries before blocking at head on acquisition */
    private static final int HEAD_SPINS = (NCPU > 1) ? 1 << 10 : 0;

    //头节点再次阻塞前的最大自旋次数 , 多核 1 << 16 = 65536, 单核 0
    /** Maximum number of retries before re-blocking */
    private static final int MAX_HEAD_SPINS = (NCPU > 1) ? 1 << 16 : 0;

    //等待自旋锁溢出的周期数
    /** The period for yielding when waiting for overflow spinlock */
    private static final int OVERFLOW_YIELD_RATE = 7; // must be power 2 - 1

    //读线程的个数占有低7位
    /** The number of bits to use for reader count before overflowing */
    private static final int LG_READERS = 7;

    // 读线程个数每次增加的单位
    // Values for lock state and stamp operations
    private static final long RUNIT = 1L;
    //写线程个数所在的位置,  写状态标识 1000 0000, 也就是 128
    private static final long WBIT  = 1L << LG_READERS;
    //读线程个数所在的位置, 低7位都是 111 1111, 高 25 位都是 0
    private static final long RBITS = WBIT - 1L;
    //最大读线程个数,  111 1110 , 也就是 126
    private static final long RFULL = RBITS - 1L;
    //读线程个数和写线程个数的掩码, 也就是高 24 位都是 0, 低 8 位都是 1
    private static final long ABITS = RBITS | WBIT;
    // 读线程个数的反数，高25位全部为1, 低7位都为 0
    private static final long SBITS = ~RBITS; // note overlap with ABITS

    // state的初始值, 也就是 256, 第九位为 1 , 其他全为 0
    // Initial value for lock state; avoid failure value zero
    private static final long ORIGIN = WBIT << 1;

    //中断标识
    // Special value from cancelled acquire methods so caller can throw IE
    private static final long INTERRUPTED = 1L;

    //节点状态 等待/取消
    // Values for node status; order matters
    private static final int WAITING   = -1;
    private static final int CANCELLED =  1;

    //节点模型 读/写
    // Modes for nodes (int not boolean to allow arithmetic)
    private static final int RMODE = 0;
    private static final int WMODE = 1;

    //双向链表节点
    /** Wait nodes */
    static final class WNode {
        // 前一个节点
        volatile WNode prev;
        // 后一个节点
        volatile WNode next;
        // 连续的读线程所用的链表（实际是一个栈结果）, 新的读节点是头插入的
        volatile WNode cowait;    // list of linked readers
        // 阻塞的线程引用, 主要是用被唤醒的
        volatile Thread thread;   // non-null while possibly parked
        // 状态, 0, WAITING(-1), CANCELLED(1)
        volatile int status;      // 0, WAITING, or CANCELLED
        //当前节点是处于读锁模式还是写锁模式
        final int mode;           // RMODE or WMODE
        WNode(int m, WNode p) { mode = m; prev = p; }
    }

    // 队列的头节点
    /** Head of CLH queue */
    private transient volatile WNode whead;
    // 队列的尾节点
    /** Tail (last) of CLH queue */
    private transient volatile WNode wtail;

    // views
    //读锁的视图，不可重入，并且不支持condition
    transient ReadLockView readLockView;
    //写锁的视图，不可重入并且不支持condition
    transient WriteLockView writeLockView;
    //读写锁的视图
    transient ReadWriteLockView readWriteLockView;

    // stampedLock的状态，用于判断当前stampedLock是属于读锁还是写锁还是乐观锁
    // 低7位是读锁, 第8位是写锁, 剩下高24位是版本号
    /** Lock sequence/state */
    private transient volatile long state;
    //读锁溢出时，记录额外的读锁大小
    /** extra reader count when state read count saturated */
    private transient int readerOverflow;

    /**
     * 默认构造方法
     * Creates a new lock, initially in unlocked state.
     */
    public StampedLock() {
        //state的初始值为 ORIGIN（256），它的二进制是 1 0000 0000，也就是初始版本号。
        //state 默认状态
        state = ORIGIN;
    }

    /**
     * 获取排他锁，如果不能马上获取到，必要的时候会将其阻塞，writeLock方法不支持中断操作
     * Exclusively acquires the lock, blocking if necessary
     * until available.
     *
     * @return a stamp that can be used to unlock or convert mode
     */
    public long writeLock() {
        long s, next;  // bypass acquireWrite in fully unlocked case only
        //((s = state) & ABITS) == 0L 表示没有写锁也没有读锁, 也就是低 8 位都是 0
        //state & ABITS 如果等于0，尝试原子更新state的值加 WBITS, 如果成功则返回更新的值 next
        //如果有读锁或写锁或更新锁状态失败, 则调用 acquireWrite() 方法
        return ((((s = state) & ABITS) == 0L &&
                 U.compareAndSwapLong(this, STATE, s, next = s + WBIT)) ?
                next : acquireWrite(false, 0L));
    }

    /**
     * 尝试获取写锁, 获取不了则返回 0L
     * Exclusively acquires the lock if it is immediately available.
     *
     * @return a stamp that can be used to unlock or convert mode,
     * or zero if the lock is not available
     */
    public long tryWriteLock() {
        long s, next;
        return ((((s = state) & ABITS) == 0L &&
                 U.compareAndSwapLong(this, STATE, s, next = s + WBIT)) ?
                next : 0L);
    }

    /**
     * //超时的获取写锁，并且支持中断操作
     * Exclusively acquires the lock if it is available within the
     * given time and the current thread has not been interrupted.
     * Behavior under timeout and interruption matches that specified
     * for method {@link Lock#tryLock(long,TimeUnit)}.
     *
     * @param time the maximum time to wait for the lock
     * @param unit the time unit of the {@code time} argument
     * @return a stamp that can be used to unlock or convert mode,
     * or zero if the lock is not available
     * @throws InterruptedException if the current thread is interrupted
     * before acquiring the lock
     */
    public long tryWriteLock(long time, TimeUnit unit)
        throws InterruptedException {
        //将其时间转成纳秒
        long nanos = unit.toNanos(time);
        //如果不是中断状态
        if (!Thread.interrupted()) {
            long next, deadline;
            //非阻塞tryWriteLock获取写锁，如果能加锁成功直接返回
            if ((next = tryWriteLock()) != 0L)
                return next;
            //如果时间小于等于0直接返回，加写锁失败
            if (nanos <= 0L)
                return 0L;
            //System.nanoTime（）可能返回负，如果和传入的时间相加等于0，deadline等于1 todo 为什么等于 0 就给 1, 这里处理有什么意义?
            if ((deadline = System.nanoTime() + nanos) == 0L)
                deadline = 1L;
            //调用acquireWrite方法，如果超时，返回的结果不是中断的值INTERRUPTED,加锁成功，返回对应的Stamp值（state+WBIT）
            if ((next = acquireWrite(true, deadline)) != INTERRUPTED)
                return next;
        }
        //默认抛出中断异常
        throw new InterruptedException();
    }

    /**
     * 中断的获取写锁，获取不到写锁抛出中断异常
     * Exclusively acquires the lock, blocking if necessary
     * until available or the current thread is interrupted.
     * Behavior under interruption matches that specified
     * for method {@link Lock#lockInterruptibly()}.
     *
     * @return a stamp that can be used to unlock or convert mode
     * @throws InterruptedException if the current thread is interrupted
     * before acquiring the lock
     */
    public long writeLockInterruptibly() throws InterruptedException {
        long next;
        //当前线程没有被中断，并且调用acquireWrite方法不是返回INTERRUPTED中断标志位，否则抛出中断异常，如果返回的标志位是0，也表示获取写锁失败
        if (!Thread.interrupted() &&
            (next = acquireWrite(true, 0L)) != INTERRUPTED)
            return next;
        throw new InterruptedException();
    }

    /**
     * 获取非排它性锁，读锁，如果获取不到读锁，阻塞直到可用，并且该方法不支持中断操作
     * Non-exclusively acquires the lock, blocking if necessary
     * until available.
     *
     * @return a stamp that can be used to unlock or convert mode
     */
    public long readLock() {
        long s = state, next;  // bypass acquireRead on common uncontended case
        // 如果头结点和尾节点相等（因为如果StampedLock只有非排他性锁，读锁或者乐观读，队列中只存在一个相同的节点），并且目前的读锁个数小于126，
        // 然后cas进行state的加1操作，如果获取成功直接退出，否则执行acquireRead方法
        return ((whead == wtail && (s & ABITS) < RFULL &&
                 U.compareAndSwapLong(this, STATE, s, next = s + RUNIT)) ?
                next : acquireRead(false, 0L));
    }

    /**
     * 非阻塞的获取非排他性锁，读锁，如果获取成功直接返回stamp的long值，否则返回0
     * Non-exclusively acquires the lock if it is immediately available.
     *
     * @return a stamp that can be used to unlock or convert mode,
     * or zero if the lock is not available
     */
    public long tryReadLock() {
        //自旋
        for (;;) {
            long s, m, next;
            //如果目前StampedLock的状态为写锁状态，直接返回0，获取读锁失败
            if ((m = (s = state) & ABITS) == WBIT)
                return 0L;
            //如果当前状态处于读锁状态，并且读锁没有溢出
            else if (m < RFULL) {
                //使用cas操作使state进行加1操作，如果cas成功，直接返回next，否则重新进行循环
                if (U.compareAndSwapLong(this, STATE, s, next = s + RUNIT))
                    return next;
            }
            //如果读锁溢出，调用下面介绍的tryIncReaderOverflow方法，如果操作成功，直接返回，否则重新进行循环操作
            else if ((next = tryIncReaderOverflow(s)) != 0L)
                return next;
        }
    }

    /**
     * 超时的获取非排他性锁，读锁，并且支持中断操作
     * Non-exclusively acquires the lock if it is available within the
     * given time and the current thread has not been interrupted.
     * Behavior under timeout and interruption matches that specified
     * for method {@link Lock#tryLock(long,TimeUnit)}.
     *
     * @param time the maximum time to wait for the lock
     * @param unit the time unit of the {@code time} argument
     * @return a stamp that can be used to unlock or convert mode,
     * or zero if the lock is not available
     * @throws InterruptedException if the current thread is interrupted
     * before acquiring the lock
     */
    public long tryReadLock(long time, TimeUnit unit)
        throws InterruptedException {
        long s, m, next, deadline;
        //时间转纳秒
        long nanos = unit.toNanos(time);
        //如果当前线程没有被中断
        if (!Thread.interrupted()) {
            //并且当前StampedLock的状态不处于写锁状态
            if ((m = (s = state) & ABITS) != WBIT) {
                //并且读锁没有溢出，使用cas操作state加1，如果成功直接返回
                if (m < RFULL) {
                    if (U.compareAndSwapLong(this, STATE, s, next = s + RUNIT))
                        return next;
                }
                //如果读锁溢出，调用tryIncReaderOverflow方法
                else if ((next = tryIncReaderOverflow(s)) != 0L)
                    return next;
            }
            //如果超时时间小于等于0，直接返回0，获取读锁失败
            if (nanos <= 0L)
                return 0L;
            //如果System.nanoTime加上nanos等于0，将其deadline时间设置为1，因为System.nanoTime可能为负数 ???
            // todo wolfleong 这里有点蒙逼, 为什么要处理成 1L ?
            if ((deadline = System.nanoTime() + nanos) == 0L)
                deadline = 1L;
            //如果调用acquireRead方法返回不是中断的标志位INTERRUPTED,直接返回，next不等于0获取读锁成功，否则获取读锁失败
            if ((next = acquireRead(true, deadline)) != INTERRUPTED)
                return next;
        }
        //抛出中断异常
        throw new InterruptedException();
    }

    /**
     * 获取非排它性锁，读锁，如果获取不到读锁，阻塞直到可用，并且该方法支持中断操作
     * Non-exclusively acquires the lock, blocking if necessary
     * until available or the current thread is interrupted.
     * Behavior under interruption matches that specified
     * for method {@link Lock#lockInterruptibly()}.
     *
     * @return a stamp that can be used to unlock or convert mode
     * @throws InterruptedException if the current thread is interrupted
     * before acquiring the lock
     */
    public long readLockInterruptibly() throws InterruptedException {
        long next;
        //如果当前线程没有被中断，并且调用acquireRead方法没有返回被中断的标志位INTERRUPTED回来,直接退出，next值不等于0获取读锁成功
        if (!Thread.interrupted() &&
            (next = acquireRead(true, 0L)) != INTERRUPTED)
            return next;
        //抛出被中断异常
        throw new InterruptedException();
    }

    /**
     * 乐观读的获取
     * Returns a stamp that can later be validated, or zero
     * if exclusively locked.
     *
     * @return a stamp, or zero if exclusively locked
     */
    public long tryOptimisticRead() {
        long s;
        //如果当前state不处于写模式返回 s&SBITS ,否则返回0失败
        //如果没有写锁，就返回state的高25位，这里把写所在位置一起返回了，是为了后面检测数据有没有被写过
        return (((s = state) & WBIT) == 0L) ? (s & SBITS) : 0L;
    }

    /**
     * 乐观读的校验
     * Returns true if the lock has not been exclusively acquired
     * since issuance of the given stamp. Always returns false if the
     * stamp is zero. Always returns true if the stamp represents a
     * currently held lock. Invoking this method with a value not
     * obtained from {@link #tryOptimisticRead} or a locking method
     * for this lock has no defined effect or result.
     *
     * @param stamp a stamp
     * @return {@code true} if the lock has not been exclusively acquired
     * since issuance of the given stamp; else false
     */
    public boolean validate(long stamp) {
        //在校验逻辑之前，会通过Unsafe的loadFence方法加入一个load内存屏障，
        // 目的是避免 copy 变量到工作内存中和 StampedLock.validate 中锁状态校验运算发生重排序导致锁状态校验不准确的问题
        // 强制加入内存屏障，刷新数据 todo wolfleong 为了避免那个变量重排序或那个变量刷新??? stamp ?
        U.loadFence();
        //如果传入进来的stamp & SBITS和state & SBITS相等
        return (stamp & SBITS) == (state & SBITS);
    }

    /**
     * 释放写锁
     * If the lock state matches the given stamp, releases the
     * exclusive lock.
     *
     * @param stamp a stamp returned by a write-lock operation
     * @throws IllegalMonitorStateException if the stamp does
     * not match the current state of this lock
     */
    public void unlockWrite(long stamp) {
        WNode h;
        // 检查版本号不对或没有写锁, 则抛异常
        if (state != stamp || (stamp & WBIT) == 0L)
            throw new IllegalMonitorStateException();
        // 这行代码实际有两个作用：
        // 1. 更新版本号加1
        // 2. 释放写锁
        // stamp + WBIT实际会把state的第8位置为0，也就相当于释放了写锁
        // 同时会进1，也就是高24位整体加1了 todo 为什么版本号会 变 0, 溢出?

        //释放写锁为什么不直接减去stamp，再加上ORIGIN，而是(stamp += WBIT) == 0L ? ORIGIN : stamp 来释放写锁，
        // 位操作表示如下： stamp += WBIT  即0010 0000 0000 = 0001 1000 0000 + 0000 1000 0000  这一步操作是重点！！！
        // 写锁的释放并不是像 ReentrantReadWriteLock 一样 +1 然后 -1，而是通过再次加 0000 1000 0000 来使高位每次都产生变化，为什么要这样做？
        // 直接减掉 0000 1000 0000 不就可以了吗？这就是为了后面乐观锁做铺垫，让每次写锁都留下痕迹。
        // 大家知道 cas ABA 的问题，字母A变化为B能看到变化，如果在一段时间内从A变到B然后又变到A，在内存中自会显示A，而不能记录变化的过程。
        // 在StampedLock中就是通过每次对高位加0000 1000 0000来达到记录写锁操作的过程，
        // 可以通过下面的步骤理解：
        //  第一次获取写锁: 0001 0000 0000 + 0000 1000 0000 = 0001 1000 0000
        //  第一次释放写锁: 0001 1000 0000 + 0000 1000 0000 = 0010 0000 0000
        //  第二次获取写锁: 0010 0000 0000 + 0000 1000 0000 = 0010 1000 0000
        //  第二次释放写锁: 0010 1000 0000 + 0000 1000 0000 = 0011 0000 0000
        //  第n次获取写锁: 1110 0000 0000 + 0000 1000 0000 = 1110 1000 0000
        //  第n次释放写锁: 1110 1000 0000 + 0000 1000 0000 = 1111 0000 0000
        // 可以看到第8位在获取和释放写锁时会产生变化，也就是说第8位是用来表示写锁状态的，前7位是用来表示读锁状态的，8位之后是用来表示写锁的获取次数的。
        // 这样就有效的解决了ABA问题，留下了每次写锁的记录，也为后面乐观锁检查变化提供了基础。
        state = (stamp += WBIT) == 0L ? ORIGIN : stamp;
        // 如果头节点不为空，并且状态不为0，调用release方法唤醒它的下一个节点
        if ((h = whead) != null && h.status != 0)
            release(h);
    }

    /**
     * 传入stamp进行读锁的释放
     * If the lock state matches the given stamp, releases the
     * non-exclusive lock.
     *
     * @param stamp a stamp returned by a read-lock operation
     * @throws IllegalMonitorStateException if the stamp does
     * not match the current state of this lock
     */
    public void unlockRead(long stamp) {
        long s, m; WNode h;
        //自旋
        for (;;) {
            //传进来的stamp和当前stampedLock的state状态不一致，或者当前处于乐观读、无锁状态，或者传进来的参数是乐观读、无锁的stamp，又或者当前状态为写锁状态，抛出非法的锁状态异常
            if (((s = state) & SBITS) != (stamp & SBITS) ||
                (stamp & ABITS) == 0L || (m = s & ABITS) == 0L || m == WBIT)
                throw new IllegalMonitorStateException();
            //如果当前StampedLock的state状态为读锁状态，并且读锁没有溢出
            if (m < RFULL) {
                //state使用cas进行减1操作
                if (U.compareAndSwapLong(this, STATE, s, s - RUNIT)) {
                    //如果减1操作成功，并且当前处于无锁状态，并且头结点不为空，并且头结点的状态为非0状态
                    if (m == RUNIT && (h = whead) != null && h.status != 0)
                        release(h);
                    break;
                }
            }
            //不等于0直接退出，否则重新进行循环
            else if (tryDecReaderOverflow(s) != 0L)
                break;
        }
    }

    /**
     * 读写锁都可以释放，如果锁状态匹配给定的 stamp, 释放锁的相应模式，
     * StampedLock的state处于乐观读时，不能调用此方法，因为乐观读不是锁
     * If the lock state matches the given stamp, releases the
     * corresponding mode of the lock.
     *
     * @param stamp a stamp returned by a lock operation
     * @throws IllegalMonitorStateException if the stamp does
     * not match the current state of this lock
     */
    public void unlock(long stamp) {
        long a = stamp & ABITS, m, s; WNode h;
        //stamp 相等的话
        while (((s = state) & SBITS) == (stamp & SBITS)) {
            //如果当前处于无锁状态，或者乐观读状态，直接退出，抛出异常
            if ((m = s & ABITS) == 0L)
                break;
            //如果当前StampedLock的状态为写模式
            else if (m == WBIT) {
                //传入进来的stamp不是写模式，直接退出，抛出异常
                if (a != m)
                    break;
                //释放写锁
                state = (s += WBIT) == 0L ? ORIGIN : s;
                //唤醒头结点的下一有效节点
                if ((h = whead) != null && h.status != 0)
                    release(h);
                //返回
                return;
            }
            //如果传入进来的状态是无锁模式，或者是乐观读模式，直接退出，抛出异常
            else if (a == 0L || a >= WBIT)
                break;
            //如果处于读锁模式，并且读锁没有溢出
            else if (m < RFULL) {
                //cas操作使StampedLock的state状态减1，释放一个读锁，失败时，重新循环
                if (U.compareAndSwapLong(this, STATE, s, s - RUNIT)) {
                    //如果当前读锁只有一个，并且头结点不为空，并且头结点的状态不为0
                    if (m == RUNIT && (h = whead) != null && h.status != 0)
                        release(h);
                    return;
                }
            }
            else if (tryDecReaderOverflow(s) != 0L)
                return;
        }
        //抛出异常
        throw new IllegalMonitorStateException();
    }

    /**
     * 升级为写锁
     * If the lock state matches the given stamp, performs one of
     * the following actions. If the stamp represents holding a write
     * lock, returns it.  Or, if a read lock, if the write lock is
     * available, releases the read lock and returns a write stamp.
     * Or, if an optimistic read, returns a write stamp only if
     * immediately available. This method returns zero in all other
     * cases.
     *
     * @param stamp a stamp
     * @return a valid write stamp, or zero on failure
     */
    public long tryConvertToWriteLock(long stamp) {
        long a = stamp & ABITS, m, s, next;
        //如果传入进来的stamp和当前StampedLock的状态相同
        while (((s = state) & SBITS) == (stamp & SBITS)) {
            //如果当前处于无锁状态，或者乐观读状态
            if ((m = s & ABITS) == 0L) {
                //传入进来的stamp不处于无锁或者乐观读状态，直接退出，升级失败
                if (a != 0L)
                    break;
                //获取state使用cas进行写锁的获取，如果获取写锁成功直接退出
                if (U.compareAndSwapLong(this, STATE, s, next = s + WBIT))
                    return next;
            }
            //如果当前stampedLock处于写锁状态
            else if (m == WBIT) {
                //传入进来的stamp不处于写锁状态，直接退出
                if (a != m)
                    break;
                //否则直接返回当前处于写锁状态的stamp
                return stamp;
            }
            //如果当前只有一个读锁，当前状态state使用cas进行减1加WBIT操作，将其读锁升级为写锁状态
            else if (m == RUNIT && a != 0L) {
                if (U.compareAndSwapLong(this, STATE, s,
                                         next = s - RUNIT + WBIT))
                    return next;
            }
            //否则直接退出
            else
                break;
        }
        return 0L;
    }

    /**
     * 升级为读锁
     * If the lock state matches the given stamp, performs one of
     * the following actions. If the stamp represents holding a write
     * lock, releases it and obtains a read lock.  Or, if a read lock,
     * returns it. Or, if an optimistic read, acquires a read lock and
     * returns a read stamp only if immediately available. This method
     * returns zero in all other cases.
     *
     * @param stamp a stamp
     * @return a valid read stamp, or zero on failure
     */
    public long tryConvertToReadLock(long stamp) {
        long a = stamp & ABITS, m, s, next; WNode h;
        //如果传入进来的stamp和当前StampedLock的状态相同
        while (((s = state) & SBITS) == (stamp & SBITS)) {
            //如果当前StampedLock处于无锁状态或者乐观读状态
            if ((m = s & ABITS) == 0L) {
                //如果传入进来的stamp不处于无锁或者乐观读状态，直接退出，升级读锁失败
                if (a != 0L)
                    break;
                //如果当前StampedLock处于读锁、无锁或者乐观读状态，并且读锁数没有溢出
                else if (m < RFULL) {
                    //state使用cas操作进行加1操作，如果操作成功直接退出
                    if (U.compareAndSwapLong(this, STATE, s, next = s + RUNIT))
                        return next;
                }
                //如果读锁溢出，调用tryIncReaderOverflow方法的溢出操作
                else if ((next = tryIncReaderOverflow(s)) != 0L)
                    return next;
            }
            //如果当前StampedLock的state处于写锁状态，如果锁升级成功，直接返回，否则重新循环
            else if (m == WBIT) {
                //传入进来的stamp不处于写锁状态
                if (a != m)
                    break;
                //释放写锁，上面在写锁的释放有解释，为什么不直接减去WBIT，再加上ORIGIN，释放写锁加读锁
                state = next = s + (WBIT + RUNIT);
                //如果头结点不为空，并且头结点的状态不等于0
                if ((h = whead) != null && h.status != 0)
                    //释放头结点的下一个有效节点
                    release(h);
                return next;
            }
            //如果传进来的stamp是读锁状态，直接返回传进来的stamp
            else if (a != 0L && a < WBIT)
                return stamp;
            else
                //否则直接退出
                break;
        }
        return 0L;
    }

    /**
     * 升级为乐观读
     * If the lock state matches the given stamp then, if the stamp
     * represents holding a lock, releases it and returns an
     * observation stamp.  Or, if an optimistic read, returns it if
     * validated. This method returns zero in all other cases, and so
     * may be useful as a form of "tryUnlock".
     *
     * @param stamp a stamp
     * @return a valid optimistic read stamp, or zero on failure
     */
    public long tryConvertToOptimisticRead(long stamp) {
        long a = stamp & ABITS, m, s, next; WNode h;
        //通过Unsafe的loadFence方法加入一个load内存屏障
        U.loadFence();
        for (;;) {
            //如果传入进来的stamp和当前的StampedLock的状态state不一致的话直接退出
            if (((s = state) & SBITS) != (stamp & SBITS))
                break;
            //如果当前处于无锁状态，或者乐观读状态
            if ((m = s & ABITS) == 0L) {
                //如果传入进来的stamp不是无锁或者乐观读状态直接退出
                if (a != 0L)
                    break;
                return s;
            }
            //如果当前处于写锁状态
            else if (m == WBIT) {
                //传入进来的stamp不是写锁状态，直接退出
                if (a != m)
                    break;
                //释放写锁
                state = next = (s += WBIT) == 0L ? ORIGIN : s;
                //如果头结点不为空，并且头结点的状态不为0
                if ((h = whead) != null && h.status != 0)
                    release(h);
                return next;
            }
            //如果传入的进来 stamp&255 等于0，或者大于写锁状态，直接退出
            else if (a == 0L || a >= WBIT)
                break;
            //如果读锁没有溢出，StampedLock的状态state使用cas进行-1操作
            else if (m < RFULL) {
                if (U.compareAndSwapLong(this, STATE, s, next = s - RUNIT)) {
                    if (m == RUNIT && (h = whead) != null && h.status != 0)
                        release(h);
                    return next & SBITS;
                }
            }
            //如果读锁溢出
            else if ((next = tryDecReaderOverflow(s)) != 0L)
                return next & SBITS;
        }
        return 0L;
    }

    /**
     * Releases the write lock if it is held, without requiring a
     * stamp value. This method may be useful for recovery after
     * errors.
     *
     * @return {@code true} if the lock was held, else false
     */
    public boolean tryUnlockWrite() {
        long s; WNode h;
        //如果当前StampedLock的锁状态state不是写锁状态，直接返回释放失败
        if (((s = state) & WBIT) != 0L) {
            //看上面解释 释放写锁为什么不直接减去stamp，再加上ORIGIN
            state = (s += WBIT) == 0L ? ORIGIN : s;
            //头结点不为空，并且头结点的状态不为0
            if ((h = whead) != null && h.status != 0)
                release(h);
            return true;
        }
        return false;
    }

    /**
     * 无需传入stamp进行释放读锁
     * Releases one hold of the read lock if it is held, without
     * requiring a stamp value. This method may be useful for recovery
     * after errors.
     *
     * @return {@code true} if the read lock was held, else false
     */
    public boolean tryUnlockRead() {
        long s, m; WNode h;
        //如果当前状态处于读锁状态，而不是乐观读状态，或者无锁状态，或者写锁状态
        while ((m = (s = state) & ABITS) != 0L && m < WBIT) {
            //如果当前state处于读锁状态，并且读锁没有溢出
            if (m < RFULL) {
                //stampedLock状态state使用cas进行减1操作，如果成功，跳出循环，直接返回
                if (U.compareAndSwapLong(this, STATE, s, s - RUNIT)) {
                    //如果操作成功，并且当前状态处于无锁状态，并且头结点不为空，及头结点的状态不为0
                    // m 是解锁前的 state 值
                    if (m == RUNIT && (h = whead) != null && h.status != 0)
                        release(h);
                    return true;
                }
            }
            //如果当前处于读锁模式，并且读锁溢出, 则处理溢出的读锁释放
            else if (tryDecReaderOverflow(s) != 0L)
                return true;
        }
        return false;
    }

    // status monitoring methods

    /**
     * Returns combined state-held and overflow read count for given
     * state s.
     */
    private int getReadLockCount(long s) {
        long readers;
        //如果当前的读锁有溢出
        if ((readers = s & RBITS) >= RFULL)
            //返回的锁个数为RFULL加上溢出的锁个数
            readers = RFULL + readerOverflow;
        return (int) readers;
    }

    /**
     * Returns {@code true} if the lock is currently held exclusively.
     *
     * @return {@code true} if the lock is currently held exclusively
     */
    public boolean isWriteLocked() {
        //判断当前是否处于写锁状态
        return (state & WBIT) != 0L;
    }

    /**
     * 判断当前是否处于读锁状态
     * Returns {@code true} if the lock is currently held non-exclusively.
     *
     * @return {@code true} if the lock is currently held non-exclusively
     */
    public boolean isReadLocked() {
        return (state & RBITS) != 0L;
    }

    /**
     * Queries the number of read locks held for this lock. This
     * method is designed for use in monitoring system state, not for
     * synchronization control.
     * @return the number of read locks held
     */
    public int getReadLockCount() {
        return getReadLockCount(state);
    }

    /**
     * Returns a string identifying this lock, as well as its lock
     * state.  The state, in brackets, includes the String {@code
     * "Unlocked"} or the String {@code "Write-locked"} or the String
     * {@code "Read-locks:"} followed by the current number of
     * read-locks held.
     *
     * @return a string identifying this lock, as well as its lock state
     */
    public String toString() {
        long s = state;
        return super.toString() +
            ((s & ABITS) == 0L ? "[Unlocked]" :
             (s & WBIT) != 0L ? "[Write-locked]" :
             "[Read-locks:" + getReadLockCount(s) + "]");
    }

    // views

    /**
     * Returns a plain {@link Lock} view of this StampedLock in which
     * the {@link Lock#lock} method is mapped to {@link #readLock},
     * and similarly for other methods. The returned Lock does not
     * support a {@link Condition}; method {@link
     * Lock#newCondition()} throws {@code
     * UnsupportedOperationException}.
     *
     * @return the lock
     */
    public Lock asReadLock() {
        ReadLockView v;
        return ((v = readLockView) != null ? v :
                (readLockView = new ReadLockView()));
    }

    /**
     * Returns a plain {@link Lock} view of this StampedLock in which
     * the {@link Lock#lock} method is mapped to {@link #writeLock},
     * and similarly for other methods. The returned Lock does not
     * support a {@link Condition}; method {@link
     * Lock#newCondition()} throws {@code
     * UnsupportedOperationException}.
     *
     * @return the lock
     */
    public Lock asWriteLock() {
        WriteLockView v;
        return ((v = writeLockView) != null ? v :
                (writeLockView = new WriteLockView()));
    }

    /**
     * Returns a {@link ReadWriteLock} view of this StampedLock in
     * which the {@link ReadWriteLock#readLock()} method is mapped to
     * {@link #asReadLock()}, and {@link ReadWriteLock#writeLock()} to
     * {@link #asWriteLock()}.
     *
     * @return the lock
     */
    public ReadWriteLock asReadWriteLock() {
        ReadWriteLockView v;
        return ((v = readWriteLockView) != null ? v :
                (readWriteLockView = new ReadWriteLockView()));
    }

    // view classes

    final class ReadLockView implements Lock {
        public void lock() { readLock(); }
        public void lockInterruptibly() throws InterruptedException {
            readLockInterruptibly();
        }
        public boolean tryLock() { return tryReadLock() != 0L; }
        public boolean tryLock(long time, TimeUnit unit)
            throws InterruptedException {
            return tryReadLock(time, unit) != 0L;
        }
        public void unlock() { unstampedUnlockRead(); }
        public Condition newCondition() {
            throw new UnsupportedOperationException();
        }
    }

    final class WriteLockView implements Lock {
        public void lock() { writeLock(); }
        public void lockInterruptibly() throws InterruptedException {
            writeLockInterruptibly();
        }
        public boolean tryLock() { return tryWriteLock() != 0L; }
        public boolean tryLock(long time, TimeUnit unit)
            throws InterruptedException {
            return tryWriteLock(time, unit) != 0L;
        }
        public void unlock() { unstampedUnlockWrite(); }
        public Condition newCondition() {
            throw new UnsupportedOperationException();
        }
    }

    final class ReadWriteLockView implements ReadWriteLock {
        public Lock readLock() { return asReadLock(); }
        public Lock writeLock() { return asWriteLock(); }
    }

    // Unlock methods without stamp argument checks for view classes.
    // Needed because view-class lock methods throw away stamps.

    final void unstampedUnlockWrite() {
        WNode h; long s;
        if (((s = state) & WBIT) == 0L)
            throw new IllegalMonitorStateException();
        state = (s += WBIT) == 0L ? ORIGIN : s;
        if ((h = whead) != null && h.status != 0)
            release(h);
    }

    final void unstampedUnlockRead() {
        for (;;) {
            long s, m; WNode h;
            if ((m = (s = state) & ABITS) == 0L || m >= WBIT)
                throw new IllegalMonitorStateException();
            else if (m < RFULL) {
                if (U.compareAndSwapLong(this, STATE, s, s - RUNIT)) {
                    if (m == RUNIT && (h = whead) != null && h.status != 0)
                        release(h);
                    break;
                }
            }
            else if (tryDecReaderOverflow(s) != 0L)
                break;
        }
    }

    private void readObject(java.io.ObjectInputStream s)
        throws java.io.IOException, ClassNotFoundException {
        s.defaultReadObject();
        state = ORIGIN; // reset to unlocked state
    }

    // internals

    /**
     * //记录溢出读锁的个数
     * Tries to increment readerOverflow by first setting state
     * access bits value to RBITS, indicating hold of spinlock,
     * then updating, then releasing.
     *
     * @param s a reader overflow stamp: (s & ABITS) >= RFULL
     * @return new stamp on success, else zero
     */
    private long tryIncReaderOverflow(long s) {
        // assert (s & ABITS) >= RFULL;
        //如果读锁已经满了
        if ((s & ABITS) == RFULL) {
            // 使用cas操作将其state设置为 127
            if (U.compareAndSwapLong(this, STATE, s, s | RBITS)) {
                //将其记录额外溢出的读锁个数进行加1操作
                ++readerOverflow;
                //将其state重新置为原来的值, 此时 s 是 126
                state = s;
                //126 的 s
                return s;
            }
        }
        // s 没有满或者 s 为 127
        //如果当前线程的随机数增加操作 & 7 等于0，将其线程进行让步操作
        else if ((LockSupport.nextSecondarySeed() &
                  OVERFLOW_YIELD_RATE) == 0)
            Thread.yield();
        //否则直接返回0失败
        return 0L;
    }

    /**
     * 读锁溢出进行减操作的方法
     * Tries to decrement readerOverflow.
     *
     * @param s a reader overflow stamp: (s & ABITS) >= RFULL
     * @return new stamp on success, else zero
     */
    private long tryDecReaderOverflow(long s) {
        // assert (s & ABITS) >= RFULL;
        //如果当前StampedLock的state的读模式已满，s&ABITS为126
        if ((s & ABITS) == RFULL) {
            //先将其state设置为127
            if (U.compareAndSwapLong(this, STATE, s, s | RBITS)) {
                int r; long next;
                //如果当前readerOverflow（记录溢出的读锁个数）大于0
                if ((r = readerOverflow) > 0) {
                    //readerOverflow做减1操作
                    readerOverflow = r - 1;
                    //将其next设置为原来的state
                    next = s;
                }
                //如果 readerOverflow == 0, 则表示 readerOverflow 还没有加过值
                else
                    //否则的话，将其当前的state做减1操作
                    next = s - RUNIT;
                //将其state设置为next
                 state = next;
                 return next;
            }
        }
        //如果当前线程随机数 & 7 要是等于 0，线程让步
        else if ((LockSupport.nextSecondarySeed() &
                  OVERFLOW_YIELD_RATE) == 0)
            Thread.yield();
        return 0L;
    }

    /**
     * 唤醒头结点的下一有效节点
     * Wakes up the successor of h (normally whead). This is normally
     * just h.next, but may require traversal from wtail if next
     * pointers are lagging. This may fail to wake up an acquiring
     * thread when one or more have been cancelled, but the cancel
     * methods themselves provide extra safeguards to ensure liveness.
     */
    private void release(WNode h) {
        //头节点不为 null
        if (h != null) {
            WNode q; Thread w;
            // 将其状态改为0
            U.compareAndSwapInt(h, WSTATUS, WAITING, 0);
            // 如果头节点的下一个节点为空或者其状态为已取消, 因为中间有些节点被删除或取消
            if ((q = h.next) == null || q.status == CANCELLED) {
                // 从尾节点向前遍历找到一个可用的节点
                for (WNode t = wtail; t != null && t != h; t = t.prev)
                    if (t.status <= 0)
                        q = t;
            }
            // 唤醒q节点所在的线程
            if (q != null && (w = q.thread) != null)
                U.unpark(w);
        }
    }

    /**
     * 获取写锁, 拿不到则阻塞直到拿到或超时或中断
     * See above for explanation.
     *
     * @param interruptible true if should check interrupts and if so
     * return INTERRUPTED
     * @param deadline if nonzero, the System.nanoTime value to timeout
     * at (and return zero)
     * @return next state, or INTERRUPTED
     */
    private long acquireWrite(boolean interruptible, long deadline) {
        // node为新增节点，p为尾节点（即将成为node的前置节点）
        WNode node = null, p;
        // 第一次自旋——入队
        for (int spins = -1;;) { // spin while enqueuing
            //m 表示当前锁的状态
            long m, s, ns;
            //再次尝试获取写锁
            //如果当前是没有锁
            if ((m = (s = state) & ABITS) == 0L) {
                // 如果获取写锁成功
                if (U.compareAndSwapLong(this, STATE, s, ns = s + WBIT))
                    //返回当前锁的状态
                    return ns;
            }
            // 如果自旋次数小于0，则计算自旋的次数
            else if (spins < 0)
                // 如果当前有写锁独占且队列无元素，说明快轮到自己了
                // 就自旋就行了，如果自旋完了还没轮到自己才入队
                // 则自旋次数为SPINS常量
                // 否则自旋次数为0
                spins = (m == WBIT && wtail == whead) ? SPINS : 0;
            else if (spins > 0) {
                // 当自旋次数大于0时，当前这次自旋随机减一次自旋次数
                // LockSupport.nextSecondarySeed() >= 0 永真
                if (LockSupport.nextSecondarySeed() >= 0)
                    --spins;
            }
            //执行到这里表示, 没有获取到写锁, 并且自旋次数为 0 了
            // 如果队列未初始化
            else if ((p = wtail) == null) { // initialize queue
                //构造写模式的头节点, 头节点代表拿到锁的线程
                WNode hd = new WNode(WMODE, null);
                //如果当前头结点设置成功，将其尾节点设置为头结点
                if (U.compareAndSwapObject(this, WHEAD, null, hd))
                    wtail = hd;
            }
            //如果当前节点为空，初始化当前节点，前驱节点为上一次的尾节点
            else if (node == null)
                //新建写模式的节点, 并且设置节点的前置节点为 p
                node = new WNode(WMODE, p);
            //如果链表尾节点有变化
            else if (node.prev != p)
                //更新新建的节点的前置节点为链表新的尾节点
                node.prev = p;
            // 尝试更新新增节点为新的尾节点成功，则退出循环
            else if (U.compareAndSwapObject(this, WTAIL, p, node)) {
                //更新 p.next 为新建的 node
                p.next = node;
                break;
            }
        }

        // 第二次自旋——阻塞并等待唤醒
        for (int spins = -1;;) {
            // h为头节点，np为新增节点的前置节点，pp为前前置节点，ps为前置节点的状态
            WNode h, np, pp; int ps;
            // 如果头节点等于前置节点，说明快轮到自己了, 先自旋等待一波
            if ((h = whead) == p) {
                // 初始化自旋次数
                if (spins < 0)
                    spins = HEAD_SPINS;
                //如果自旋次数没达到最大
                else if (spins < MAX_HEAD_SPINS)
                    // 增加自旋次数
                    spins <<= 1;
                // 第三次自旋，不断尝试获取写锁
                for (int k = spins;;) { // spin at head
                    long s, ns;
                    //如果当前状态没锁
                    if (((s = state) & ABITS) == 0L) {
                        //尝试获取写锁
                        if (U.compareAndSwapLong(this, STATE, s,
                                                 ns = s + WBIT)) {
                            // 尝试获取写锁成功，将 node 设置为新头节点并清除其前置节点(gc)
                            whead = node;
                            node.prev = null;
                            return ns;
                        }
                    }
                    //LockSupport.nextSecondarySeed() >= 0永真，k做自减操作, 直到 0 , 退出
                    else if (LockSupport.nextSecondarySeed() >= 0 &&
                             --k <= 0)
                        break;
                }
            }
            //头节点不是 p 的前置节点, 且头节点不为 null , 帮忙唤醒 cowait 的线程
            else if (h != null) { // help release stale waiters
                WNode c; Thread w;
                // 如果头节点的 cowait 链表（栈）不为空，唤醒里面的所有读节点
                while ((c = h.cowait) != null) {
                    if (U.compareAndSwapObject(h, WCOWAIT, c, c.cowait) &&
                        (w = c.thread) != null)
                        U.unpark(w);
                }
            }

            // 如果头节点没有变化
            if (whead == h) {
                // 如果尾节点有变化, 啥情况会出现呢, p 节点被删除了 todo wolfleong 这个场景是什么时候出现的???
                if ((np = node.prev) != p) {
                    //更新 node 的前置节点
                    if (np != null)
                        (p = np).next = node;   // stale
                }
                // 如果 p 节点状态为 0，则更新成WAITING
                else if ((ps = p.status) == 0)
                    U.compareAndSwapInt(p, WSTATUS, 0, WAITING);
                // 如果 p 节点状态为取消，则把它从链表中删除, 因为取消节点时并没有处理 pred, 只处理 next
                else if (ps == CANCELLED) {
                    //为什么要清除前面的取消节点呢, 主要是取消的时候没有删除 pred 引用
                    //前前节点不为 null, 则删除 p 节点
                    if ((pp = p.prev) != null) {
                        node.prev = pp;
                        //这里为什么要处理 next 呢, 在发现 cancelWaiter 方法设置完状态后, 但还没清除 next 前,
                        // 可以帮忙清除了
                        pp.next = node;
                    }
                }
                else {
                    // 有超时时间的处理
                    long time; // 0 argument to park means no timeout
                    //如果传入的时间为0，阻塞直到UnSafe.unpark唤醒
                    if (deadline == 0L)
                        time = 0L;
                    //如果时间已经超时，取消当前的等待节点
                    else if ((time = deadline - System.nanoTime()) <= 0L)
                        return cancelWaiter(node, node, false);

                    //当前线程
                    Thread wt = Thread.currentThread();
                    ////设置线程 Thread 的 parkblocker 属性，表示当前线程被谁阻塞，用于监控线程使用
                    U.putObject(wt, PARKBLOCKER, this);
                    //设置当前线程到节点中
                    node.thread = wt;
                    //p.status < 0 : 前一个节点是等待状态
                    //p != h || (state & ABITS) != 0L : 前一个节点不是头节点 或 前一个节点是头节点但还没释放锁
                    //whead == h : 头节点没有变化
                    //node.prev == p: 前一个节点也没变化
                    if (p.status < 0 && (p != h || (state & ABITS) != 0L) &&
                        whead == h && node.prev == p)
                        // 阻塞当前线程
                        U.park(false, time);  // emulate LockSupport.park

                    // 当前节点被唤醒后，清除线程
                    node.thread = null;
                    //当前线程的监控对象也置为空
                    U.putObject(wt, PARKBLOCKER, null);
                    //如果传入的参数 interruptible 为true，并且当前线程中断，取消当前节点
                    if (interruptible && Thread.interrupted())
                        return cancelWaiter(node, node, true);
                }
            }
        }
    }

    /**
     * 支持中断和超时的获取读锁.
     * 这个方法主要做的事情包括，如果头结点和尾节点相等，自旋一段时间，获取读锁，否则的话，如果队列为空，构建头尾节点，
     * 如果当前队列头节点和尾节点相等或者是当前StampedLock处于写锁状态，初始化当前节点，将其设置成尾节点。如果头结点的cowait队列不为空，
     * 唤醒cwait队列的线程，将其当前节点阻塞，直到被唤醒可用
     *
     * See above for explanation.
     *
     * @param interruptible true if should check interrupts and if so
     * return INTERRUPTED
     * @param deadline if nonzero, the System.nanoTime value to timeout
     * at (and return zero)
     * @return next state, or INTERRUPTED
     */
    private long acquireRead(boolean interruptible, long deadline) {
        // node为新增节点，p为尾节点
        WNode node = null, p;

        //如果头结点和尾节点相等，先让其线程自旋一段时间，如果队列为空初始化队列，生成头结点和尾节点。如果自旋操作没有获取到锁，
        // 并且头结点和尾节点相等，或者当前stampedLock的状态为写锁状态，将其当前节点加入队列中，如果加入当前队列失败，
        // 或者头结点和尾节点不相等，或者当前处于读锁状态，将其加入尾节点的cwait中，如果头结点的cwait节点不为空，并且线程也不为空，唤醒其cwait队列，阻塞当前节点

        // 第一段自旋——入队
        for (int spins = -1;;) {
            // 头节点
            WNode h;
            // 如果头节点等于尾节点
            // 说明没有排队的线程了，快轮到自己了，直接自旋不断尝试获取读锁
            if ((h = whead) == (p = wtail)) {
                // 第二段自旋——不断尝试获取读锁
                for (long m, s, ns;;) {
                    //如果当前StampedLock的state状态为读锁状态，并且读锁没有溢出，使用cas操作state进行加1操作
                    if ((m = (s = state) & ABITS) < RFULL ?
                        U.compareAndSwapLong(this, STATE, s, ns = s + RUNIT) :
                            //否则当前处于读锁，并且读锁溢出，调用 tryIncReaderOverflow 方法，看上面的此方法的介绍
                        (m < WBIT && (ns = tryIncReaderOverflow(s)) != 0L))
                        // 如果读线程个数达到了最大值，会溢出，返回的是0
                        return ns;
                    //如果当前状态处于写锁状态，或者大于写锁的状态(读锁和写锁都有)
                    else if (m >= WBIT) {
                        // spins 大于 0 表示自旋次数已经初始化
                        if (spins > 0) {
                            // 随机立减自旋次数
                            if (LockSupport.nextSecondarySeed() >= 0)
                                --spins;
                        }
                        else {
                            // 如果自旋次数为0了，看看是否要跳出循环
                            if (spins == 0) {
                                WNode nh = whead, np = wtail;
                                //如果头尾结点没有改变，那就是暂时还获取不了锁, 则退出自旋尝试
                                //如果头节点或尾节点变了, 则判断新的头尾节点是否相等，相等则相当于只有一个节点, 可以再自旋尝试一下,
                                // 不相等则表示前面至少有两个节点, 那么也没这么快获取锁, 则退出自旋
                                if ((nh == h && np == p) || (h = nh) != (p = np))
                                    break;
                            }
                            // 重置自旋次数
                            spins = SPINS;
                        }
                    }
                }
            }
            //如果尾节点为空，初始化队列
            if (p == null) { // initialize queue
                //构造头结点
                WNode hd = new WNode(WMODE, null);
                //使用cas构造队列的头结点，如果成功，将其尾节点设置为头结点
                if (U.compareAndSwapObject(this, WHEAD, null, hd))
                    wtail = hd;
            }
            //如果当前节点为空，构造当前节点
            else if (node == null)
                //创建节点, 节点模式为读
                node = new WNode(RMODE, p);
            //如果头结点和尾节点相等，或者当前StampedLock的state状态不为读锁状态
            //头节点肯定是写锁状态的
            else if (h == p || p.mode != RMODE) {
                //如果当前节点的前驱节点不是尾节点，重新设置当前节点的前驱节点
                if (node.prev != p)
                    node.prev = p;
                //将其当前节点加入队列中，并且当前节点做为尾节点，如果成功，直接退出循环操作
                else if (U.compareAndSwapObject(this, WTAIL, p, node)) {
                    p.next = node;
                    break;
                }
            }
            //来到这里表示, h != p && p.mode == RMODE , 也就是最后一个节点是读模式的并且不为头节点
            //将其当前节点加入尾节点的cowait队列中，相当于在 cowait 链表头部插入
            // 如果失败，将其当前节点的cowait置为null
            else if (!U.compareAndSwapObject(p, WCOWAIT,
                                             node.cowait = p.cowait, node))
                // 接着上一个elseif，这里肯定是尾节点为读模式了
                // 将当前节点加入到尾节点的cowait中，这是一个栈
                // 上面的CAS成功了是不会进入到这里来的
                node.cowait = null;
            //如果当前队列不为空，当前节点不为空，并且头结点和尾节点不相等，并且当前StampedLock的状态为读锁状态，
            // 并且当前节点cas加入尾节点的cowait队列中失败
            else {
                // 上面的 cas 成功了, 也就是当前节点 node 已经入队
                // 这里是 cowait 链表上的阻塞
                // 第三段自旋——阻塞等待读锁，直到唤醒时，前驱已为头节点
                for (;;) {
                    WNode pp, c; Thread w;
                    //如果头结点的cowait队列不为空，并且其线程也不为null，将其cowait队列唤醒
                    //唤醒一个之后, 便相当于递归唤醒整个链表
                    if ((h = whead) != null && (c = h.cowait) != null &&
                        U.compareAndSwapObject(h, WCOWAIT, c, c.cowait) &&
                        (w = c.thread) != null) // help release
                        //唤醒线程
                        U.unpark(w);

                    // p 代表尾节点
                    //如果当前头结点为尾节点的前驱节点，或者头尾节点相等，或者尾节点的前驱节点为空
                    // 这同样说明快轮到自己了
                    if (h == (pp = p.prev) || h == p || pp == null) {
                        long m, s, ns;
                        // 第四段自旋——又是不断尝试获取锁
                        do {
                            //判断当前状态是否处于读锁状态，如果是，并且读锁没有溢出，state进行cas加1操作
                            if ((m = (s = state) & ABITS) < RFULL ?
                                U.compareAndSwapLong(this, STATE, s,
                                                     ns = s + RUNIT) :
                                (m < WBIT &&
                                        //否则进行溢出操作
                                 (ns = tryIncReaderOverflow(s)) != 0L))
                                //获取成功, 则返回
                                return ns;
                        } while (m < WBIT); //当前StampedLock的state状态不是写模式，才能进行循环操作
                    }

                    //如果头结点没有改变，并且尾节点的前驱节点不变
                    if (whead == h && p.prev == pp) {
                        long time;
                        //p 是读节点的主节点
                        //如果尾节点的前驱节点为空，或者头尾节点相等，或者尾节点的状态为取消
                        // h == p 表示 cowaiter 链头已经拿到锁
                        // p.status > 0 表示链头已经取消
                        // pp == null todo wolfleong 什么时候 pp 为 null???
                        if (pp == null || h == p || p.status > 0) {
                            //将其当前节点设置为空，退出循环
                            node = null; // throw away
                            break;
                        }
                        //如果超时时间为0，会一直阻塞，直到调用UnSafe的unpark方法
                        if (deadline == 0L)
                            time = 0L;
                        //如果传入的超时时间已经过期，将当前节点取消
                        else if ((time = deadline - System.nanoTime()) <= 0L)
                            // 如果超时了，取消当前节点
                            return cancelWaiter(node, p, false);
                        // 获取当前线程
                        Thread wt = Thread.currentThread();
                        //设置当前线程被谁阻塞的监控对象
                        U.putObject(wt, PARKBLOCKER, this);
                        //记录线程到 node 中
                        node.thread = wt;
                        // 检测之前的条件未曾改变
                        if ((h != pp || (state & ABITS) == WBIT) &&
                            whead == h && p.prev == pp)
                            // 阻塞当前线程并等待被唤醒
                            U.park(false, time);

                        // 唤醒之后清除线程
                        node.thread = null;
                        //将其当前线程的监控对象置为空
                        U.putObject(wt, PARKBLOCKER, null);

                        // 如果中断了，取消当前节点
                        if (interruptible && Thread.interrupted())
                            return cancelWaiter(node, p, true);
                    }
                }
            }
        }

        // 只有第一个读线程会走到下面的for循环处，参考上面第一段自旋中有一个break，当第一个读线程入队的时候break出来的

        //阻塞当前线程，再阻塞当前线程之前，如果头节点和尾节点相等，让其自旋一段时间获取写锁。如果头结点不为空，释放头节点的cowait队列

        for (int spins = -1;;) {
            WNode h, np, pp; int ps;
            //如果头节点和尾节点相等, 也就是 cowaiter 链头已经拿到锁了或者是前一个节点已经拿到锁了
            if ((h = whead) == p) {
                //自旋的初始值
                if (spins < 0)
                    spins = HEAD_SPINS;
                //如果spins小于MAX_HEAD_SPINS
                else if (spins < MAX_HEAD_SPINS)
                    //将其spins进行扩大两倍
                    spins <<= 1;

                //自旋一段时间获取读锁
                for (int k = spins;;) { // spin at head
                    long m, s, ns;
                    //如果当前状态为无锁或者读锁模式
                    if ((m = (s = state) & ABITS) < RFULL ?
                            //state进行cas加1操作
                        U.compareAndSwapLong(this, STATE, s, ns = s + RUNIT) :
                            //如果当前state状态为读锁状态，并且读锁溢出，使用tryIncReaderOverflows方法进行溢出的读锁数累加
                        (m < WBIT && (ns = tryIncReaderOverflow(s)) != 0L)) {
                        //获取读锁成功
                        WNode c; Thread w;
                        //将其节点设置为头结点
                        whead = node;
                        //将其当前节点的前驱节点设置为 null
                        node.prev = null;
                        //如果当前节点的cowait队列不为空，循环的唤醒cowait队列中，线程不为空的线程
                        while ((c = node.cowait) != null) {
                            if (U.compareAndSwapObject(node, WCOWAIT,
                                                       c, c.cowait) &&
                                (w = c.thread) != null)
                                U.unpark(w);
                        }
                        return ns;
                    }
                    //如果当前状态为写状态，采取自减操作
                    else if (m >= WBIT &&
                             LockSupport.nextSecondarySeed() >= 0 && --k <= 0)
                        break;
                }
            }
            //如果头结点不为空
            else if (h != null) {
                WNode c; Thread w;
                //头结点的cowait队列不为空，循环的唤醒的cowait队列中，线程不为空的节点的线程
                while ((c = h.cowait) != null) {
                    if (U.compareAndSwapObject(h, WCOWAIT, c, c.cowait) &&
                        (w = c.thread) != null)
                        U.unpark(w);
                }
            }

            //如果头结点没改变
            if (whead == h) {
                //如果当前节点的前驱节点不等于尾节点 todo wolfleong 什么时候才会改 node.prev ???
                if ((np = node.prev) != p) {
                    //当前节点的前驱节点不为空
                    if (np != null)
                        //将其p设置为当前节点的前驱节点，如果前面的节点已经被唤醒，将p设置为当前节点的前驱节点，有可能其前驱节点就是头结点，重新进行循环操作
                        (p = np).next = node;   // stale
                }
                //如果当前节点的前驱节点的状态为0
                else if ((ps = p.status) == 0)
                    //将其当前节点的状态使用cas操作将其0替换为等待状态, 这个状态只有后一个节点来改的, 有点特别
                    U.compareAndSwapInt(p, WSTATUS, 0, WAITING);
                //如果当前节点的前驱节点已经取消，重新设置当前节点的前驱节点
                else if (ps == CANCELLED) {
                    if ((pp = p.prev) != null) {
                        node.prev = pp;
                        pp.next = node;
                    }
                }
                else {
                    long time;
                    //如果超时时间为0，永久阻塞，直到调用UnSafe的unpark()方法
                    if (deadline == 0L)
                        time = 0L;
                    //如果当前时间已经过期，取消当前节点
                    else if ((time = deadline - System.nanoTime()) <= 0L)
                        return cancelWaiter(node, node, false);
                    //获取当前线程
                    Thread wt = Thread.currentThread();
                    //将其当前线程的监控对象设置为当前StampedLock，监控此线程被那个对象阻塞
                    U.putObject(wt, PARKBLOCKER, this);
                    //将其当前线程设置为当前队列中的线程
                    node.thread = wt;
                    //如果当前节点的前驱节点为等待状态，并且头尾节点不相等或者当前StampedLock的状态为写锁状态，并且头结点不变，当前节点的前驱节点不变
                    if (p.status < 0 &&
                        (p != h || (state & ABITS) == WBIT) &&
                        whead == h && node.prev == p)
                        //调用UnSafe的park方法阻塞当前线程
                        U.park(false, time);

                    //被唤醒或节点不一致

                    //将其当前节点对应的线程置为空
                    node.thread = null;
                    //将其当前线程的监控对象置为空
                    U.putObject(wt, PARKBLOCKER, null);
                    //如果传入进来的参数interruptible为true，并且当前线程被中断
                    if (interruptible && Thread.interrupted())
                        //取消当前节点，cancelWaiter方法可以看上面对此方法的介绍
                        return cancelWaiter(node, node, true);
                }
            }
        }
    }

    /**
     * 取消等待节点
     * If node non-null, forces cancel status and unsplices it from
     * queue if possible and wakes up any cowaiters (of the node, or
     * group, as applicable), and in any case helps release current
     * first waiter if lock is free. (Calling with null arguments
     * serves as a conditional form of release, which is not currently
     * needed but may be needed under possible future cancellation
     * policies). This is a variant of cancellation methods in
     * AbstractQueuedSynchronizer (see its detailed explanation in AQS
     * internal documentation).
     *
     * @param node if nonnull, the waiter
     * @param group either node or the group node is cowaiting with
     * @param interrupted if already interrupted
     * @return INTERRUPTED if interrupted or Thread.interrupted, else zero
     */
    private long cancelWaiter(WNode node, WNode group, boolean interrupted) {
        //当 node 和 group 不一样时, node 是 cowaiter 的节点, group 是主节点
        //node和group为同一节点，要取消的节点，都不为空时
        if (node != null && group != null) {
            Thread w;
            //将其当前节点的状态设置为取消状态, 这个 node 有可能是主链上的, 有可能是 cowaiter 链上的
            node.status = CANCELLED;
            // unsplice cancelled nodes from group
            //如果当前要取消节点的cowait队列不为空，将其cowait队列中取消的节点去除, 如果当前 node 在 cowaiter 链表上, 则可以在这里去掉
            for (WNode p = group, q; (q = p.cowait) != null;) {
                //如果 cowait 节点的状态是 CANCELLED
                if (q.status == CANCELLED) {
                    //删除当前 cowait 节点
                    U.compareAndSwapObject(p, WCOWAIT, q, q.cowait);
                    //重置
                    p = group; // restart
                }
                else
                    //下一个 cowait
                    p = q;
            }

            //group和node为同一节点, 表示要取消的 node 是在主链表上的, 不是在 cowaiter 链表上
            if (group == node) {
                //唤醒状态没有取消的cowait队列中的节点 todo wolfleong 这里为什么又要唤醒
                for (WNode r = group.cowait; r != null; r = r.cowait) {
                    if ((w = r.thread) != null)
                        U.unpark(w);       // wake up uncancelled co-waiters
                }

                //下面主要处理主链的移除

                //将其当前取消节点的前驱节点的下一个节点设置为当前取消节点的next节点
                for (WNode pred = node.prev; pred != null; ) { // unsplice
                    WNode succ, pp;        // find valid successor

                    //如果当前取消节点的下一个节点为空或者是取消状态，从尾节点开始，寻找有效的节点
                    while ((succ = node.next) == null ||
                           succ.status == CANCELLED) {
                        //当前取消节点的后继节点
                        WNode q = null;    // find successor the slow way
                        //从尾节点开始寻找当前取消节点的下一个节点
                        for (WNode t = wtail; t != null && t != node; t = t.prev)
                            if (t.status != CANCELLED)
                                q = t;     // don't link if succ cancelled
                        //如果 succ == q , 则表示 succ == q ==  null
                        //如果 succ != q, 则表法 q 是非 CANCELLED 节点, 那么表示寻找到 node 的下一任后继节点, 那就进行替换
                        //如果当前取消节点的next节点和从尾节点寻找的节点相等，或者将其寻找的节点q设置为下一个节点成功
                        //注意: 这里的替换也是只处理了 next 这个引用, 并没有处理 pred 这个引用, 需要后继节点自己处理
                        if (succ == q ||   // ensure accurate successor
                            U.compareAndSwapObject(node, WNEXT,
                                                   succ, succ = q)) {
                            //succ == null 表示没找到要取消节点 node 的后继节点, 如果被取消节点 node 是尾节点, 则直接将 tail 指向 node 的前一个节点
                            //判断当前取消节点的曾经的下一个节点为空并且当前取消节点为尾节点
                            if (succ == null && node == wtail)
                                U.compareAndSwapObject(this, WTAIL, node, pred);
                            //退出
                            break;
                        }
                    }

                    //如果当前取消节点的前驱节点的下一节点为当前取消节点
                    if (pred.next == node) // unsplice pred link
                        //将其前驱节点的下一节点设置为当前取消节点的next有效节点, succ 是有可能为 null 的
                        //注意: 这里删除取消节点 node 的时候, 只改了 next 的引用, 并没有改 pred 的引用
                        U.compareAndSwapObject(pred, WNEXT, node, succ);

                    //唤醒当前取消节点的下一节点，观察其新的前驱节点
                    if (succ != null && (w = succ.thread) != null) {
                        succ.thread = null;
                        //唤醒取消节点的后继节点线程是为了改 pred 引用的
                        U.unpark(w);       // wake up succ to observe new pred
                    }
                    //如果当前取消节点的前驱节点状态不是取消状态，或者其前驱节点的前驱节点为空，直接退出循环
                    if (pred.status != CANCELLED || (pp = pred.prev) == null)
                        break;

                    //能执行下来, 表示 pred.status == CANCELLED && pred.prev != null

                    //重新设置当前取消节点的前驱节点
                    node.prev = pp;        // repeat if new pred wrong/cancelled
                    //重新设置pp的下一节点
                    U.compareAndSwapObject(pp, WNEXT, pred, succ);
                    //将其前驱节点设置为pp，重新循环
                    pred = pp;
                }
            }
        }

        //todo wolfleong 这段逻辑的意义在那里 ???
        //下面主要是唤醒下一个有效节点
        WNode h; // Possibly release first waiter
        //头节点不为空
        while ((h = whead) != null) {
            long s; WNode q; // similar to release() but check eligibility
            //头节点的下一节点为空或者是取消状态，从尾节点开始寻找有效的节点（包括等待状态，和运行状态）
            if ((q = h.next) == null || q.status == CANCELLED) {
                for (WNode t = wtail; t != null && t != h; t = t.prev)
                    if (t.status <= 0)
                        q = t;
            }
            //如果头节点没有改变
            if (h == whead) {
                //头节点的下一有效节点不为空，并且头节点的状态为0，并且当前 StampedLock 的不为写锁状态，
                // 并且头节点的下一节点为读模式，唤醒头结点的下一节点
                if (q != null && h.status == 0 &&
                    ((s = state) & ABITS) != WBIT && // waiter is eligible
                    (s == 0L || q.mode == RMODE))
                    //唤醒头结点的下一有效节点
                    release(h);
                break;
            }
        }
        //如果当前线程被中断或者传入进来的 interrupted 为 true，直接返回中断标志位，否则返回0
        return (interrupted || Thread.interrupted()) ? INTERRUPTED : 0L;
    }

    // Unsafe mechanics
    private static final sun.misc.Unsafe U;
    private static final long STATE;
    private static final long WHEAD;
    private static final long WTAIL;
    private static final long WNEXT;
    private static final long WSTATUS;
    private static final long WCOWAIT;
    private static final long PARKBLOCKER;

    static {
        try {
            U = sun.misc.Unsafe.getUnsafe();
            Class<?> k = StampedLock.class;
            Class<?> wk = WNode.class;
            STATE = U.objectFieldOffset
                (k.getDeclaredField("state"));
            WHEAD = U.objectFieldOffset
                (k.getDeclaredField("whead"));
            WTAIL = U.objectFieldOffset
                (k.getDeclaredField("wtail"));
            WSTATUS = U.objectFieldOffset
                (wk.getDeclaredField("status"));
            WNEXT = U.objectFieldOffset
                (wk.getDeclaredField("next"));
            WCOWAIT = U.objectFieldOffset
                (wk.getDeclaredField("cowait"));
            Class<?> tk = Thread.class;
            PARKBLOCKER = U.objectFieldOffset
                (tk.getDeclaredField("parkBlocker"));

        } catch (Exception e) {
            throw new Error(e);
        }
    }
}
