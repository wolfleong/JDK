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

package java.util.concurrent.atomic;
import java.util.function.LongBinaryOperator;
import java.util.function.DoubleBinaryOperator;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A package-local class holding common representation and mechanics
 * for classes supporting dynamic striping on 64bit values. The class
 * extends Number so that concrete subclasses must publicly do so.
 */
@SuppressWarnings("serial")
abstract class Striped64 extends Number {
    /*
     * This class maintains a lazily-initialized table of atomically
     * updated variables, plus an extra "base" field. The table size
     * is a power of two. Indexing uses masked per-thread hash codes.
     * Nearly all declarations in this class are package-private,
     * accessed directly by subclasses.
     *
     * Table entries are of class Cell; a variant of AtomicLong padded
     * (via @sun.misc.Contended) to reduce cache contention. Padding
     * is overkill for most Atomics because they are usually
     * irregularly scattered in memory and thus don't interfere much
     * with each other. But Atomic objects residing in arrays will
     * tend to be placed adjacent to each other, and so will most
     * often share cache lines (with a huge negative performance
     * impact) without this precaution.
     *
     * In part because Cells are relatively large, we avoid creating
     * them until they are needed.  When there is no contention, all
     * updates are made to the base field.  Upon first contention (a
     * failed CAS on base update), the table is initialized to size 2.
     * The table size is doubled upon further contention until
     * reaching the nearest power of two greater than or equal to the
     * number of CPUS. Table slots remain empty (null) until they are
     * needed.
     *
     * A single spinlock ("cellsBusy") is used for initializing and
     * resizing the table, as well as populating slots with new Cells.
     * There is no need for a blocking lock; when the lock is not
     * available, threads try other slots (or the base).  During these
     * retries, there is increased contention and reduced locality,
     * which is still better than alternatives.
     *
     * The Thread probe fields maintained via ThreadLocalRandom serve
     * as per-thread hash codes. We let them remain uninitialized as
     * zero (if they come in this way) until they contend at slot
     * 0. They are then initialized to values that typically do not
     * often conflict with others.  Contention and/or table collisions
     * are indicated by failed CASes when performing an update
     * operation. Upon a collision, if the table size is less than
     * the capacity, it is doubled in size unless some other thread
     * holds the lock. If a hashed slot is empty, and lock is
     * available, a new Cell is created. Otherwise, if the slot
     * exists, a CAS is tried.  Retries proceed by "double hashing",
     * using a secondary hash (Marsaglia XorShift) to try to find a
     * free slot.
     *
     * The table size is capped because, when there are more threads
     * than CPUs, supposing that each thread were bound to a CPU,
     * there would exist a perfect hash function mapping threads to
     * slots that eliminates collisions. When we reach capacity, we
     * search for this mapping by randomly varying the hash codes of
     * colliding threads.  Because search is random, and collisions
     * only become known via CAS failures, convergence can be slow,
     * and because threads are typically not bound to CPUS forever,
     * may not occur at all. However, despite these limitations,
     * observed contention rates are typically low in these cases.
     *
     * It is possible for a Cell to become unused when threads that
     * once hashed to it terminate, as well as in the case where
     * doubling the table causes no thread to hash to it under
     * expanded mask.  We do not try to detect or remove such cells,
     * under the assumption that for long-running instances, observed
     * contention levels will recur, so the cells will eventually be
     * needed again; and for short-lived ones, it does not matter.
     */

    /**
     * Padded variant of AtomicLong supporting only raw accesses plus CAS.
     *
     * JVM intrinsics note: It would be possible to use a release-only
     * form of CAS here, if it were provided.
     */
    @sun.misc.Contended static final class Cell {
        /**
         * //用来保存要累加的值
         */
        volatile long value;
        Cell(long x) { value = x; }
        final boolean cas(long cmp, long val) {
            //使用UNSAFE类的cas来更新value值
            return UNSAFE.compareAndSwapLong(this, valueOffset, cmp, val);
        }

        // Unsafe mechanics
        private static final sun.misc.Unsafe UNSAFE;
        //value在Cell类中存储位置的偏移量；
        private static final long valueOffset;
        //这个静态方法用于获取偏移量
        static {
            try {
                UNSAFE = sun.misc.Unsafe.getUnsafe();
                Class<?> ak = Cell.class;
                valueOffset = UNSAFE.objectFieldOffset
                    (ak.getDeclaredField("value"));
            } catch (Exception e) {
                throw new Error(e);
            }
        }
    }

    /**
     * cells 数组的最大长度
     */
    /** Number of CPUS, to place bound on table size */
    static final int NCPU = Runtime.getRuntime().availableProcessors();

