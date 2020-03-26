/*
 * Copyright (c) 2003, 2013, Oracle and/or its affiliates. All rights reserved.
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

package java.util;

import java.util.function.Consumer;

/**
 * An unbounded priority {@linkplain Queue queue} based on a priority heap.
 * The elements of the priority queue are ordered according to their
 * {@linkplain Comparable natural ordering}, or by a {@link Comparator}
 * provided at queue construction time, depending on which constructor is
 * used.  A priority queue does not permit {@code null} elements.
 * A priority queue relying on natural ordering also does not permit
 * insertion of non-comparable objects (doing so may result in
 * {@code ClassCastException}).
 *
 * <p>The <em>head</em> of this queue is the <em>least</em> element
 * with respect to the specified ordering.  If multiple elements are
 * tied for least value, the head is one of those elements -- ties are
 * broken arbitrarily.  The queue retrieval operations {@code poll},
 * {@code remove}, {@code peek}, and {@code element} access the
 * element at the head of the queue.
 *
 * <p>A priority queue is unbounded, but has an internal
 * <i>capacity</i> governing the size of an array used to store the
 * elements on the queue.  It is always at least as large as the queue
 * size.  As elements are added to a priority queue, its capacity
 * grows automatically.  The details of the growth policy are not
 * specified.
 *
 * <p>This class and its iterator implement all of the
 * <em>optional</em> methods of the {@link Collection} and {@link
 * Iterator} interfaces.  The Iterator provided in method {@link
 * #iterator()} is <em>not</em> guaranteed to traverse the elements of
 * the priority queue in any particular order. If you need ordered
 * traversal, consider using {@code Arrays.sort(pq.toArray())}.
 *
 * <p><strong>Note that this implementation is not synchronized.</strong>
 * Multiple threads should not access a {@code PriorityQueue}
 * instance concurrently if any of the threads modifies the queue.
 * Instead, use the thread-safe {@link
 * java.util.concurrent.PriorityBlockingQueue} class.
 *
 * <p>Implementation note: this implementation provides
 * O(log(n)) time for the enqueuing and dequeuing methods
 * ({@code offer}, {@code poll}, {@code remove()} and {@code add});
 * linear time for the {@code remove(Object)} and {@code contains(Object)}
 * methods; and constant time for the retrieval methods
 * ({@code peek}, {@code element}, and {@code size}).
 *
 * <p>This class is a member of the
 * <a href="{@docRoot}/../technotes/guides/collections/index.html">
 * Java Collections Framework</a>.
 *
 * 优先级队列，是0个或多个元素的集合，集合中的每个元素都有一个权重值，每次出队都弹出优先级最大或最小的元素。
 * 一般来说，优先级队列使用堆来实现, 而堆又是用数组来实现。
 *
 * PS: 看代码前, 先补一下堆的基础知识.
 *
 * 堆的定义(堆是一种特殊的树)
 * （1）堆是一颗完全二叉树；
 * （2）堆中某个节点的值总是不大于（或不小于）其父节点的值。
 *
 * 当前节点(i)与父节点和各子节点的索引关系, 当 i=0 时没有父节点:
 *  - 父节点: (i - 1) / 2
 *  - 左子节点: (i + 1) * 2 - 1
 *  - 右子节点: (i + 1) * 2
 *
 * PriorityQueue 的总结:
 * （1）PriorityQueue是一个小顶堆, 并且是无界队列；
 * （2）PriorityQueue是非线程安全的；
 * （3）PriorityQueue不是有序的，只有堆顶存储着最小的元素；
 * （4）入队就是堆的插入元素的实现；
 * （5）出队就是堆的删除元素的实现；
 * （6）PriorityQueue是从数组的第0位开始存在；
 *
 * @since 1.5
 * @author Josh Bloch, Doug Lea
 * @param <E> the type of elements held in this collection
 */