    /**
     * 存放Cell的hash表，大小为2的幂。
     * Table of cells. When non-null, size is a power of 2.
     */
    transient volatile Cell[] cells;

    /**
     * 基础值
     * 1. 在没有竞争时会更新这个值；
     * 2. 在cells初始化的过程中，cells处于不可用的状态，这时候也会尝试将通过cas操作值累加到base。
     * Base value, used mainly when there is no contention, but also as
     * a fallback during table initialization races. Updated via CAS.
     */
    transient volatile long base;

    /**
     * 自旋锁，通过CAS操作加锁，用于保护创建或者扩展Cell表。
     * Spinlock (locked via CAS) used when resizing and/or creating Cells.
     */
    transient volatile int cellsBusy;

    /**
     * Package-private default constructor
     */
    Striped64() {
    }

    /**
     * CASes the base field.
     */
    final boolean casBase(long cmp, long val) {
        return UNSAFE.compareAndSwapLong(this, BASE, cmp, val);
    }

    /**
     * CASes the cellsBusy field from 0 to 1 to acquire lock.
     */
    final boolean casCellsBusy() {
        return UNSAFE.compareAndSwapInt(this, CELLSBUSY, 0, 1);
    }

    /**
     * 获取线程中缓存的 threadLocalRandomProbe 变量的值
     * Returns the probe value for the current thread.
     * Duplicated from ThreadLocalRandom because of packaging restrictions.
     */
    static final int getProbe() {
        return UNSAFE.getInt(Thread.currentThread(), PROBE);
    }

    /**
     * threadLocalRandomProbe 重新生成
     * Pseudo-randomly advances and records the given probe value for the
     * given thread.
     * Duplicated from ThreadLocalRandom because of packaging restrictions.
     */
    static final int advanceProbe(int probe) {
        probe ^= probe << 13;   // xorshift
        probe ^= probe >>> 17;
        probe ^= probe << 5;
        UNSAFE.putInt(Thread.currentThread(), PROBE, probe);
        return probe;
    }