public class PriorityQueue<E> extends AbstractQueue<E>
    implements java.io.Serializable {

    private static final long serialVersionUID = -7720805057305804111L;

    /**
     * 数组默认的初始化容量
     */
    private static final int DEFAULT_INITIAL_CAPACITY = 11;

    /**
     * 使用数组来构造堆，也就是优先级队列
     * Priority queue represented as a balanced binary heap: the two
     * children of queue[n] are queue[2*n+1] and queue[2*(n+1)].  The
     * priority queue is ordered by comparator, or by the elements'
     * natural ordering, if comparator is null: For each node n in the
     * heap and each descendant d of n, n <= d.  The element with the
     * lowest value is in queue[0], assuming the queue is nonempty.
     */
    transient Object[] queue; // non-private to simplify nested class access

    /**
     * 队列的大小
     * The number of elements in the priority queue.
     */
    private int size = 0;

    /**
     * 比较器
     * The comparator, or null if priority queue uses elements'
     * natural ordering.
     */
    private final Comparator<? super E> comparator;

    /**
     * 修改次数
     * The number of times this priority queue has been
     * <i>structurally modified</i>.  See AbstractList for gory details.
     */
    transient int modCount = 0; // non-private to simplify nested class access

    /**
     * 默认构造方法，初始化容量为11，比较器为空
     * Creates a {@code PriorityQueue} with the default initial
     * capacity (11) that orders its elements according to their
     * {@linkplain Comparable natural ordering}.
     */
    public PriorityQueue() {
        this(DEFAULT_INITIAL_CAPACITY, null);
    }

    /**
     * Creates a {@code PriorityQueue} with the specified initial
     * capacity that orders its elements according to their
     * {@linkplain Comparable natural ordering}.
     *
     * @param initialCapacity the initial capacity for this priority queue
     * @throws IllegalArgumentException if {@code initialCapacity} is less
     *         than 1
     */
    public PriorityQueue(int initialCapacity) {
        this(initialCapacity, null);
    }

    /**
     * Creates a {@code PriorityQueue} with the default initial capacity and
     * whose elements are ordered according to the specified comparator.
     *
     * @param  comparator the comparator that will be used to order this
     *         priority queue.  If {@code null}, the {@linkplain Comparable
     *         natural ordering} of the elements will be used.
     * @since 1.8
     */
    public PriorityQueue(Comparator<? super E> comparator) {
        this(DEFAULT_INITIAL_CAPACITY, comparator);
    }

    /**
     * Creates a {@code PriorityQueue} with the specified initial capacity
     * that orders its elements according to the specified comparator.
     *
     * @param  initialCapacity the initial capacity for this priority queue
     * @param  comparator the comparator that will be used to order this
     *         priority queue.  If {@code null}, the {@linkplain Comparable
     *         natural ordering} of the elements will be used.
     * @throws IllegalArgumentException if {@code initialCapacity} is
     *         less than 1
     */
    public PriorityQueue(int initialCapacity,
                         Comparator<? super E> comparator) {
        // Note: This restriction of at least one is not actually needed,
        // but continues for 1.5 compatibility
        if (initialCapacity < 1)
            throw new IllegalArgumentException();
        //根据容量创建数组
        this.queue = new Object[initialCapacity];
        this.comparator = comparator;
    }

    /**
     * 将集合转换为队列，根据集合类型不同，又分为三种情况
     * Creates a {@code PriorityQueue} containing the elements in the
     * specified collection.  If the specified collection is an instance of
     * a {@link SortedSet} or is another {@code PriorityQueue}, this
     * priority queue will be ordered according to the same ordering.
     * Otherwise, this priority queue will be ordered according to the
     * {@linkplain Comparable natural ordering} of its elements.
     *
     * @param  c the collection whose elements are to be placed
     *         into this priority queue
     * @throws ClassCastException if elements of the specified collection
     *         cannot be compared to one another according to the priority
     *         queue's ordering
     * @throws NullPointerException if the specified collection or any
     *         of its elements are null
     */
    @SuppressWarnings("unchecked")
    public PriorityQueue(Collection<? extends E> c) {
        //如果是有序 Set
        if (c instanceof SortedSet<?>) {
            SortedSet<? extends E> ss = (SortedSet<? extends E>) c;
            //直接使用原有的比较器
            this.comparator = (Comparator<? super E>) ss.comparator();
            //从有序集合中直接构建堆, 不需要做调整, 因为排好序的元素就是堆
            initElementsFromCollection(ss);
        }
        //如果优先级队列
        else if (c instanceof PriorityQueue<?>) {
            PriorityQueue<? extends E> pq = (PriorityQueue<? extends E>) c;
            //直接使用原有的比较器
            this.comparator = (Comparator<? super E>) pq.comparator();
            //从优先级队列构建堆
            initFromPriorityQueue(pq);
        }
        else {
            //设置 comparator null, 也就是使用默认的
            this.comparator = null;
            //从集合中构建堆
            initFromCollection(c);
        }
    }

    /**
     * Creates a {@code PriorityQueue} containing the elements in the
     * specified priority queue.  This priority queue will be
     * ordered according to the same ordering as the given priority
     * queue.
     *
     * @param  c the priority queue whose elements are to be placed
     *         into this priority queue
     * @throws ClassCastException if elements of {@code c} cannot be
     *         compared to one another according to {@code c}'s
     *         ordering
     * @throws NullPointerException if the specified priority queue or any
     *         of its elements are null
     */
    @SuppressWarnings("unchecked")
    public PriorityQueue(PriorityQueue<? extends E> c) {
        this.comparator = (Comparator<? super E>) c.comparator();
        initFromPriorityQueue(c);
    }

    /**
     * Creates a {@code PriorityQueue} containing the elements in the
     * specified sorted set.   This priority queue will be ordered
     * according to the same ordering as the given sorted set.
     *
     * @param  c the sorted set whose elements are to be placed
     *         into this priority queue
     * @throws ClassCastException if elements of the specified sorted
     *         set cannot be compared to one another according to the
     *         sorted set's ordering
     * @throws NullPointerException if the specified sorted set or any
     *         of its elements are null
     */
    @SuppressWarnings("unchecked")
    public PriorityQueue(SortedSet<? extends E> c) {
        this.comparator = (Comparator<? super E>) c.comparator();
        initElementsFromCollection(c);
    }

    private void initFromPriorityQueue(PriorityQueue<? extends E> c) {
        //如果是 PriorityQueue ,直接使用数组
        if (c.getClass() == PriorityQueue.class) {
            this.queue = c.toArray();
            this.size = c.size();
        } else {
            //其他实现的列队, 要重新构建
            initFromCollection(c);
        }
    }

    /**
     * 将集合变数组, 直接赋值使用, 校验元素不能为 null
     */
    private void initElementsFromCollection(Collection<? extends E> c) {
        Object[] a = c.toArray();
        // If c.toArray incorrectly doesn't return Object[], copy it.
        if (a.getClass() != Object[].class)
            a = Arrays.copyOf(a, a.length, Object[].class);
        int len = a.length;
        if (len == 1 || this.comparator != null)
            for (int i = 0; i < len; i++)
                if (a[i] == null)
                    throw new NullPointerException();
        this.queue = a;
        this.size = a.length;
    }

    /**
     * 从集合中构建堆
     * Initializes queue array with elements from the given Collection.
     *
     * @param c the collection
     */
    private void initFromCollection(Collection<? extends E> c) {
        //集合转数组
        initElementsFromCollection(c);
        //堆化
        heapify();
    }

    /**
     * 数组最大容量限制，Integer最大值-8是说一些虚拟机可能会在数组中保留一些header words的空间，所以没有取Integer最大值
     * The maximum size of array to allocate.
     * Some VMs reserve some header words in an array.
     * Attempts to allocate larger arrays may result in
     * OutOfMemoryError: Requested array size exceeds VM limit
     */
    private static final int MAX_ARRAY_SIZE = Integer.MAX_VALUE - 8;

    /**
     * Increases the capacity of the array.
     *
     * @param minCapacity the desired minimum capacity
     */
    private void grow(int minCapacity) {
        // 旧容量
        int oldCapacity = queue.length;
        // 旧容量小于64时，容量翻倍 + 2
        // 旧容量大于等于64，容量只增加旧容量的一半
        // Double size if small; else grow by 50%
        int newCapacity = oldCapacity + ((oldCapacity < 64) ?
                                         (oldCapacity + 2) :
                                         (oldCapacity >> 1));
        // 检查是否溢出
        // overflow-conscious code
        if (newCapacity - MAX_ARRAY_SIZE > 0)
            newCapacity = hugeCapacity(minCapacity);
        //创建并拷贝数据到新数组
        queue = Arrays.copyOf(queue, newCapacity);
    }

    private static int hugeCapacity(int minCapacity) {
        //如果最小容量溢出, 则抛异常
        if (minCapacity < 0) // overflow
            throw new OutOfMemoryError();
        //处理容量最大值
        return (minCapacity > MAX_ARRAY_SIZE) ?
            Integer.MAX_VALUE :
            MAX_ARRAY_SIZE;
    }

    /**
     * Inserts the specified element into this priority queue.
     *
     * @return {@code true} (as specified by {@link Collection#add})
     * @throws ClassCastException if the specified element cannot be
     *         compared with elements currently in this priority queue
     *         according to the priority queue's ordering
     * @throws NullPointerException if the specified element is null
     */
    public boolean add(E e) {
        return offer(e);
    }

    /**
     * Inserts the specified element into this priority queue.
     *
     * @return {@code true} (as specified by {@link Queue#offer})
     * @throws ClassCastException if the specified element cannot be
     *         compared with elements currently in this priority queue
     *         according to the priority queue's ordering
     * @throws NullPointerException if the specified element is null
     */
    public boolean offer(E e) {
        //不支持null元素
        if (e == null)
            throw new NullPointerException();
        //修改次数增加
        modCount++;
        // 取size
        int i = size;
        //元素个数达到最大容量了，扩容
        if (i >= queue.length)
            grow(i + 1);
        // 元素个数加1
        size = i + 1;
        // 如果还没有元素
        // 直接插入到数组第一个位置
        // 这里跟我们之前讲堆不一样了
        // java里面是从0开始的
        // 我们说的堆是从1开始的
        if (i == 0)
            queue[0] = e;
        else
            // 否则，插入元素到数组size的位置，也就是最后一个元素的下一位
            // 注意这里的size不是数组大小，而是元素个数
            // 然后，再做自下而上的堆化
            siftUp(i, e);
        //返回插入成功
        return true;
    }

    @SuppressWarnings("unchecked")
    public E peek() {
        //读取首节点的值
        return (size == 0) ? null : (E) queue[0];
    }

    /**
     * 直接遍历查找
     */
    private int indexOf(Object o) {
        if (o != null) {
            for (int i = 0; i < size; i++)
                if (o.equals(queue[i]))
                    return i;
        }
        return -1;
    }

    /**
     * Removes a single instance of the specified element from this queue,
     * if it is present.  More formally, removes an element {@code e} such
     * that {@code o.equals(e)}, if this queue contains one or more such
     * elements.  Returns {@code true} if and only if this queue contained
     * the specified element (or equivalently, if this queue changed as a
     * result of the call).
     *
     * @param o element to be removed from this queue, if present
     * @return {@code true} if this queue changed as a result of the call
     */
    public boolean remove(Object o) {
        //获取元素的下标
        int i = indexOf(o);
        if (i == -1)
            return false;
        else {
            removeAt(i);
            return true;
        }
    }

    /**
     * Version of remove using reference equality, not equals.
     * Needed by iterator.remove.
     *
     * @param o element to be removed from this queue, if present
     * @return {@code true} if removed
     */
    boolean removeEq(Object o) {
        for (int i = 0; i < size; i++) {
            if (o == queue[i]) {
                removeAt(i);
                return true;
            }
        }
        return false;
    }

    /**
     * 相当于遍历判断
     * Returns {@code true} if this queue contains the specified element.
     * More formally, returns {@code true} if and only if this queue contains
     * at least one element {@code e} such that {@code o.equals(e)}.
     *
     * @param o object to be checked for containment in this queue
     * @return {@code true} if this queue contains the specified element
     */
    public boolean contains(Object o) {
        return indexOf(o) != -1;
    }

    /**
     * Returns an array containing all of the elements in this queue.
     * The elements are in no particular order.
     *
     * <p>The returned array will be "safe" in that no references to it are
     * maintained by this queue.  (In other words, this method must allocate
     * a new array).  The caller is thus free to modify the returned array.
     *
     * <p>This method acts as bridge between array-based and collection-based
     * APIs.
     *
     * @return an array containing all of the elements in this queue
     */
    public Object[] toArray() {
        return Arrays.copyOf(queue, size);
    }

    /**
     * Returns an array containing all of the elements in this queue; the
     * runtime type of the returned array is that of the specified array.
     * The returned array elements are in no particular order.
     * If the queue fits in the specified array, it is returned therein.
     * Otherwise, a new array is allocated with the runtime type of the
     * specified array and the size of this queue.
     *
     * <p>If the queue fits in the specified array with room to spare
     * (i.e., the array has more elements than the queue), the element in
     * the array immediately following the end of the collection is set to
     * {@code null}.
     *
     * <p>Like the {@link #toArray()} method, this method acts as bridge between
     * array-based and collection-based APIs.  Further, this method allows
     * precise control over the runtime type of the output array, and may,
     * under certain circumstances, be used to save allocation costs.
     *
     * <p>Suppose {@code x} is a queue known to contain only strings.
     * The following code can be used to dump the queue into a newly
     * allocated array of {@code String}:
     *
     *  <pre> {@code String[] y = x.toArray(new String[0]);}</pre>
     *
     * Note that {@code toArray(new Object[0])} is identical in function to
     * {@code toArray()}.
     *
     * @param a the array into which the elements of the queue are to
     *          be stored, if it is big enough; otherwise, a new array of the
     *          same runtime type is allocated for this purpose.
     * @return an array containing all of the elements in this queue
     * @throws ArrayStoreException if the runtime type of the specified array
     *         is not a supertype of the runtime type of every element in
     *         this queue
     * @throws NullPointerException if the specified array is null
     */
    @SuppressWarnings("unchecked")
    public <T> T[] toArray(T[] a) {
        final int size = this.size;
        if (a.length < size)
            // Make a new array of a's runtime type, but my contents:
            return (T[]) Arrays.copyOf(queue, size, a.getClass());
        System.arraycopy(queue, 0, a, 0, size);
        if (a.length > size)
            a[size] = null;
        return a;
    }

    /**
     * Returns an iterator over the elements in this queue. The iterator
     * does not return the elements in any particular order.
     *
     * @return an iterator over the elements in this queue
     */
    public Iterator<E> iterator() {
        return new Itr();
    }

    private final class Itr implements Iterator<E> {
        /**
         * 游标, 相当于下一个要访问的数组索引
         * Index (into queue array) of element to be returned by
         * subsequent call to next.
         */
        private int cursor = 0;

        /**
         * 缓存上一个已经访问的索引, -1代表没有
         * Index of element returned by most recent call to next,
         * unless that element came from the forgetMeNot list.
         * Set to -1 if element is deleted by a call to remove.
         */
        private int lastRet = -1;

        /**
         * 记录那些向前浮动的元素, 也就是还没被迭代但是移动已经迭代过的索引上面的元素
         * A queue of elements that were moved from the unvisited portion of
         * the heap into the visited portion as a result of "unlucky" element
         * removals during the iteration.  (Unlucky element removals are those
         * that require a siftup instead of a siftdown.)  We must visit all of
         * the elements in this list to complete the iteration.  We do this
         * after we've completed the "normal" iteration.
         *
         * We expect that most iterations, even those involving removals,
         * will not need to store elements in this field.
         */
        private ArrayDeque<E> forgetMeNot = null;

        /**
         * 缓存代表上一次访问过, 但不在当前迭代索引上的元素
         * Element returned by the most recent call to next iff that
         * element was drawn from the forgetMeNot list.
         */
        private E lastRetElt = null;

        /**
         * 缓存迭代前的修改次数, 以快速失败
         * The modCount value that the iterator believes that the backing
         * Queue should have.  If this expectation is violated, the iterator
         * has detected concurrent modification.
         */
        private int expectedModCount = modCount;

        public boolean hasNext() {
            return cursor < size ||
                (forgetMeNot != null && !forgetMeNot.isEmpty());
        }

        @SuppressWarnings("unchecked")
        public E next() {
            if (expectedModCount != modCount)
                throw new ConcurrentModificationException();
            //游标正常小于队列元素个数
            if (cursor < size)
                return (E) queue[lastRet = cursor++];
            //到这里, 发现游标大于等于 size , 代表有元素被删除了且浮动到前面已经迭代过的索引去了
            //forgetMeNot 队列不为 null
            if (forgetMeNot != null) {
                //设置成 -1
                lastRet = -1;
                //获取一个未被迭代的元素
                lastRetElt = forgetMeNot.poll();
                //不为 null 就直接返回
                if (lastRetElt != null)
                    return lastRetElt;
            }
            throw new NoSuchElementException();
        }

        public void remove() {
            if (expectedModCount != modCount)
                throw new ConcurrentModificationException();
            //如果最后一次访问的元素不为 -1
            if (lastRet != -1) {
                //获取向前浮动的元素
                E moved = PriorityQueue.this.removeAt(lastRet);
                //最后一次访问的元素已经被删除, 重置 lastRet
                lastRet = -1;
                //如果没有向前浮动, 则不影响后续迭代
                if (moved == null)
                    //当前游标退
                    cursor--;
                else {
                    //如果队列不存在
                    if (forgetMeNot == null)
                        //创建
                        forgetMeNot = new ArrayDeque<>();
                    //添加到队列中
                    forgetMeNot.add(moved);
                }
                //如果 lastRet = -1 , 但是 lastRetElt 不为 null, 表明要删除当前这个被遗忘的元素
            } else if (lastRetElt != null) {
                //直接删除
                PriorityQueue.this.removeEq(lastRetElt);
                //设置为 null
                lastRetElt = null;
            } else {
                throw new IllegalStateException();
            }
            expectedModCount = modCount;
        }
    }

    public int size() {
        return size;
    }

    /**
     * Removes all of the elements from this priority queue.
     * The queue will be empty after this call returns.
     */
    public void clear() {
        modCount++;
        for (int i = 0; i < size; i++)
            queue[i] = null;
        size = 0;
    }

    @SuppressWarnings("unchecked")
    public E poll() {
        // 如果 size 为 0，说明没有元素, 返回 null
        if (size == 0)
            return null;
        //弹出元素，元素个数减1
        int s = --size;
        modCount++;
        // 队列首元素
        E result = (E) queue[0];
        // 队列末元素
        E x = (E) queue[s];
        // 将队列末元素删除
        queue[s] = null;
        // 如果弹出元素后还有元素
        if (s != 0)
            // 将队列末元素移到队列首
            // 再做自上而下的堆化
            siftDown(0, x);
        // 返回弹出的元素
        return result;
    }

    /**
     * 执行移除操作, 返回向上浮动的元素
     * Removes the ith element from queue.
     *
     * Normally this method leaves the elements at up to i-1,
     * inclusive, untouched.  Under these circumstances, it returns
     * null.  Occasionally, in order to maintain the heap invariant,
     * it must swap a later element of the list with one earlier than
     * i.  Under these circumstances, this method returns the element
     * that was previously at the end of the list and is now at some
     * position before i. This fact is used by iterator.remove so as to
     * avoid missing traversing elements.
     */
    @SuppressWarnings("unchecked")
    private E removeAt(int i) {
        // assert i >= 0 && i < size;
        modCount++;
        //元素个数减 1
        int s = --size;
        //如果要删除的是最后一个
        if (s == i) // removed last element
            //直接删除就行
            queue[i] = null;
        else {
            //取队列尾元素
            E moved = (E) queue[s];
            //将队尾元素置为null
            queue[s] = null;
            //用队尾元素, 替换删除元素, 并且自上而下堆化, 相当于下沉操作
            siftDown(i, moved);
            //如果下移后元素位置没发生变化，说明moved的左右子结点都大于moved，这时就需要上浮操作
            if (queue[i] == moved) {
                //则自下而上堆化
                siftUp(i, moved);
                //如果上浮之后发生了元素位置变化
                if (queue[i] != moved)
                    //返回位置变化的元素, 主要是为了处理迭代中删除的问题
                    return moved;
            }
        }
        //没有向上浮动, 也就是对迭代没影响, 就返回 null
        return null;
    }

    /**
     * 自下而上堆化
     * Inserts item x at position k, maintaining heap invariant by
     * promoting x up the tree until it is greater than or equal to
     * its parent, or is the root.
     *
     * To simplify and speed up coercions and comparisons. the
     * Comparable and Comparator versions are separated into different
     * methods that are otherwise identical. (Similarly for siftDown.)
     *
     * @param k the position to fill
     * @param x the item to insert
     */
    private void siftUp(int k, E x) {
        // 根据是否有比较器，使用不同的方法
        if (comparator != null)
            siftUpUsingComparator(k, x);
        else
            siftUpComparable(k, x);
    }

    @SuppressWarnings("unchecked")
    private void siftUpComparable(int k, E x) {
        Comparable<? super E> key = (Comparable<? super E>) x;
        //k是下标值，保证结点有父级结点
        while (k > 0) {
            // 找到父节点的位置
            // 因为元素是从 0 开始的，所以减 1 之后再除以 2
            int parent = (k - 1) >>> 1;
            // 父节点的值
            Object e = queue[parent];
            // 比较插入的元素与父节点的值
            // 如果比父节点大，则跳出循环
            // 否则交换位置
            if (key.compareTo((E) e) >= 0)
                break;
            // 与父节点交换位置
            queue[k] = e;
            // 现在插入的元素位置移到了父节点的位置
            // 继续与父节点再比较
            k = parent;
        }
        // 最后找到应该插入的位置，放入元素
        queue[k] = key;
    }

    @SuppressWarnings("unchecked")
    private void siftUpUsingComparator(int k, E x) {
        //这个方法的实现跟 siftUpComparable 一样
        while (k > 0) {
            int parent = (k - 1) >>> 1;
            Object e = queue[parent];
            if (comparator.compare(x, (E) e) >= 0)
                break;
            queue[k] = e;
            k = parent;
        }
        queue[k] = x;
    }

    /**
     * 自上而下堆化
     * Inserts item x at position k, maintaining heap invariant by
     * demoting x down the tree repeatedly until it is less than or
     * equal to its children or is a leaf.
     *
     * @param k the position to fill
     * @param x the item to insert
     */
    private void siftDown(int k, E x) {
        // 根据是否有比较器，选择不同的方法
        if (comparator != null)
            siftDownUsingComparator(k, x);
        else
            siftDownComparable(k, x);
    }

    @SuppressWarnings("unchecked")
    private void siftDownComparable(int k, E x) {
        Comparable<? super E> key = (Comparable<? super E>)x;
        // 只需要比较一半就行了，因为叶子节点占了一半的元素. 不用遍历叶子节点, 进入 while 之后, 会跟左右子节点进行对比
        //计算非叶子结点元素的最大位置，循环的终止条件（在最后一个非叶子节点处结束）
        int half = size >>> 1;        // loop while a non-leaf
        while (k < half) {
            // 寻找左子节点的位置，这里加 1 是因为元素从 0 号位置开始
            int child = (k << 1) + 1; // assume left child is least
            // 左子节点的值
            Object c = queue[child];
            // 右子节点的位置
            int right = child + 1;
            if (right < size &&
                ((Comparable<? super E>) c).compareTo((E) queue[right]) > 0)
                // 左右节点取其小者
                c = queue[child = right];
            // 如果比子节点都小，则结束
            if (key.compareTo((E) c) <= 0)
                break;
            // 如果比最小的子节点大，则交换位置
            queue[k] = c;
            // 指针移到最小子节点的位置继续往下比较
            k = child;
        }
        // 找到正确的位置，放入元素
        queue[k] = key;
    }

    @SuppressWarnings("unchecked")
    private void siftDownUsingComparator(int k, E x) {
        int half = size >>> 1;
        while (k < half) {
            int child = (k << 1) + 1;
            Object c = queue[child];
            int right = child + 1;
            if (right < size &&
                comparator.compare((E) c, (E) queue[right]) > 0)
                c = queue[child = right];
            if (comparator.compare(x, (E) c) <= 0)
                break;
            queue[k] = c;
            k = child;
        }
        queue[k] = x;
    }

    /**
     * 堆化
     * Establishes the heap invariant (described above) in the entire tree,
     * assuming nothing about the order of the elements prior to the call.
     */
    @SuppressWarnings("unchecked")
    private void heapify() {
        //下层一半就行了，因为叶子节点占了一半的元素
        for (int i = (size >>> 1) - 1; i >= 0; i--)
            siftDown(i, (E) queue[i]);
    }

    /**
     * Returns the comparator used to order the elements in this
     * queue, or {@code null} if this queue is sorted according to
     * the {@linkplain Comparable natural ordering} of its elements.
     *
     * @return the comparator used to order this queue, or
     *         {@code null} if this queue is sorted according to the
     *         natural ordering of its elements
     */
    public Comparator<? super E> comparator() {
        return comparator;
    }

    /**
     * Saves this queue to a stream (that is, serializes it).
     *
     * @serialData The length of the array backing the instance is
     *             emitted (int), followed by all of its elements
     *             (each an {@code Object}) in the proper order.
     * @param s the stream
     */
    private void writeObject(java.io.ObjectOutputStream s)
        throws java.io.IOException {
        // Write out element count, and any hidden stuff
        s.defaultWriteObject();

        // Write out array length, for compatibility with 1.5 version
        s.writeInt(Math.max(2, size + 1));

        // Write out all elements in the "proper order".
        for (int i = 0; i < size; i++)
            s.writeObject(queue[i]);
    }

    /**
     * Reconstitutes the {@code PriorityQueue} instance from a stream
     * (that is, deserializes it).
     *
     * @param s the stream
     */
    private void readObject(java.io.ObjectInputStream s)
        throws java.io.IOException, ClassNotFoundException {
        // Read in size, and any hidden stuff
        s.defaultReadObject();

        // Read in (and discard) array length
        s.readInt();

        queue = new Object[size];

        // Read in all elements.
        for (int i = 0; i < size; i++)
            queue[i] = s.readObject();

        // Elements are guaranteed to be in "proper order", but the
        // spec has never explained what that might be.
        heapify();
    }

    /**
     * Creates a <em><a href="Spliterator.html#binding">late-binding</a></em>
     * and <em>fail-fast</em> {@link Spliterator} over the elements in this
     * queue.
     *
     * <p>The {@code Spliterator} reports {@link Spliterator#SIZED},
     * {@link Spliterator#SUBSIZED}, and {@link Spliterator#NONNULL}.
     * Overriding implementations should document the reporting of additional
     * characteristic values.
     *
     * @return a {@code Spliterator} over the elements in this queue
     * @since 1.8
     */
    public final Spliterator<E> spliterator() {
        return new PriorityQueueSpliterator<E>(this, 0, -1, 0);
    }

    static final class PriorityQueueSpliterator<E> implements Spliterator<E> {
        /*
         * This is very similar to ArrayList Spliterator, except for
         * extra null checks.
         */
        private final PriorityQueue<E> pq;
        private int index;            // current index, modified on advance/split
        private int fence;            // -1 until first use
        private int expectedModCount; // initialized when fence set

        /** Creates new spliterator covering the given range */
        PriorityQueueSpliterator(PriorityQueue<E> pq, int origin, int fence,
                             int expectedModCount) {
            this.pq = pq;
            this.index = origin;
            this.fence = fence;
            this.expectedModCount = expectedModCount;
        }

        private int getFence() { // initialize fence to size on first use
            int hi;
            if ((hi = fence) < 0) {
                expectedModCount = pq.modCount;
                hi = fence = pq.size;
            }
            return hi;
        }

        public PriorityQueueSpliterator<E> trySplit() {
            int hi = getFence(), lo = index, mid = (lo + hi) >>> 1;
            return (lo >= mid) ? null :
                new PriorityQueueSpliterator<E>(pq, lo, index = mid,
                                                expectedModCount);
        }

        @SuppressWarnings("unchecked")
        public void forEachRemaining(Consumer<? super E> action) {
            int i, hi, mc; // hoist accesses and checks from loop
            PriorityQueue<E> q; Object[] a;
            if (action == null)
                throw new NullPointerException();
            if ((q = pq) != null && (a = q.queue) != null) {
                if ((hi = fence) < 0) {
                    mc = q.modCount;
                    hi = q.size;
                }
                else
                    mc = expectedModCount;
                if ((i = index) >= 0 && (index = hi) <= a.length) {
                    for (E e;; ++i) {
                        if (i < hi) {
                            if ((e = (E) a[i]) == null) // must be CME
                                break;
                            action.accept(e);
                        }
                        else if (q.modCount != mc)
                            break;
                        else
                            return;
                    }
                }
            }
            throw new ConcurrentModificationException();
        }

        public boolean tryAdvance(Consumer<? super E> action) {
            if (action == null)
                throw new NullPointerException();
            int hi = getFence(), lo = index;
            if (lo >= 0 && lo < hi) {
                index = lo + 1;
                @SuppressWarnings("unchecked") E e = (E)pq.queue[lo];
                if (e == null)
                    throw new ConcurrentModificationException();
                action.accept(e);
                if (pq.modCount != expectedModCount)
                    throw new ConcurrentModificationException();
                return true;
            }
            return false;
        }

        public long estimateSize() {
            return (long) (getFence() - index);
        }

        public int characteristics() {
            return Spliterator.SIZED | Spliterator.SUBSIZED | Spliterator.NONNULL;
        }
    }
}