    /**
     * Handles cases of updates involving initialization, resizing,
     * creating new Cells, and/or contention. See above for
     * explanation. This method suffers the usual non-modularity
     * problems of optimistic retry code, relying on rechecked sets of
     * reads.
     *
     * @param x the value (要累加的值)
     * @param fn the update function, or null for add (this convention
     * avoids the need for an extra field or function in LongAdder).
     * @param wasUncontended false if CAS failed before call (true 为没有竞争)
     */
    final void longAccumulate(long x, LongBinaryOperator fn,
                              boolean wasUncontended) {
        //获取当前线程的threadLocalRandomProbe值作为hash值,如果当前线程的threadLocalRandomProbe为0，
        //说明当前线程是第一次进入该方法，则强制设置线程的threadLocalRandomProbe为ThreadLocalRandom类的成员静态私有变量probeGenerator的值，后面会详细将hash值的生成;
        //另外需要注意，如果threadLocalRandomProbe=0，代表新的线程开始参与cell争用的情况
        //1.当前线程之前还没有参与过cells争用（也许cells数组还没初始化，进到当前方法来就是为了初始化cells数组后争用的）,是第一次执行base的cas累加操作失败；
        //2.或者是在执行add方法时，对cells某个位置的Cell的cas操作第一次失败，则将wasUncontended设置为false，那么这里会将其重新置为true；第一次执行操作失败；
        //凡是参与了cell争用操作的线程threadLocalRandomProbe都不为0；
        int h;
        if ((h = getProbe()) == 0) {
            //初始化ThreadLocalRandom;
            ThreadLocalRandom.current(); // force initialization
            //将h设置为0x9e3779b9
            h = getProbe();
            //设置未竞争标记为true, 因为凡是参与了cell争用操作的线程threadLocalRandomProbe都不为0
            wasUncontended = true;
        }
        //cas冲突标志，表示当前线程hash到的Cells数组的位置，做cas累加操作时与其它线程发生了冲突，cas失败；collide=true代表有冲突，collide=false代表无冲突
        boolean collide = false;                // True if last slot nonempty
        for (;;) {
            Cell[] as; Cell a; int n; long v;
            //这个主干if有三个分支
            //1.CASE1：处理cells数组已经正常初始化了的情况（这个if分支处理add方法的四个条件中的3和4）
            //2.CASE2：处理cells数组没有初始化或者长度为0的情况；（这个分支处理add方法的四个条件中的1和2）
            //3.CASE3：处理如果cell数组没有初始化，并且其它线程正在执行对cells数组初始化的操作，及cellbusy=1；则尝试将累加值通过cas累加到base上
            //先看主分支一
            if ((as = cells) != null && (n = as.length) > 0) {
                //CASE1：这个是处理add方法内部if分支的条件3：如果被hash到的位置为null，说明没有线程在这个位置设置过值，没有竞争，
                //可以直接使用，则用x值作为初始值创建一个新的Cell对象，对cells数组使用cellsBusy加锁，然后将这个Cell对象放到cells[m%cells.length]位置上
                if ((a = as[(n - 1) & h]) == null) {
                    //cellsBusy == 0 代表当前没有线程cells数组做修改
                    if (cellsBusy == 0) {       // Try to attach new Cell
                        //将要累加的x值作为初始值创建一个新的Cell对象，
                        Cell r = new Cell(x);   // Optimistically create
                        //如果cellsBusy=0无锁，则通过cas将cellsBusy设置为1加锁
                        if (cellsBusy == 0 && casCellsBusy()) {
                            //标记Cell是否创建成功并放入到cells数组被hash的位置上
                            boolean created = false;
                            try {               // Recheck under lock
                                Cell[] rs; int m, j;
                                //再次检查cells数组不为null，且长度不为空，且hash到的位置的Cell为null
                                if ((rs = cells) != null &&
                                    (m = rs.length) > 0 &&
                                    rs[j = (m - 1) & h] == null) {
                                    //将新的cell设置到该位置
                                    rs[j] = r;
                                    //设置创建成功
                                    created = true;
                                }
                            } finally {
                                //去掉锁
                                cellsBusy = 0;
                            }
                            //生成成功，跳出循环
                            if (created)
                                break;
                            //如果created为false，说明上面指定的cells数组的位置cells[m%cells.length]已经有其它线程设置了cell了，继续执行循环。
                            //为什么有可能不成功呢, 在 new Cell(x) 时, 其他线程有可能已经将当前 cell 初始化完成, 并且设置 cellsBusy 为 0 了
                            continue;           // Slot is now non-empty
                        }
                    }
                    //如果执行的当前行，代表cellsBusy=1，有线程正在更改cells数组，代表产生了冲突，将collide设置为false
                    collide = false;
                }
                //case2：如果add方法中条件4的通过cas设置cells[m%cells.length]位置的Cell对象中的value值设置为v+x失败,说明已经发生竞争，
                // 将wasUncontended设置为true，跳出内部的if判断，最后重新计算一个新的probe，然后重新执行循环;
                else if (!wasUncontended)       // CAS already known to fail
                    //设置未竞争标志位true，继续执行，后面会算一个新的probe值，然后重新执行循环。
                    wasUncontended = true;      // Continue after rehash
                //case3：新的争用线程参与争用的情况：处理刚进入当前方法时threadLocalRandomProbe=0的情况，也就是当前线程第一次参与cell争用的cas失败，
                // 这里会尝试将x值加到cells[m%cells.length]的value ，如果成功直接退出
                else if (a.cas(v = a.value, ((fn == null) ? v + x :
                                             fn.applyAsLong(v, x))))
                    break;
                //case4: case3处理新的线程争用执行失败了，这时如果cells数组的长度已经到了最大值（大于等于cup数量）, 不能再扩展了，
                // 或者是当前cells已经做了扩容，则将collide设置为false, 相当于重新计算prob的值再次尝试添加
                else if (n >= NCPU || cells != as)
                    collide = false;            // At max size or stale
                //case5:如果发生了冲突collide=false，则设置其为true；会在最后重新计算hash值后，进入下一次for循环
                else if (!collide)
                    //设置冲突标志，表示发生了冲突，需要再次生成hash，重试。
                    // 如果下次重试任然走到了该分支此时collide=true，表明已经重新 hash 了也无法添加, 则只能走下一个分支继续扩容
                    collide = true;
                //case6:扩容cells数组，新参与cell争用的线程两次均失败，且符合扩容条件，会执行该分支
                else if (cellsBusy == 0 && casCellsBusy()) {
                    try {
                        if (cells == as) {      // Expand table unless stale
                            Cell[] rs = new Cell[n << 1];
                            for (int i = 0; i < n; ++i)
                                rs[i] = as[i];
                            cells = rs;
                        }
                    } finally {
                        cellsBusy = 0;
                    }
                    collide = false;
                    continue;                   // Retry with expanded table
                }
                //为当前线程重新计算hash值
                h = advanceProbe(h);
            }
            //这个大的分支处理add方法中的条件1与条件2成立的情况，如果cell表还未初始化或者长度为0，先尝试获取cellsBusy锁。
            else if (cellsBusy == 0 && cells == as && casCellsBusy()) {
                //初始化结果是否成功
                boolean init = false;
                try {                           // Initialize table
                    //如果数组引用还没有变, 初始化cells数组，初始容量为2,并将x值通过hash&1，放到0个或第1个位置上
                    if (cells == as) {
                        Cell[] rs = new Cell[2];
                        rs[h & 1] = new Cell(x);
                        cells = rs;
                        //设置初始化成功
                        init = true;
                    }
                } finally {
                    //解锁
                    cellsBusy = 0;
                }
                //如果初始化成功, 则退出
                if (init)
                    break;
            }
            //如果以上操作都失败了，则尝试将值累加到base上；
            else if (casBase(v = base, ((fn == null) ? v + x :
                                        fn.applyAsLong(v, x))))
                //累加成功则退出
                break;                          // Fall back on using base
        }
    }

    /**
     * Same as longAccumulate, but injecting long/double conversions
     * in too many places to sensibly merge with long version, given
     * the low-overhead requirements of this class. So must instead be
     * maintained by copy/paste/adapt.
     */
    final void doubleAccumulate(double x, DoubleBinaryOperator fn,
                                boolean wasUncontended) {
        int h;
        if ((h = getProbe()) == 0) {
            ThreadLocalRandom.current(); // force initialization
            h = getProbe();
            wasUncontended = true;
        }
        boolean collide = false;                // True if last slot nonempty
        for (;;) {
            Cell[] as; Cell a; int n; long v;
            if ((as = cells) != null && (n = as.length) > 0) {
                if ((a = as[(n - 1) & h]) == null) {
                    if (cellsBusy == 0) {       // Try to attach new Cell
                        Cell r = new Cell(Double.doubleToRawLongBits(x));
                        if (cellsBusy == 0 && casCellsBusy()) {
                            boolean created = false;
                            try {               // Recheck under lock
                                Cell[] rs; int m, j;
                                if ((rs = cells) != null &&
                                    (m = rs.length) > 0 &&
                                    rs[j = (m - 1) & h] == null) {
                                    rs[j] = r;
                                    created = true;
                                }
                            } finally {
                                cellsBusy = 0;
                            }
                            if (created)
                                break;
                            continue;           // Slot is now non-empty
                        }
                    }
                    collide = false;
                }
                else if (!wasUncontended)       // CAS already known to fail
                    wasUncontended = true;      // Continue after rehash
                else if (a.cas(v = a.value,
                               ((fn == null) ?
                                Double.doubleToRawLongBits
                                (Double.longBitsToDouble(v) + x) :
                                Double.doubleToRawLongBits
                                (fn.applyAsDouble
                                 (Double.longBitsToDouble(v), x)))))
                    break;
                else if (n >= NCPU || cells != as)
                    collide = false;            // At max size or stale
                else if (!collide)
                    collide = true;
                else if (cellsBusy == 0 && casCellsBusy()) {
                    try {
                        if (cells == as) {      // Expand table unless stale
                            Cell[] rs = new Cell[n << 1];
                            for (int i = 0; i < n; ++i)
                                rs[i] = as[i];
                            cells = rs;
                        }
                    } finally {
                        cellsBusy = 0;
                    }
                    collide = false;
                    continue;                   // Retry with expanded table
                }
                h = advanceProbe(h);
            }
            else if (cellsBusy == 0 && cells == as && casCellsBusy()) {
                boolean init = false;
                try {                           // Initialize table
                    if (cells == as) {
                        Cell[] rs = new Cell[2];
                        rs[h & 1] = new Cell(Double.doubleToRawLongBits(x));
                        cells = rs;
                        init = true;
                    }
                } finally {
                    cellsBusy = 0;
                }
                if (init)
                    break;
            }
            else if (casBase(v = base,
                             ((fn == null) ?
                              Double.doubleToRawLongBits
                              (Double.longBitsToDouble(v) + x) :
                              Double.doubleToRawLongBits
                              (fn.applyAsDouble
                               (Double.longBitsToDouble(v), x)))))
                break;                          // Fall back on using base
        }
    }

    // Unsafe mechanics
    private static final sun.misc.Unsafe UNSAFE;
    private static final long BASE;
    private static final long CELLSBUSY;
    private static final long PROBE;
    static {
        try {
            UNSAFE = sun.misc.Unsafe.getUnsafe();
            Class<?> sk = Striped64.class;
            BASE = UNSAFE.objectFieldOffset
                (sk.getDeclaredField("base"));
            CELLSBUSY = UNSAFE.objectFieldOffset
                (sk.getDeclaredField("cellsBusy"));
            Class<?> tk = Thread.class;
            PROBE = UNSAFE.objectFieldOffset
                (tk.getDeclaredField("threadLocalRandomProbe"));
        } catch (Exception e) {
            throw new Error(e);
        }
    }

}
