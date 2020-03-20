/*
 * Copyright (c) 1997, 2013, Oracle and/or its affiliates. All rights reserved.
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

import java.io.IOException;
import java.io.InvalidObjectException;
import java.io.Serializable;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Hash table based implementation of the <tt>Map</tt> interface.  This
 * implementation provides all of the optional map operations, and permits
 * <tt>null</tt> values and the <tt>null</tt> key.  (The <tt>HashMap</tt>
 * class is roughly equivalent to <tt>Hashtable</tt>, except that it is
 * unsynchronized and permits nulls.)  This class makes no guarantees as to
 * the order of the map; in particular, it does not guarantee that the order
 * will remain constant over time.
 *
 * <p>This implementation provides constant-time performance for the basic
 * operations (<tt>get</tt> and <tt>put</tt>), assuming the hash function
 * disperses the elements properly among the buckets.  Iteration over
 * collection views requires time proportional to the "capacity" of the
 * <tt>HashMap</tt> instance (the number of buckets) plus its size (the number
 * of key-value mappings).  Thus, it's very important not to set the initial
 * capacity too high (or the load factor too low) if iteration performance is
 * important.
 *
 * <p>An instance of <tt>HashMap</tt> has two parameters that affect its
 * performance: <i>initial capacity</i> and <i>load factor</i>.  The
 * <i>capacity</i> is the number of buckets in the hash table, and the initial
 * capacity is simply the capacity at the time the hash table is created.  The
 * <i>load factor</i> is a measure of how full the hash table is allowed to
 * get before its capacity is automatically increased.  When the number of
 * entries in the hash table exceeds the product of the load factor and the
 * current capacity, the hash table is <i>rehashed</i> (that is, internal data
 * structures are rebuilt) so that the hash table has approximately twice the
 * number of buckets.
 *
 * <p>As a general rule, the default load factor (.75) offers a good
 * tradeoff between time and space costs.  Higher values decrease the
 * space overhead but increase the lookup cost (reflected in most of
 * the operations of the <tt>HashMap</tt> class, including
 * <tt>get</tt> and <tt>put</tt>).  The expected number of entries in
 * the map and its load factor should be taken into account when
 * setting its initial capacity, so as to minimize the number of
 * rehash operations.  If the initial capacity is greater than the
 * maximum number of entries divided by the load factor, no rehash
 * operations will ever occur.
 *
 * <p>If many mappings are to be stored in a <tt>HashMap</tt>
 * instance, creating it with a sufficiently large capacity will allow
 * the mappings to be stored more efficiently than letting it perform
 * automatic rehashing as needed to grow the table.  Note that using
 * many keys with the same {@code hashCode()} is a sure way to slow
 * down performance of any hash table. To ameliorate impact, when keys
 * are {@link Comparable}, this class may use comparison order among
 * keys to help break ties.
 *
 * <p><strong>Note that this implementation is not synchronized.</strong>
 * If multiple threads access a hash map concurrently, and at least one of
 * the threads modifies the map structurally, it <i>must</i> be
 * synchronized externally.  (A structural modification is any operation
 * that adds or deletes one or more mappings; merely changing the value
 * associated with a key that an instance already contains is not a
 * structural modification.)  This is typically accomplished by
 * synchronizing on some object that naturally encapsulates the map.
 *
 * If no such object exists, the map should be "wrapped" using the
 * {@link Collections#synchronizedMap Collections.synchronizedMap}
 * method.  This is best done at creation time, to prevent accidental
 * unsynchronized access to the map:<pre>
 *   Map m = Collections.synchronizedMap(new HashMap(...));</pre>
 *
 * <p>The iterators returned by all of this class's "collection view methods"
 * are <i>fail-fast</i>: if the map is structurally modified at any time after
 * the iterator is created, in any way except through the iterator's own
 * <tt>remove</tt> method, the iterator will throw a
 * {@link ConcurrentModificationException}.  Thus, in the face of concurrent
 * modification, the iterator fails quickly and cleanly, rather than risking
 * arbitrary, non-deterministic behavior at an undetermined time in the
 * future.
 *
 * <p>Note that the fail-fast behavior of an iterator cannot be guaranteed
 * as it is, generally speaking, impossible to make any hard guarantees in the
 * presence of unsynchronized concurrent modification.  Fail-fast iterators
 * throw <tt>ConcurrentModificationException</tt> on a best-effort basis.
 * Therefore, it would be wrong to write a program that depended on this
 * exception for its correctness: <i>the fail-fast behavior of iterators
 * should be used only to detect bugs.</i>
 *
 * <p>This class is a member of the
 * <a href="{@docRoot}/../technotes/guides/collections/index.html">
 * Java Collections Framework</a>.
 *
 * - 在Java8 中，HashMap的实现采用了（数组 + 链表 + 红黑树）的复杂结构，数组的一个元素又称作桶。
 * - 当一个链表的元素个数达到一定的数量（且数组的长度达到一定的长度）后，则把链表转化为红黑树，从而提高效率。
 * - 数组的查询效率为O(1)，链表的查询效率是O(k)，红黑树的查询效率是O(log k)，k为桶中的元素个数，所以当元素数量非常多的时候，转化为红黑树能极大地提高效率。
 *
 * @param <K> the type of keys maintained by this map
 * @param <V> the type of mapped values
 *
 * @author  Doug Lea
 * @author  Josh Bloch
 * @author  Arthur van Hoff
 * @author  Neal Gafter
 * @see     Object#hashCode()
 * @see     Collection
 * @see     Map
 * @see     TreeMap
 * @see     Hashtable
 * @since   1.2
 */
public class HashMap<K,V> extends AbstractMap<K,V>
    implements Map<K,V>, Cloneable, Serializable {

    private static final long serialVersionUID = 362498820763181265L;

    /*
     * Implementation notes.
     *
     * This map usually acts as a binned (bucketed) hash table, but
     * when bins get too large, they are transformed into bins of
     * TreeNodes, each structured similarly to those in
     * java.util.TreeMap. Most methods try to use normal bins, but
     * relay to TreeNode methods when applicable (simply by checking
     * instanceof a node).  Bins of TreeNodes may be traversed and
     * used like any others, but additionally support faster lookup
     * when overpopulated. However, since the vast majority of bins in
     * normal use are not overpopulated, checking for existence of
     * tree bins may be delayed in the course of table methods.
     *
     * Tree bins (i.e., bins whose elements are all TreeNodes) are
     * ordered primarily by hashCode, but in the case of ties, if two
     * elements are of the same "class C implements Comparable<C>",
     * type then their compareTo method is used for ordering. (We
     * conservatively check generic types via reflection to validate
     * this -- see method comparableClassFor).  The added complexity
     * of tree bins is worthwhile in providing worst-case O(log n)
     * operations when keys either have distinct hashes or are
     * orderable, Thus, performance degrades gracefully under
     * accidental or malicious usages in which hashCode() methods
     * return values that are poorly distributed, as well as those in
     * which many keys share a hashCode, so long as they are also
     * Comparable. (If neither of these apply, we may waste about a
     * factor of two in time and space compared to taking no
     * precautions. But the only known cases stem from poor user
     * programming practices that are already so slow that this makes
     * little difference.)
     *
     * Because TreeNodes are about twice the size of regular nodes, we
     * use them only when bins contain enough nodes to warrant use
     * (see TREEIFY_THRESHOLD). And when they become too small (due to
     * removal or resizing) they are converted back to plain bins.  In
     * usages with well-distributed user hashCodes, tree bins are
     * rarely used.  Ideally, under random hashCodes, the frequency of
     * nodes in bins follows a Poisson distribution
     * (http://en.wikipedia.org/wiki/Poisson_distribution) with a
     * parameter of about 0.5 on average for the default resizing
     * threshold of 0.75, although with a large variance because of
     * resizing granularity. Ignoring variance, the expected
     * occurrences of list size k are (exp(-0.5) * pow(0.5, k) /
     * factorial(k)). The first values are:
     *
     * 0:    0.60653066
     * 1:    0.30326533
     * 2:    0.07581633
     * 3:    0.01263606
     * 4:    0.00157952
     * 5:    0.00015795
     * 6:    0.00001316
     * 7:    0.00000094
     * 8:    0.00000006
     * more: less than 1 in ten million
     *
     * The root of a tree bin is normally its first node.  However,
     * sometimes (currently only upon Iterator.remove), the root might
     * be elsewhere, but can be recovered following parent links
     * (method TreeNode.root()).
     *
     * All applicable internal methods accept a hash code as an
     * argument (as normally supplied from a public method), allowing
     * them to call each other without recomputing user hashCodes.
     * Most internal methods also accept a "tab" argument, that is
     * normally the current table, but may be a new or old one when
     * resizing or converting.
     *
     * When bin lists are treeified, split, or untreeified, we keep
     * them in the same relative access/traversal order (i.e., field
     * Node.next) to better preserve locality, and to slightly
     * simplify handling of splits and traversals that invoke
     * iterator.remove. When using comparators on insertion, to keep a
     * total ordering (or as close as is required here) across
     * rebalancings, we compare classes and identityHashCodes as
     * tie-breakers.
     *
     * The use and transitions among plain vs tree modes is
     * complicated by the existence of subclass LinkedHashMap. See
     * below for hook methods defined to be invoked upon insertion,
     * removal and access that allow LinkedHashMap internals to
     * otherwise remain independent of these mechanics. (This also
     * requires that a map instance be passed to some utility methods
     * that may create new nodes.)
     *
     * The concurrent-programming-like SSA-based coding style helps
     * avoid aliasing errors amid all of the twisty pointer operations.
     */

    /**
     * 默认的初始容量为 16
     * The default initial capacity - MUST be a power of two.
     */
    static final int DEFAULT_INITIAL_CAPACITY = 1 << 4; // aka 16

    /**
     * 最大的容量为2的30次方
     * The maximum capacity, used if a higher value is implicitly specified
     * by either of the constructors with arguments.
     * MUST be a power of two <= 1<<30.
     */
    static final int MAXIMUM_CAPACITY = 1 << 30;

    /**
     * 默认的装载因子
     * The load factor used when none specified in constructor.
     */
    static final float DEFAULT_LOAD_FACTOR = 0.75f;

    /**
     * 当一个桶中的元素个数大于等于8时进行树化
     * The bin count threshold for using a tree rather than list for a
     * bin.  Bins are converted to trees when adding an element to a
     * bin with at least this many nodes. The value must be greater
     * than 2 and should be at least 8 to mesh with assumptions in
     * tree removal about conversion back to plain bins upon
     * shrinkage.
     */
    static final int TREEIFY_THRESHOLD = 8;

    /**
     * 当一个桶中的元素个数小于等于6时把树转化为链表
     * The bin count threshold for untreeifying a (split) bin during a
     * resize operation. Should be less than TREEIFY_THRESHOLD, and at
     * most 6 to mesh with shrinkage detection under removal.
     */
    static final int UNTREEIFY_THRESHOLD = 6;

    /**
     * 当桶的个数达到64的时候才进行树化, 也就是数组的长度达到 64 才能树化
     * The smallest table capacity for which bins may be treeified.
     * (Otherwise the table is resized if too many nodes in a bin.)
     * Should be at least 4 * TREEIFY_THRESHOLD to avoid conflicts
     * between resizing and treeification thresholds.
     */
    static final int MIN_TREEIFY_CAPACITY = 64;

    /**
     * Map.Entry 的实现, 单向链表结构
     * Basic hash bin node, used for most entries.  (See below for
     * TreeNode subclass, and in LinkedHashMap for its Entry subclass.)
     */
    static class Node<K,V> implements Map.Entry<K,V> {
        /**
         * 缓存节点的 hash 值
         */
        final int hash;
        /**
         * 节点的 key
         */
        final K key;
        /**
         * 节点的值
         */
        V value;
        /**
         * 单向链表下一个节点
         */
        Node<K,V> next;

        Node(int hash, K key, V value, Node<K,V> next) {
            this.hash = hash;
            this.key = key;
            this.value = value;
            this.next = next;
        }

        public final K getKey()        { return key; }
        public final V getValue()      { return value; }
        public final String toString() { return key + "=" + value; }

        public final int hashCode() {
            return Objects.hashCode(key) ^ Objects.hashCode(value);
        }

        /**
         * 设置新值, 并返回旧值
         */
        public final V setValue(V newValue) {
            V oldValue = value;
            value = newValue;
            return oldValue;
        }

        public final boolean equals(Object o) {
            if (o == this)
                return true;
            if (o instanceof Map.Entry) {
                Map.Entry<?,?> e = (Map.Entry<?,?>)o;
                if (Objects.equals(key, e.getKey()) &&
                    Objects.equals(value, e.getValue()))
                    return true;
            }
            return false;
        }
    }

    /* ---------------- Static utilities -------------- */

    /**
     * Computes key.hashCode() and spreads (XORs) higher bits of hash
     * to lower.  Because the table uses power-of-two masking, sets of
     * hashes that vary only in bits above the current mask will
     * always collide. (Among known examples are sets of Float keys
     * holding consecutive whole numbers in small tables.)  So we
     * apply a transform that spreads the impact of higher bits
     * downward. There is a tradeoff between speed, utility, and
     * quality of bit-spreading. Because many common sets of hashes
     * are already reasonably distributed (so don't benefit from
     * spreading), and because we use trees to handle large sets of
     * collisions in bins, we just XOR some shifted bits in the
     * cheapest possible way to reduce systematic lossage, as well as
     * to incorporate impact of the highest bits that would otherwise
     * never be used in index calculations because of table bounds.
     */
    static final int hash(Object key) {
        int h;
        // 如果key为null，则hash值为0，否则调用key的hashCode()方法
        // 并让高16位与整个hash异或，这样做是为了使计算出的hash更分散
        return (key == null) ? 0 : (h = key.hashCode()) ^ (h >>> 16);
    }

    /**
     * Returns x's Class if it is of the form "class C implements
     * Comparable<C>", else null.
     */
    static Class<?> comparableClassFor(Object x) {
        if (x instanceof Comparable) {
            Class<?> c; Type[] ts, as; Type t; ParameterizedType p;
            if ((c = x.getClass()) == String.class) // bypass checks
                return c;
            if ((ts = c.getGenericInterfaces()) != null) {
                for (int i = 0; i < ts.length; ++i) {
                    if (((t = ts[i]) instanceof ParameterizedType) &&
                        ((p = (ParameterizedType)t).getRawType() ==
                         Comparable.class) &&
                        (as = p.getActualTypeArguments()) != null &&
                        as.length == 1 && as[0] == c) // type arg is c
                        return c;
                }
            }
        }
        return null;
    }

    /**
     * Returns k.compareTo(x) if x matches kc (k's screened comparable
     * class), else 0.
     */
    @SuppressWarnings({"rawtypes","unchecked"}) // for cast to Comparable
    static int compareComparables(Class<?> kc, Object k, Object x) {
        return (x == null || x.getClass() != kc ? 0 :
                ((Comparable)k).compareTo(x));
    }

    /**
     * 获取给定数字 cap 的最小 2 的 n 次方的值, 这个算法优化不错, 值得学习
     * Returns a power of two size for the given target capacity.
     */
    static final int tableSizeFor(int cap) {
        int n = cap - 1;
        n |= n >>> 1;
        n |= n >>> 2;
        n |= n >>> 4;
        n |= n >>> 8;
        n |= n >>> 16;
        return (n < 0) ? 1 : (n >= MAXIMUM_CAPACITY) ? MAXIMUM_CAPACITY : n + 1;
    }

    /* ---------------- Fields -------------- */

    /**
     * Node 数组, 数组的长度，亦即桶的个数，默认为16，最大为2的30次方，当容量达到64时才可以树化。
     * The table, initialized on first use, and resized as
     * necessary. When allocated, length is always a power of two.
     * (We also tolerate length zero in some operations to allow
     * bootstrapping mechanics that are currently not needed.)
     */
    transient Node<K,V>[] table;

    /**
     * 作为 entrySet() 的缓存
     * Holds cached entrySet(). Note that AbstractMap fields are used
     * for keySet() and values().
     */
    transient Set<Map.Entry<K,V>> entrySet;

    /**
     * 已经存储 k-v 的数量
     * The number of key-value mappings contained in this map.
     */
    transient int size;

    /**
     * 修改次数，用于在迭代的时候执行快速失败策略
     * The number of times this HashMap has been structurally modified
     * Structural modifications are those that change the number of mappings in
     * the HashMap or otherwise modify its internal structure (e.g.,
     * rehash).  This field is used to make iterators on Collection-views of
     * the HashMap fail-fast.  (See ConcurrentModificationException).
     */
    transient int modCount;

    /**
     * 当桶的使用数量达到多少时进行扩容，threshold = capacity * loadFactor
     * The next size value at which to resize (capacity * load factor).
     *
     * @serial
     */
    // (The javadoc description is true upon serialization.
    // Additionally, if the table array has not been allocated, this
    // field holds the initial array capacity, or zero signifying
    // DEFAULT_INITIAL_CAPACITY.)
    int threshold;

    /**
     * 装载因子
     * The load factor for the hash table.
     *
     * @serial
     */
    final float loadFactor;

    /* ---------------- Public operations -------------- */

    /**
     * 指定初始化容量和加载因子创建
     * Constructs an empty <tt>HashMap</tt> with the specified initial
     * capacity and load factor.
     *
     * @param  initialCapacity the initial capacity
     * @param  loadFactor      the load factor
     * @throws IllegalArgumentException if the initial capacity is negative
     *         or the load factor is nonpositive
     */
    public HashMap(int initialCapacity, float loadFactor) {
        //校验容量
        if (initialCapacity < 0)
            throw new IllegalArgumentException("Illegal initial capacity: " +
                                               initialCapacity);
        //容量超过最大值, 则直接用 MAXIMUM_CAPACITY
        if (initialCapacity > MAXIMUM_CAPACITY)
            initialCapacity = MAXIMUM_CAPACITY;
        //加载因子不能小于等于 0, 且
        if (loadFactor <= 0 || Float.isNaN(loadFactor))
            throw new IllegalArgumentException("Illegal load factor: " +
                                               loadFactor);
        //赋值
        this.loadFactor = loadFactor;
        //计算扩容门槛, 也就是计算当前 initialCapacity 的最小 2 的 n 次方, 当传入数字是 5 , 则返回8, 当是 10 时, 则返回 16
        //这里没有直接存 initialCapacity 的, 而是用 threshold 先存起来
        this.threshold = tableSizeFor(initialCapacity);
    }

    /**
     * Constructs an empty <tt>HashMap</tt> with the specified initial
     * capacity and the default load factor (0.75).
     *
     * @param  initialCapacity the initial capacity.
     * @throws IllegalArgumentException if the initial capacity is negative.
     */
    public HashMap(int initialCapacity) {
        this(initialCapacity, DEFAULT_LOAD_FACTOR);
    }

    /**
     * 默认构造函数, 指定加载因子为 DEFAULT_LOAD_FACTOR
     * Constructs an empty <tt>HashMap</tt> with the default initial capacity
     * (16) and the default load factor (0.75).
     */
    public HashMap() {
        this.loadFactor = DEFAULT_LOAD_FACTOR; // all other fields defaulted
    }

    /**
     * 根据 Map 来构造 HashMap
     * Constructs a new <tt>HashMap</tt> with the same mappings as the
     * specified <tt>Map</tt>.  The <tt>HashMap</tt> is created with
     * default load factor (0.75) and an initial capacity sufficient to
     * hold the mappings in the specified <tt>Map</tt>.
     *
     * @param   m the map whose mappings are to be placed in this map
     * @throws  NullPointerException if the specified map is null
     */
    public HashMap(Map<? extends K, ? extends V> m) {
        this.loadFactor = DEFAULT_LOAD_FACTOR;
        putMapEntries(m, false);
    }

    /**
     * 批量添加
     * Implements Map.putAll and Map constructor
     *
     * @param m the map
     * @param evict false when initially constructing this map, else
     * true (relayed to method afterNodeInsertion).
     */
    final void putMapEntries(Map<? extends K, ? extends V> m, boolean evict) {
        //获取给定 map 的 kv 数量
        int s = m.size();
        //如果有数量
        if (s > 0) {
            //如果当前 HashMap 还没有初始化
            if (table == null) { // pre-size
                //计算数组最少的长度, size / loadFactor + 1
                float ft = ((float)s / loadFactor) + 1.0F;
                //如果计算出的长度小于 MAXIMUM_CAPACITY 最大容量, 则用, 否则直接用 MAXIMUM_CAPACITY
                int t = ((ft < (float)MAXIMUM_CAPACITY) ?
                         (int)ft : MAXIMUM_CAPACITY);
                //如果当前的容量大于 threshold
                if (t > threshold)
                    //重新计算出 threshold, 第一次用它进行扩容
                    threshold = tableSizeFor(t);
            }
            //要加入的map的数量大于 threshold
            else if (s > threshold)
                //扩容
                resize();
            //遍历指定的 Map
            for (Map.Entry<? extends K, ? extends V> e : m.entrySet()) {
                K key = e.getKey();
                V value = e.getValue();
                //添加到当前的 hashMap 中
                putVal(hash(key), key, value, false, evict);
            }
        }
    }

    /**
     * Returns the number of key-value mappings in this map.
     *
     * @return the number of key-value mappings in this map
     */
    public int size() {
        return size;
    }

    /**
     * Returns <tt>true</tt> if this map contains no key-value mappings.
     *
     * @return <tt>true</tt> if this map contains no key-value mappings
     */
    public boolean isEmpty() {
        return size == 0;
    }

    /**
     * 根据 key 获取 value
     * Returns the value to which the specified key is mapped,
     * or {@code null} if this map contains no mapping for the key.
     *
     * <p>More formally, if this map contains a mapping from a key
     * {@code k} to a value {@code v} such that {@code (key==null ? k==null :
     * key.equals(k))}, then this method returns {@code v}; otherwise
     * it returns {@code null}.  (There can be at most one such mapping.)
     *
     * <p>A return value of {@code null} does not <i>necessarily</i>
     * indicate that the map contains no mapping for the key; it's also
     * possible that the map explicitly maps the key to {@code null}.
     * The {@link #containsKey containsKey} operation may be used to
     * distinguish these two cases.
     *
     * @see #put(Object, Object)
     */
    public V get(Object key) {
        //获取 key 对应的 Node
        Node<K,V> e;
        //获取 Node 对应的 value, 如果 Node 是 null, 则返回 null
        return (e = getNode(hash(key), key)) == null ? null : e.value;
    }

    /**
     * 根据 Key 查询相应的 Node 节点
     * Implements Map.get and related methods
     *
     * @param hash hash for key
     * @param key the key
     * @return the node, or null if none
     */
    final Node<K,V> getNode(int hash, Object key) {
        Node<K,V>[] tab; Node<K,V> first, e; int n; K k;
        //如果数组已经初始化且数组长度不为0且槽中的 Node 不为 null
        if ((tab = table) != null && (n = tab.length) > 0 &&
            (first = tab[(n - 1) & hash]) != null) {
            //判断第一个节点是否是对应的 Key , 是则直接返回
            if (first.hash == hash && // always check first node
                ((k = first.key) == key || (key != null && key.equals(k))))
                return first;
            //如果有多个 Node, 则往下遍历
            if ((e = first.next) != null) {
                //如果第一个节点是 TreeNode, 则从树中遍历
                if (first instanceof TreeNode)
                    //强转并查找返回
                    return ((TreeNode<K,V>)first).getTreeNode(hash, key);
                //不是树, 就肯定是链表, 遍历查找
                do {
                    if (e.hash == hash &&
                        ((k = e.key) == key || (key != null && key.equals(k))))
                        return e;
                } while ((e = e.next) != null);
            }
        }
        //没找到, 则返回 null
        return null;
    }

    /**
     * Returns <tt>true</tt> if this map contains a mapping for the
     * specified key.
     *
     * @param   key   The key whose presence in this map is to be tested
     * @return <tt>true</tt> if this map contains a mapping for the specified
     * key.
     */
    public boolean containsKey(Object key) {
        return getNode(hash(key), key) != null;
    }

    /**
     * 添加 kv 对
     * Associates the specified value with the specified key in this map.
     * If the map previously contained a mapping for the key, the old
     * value is replaced.
     *
     * @param key key with which the specified value is to be associated
     * @param value value to be associated with the specified key
     * @return the previous value associated with <tt>key</tt>, or
     *         <tt>null</tt> if there was no mapping for <tt>key</tt>.
     *         (A <tt>null</tt> return can also indicate that the map
     *         previously associated <tt>null</tt> with <tt>key</tt>.)
     */
    public V put(K key, V value) {
        //调用内部方法进行添加
        return putVal(hash(key), key, value, false, true);
    }

    /**
     * 添加值的内部通用实现
     * Implements Map.put and related methods
     *
     * @param hash hash for key (key的hash)
     * @param key the key
     * @param value the value to put
     * @param onlyIfAbsent if true, don't change existing value
     * @param evict if false, the table is in creation mode.
     * @return previous value, or null if none
     */
    final V putVal(int hash, K key, V value, boolean onlyIfAbsent,
                   boolean evict) {
        //tab 表示数组
        //这里的 p 表示当前 hash 槽的第一个 Node , 首节点, 在下面表示前一个节点
        //n 表示数组长度,
        // i 表示当前 hash 在数组的索引
        Node<K,V>[] tab; Node<K,V> p; int n, i;
        //如果数组未初始化
        if ((tab = table) == null || (n = tab.length) == 0)
            //调用  resize() 进行初始化, 并返回数组长度
            n = (tab = resize()).length;
        //如果当前 hash 槽没有节点, 则直接创建
        if ((p = tab[i = (n - 1) & hash]) == null)
            //创建 Node 节点, 并放入槽
            tab[i] = newNode(hash, key, value, null);
        //else 分支表示 hash 槽已经有 Node 存在了
        else {
            //e 表示与 key 相等的 Node
            Node<K,V> e;
            K k;
            //第一个 Node 的 hash 与给定的相等, key 也相等, 则缓存这个 Node 到 e 中
            if (p.hash == hash &&
                ((k = p.key) == key || (key != null && key.equals(k))))
                e = p;
            //如果 p 首节点是 TreeNode, 则从红黑树中查找
            else if (p instanceof TreeNode)
                e = ((TreeNode<K,V>)p).putTreeVal(this, tab, hash, key, value);
            else {
                // 遍历这个桶对应的链表，binCount 用于存储链表中元素的个数
                for (int binCount = 0; ; ++binCount) {
                    //获取下一个节点, 并判断 next 是否为 null
                    if ((e = p.next) == null) {
                        //如果 next 是 null, 则创建新的 Node 放入链表中
                        p.next = newNode(hash, key, value, null);
                        //如果插入新节点后链表长度大于8，则判断是否需要树化，因为第一个元素没有算入到binCount中，所以这里要减 1
                        if (binCount >= TREEIFY_THRESHOLD - 1) // -1 for 1st
                            //如果必要的话则树化, 因为还要判断数组的长度够不够
                            treeifyBin(tab, hash);
                        //创建完节点则退出
                        break;
                    }
                    //如果找到相同 key 的 Node 也退出循环
                    if (e.hash == hash &&
                        ((k = e.key) == key || (key != null && key.equals(k))))
                        break;
                    //更新 p 节点, 这里表示为前一个节点
                    p = e;
                }
            }
            //如果找到相同的 Node
            if (e != null) { // existing mapping for key
                //获取旧值
                V oldValue = e.value;
                // onlyIfAbsent 为false 或没有值, 则更新
                //onlyIfAbsent 表示有值则不更新
                if (!onlyIfAbsent || oldValue == null)
                    e.value = value;
                //在节点被访问后做点什么事，在LinkedHashMap中用到
                afterNodeAccess(e);
                //返回旧值
                return oldValue;
            }
        }
        // 到这里了说明没有找到相同key的Node
        //修改次数加1
        ++modCount;
        //元素的数量加 1, 如果数量大于 threshold, 则扩容
        if (++size > threshold)
            resize();
        //在节点插入后做点什么事，在LinkedHashMap中用到
        afterNodeInsertion(evict);
        //没有旧值, 返回 null
        return null;
    }

    /**
     * 扩容方法
     * Initializes or doubles table size.  If null, allocates in
     * accord with initial capacity target held in field threshold.
     * Otherwise, because we are using power-of-two expansion, the
     * elements from each bin must either stay at same index, or move
     * with a power of two offset in the new table.
     *
     * @return the table
     */
    final Node<K,V>[] resize() {
        //获取旧的 Node 数组
        Node<K,V>[] oldTab = table;
        //获取旧数组的长度, 如果没有初始化则为 0
        int oldCap = (oldTab == null) ? 0 : oldTab.length;
        //获取旧的 threshold
        int oldThr = threshold;
        //新容量, 新 threshold
        int newCap, newThr = 0;
        //如果旧容量大于 0
        if (oldCap > 0) {
            //旧容量大于 MAXIMUM_CAPACITY, 则  threshold 取 Integer.MAX_VALUE
            if (oldCap >= MAXIMUM_CAPACITY) {
                threshold = Integer.MAX_VALUE;
                //容量已经超过最大值, 不能再扩容了, 所以直接返回旧的数组
                return oldTab;
            }
            //如果旧容量 double 没超出最大容量, 则将新 threshold 也 double(两倍), 也就是正常的扩容是两倍
            else if ((newCap = oldCap << 1) < MAXIMUM_CAPACITY &&
                     oldCap >= DEFAULT_INITIAL_CAPACITY)
                newThr = oldThr << 1; // double threshold
        }
        //如果旧容量为 0 , 但旧 threshold 不为 0, 则表示创建 hashMap 的时候指定了容量
        else if (oldThr > 0) // initial capacity was placed in threshold
            //用旧的 threshold 作为新容量
            newCap = oldThr;
        else {               // zero initial threshold signifies using defaults
            //默认构造函数的初始化的情况
            newCap = DEFAULT_INITIAL_CAPACITY;
            newThr = (int)(DEFAULT_LOAD_FACTOR * DEFAULT_INITIAL_CAPACITY);
        }
        //如果是指定容量创建的 hashMap , 则 newThr 肯定为 0
        if (newThr == 0) {
            //计算阈值
            float ft = (float)newCap * loadFactor;
            //如果 newCap 没超过最大容量且 ft 也没超过最大容量, 则用 ft 做新阈值, 如果超出则用 Integer.MAX_VALUE
            newThr = (newCap < MAXIMUM_CAPACITY && ft < (float)MAXIMUM_CAPACITY ?
                      (int)ft : Integer.MAX_VALUE);
        }
        //设置新的阈值
        threshold = newThr;
        //创建新的数组
        @SuppressWarnings({"rawtypes","unchecked"})
            Node<K,V>[] newTab = (Node<K,V>[])new Node[newCap];
        //设置新的 Node 数组
        table = newTab;
        //如果旧的 Node 数组不为 null
        if (oldTab != null) {
            //遍历旧数组
            for (int j = 0; j < oldCap; ++j) {
                //当前索引的 Node
                Node<K,V> e;
                // 如果桶中第一个 Node 不为空，赋值给e
                if ((e = oldTab[j]) != null) {
                    // 清空旧桶，便于GC回收
                    oldTab[j] = null;
                    // 如果这个桶中只有一个元素，则计算它在新桶中的位置并把它搬移到新桶中
                    // 因为每次都扩容两倍，所以这里的第一个元素搬移到新桶的时候新桶在对应的位置上肯定还没有元素
                    if (e.next == null)
                        newTab[e.hash & (newCap - 1)] = e;
                    //如果第一个元素是树节点，则把这颗树打散成两颗树插入到新桶中去
                    else if (e instanceof TreeNode)
                        ((TreeNode<K,V>)e).split(this, newTab, j, oldCap);
                    else { // preserve order
                        // 如果这个链表不止一个元素且不是一颗树
                        // 则分化成两个链表插入到新的桶中去
                        // 比如，假如原来容量为4，3、7、11、15这四个元素都在三号桶中
                        // 现在扩容到8，则3和11还是在三号桶，7和15要搬移到七号桶中去
                        // 也就是分化成了两个链表

                        //低位链表, 如 3 , 11
                        Node<K,V> loHead = null, loTail = null;
                        //高位链接, 如 7 , 15
                        Node<K,V> hiHead = null, hiTail = null;

                        //下一个节点
                        Node<K,V> next;
                        do {
                            next = e.next;
                            //(e.hash & oldCap) == 0的元素放在低位链表中
                            // 比如，3 & 4 == 0
                            if ((e.hash & oldCap) == 0) {
                                if (loTail == null)
                                    loHead = e;
                                else
                                    loTail.next = e;
                                loTail = e;
                            }
                            else {
                                // (e.hash & oldCap) != 0的元素放在高位链表中
                                // 比如，7 & 4 != 0
                                if (hiTail == null)
                                    hiHead = e;
                                else
                                    hiTail.next = e;
                                hiTail = e;
                            }
                        } while ((e = next) != null);
                        // 遍历完成分化成两个链表了
                        // 低位链表在新桶中的位置与旧桶一样（即3和11还在三号桶中）
                        if (loTail != null) {
                            loTail.next = null;
                            newTab[j] = loHead;
                        }
                        // 高位链表在新桶中的位置正好是原来的位置加上旧容量（即7和15搬移到七号桶了）
                        if (hiTail != null) {
                            hiTail.next = null;
                            newTab[j + oldCap] = hiHead;
                        }
                    }
                }
            }
        }
        //返回新建的数组
        return newTab;
    }

    /**
     * 如果可以树化则进行树化, 否则扩容
     * - 进行树化前, 会将 Node 节点转变成 TreeNode 节点, 并维护双向链表
     * Replaces all linked nodes in bin at index for given hash unless
     * table is too small, in which case resizes instead.
     */
    final void treeifyBin(Node<K,V>[] tab, int hash) {
        int n, index; Node<K,V> e;
        //如果数组没初始化, 或数组长度长度不够树化的最小值
        if (tab == null || (n = tab.length) < MIN_TREEIFY_CAPACITY)
            // 如果桶数量小于64，直接扩容而不用树化
            // 因为扩容之后，链表会分化成两个链表，达到减少元素的作用
            // 当然也不一定，比如容量为4，里面存的全是除以4余数等于3的元素
            // 这样即使扩容也无法减少链表的长度
            resize();
        //如果数组容量已经足够, 则根据当前 hash 获取 Node 节点
        else if ((e = tab[index = (n - 1) & hash]) != null) {
            //hd 头节点, tl 尾节点
            TreeNode<K,V> hd = null, tl = null;
            do {
                //根据 Node 创建 TreeNode 节点
                TreeNode<K,V> p = replacementTreeNode(e, null);
                //如果尾节点为 null, 则初始化
                if (tl == null)
                    hd = p;
                else {
                    p.prev = tl;
                    tl.next = p;
                }
                //每次都将创建的 p(TreeNode) 加到链表尾部
                tl = p;
                //遍历下一个节点
            } while ((e = e.next) != null);
            //将双向链表的头节点放入数组槽中
            if ((tab[index] = hd) != null)
                //树化处理
                hd.treeify(tab);
        }
    }

    /**
     * Copies all of the mappings from the specified map to this map.
     * These mappings will replace any mappings that this map had for
     * any of the keys currently in the specified map.
     *
     * @param m mappings to be stored in this map
     * @throws NullPointerException if the specified map is null
     */
    public void putAll(Map<? extends K, ? extends V> m) {
        putMapEntries(m, true);
    }

    /**
     * 根据 key 删除
     * Removes the mapping for the specified key from this map if present.
     *
     * @param  key key whose mapping is to be removed from the map
     * @return the previous value associated with <tt>key</tt>, or
     *         <tt>null</tt> if there was no mapping for <tt>key</tt>.
     *         (A <tt>null</tt> return can also indicate that the map
     *         previously associated <tt>null</tt> with <tt>key</tt>.)
     */
    public V remove(Object key) {
        Node<K,V> e;
        //能找到 Node 则返回 value, 否则返回 null
        return (e = removeNode(hash(key), key, null, false, true)) == null ?
            null : e.value;
    }

    /**
     * 删除指定的 Node
     * Implements Map.remove and related methods
     *
     * @param hash hash for key
     * @param key the key
     * @param value the value to match if matchValue, else ignored
     * @param matchValue if true only remove if value is equal (是否值也要相等才能删除, true 表示要值也匹配)
     * @param movable if false do not move other nodes while removing
     * @return the node, or null if none
     */
    final Node<K,V> removeNode(int hash, Object key, Object value,
                               boolean matchValue, boolean movable) {
        //p 表示前一个节点, 用于删除时重新链接下一个节点
        Node<K,V>[] tab; Node<K,V> p; int n, index;
        //数组初始化了且长度不为0且key对应的槽中 Node 存在
        if ((tab = table) != null && (n = tab.length) > 0 &&
            (p = tab[index = (n - 1) & hash]) != null) {
            //node 表示查询到相等 key 的 Node
            //e 表示下一个节点
            Node<K,V> node = null, e; K k; V v;
            //如果第一个节点的 key 就相等
            if (p.hash == hash &&
                ((k = p.key) == key || (key != null && key.equals(k))))
                //放到 node 中缓存
                node = p;
            //如果槽位的节点有多个
            else if ((e = p.next) != null) {
                //判断是否则树
                if (p instanceof TreeNode)
                    //根据 hash 和 key 从树中查找
                    node = ((TreeNode<K,V>)p).getTreeNode(hash, key);
                //不是树就是链表
                else {
                    //遍历
                    do {
                        //如果 hash 和 key 等相等, 则放入 node 中
                        if (e.hash == hash &&
                            ((k = e.key) == key ||
                             (key != null && key.equals(k)))) {
                            node = e;
                            //找到, 退出循环
                            break;
                        }
                        //往下迭代
                        p = e;
                    } while ((e = e.next) != null);
                }
            }
            //如果最终找到节点,  则判断是否要根据值删除
            if (node != null && (!matchValue || (v = node.value) == value ||
                                 (value != null && value.equals(v)))) {
                //删除树中的节点
                if (node instanceof TreeNode)
                    ((TreeNode<K,V>)node).removeTreeNode(this, tab, movable);
                //如果是槽中第一个节点
                else if (node == p)
                    //直接把下一个节点放入数组中
                    tab[index] = node.next;
                //如果不是第一个节点, 则删除 node , 并重新链接
                else
                    p.next = node.next;
                //统计修改次数
                ++modCount;
                //kv 个数减 1
                --size;
                //执行删除后的相关操作
                afterNodeRemoval(node);
                //返回删除的 Node
                return node;
            }
        }
        //没找到, 返回 null
        return null;
    }

    /**
     * Removes all of the mappings from this map.
     * The map will be empty after this call returns.
     */
    public void clear() {
        Node<K,V>[] tab;
        modCount++;
        if ((tab = table) != null && size > 0) {
            size = 0;
            for (int i = 0; i < tab.length; ++i)
                tab[i] = null;
        }
    }

    /**
     * Returns <tt>true</tt> if this map maps one or more keys to the
     * specified value.
     *
     * @param value value whose presence in this map is to be tested
     * @return <tt>true</tt> if this map maps one or more keys to the
     *         specified value
     */
    public boolean containsValue(Object value) {
        Node<K,V>[] tab; V v;
        if ((tab = table) != null && size > 0) {
            for (int i = 0; i < tab.length; ++i) {
                for (Node<K,V> e = tab[i]; e != null; e = e.next) {
                    if ((v = e.value) == value ||
                        (value != null && value.equals(v)))
                        return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns a {@link Set} view of the keys contained in this map.
     * The set is backed by the map, so changes to the map are
     * reflected in the set, and vice-versa.  If the map is modified
     * while an iteration over the set is in progress (except through
     * the iterator's own <tt>remove</tt> operation), the results of
     * the iteration are undefined.  The set supports element removal,
     * which removes the corresponding mapping from the map, via the
     * <tt>Iterator.remove</tt>, <tt>Set.remove</tt>,
     * <tt>removeAll</tt>, <tt>retainAll</tt>, and <tt>clear</tt>
     * operations.  It does not support the <tt>add</tt> or <tt>addAll</tt>
     * operations.
     *
     * @return a set view of the keys contained in this map
     */
    public Set<K> keySet() {
        Set<K> ks;
        return (ks = keySet) == null ? (keySet = new KeySet()) : ks;
    }

    final class KeySet extends AbstractSet<K> {
        public final int size()                 { return size; }
        public final void clear()               { HashMap.this.clear(); }
        public final Iterator<K> iterator()     { return new KeyIterator(); }
        public final boolean contains(Object o) { return containsKey(o); }
        public final boolean remove(Object key) {
            return removeNode(hash(key), key, null, false, true) != null;
        }
        public final Spliterator<K> spliterator() {
            return new KeySpliterator<>(HashMap.this, 0, -1, 0, 0);
        }
        public final void forEach(Consumer<? super K> action) {
            Node<K,V>[] tab;
            if (action == null)
                throw new NullPointerException();
            if (size > 0 && (tab = table) != null) {
                int mc = modCount;
                for (int i = 0; i < tab.length; ++i) {
                    for (Node<K,V> e = tab[i]; e != null; e = e.next)
                        action.accept(e.key);
                }
                if (modCount != mc)
                    throw new ConcurrentModificationException();
            }
        }
    }

    /**
     * Returns a {@link Collection} view of the values contained in this map.
     * The collection is backed by the map, so changes to the map are
     * reflected in the collection, and vice-versa.  If the map is
     * modified while an iteration over the collection is in progress
     * (except through the iterator's own <tt>remove</tt> operation),
     * the results of the iteration are undefined.  The collection
     * supports element removal, which removes the corresponding
     * mapping from the map, via the <tt>Iterator.remove</tt>,
     * <tt>Collection.remove</tt>, <tt>removeAll</tt>,
     * <tt>retainAll</tt> and <tt>clear</tt> operations.  It does not
     * support the <tt>add</tt> or <tt>addAll</tt> operations.
     *
     * @return a view of the values contained in this map
     */
    public Collection<V> values() {
        Collection<V> vs;
        return (vs = values) == null ? (values = new Values()) : vs;
    }

    final class Values extends AbstractCollection<V> {
        public final int size()                 { return size; }
        public final void clear()               { HashMap.this.clear(); }
        public final Iterator<V> iterator()     { return new ValueIterator(); }
        public final boolean contains(Object o) { return containsValue(o); }
        public final Spliterator<V> spliterator() {
            return new ValueSpliterator<>(HashMap.this, 0, -1, 0, 0);
        }
        public final void forEach(Consumer<? super V> action) {
            Node<K,V>[] tab;
            if (action == null)
                throw new NullPointerException();
            if (size > 0 && (tab = table) != null) {
                int mc = modCount;
                for (int i = 0; i < tab.length; ++i) {
                    for (Node<K,V> e = tab[i]; e != null; e = e.next)
                        action.accept(e.value);
                }
                if (modCount != mc)
                    throw new ConcurrentModificationException();
            }
        }
    }

    /**
     * Returns a {@link Set} view of the mappings contained in this map.
     * The set is backed by the map, so changes to the map are
     * reflected in the set, and vice-versa.  If the map is modified
     * while an iteration over the set is in progress (except through
     * the iterator's own <tt>remove</tt> operation, or through the
     * <tt>setValue</tt> operation on a map entry returned by the
     * iterator) the results of the iteration are undefined.  The set
     * supports element removal, which removes the corresponding
     * mapping from the map, via the <tt>Iterator.remove</tt>,
     * <tt>Set.remove</tt>, <tt>removeAll</tt>, <tt>retainAll</tt> and
     * <tt>clear</tt> operations.  It does not support the
     * <tt>add</tt> or <tt>addAll</tt> operations.
     *
     * @return a set view of the mappings contained in this map
     */
    public Set<Map.Entry<K,V>> entrySet() {
        Set<Map.Entry<K,V>> es;
        return (es = entrySet) == null ? (entrySet = new EntrySet()) : es;
    }

    final class EntrySet extends AbstractSet<Map.Entry<K,V>> {
        public final int size()                 { return size; }
        public final void clear()               { HashMap.this.clear(); }
        public final Iterator<Map.Entry<K,V>> iterator() {
            return new EntryIterator();
        }
        public final boolean contains(Object o) {
            if (!(o instanceof Map.Entry))
                return false;
            Map.Entry<?,?> e = (Map.Entry<?,?>) o;
            Object key = e.getKey();
            Node<K,V> candidate = getNode(hash(key), key);
            return candidate != null && candidate.equals(e);
        }
        public final boolean remove(Object o) {
            if (o instanceof Map.Entry) {
                Map.Entry<?,?> e = (Map.Entry<?,?>) o;
                Object key = e.getKey();
                Object value = e.getValue();
                return removeNode(hash(key), key, value, true, true) != null;
            }
            return false;
        }
        public final Spliterator<Map.Entry<K,V>> spliterator() {
            return new EntrySpliterator<>(HashMap.this, 0, -1, 0, 0);
        }
        public final void forEach(Consumer<? super Map.Entry<K,V>> action) {
            Node<K,V>[] tab;
            if (action == null)
                throw new NullPointerException();
            if (size > 0 && (tab = table) != null) {
                int mc = modCount;
                for (int i = 0; i < tab.length; ++i) {
                    for (Node<K,V> e = tab[i]; e != null; e = e.next)
                        action.accept(e);
                }
                if (modCount != mc)
                    throw new ConcurrentModificationException();
            }
        }
    }

    // Overrides of JDK8 Map extension methods

    @Override
    public V getOrDefault(Object key, V defaultValue) {
        Node<K,V> e;
        return (e = getNode(hash(key), key)) == null ? defaultValue : e.value;
    }

    @Override
    public V putIfAbsent(K key, V value) {
        return putVal(hash(key), key, value, true, true);
    }

    @Override
    public boolean remove(Object key, Object value) {
        return removeNode(hash(key), key, value, true, true) != null;
    }

    @Override
    public boolean replace(K key, V oldValue, V newValue) {
        Node<K,V> e; V v;
        if ((e = getNode(hash(key), key)) != null &&
            ((v = e.value) == oldValue || (v != null && v.equals(oldValue)))) {
            e.value = newValue;
            afterNodeAccess(e);
            return true;
        }
        return false;
    }

    @Override
    public V replace(K key, V value) {
        Node<K,V> e;
        if ((e = getNode(hash(key), key)) != null) {
            V oldValue = e.value;
            e.value = value;
            afterNodeAccess(e);
            return oldValue;
        }
        return null;
    }

    @Override
    public V computeIfAbsent(K key,
                             Function<? super K, ? extends V> mappingFunction) {
        if (mappingFunction == null)
            throw new NullPointerException();
        int hash = hash(key);
        Node<K,V>[] tab; Node<K,V> first; int n, i;
        int binCount = 0;
        TreeNode<K,V> t = null;
        Node<K,V> old = null;
        if (size > threshold || (tab = table) == null ||
            (n = tab.length) == 0)
            n = (tab = resize()).length;
        if ((first = tab[i = (n - 1) & hash]) != null) {
            if (first instanceof TreeNode)
                old = (t = (TreeNode<K,V>)first).getTreeNode(hash, key);
            else {
                Node<K,V> e = first; K k;
                do {
                    if (e.hash == hash &&
                        ((k = e.key) == key || (key != null && key.equals(k)))) {
                        old = e;
                        break;
                    }
                    ++binCount;
                } while ((e = e.next) != null);
            }
            V oldValue;
            if (old != null && (oldValue = old.value) != null) {
                afterNodeAccess(old);
                return oldValue;
            }
        }
        V v = mappingFunction.apply(key);
        if (v == null) {
            return null;
        } else if (old != null) {
            old.value = v;
            afterNodeAccess(old);
            return v;
        }
        else if (t != null)
            t.putTreeVal(this, tab, hash, key, v);
        else {
            tab[i] = newNode(hash, key, v, first);
            if (binCount >= TREEIFY_THRESHOLD - 1)
                treeifyBin(tab, hash);
        }
        ++modCount;
        ++size;
        afterNodeInsertion(true);
        return v;
    }

    public V computeIfPresent(K key,
                              BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        if (remappingFunction == null)
            throw new NullPointerException();
        Node<K,V> e; V oldValue;
        int hash = hash(key);
        if ((e = getNode(hash, key)) != null &&
            (oldValue = e.value) != null) {
            V v = remappingFunction.apply(key, oldValue);
            if (v != null) {
                e.value = v;
                afterNodeAccess(e);
                return v;
            }
            else
                removeNode(hash, key, null, false, true);
        }
        return null;
    }

    @Override
    public V compute(K key,
                     BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        if (remappingFunction == null)
            throw new NullPointerException();
        int hash = hash(key);
        Node<K,V>[] tab; Node<K,V> first; int n, i;
        int binCount = 0;
        TreeNode<K,V> t = null;
        Node<K,V> old = null;
        if (size > threshold || (tab = table) == null ||
            (n = tab.length) == 0)
            n = (tab = resize()).length;
        if ((first = tab[i = (n - 1) & hash]) != null) {
            if (first instanceof TreeNode)
                old = (t = (TreeNode<K,V>)first).getTreeNode(hash, key);
            else {
                Node<K,V> e = first; K k;
                do {
                    if (e.hash == hash &&
                        ((k = e.key) == key || (key != null && key.equals(k)))) {
                        old = e;
                        break;
                    }
                    ++binCount;
                } while ((e = e.next) != null);
            }
        }
        V oldValue = (old == null) ? null : old.value;
        V v = remappingFunction.apply(key, oldValue);
        if (old != null) {
            if (v != null) {
                old.value = v;
                afterNodeAccess(old);
            }
            else
                removeNode(hash, key, null, false, true);
        }
        else if (v != null) {
            if (t != null)
                t.putTreeVal(this, tab, hash, key, v);
            else {
                tab[i] = newNode(hash, key, v, first);
                if (binCount >= TREEIFY_THRESHOLD - 1)
                    treeifyBin(tab, hash);
            }
            ++modCount;
            ++size;
            afterNodeInsertion(true);
        }
        return v;
    }

    @Override
    public V merge(K key, V value,
                   BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
        if (value == null)
            throw new NullPointerException();
        if (remappingFunction == null)
            throw new NullPointerException();
        int hash = hash(key);
        Node<K,V>[] tab; Node<K,V> first; int n, i;
        int binCount = 0;
        TreeNode<K,V> t = null;
        Node<K,V> old = null;
        if (size > threshold || (tab = table) == null ||
            (n = tab.length) == 0)
            n = (tab = resize()).length;
        if ((first = tab[i = (n - 1) & hash]) != null) {
            if (first instanceof TreeNode)
                old = (t = (TreeNode<K,V>)first).getTreeNode(hash, key);
            else {
                Node<K,V> e = first; K k;
                do {
                    if (e.hash == hash &&
                        ((k = e.key) == key || (key != null && key.equals(k)))) {
                        old = e;
                        break;
                    }
                    ++binCount;
                } while ((e = e.next) != null);
            }
        }
        if (old != null) {
            V v;
            if (old.value != null)
                v = remappingFunction.apply(old.value, value);
            else
                v = value;
            if (v != null) {
                old.value = v;
                afterNodeAccess(old);
            }
            else
                removeNode(hash, key, null, false, true);
            return v;
        }
        if (value != null) {
            if (t != null)
                t.putTreeVal(this, tab, hash, key, value);
            else {
                tab[i] = newNode(hash, key, value, first);
                if (binCount >= TREEIFY_THRESHOLD - 1)
                    treeifyBin(tab, hash);
            }
            ++modCount;
            ++size;
            afterNodeInsertion(true);
        }
        return value;
    }

    @Override
    public void forEach(BiConsumer<? super K, ? super V> action) {
        Node<K,V>[] tab;
        if (action == null)
            throw new NullPointerException();
        if (size > 0 && (tab = table) != null) {
            int mc = modCount;
            for (int i = 0; i < tab.length; ++i) {
                for (Node<K,V> e = tab[i]; e != null; e = e.next)
                    action.accept(e.key, e.value);
            }
            if (modCount != mc)
                throw new ConcurrentModificationException();
        }
    }

    @Override
    public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
        Node<K,V>[] tab;
        if (function == null)
            throw new NullPointerException();
        if (size > 0 && (tab = table) != null) {
            int mc = modCount;
            for (int i = 0; i < tab.length; ++i) {
                for (Node<K,V> e = tab[i]; e != null; e = e.next) {
                    e.value = function.apply(e.key, e.value);
                }
            }
            if (modCount != mc)
                throw new ConcurrentModificationException();
        }
    }

    /* ------------------------------------------------------------ */
    // Cloning and serialization

    /**
     * Returns a shallow copy of this <tt>HashMap</tt> instance: the keys and
     * values themselves are not cloned.
     *
     * @return a shallow copy of this map
     */
    @SuppressWarnings("unchecked")
    @Override
    public Object clone() {
        HashMap<K,V> result;
        try {
            result = (HashMap<K,V>)super.clone();
        } catch (CloneNotSupportedException e) {
            // this shouldn't happen, since we are Cloneable
            throw new InternalError(e);
        }
        result.reinitialize();
        result.putMapEntries(this, false);
        return result;
    }

    // These methods are also used when serializing HashSets
    final float loadFactor() { return loadFactor; }
    final int capacity() {
        return (table != null) ? table.length :
            (threshold > 0) ? threshold :
            DEFAULT_INITIAL_CAPACITY;
    }

    /**
     * Save the state of the <tt>HashMap</tt> instance to a stream (i.e.,
     * serialize it).
     *
     * @serialData The <i>capacity</i> of the HashMap (the length of the
     *             bucket array) is emitted (int), followed by the
     *             <i>size</i> (an int, the number of key-value
     *             mappings), followed by the key (Object) and value (Object)
     *             for each key-value mapping.  The key-value mappings are
     *             emitted in no particular order.
     */
    private void writeObject(java.io.ObjectOutputStream s)
        throws IOException {
        int buckets = capacity();
        // Write out the threshold, loadfactor, and any hidden stuff
        s.defaultWriteObject();
        s.writeInt(buckets);
        s.writeInt(size);
        internalWriteEntries(s);
    }

    /**
     * Reconstitute the {@code HashMap} instance from a stream (i.e.,
     * deserialize it).
     */
    private void readObject(java.io.ObjectInputStream s)
        throws IOException, ClassNotFoundException {
        // Read in the threshold (ignored), loadfactor, and any hidden stuff
        s.defaultReadObject();
        reinitialize();
        if (loadFactor <= 0 || Float.isNaN(loadFactor))
            throw new InvalidObjectException("Illegal load factor: " +
                                             loadFactor);
        s.readInt();                // Read and ignore number of buckets
        int mappings = s.readInt(); // Read number of mappings (size)
        if (mappings < 0)
            throw new InvalidObjectException("Illegal mappings count: " +
                                             mappings);
        else if (mappings > 0) { // (if zero, use defaults)
            // Size the table using given load factor only if within
            // range of 0.25...4.0
            float lf = Math.min(Math.max(0.25f, loadFactor), 4.0f);
            float fc = (float)mappings / lf + 1.0f;
            int cap = ((fc < DEFAULT_INITIAL_CAPACITY) ?
                       DEFAULT_INITIAL_CAPACITY :
                       (fc >= MAXIMUM_CAPACITY) ?
                       MAXIMUM_CAPACITY :
                       tableSizeFor((int)fc));
            float ft = (float)cap * lf;
            threshold = ((cap < MAXIMUM_CAPACITY && ft < MAXIMUM_CAPACITY) ?
                         (int)ft : Integer.MAX_VALUE);
            @SuppressWarnings({"rawtypes","unchecked"})
                Node<K,V>[] tab = (Node<K,V>[])new Node[cap];
            table = tab;

            // Read the keys and values, and put the mappings in the HashMap
            for (int i = 0; i < mappings; i++) {
                @SuppressWarnings("unchecked")
                    K key = (K) s.readObject();
                @SuppressWarnings("unchecked")
                    V value = (V) s.readObject();
                putVal(hash(key), key, value, false, false);
            }
        }
    }

    /* ------------------------------------------------------------ */
    // iterators

    abstract class HashIterator {
        Node<K,V> next;        // next entry to return
        Node<K,V> current;     // current entry
        int expectedModCount;  // for fast-fail
        int index;             // current slot

        HashIterator() {
            expectedModCount = modCount;
            Node<K,V>[] t = table;
            current = next = null;
            index = 0;
            if (t != null && size > 0) { // advance to first entry
                do {} while (index < t.length && (next = t[index++]) == null);
            }
        }

        public final boolean hasNext() {
            return next != null;
        }

        final Node<K,V> nextNode() {
            Node<K,V>[] t;
            Node<K,V> e = next;
            if (modCount != expectedModCount)
                throw new ConcurrentModificationException();
            if (e == null)
                throw new NoSuchElementException();
            if ((next = (current = e).next) == null && (t = table) != null) {
                do {} while (index < t.length && (next = t[index++]) == null);
            }
            return e;
        }

        public final void remove() {
            Node<K,V> p = current;
            if (p == null)
                throw new IllegalStateException();
            if (modCount != expectedModCount)
                throw new ConcurrentModificationException();
            current = null;
            K key = p.key;
            removeNode(hash(key), key, null, false, false);
            expectedModCount = modCount;
        }
    }

    final class KeyIterator extends HashIterator
        implements Iterator<K> {
        public final K next() { return nextNode().key; }
    }

    final class ValueIterator extends HashIterator
        implements Iterator<V> {
        public final V next() { return nextNode().value; }
    }

    final class EntryIterator extends HashIterator
        implements Iterator<Map.Entry<K,V>> {
        public final Map.Entry<K,V> next() { return nextNode(); }
    }

    /* ------------------------------------------------------------ */
    // spliterators

    static class HashMapSpliterator<K,V> {
        final HashMap<K,V> map;
        Node<K,V> current;          // current node
        int index;                  // current index, modified on advance/split
        int fence;                  // one past last index
        int est;                    // size estimate
        int expectedModCount;       // for comodification checks

        HashMapSpliterator(HashMap<K,V> m, int origin,
                           int fence, int est,
                           int expectedModCount) {
            this.map = m;
            this.index = origin;
            this.fence = fence;
            this.est = est;
            this.expectedModCount = expectedModCount;
        }

        final int getFence() { // initialize fence and size on first use
            int hi;
            if ((hi = fence) < 0) {
                HashMap<K,V> m = map;
                est = m.size;
                expectedModCount = m.modCount;
                Node<K,V>[] tab = m.table;
                hi = fence = (tab == null) ? 0 : tab.length;
            }
            return hi;
        }

        public final long estimateSize() {
            getFence(); // force init
            return (long) est;
        }
    }

    static final class KeySpliterator<K,V>
        extends HashMapSpliterator<K,V>
        implements Spliterator<K> {
        KeySpliterator(HashMap<K,V> m, int origin, int fence, int est,
                       int expectedModCount) {
            super(m, origin, fence, est, expectedModCount);
        }

        public KeySpliterator<K,V> trySplit() {
            int hi = getFence(), lo = index, mid = (lo + hi) >>> 1;
            return (lo >= mid || current != null) ? null :
                new KeySpliterator<>(map, lo, index = mid, est >>>= 1,
                                        expectedModCount);
        }

        public void forEachRemaining(Consumer<? super K> action) {
            int i, hi, mc;
            if (action == null)
                throw new NullPointerException();
            HashMap<K,V> m = map;
            Node<K,V>[] tab = m.table;
            if ((hi = fence) < 0) {
                mc = expectedModCount = m.modCount;
                hi = fence = (tab == null) ? 0 : tab.length;
            }
            else
                mc = expectedModCount;
            if (tab != null && tab.length >= hi &&
                (i = index) >= 0 && (i < (index = hi) || current != null)) {
                Node<K,V> p = current;
                current = null;
                do {
                    if (p == null)
                        p = tab[i++];
                    else {
                        action.accept(p.key);
                        p = p.next;
                    }
                } while (p != null || i < hi);
                if (m.modCount != mc)
                    throw new ConcurrentModificationException();
            }
        }

        public boolean tryAdvance(Consumer<? super K> action) {
            int hi;
            if (action == null)
                throw new NullPointerException();
            Node<K,V>[] tab = map.table;
            if (tab != null && tab.length >= (hi = getFence()) && index >= 0) {
                while (current != null || index < hi) {
                    if (current == null)
                        current = tab[index++];
                    else {
                        K k = current.key;
                        current = current.next;
                        action.accept(k);
                        if (map.modCount != expectedModCount)
                            throw new ConcurrentModificationException();
                        return true;
                    }
                }
            }
            return false;
        }

        public int characteristics() {
            return (fence < 0 || est == map.size ? Spliterator.SIZED : 0) |
                Spliterator.DISTINCT;
        }
    }

    static final class ValueSpliterator<K,V>
        extends HashMapSpliterator<K,V>
        implements Spliterator<V> {
        ValueSpliterator(HashMap<K,V> m, int origin, int fence, int est,
                         int expectedModCount) {
            super(m, origin, fence, est, expectedModCount);
        }

        public ValueSpliterator<K,V> trySplit() {
            int hi = getFence(), lo = index, mid = (lo + hi) >>> 1;
            return (lo >= mid || current != null) ? null :
                new ValueSpliterator<>(map, lo, index = mid, est >>>= 1,
                                          expectedModCount);
        }

        public void forEachRemaining(Consumer<? super V> action) {
            int i, hi, mc;
            if (action == null)
                throw new NullPointerException();
            HashMap<K,V> m = map;
            Node<K,V>[] tab = m.table;
            if ((hi = fence) < 0) {
                mc = expectedModCount = m.modCount;
                hi = fence = (tab == null) ? 0 : tab.length;
            }
            else
                mc = expectedModCount;
            if (tab != null && tab.length >= hi &&
                (i = index) >= 0 && (i < (index = hi) || current != null)) {
                Node<K,V> p = current;
                current = null;
                do {
                    if (p == null)
                        p = tab[i++];
                    else {
                        action.accept(p.value);
                        p = p.next;
                    }
                } while (p != null || i < hi);
                if (m.modCount != mc)
                    throw new ConcurrentModificationException();
            }
        }

        public boolean tryAdvance(Consumer<? super V> action) {
            int hi;
            if (action == null)
                throw new NullPointerException();
            Node<K,V>[] tab = map.table;
            if (tab != null && tab.length >= (hi = getFence()) && index >= 0) {
                while (current != null || index < hi) {
                    if (current == null)
                        current = tab[index++];
                    else {
                        V v = current.value;
                        current = current.next;
                        action.accept(v);
                        if (map.modCount != expectedModCount)
                            throw new ConcurrentModificationException();
                        return true;
                    }
                }
            }
            return false;
        }

        public int characteristics() {
            return (fence < 0 || est == map.size ? Spliterator.SIZED : 0);
        }
    }

    static final class EntrySpliterator<K,V>
        extends HashMapSpliterator<K,V>
        implements Spliterator<Map.Entry<K,V>> {
        EntrySpliterator(HashMap<K,V> m, int origin, int fence, int est,
                         int expectedModCount) {
            super(m, origin, fence, est, expectedModCount);
        }

        public EntrySpliterator<K,V> trySplit() {
            int hi = getFence(), lo = index, mid = (lo + hi) >>> 1;
            return (lo >= mid || current != null) ? null :
                new EntrySpliterator<>(map, lo, index = mid, est >>>= 1,
                                          expectedModCount);
        }

        public void forEachRemaining(Consumer<? super Map.Entry<K,V>> action) {
            int i, hi, mc;
            if (action == null)
                throw new NullPointerException();
            HashMap<K,V> m = map;
            Node<K,V>[] tab = m.table;
            if ((hi = fence) < 0) {
                mc = expectedModCount = m.modCount;
                hi = fence = (tab == null) ? 0 : tab.length;
            }
            else
                mc = expectedModCount;
            if (tab != null && tab.length >= hi &&
                (i = index) >= 0 && (i < (index = hi) || current != null)) {
                Node<K,V> p = current;
                current = null;
                do {
                    if (p == null)
                        p = tab[i++];
                    else {
                        action.accept(p);
                        p = p.next;
                    }
                } while (p != null || i < hi);
                if (m.modCount != mc)
                    throw new ConcurrentModificationException();
            }
        }

        public boolean tryAdvance(Consumer<? super Map.Entry<K,V>> action) {
            int hi;
            if (action == null)
                throw new NullPointerException();
            Node<K,V>[] tab = map.table;
            if (tab != null && tab.length >= (hi = getFence()) && index >= 0) {
                while (current != null || index < hi) {
                    if (current == null)
                        current = tab[index++];
                    else {
                        Node<K,V> e = current;
                        current = current.next;
                        action.accept(e);
                        if (map.modCount != expectedModCount)
                            throw new ConcurrentModificationException();
                        return true;
                    }
                }
            }
            return false;
        }

        public int characteristics() {
            return (fence < 0 || est == map.size ? Spliterator.SIZED : 0) |
                Spliterator.DISTINCT;
        }
    }

    /* ------------------------------------------------------------ */
    // LinkedHashMap support


    /*
     * The following package-protected methods are designed to be
     * overridden by LinkedHashMap, but not by any other subclass.
     * Nearly all other internal methods are also package-protected
     * but are declared final, so can be used by LinkedHashMap, view
     * classes, and HashSet.
     */

    /**
     * 创建链表节点
     */
    // Create a regular (non-tree) node
    Node<K,V> newNode(int hash, K key, V value, Node<K,V> next) {
        return new Node<>(hash, key, value, next);
    }

    // For conversion from TreeNodes to plain nodes
    Node<K,V> replacementNode(Node<K,V> p, Node<K,V> next) {
        return new Node<>(p.hash, p.key, p.value, next);
    }

    // Create a tree bin node
    TreeNode<K,V> newTreeNode(int hash, K key, V value, Node<K,V> next) {
        return new TreeNode<>(hash, key, value, next);
    }

    // For treeifyBin
    TreeNode<K,V> replacementTreeNode(Node<K,V> p, Node<K,V> next) {
        return new TreeNode<>(p.hash, p.key, p.value, next);
    }

    /**
     * Reset to initial default state.  Called by clone and readObject.
     */
    void reinitialize() {
        table = null;
        entrySet = null;
        keySet = null;
        values = null;
        modCount = 0;
        threshold = 0;
        size = 0;
    }

    // Callbacks to allow LinkedHashMap post-actions
    void afterNodeAccess(Node<K,V> p) { }
    void afterNodeInsertion(boolean evict) { }
    void afterNodeRemoval(Node<K,V> p) { }

    // Called only from writeObject, to ensure compatible ordering.
    void internalWriteEntries(java.io.ObjectOutputStream s) throws IOException {
        Node<K,V>[] tab;
        if (size > 0 && (tab = table) != null) {
            for (int i = 0; i < tab.length; ++i) {
                for (Node<K,V> e = tab[i]; e != null; e = e.next) {
                    s.writeObject(e.key);
                    s.writeObject(e.value);
                }
            }
        }
    }

    /* ------------------------------------------------------------ */
    // Tree bins

    /**
     * 红黑树的树节点
     * Entry for Tree bins. Extends LinkedHashMap.Entry (which in turn
     * extends Node) so can be used as extension of either regular or
     * linked node.
     */
    static final class TreeNode<K,V> extends LinkedHashMap.Entry<K,V> {
        /**
         * 父节点
         */
        TreeNode<K,V> parent;  // red-black tree links
        /**
         * 左子节点
         */
        TreeNode<K,V> left;
        /**
         * 右子节点
         */
        TreeNode<K,V> right;
        /**
         * 前一个节点(双向链表)
         */
        TreeNode<K,V> prev;    // needed to unlink next upon deletion
        /**
         * 是否是红色
         */
        boolean red;
        TreeNode(int hash, K key, V val, Node<K,V> next) {
            super(hash, key, val, next);
        }

        /**
         * 遍历获取根节点
         * Returns root of tree containing this node.
         */
        final TreeNode<K,V> root() {
            //遍历
            for (TreeNode<K,V> r = this, p;;) {
                //如果 parent 为 null 则为根节点
                if ((p = r.parent) == null)
                    return r;
                r = p;
            }
        }

        /**
         * Ensures that the given root is the first node of its bin.
         */
        static <K,V> void moveRootToFront(Node<K,V>[] tab, TreeNode<K,V> root) {
            int n;
            //如果根节点不为 null 且数组不为 null 且数组长度大于 0
            if (root != null && tab != null && (n = tab.length) > 0) {
                //计算根节点在数组的槽位
                int index = (n - 1) & root.hash;
                //获取这个位置的 TreeNode
                TreeNode<K,V> first = (TreeNode<K,V>)tab[index];
                //如果当前的 TreeNode 不是根节点
                if (root != first) {
                    Node<K,V> rn;
                    //将根节点放入给定索引位置
                    tab[index] = root;
                    //获取根节点链表中的前一个节点
                    TreeNode<K,V> rp = root.prev;
                    //取出 root 的下一个节点
                    if ((rn = root.next) != null)
                        //将 root 的下一个节点的前节点指向root的前一个节点
                        ((TreeNode<K,V>)rn).prev = rp;
                    //rp 直接指向 rn
                    if (rp != null)
                        rp.next = rn;
                    //经过前面两个if , 已经将 root 节点从双向链表中移除
                    //然后, 把 root 节点直接放在双向节点的头位置(head), 也就是 first 节点的前一个节点
                    if (first != null)
                        first.prev = root;
                    root.next = first;
                    root.prev = null;
                }
                //校验
                assert checkInvariants(root);
            }
        }

        /**
         * 从树中查找节点, 主要就是二叉树的查找规则
         * Finds the node starting at root p with the given hash and key.
         * The kc argument caches comparableClassFor(key) upon first use
         * comparing keys.
         */
        final TreeNode<K,V> find(int h, Object k, Class<?> kc) {
            TreeNode<K,V> p = this;
            do {
                // p 表示当前节点
                // dir 表示左(-1)或右(1)
                // pk 表示 p 节点的 key
                int ph, dir; K pk;
                //pl 表示 p 的左孩子, pr 表示 p 的右孩子
                TreeNode<K,V> pl = p.left, pr = p.right, q;
                //如果 p 的 hash 比 h 大, 往左继续遍历
                if ((ph = p.hash) > h)
                    p = pl;
                //如果 p 的 hash 比 h 小, 往右继续遍历
                else if (ph < h)
                    p = pr;
                //如果 hash 相等, 且 key 也相等, 则返回找到的 Node
                else if ((pk = p.key) == k || (k != null && k.equals(pk)))
                    return p;
                //如果 hash 相等, 且 key 不相等的话, 要继续遍历
                //左为空, 则往右遍历
                else if (pl == null)
                    p = pr;
                //右为空, 则往左遍历
                else if (pr == null)
                    p = pl;
                //如果 hash 相等, 但是可以比较, 则根据比较的大小来往左往右遍历
                else if ((kc != null ||
                          (kc = comparableClassFor(k)) != null) &&
                         (dir = compareComparables(kc, k, pk)) != 0)
                    p = (dir < 0) ? pl : pr;
                //hash 相等, 先往遍历右找, 找到则返回
                else if ((q = pr.find(h, k, kc)) != null)
                    return q;
                //右没找到, 则往左遍历继续找
                else
                    p = pl;
                //直到所有节点为 null 才返回
            } while (p != null);
            //没找到, 返回 null
            return null;
        }

        /**
         * 根据 hash 和 key 从树中查找
         * Calls find for root node.
         */
        final TreeNode<K,V> getTreeNode(int h, Object k) {
            //从根节点开始查找
            return ((parent != null) ? root() : this).find(h, k, null);
        }

        /**
         * 这个方法主要保证在给定 hashCode 相等且不可比较时, 有一个插入顺序.
         * 这个方法不要求一个全局的顺序, 只要一个插入规则用于保证二叉树的平衡就可以了. 这种情况很少见, 超出了日常的需求
         * - 如果能用类名比较排序则用类名
         * - 如果有一个对象为 null 或类名排序相等, 则用内存地址比较排序
         * Tie-breaking utility for ordering insertions when equal
         * hashCodes and non-comparable. We don't require a total
         * order, just a consistent insertion rule to maintain
         * equivalence across rebalancings. Tie-breaking further than
         * necessary simplifies testing a bit.
         */
        static int tieBreakOrder(Object a, Object b) {
            int d;
            if (a == null || b == null ||
                (d = a.getClass().getName().
                 compareTo(b.getClass().getName())) == 0)
                d = (System.identityHashCode(a) <= System.identityHashCode(b) ?
                     -1 : 1);
            return d;
        }

        /**
         * Forms tree of the nodes linked from this node.
         * @return root of tree
         */
        final void treeify(Node<K,V>[] tab) {
            //树的根节点
            TreeNode<K,V> root = null;
            //从当前槽第一个 TreeNode 遍历链表
            for (TreeNode<K,V> x = this, next; x != null; x = next) {
                //获取下一个节点
                next = (TreeNode<K,V>)x.next;
                //初始化左右子节点为 null
                x.left = x.right = null;
                //如果根节点是 null
                if (root == null) {
                    //根节点没有 parent
                    x.parent = null;
                    //根节点为黑色
                    x.red = false;
                    //当前节点作为根节点
                    root = x;
                }
                else {
                    //获取 key
                    K k = x.key;
                    //获取 hash
                    int h = x.hash;
                    //key 的类型
                    Class<?> kc = null;
                    //从根节点开始遍历
                    for (TreeNode<K,V> p = root;;) {
                        //dir 表示在当前节点左边(-1)还是右边(1)
                        //ph 表示父节点的 hash
                        //p 表示父节点, x 表示当前节点
                        int dir, ph;
                        //获取父节点 的key
                        K pk = p.key;
                        //父节点 hash 大, 则放左边
                        if ((ph = p.hash) > h)
                            dir = -1;
                        //比父节点 hash 小, 则放右边
                        else if (ph < h)
                            dir = 1;
                        // hash 相同且 key 不能比较
                        else if ((kc == null &&
                                  (kc = comparableClassFor(k)) == null) ||
                                 (dir = compareComparables(kc, k, pk)) == 0)
                            //用类名或内存地址来比较
                            dir = tieBreakOrder(k, pk);

                        //缓存父节点
                        TreeNode<K,V> xp = p;
                        //根据左右获取空的子节点
                        if ((p = (dir <= 0) ? p.left : p.right) == null) {
                            //设置 x 的父节点
                            x.parent = xp;
                            //将x放到子节点
                            if (dir <= 0)
                                xp.left = x;
                            else
                                xp.right = x;
                            //做平衡处理, 并返回根节点
                            root = balanceInsertion(root, x);
                            //退出循环
                            break;
                        }
                    }
                }
            }
            //移根节点到数组的槽中
            moveRootToFront(tab, root);
        }

        /**
         * Returns a list of non-TreeNodes replacing those linked from
         * this node.
         */
        final Node<K,V> untreeify(HashMap<K,V> map) {
            Node<K,V> hd = null, tl = null;
            for (Node<K,V> q = this; q != null; q = q.next) {
                Node<K,V> p = map.replacementNode(q, null);
                if (tl == null)
                    hd = p;
                else
                    tl.next = p;
                tl = p;
            }
            return hd;
        }

        /**
         * 添加红黑树的节点, 二叉树是左小右大
         * Tree version of putVal.
         */
        final TreeNode<K,V> putTreeVal(HashMap<K,V> map, Node<K,V>[] tab,
                                       int h, K k, V v) {
            Class<?> kc = null;
            //标记是否在相同 hash 下的子树找过, 防止遍历多次
            boolean searched = false;
            //parent不为 null, 则遍历找到树的根节点, 为 null 则根节点为当前节点
            TreeNode<K,V> root = (parent != null) ? root() : this;
            //// 从树的根节点开始遍历
            for (TreeNode<K,V> p = root;;) {
                // dir=direction，标记给定 k 是在当前 TreeNode 的左边还是右边, dir 的值为 -1(左) 或 1(右)
                // ph=p.hash，当前节点的hash值
                // pk=p.key，当前节点的key值
                int dir, ph; K pk;
                //如果当前 TreeNode 的 hash 大于  h
                if ((ph = p.hash) > h)
                    //标记在左边
                    dir = -1;
                //小于, 则标记在右
                else if (ph < h)
                    dir = 1;
                //如果 hash 值与当前 TreeNode 相等且 key 也相等, 则直接返回旧节点
                else if ((pk = p.key) == k || (k != null && k.equals(pk)))
                    return p;
                //这个分支表示, hash 相等, 但 key 不相等
                else if ((kc == null &&
                        // 如果k是Comparable的子类则返回其真实的类，否则返回null
                          (kc = comparableClassFor(k)) == null) ||
                        // 如果k和pk不是同样的类型则返回0，否则返回两者比较的结果
                         (dir = compareComparables(kc, k, pk)) == 0) {
                    // 这个条件表示两者hash相同但是其中一个不是Comparable类型或者两者类型不同
                    // 比如key是Object类型，这时可以传String也可以传Integer，两者hash值可能相同
                    // 在红黑树中把同样hash值的元素存储在同一颗子树，这里相当于找到了这颗子树的顶点
                    // 从这个顶点分别遍历其左右子树去寻找有没有跟待插入的key相同的元素, 缩小了查找范围

                    //如果没有查找, 在相同子数下找
                    if (!searched) {
                        TreeNode<K,V> q, ch;
                        //标记为已经查找过了
                        searched = true;
                        //如果左子树不为 null, 则根据给定的 hash 和 k 和类型继续查找
                        if (((ch = p.left) != null &&
                             (q = ch.find(h, k, kc)) != null) ||
                                //如果右子树不为 null, 则在右子树中查找
                            ((ch = p.right) != null &&
                             (q = ch.find(h, k, kc)) != null))
                            //只要找到就返回
                            return q;
                    }
                    //因为前面给定的 hash 相同, 所以根据相应的规则重做排序:  用类名比较排序, 如果类名相同, 则用内存地址来比较排序
                    //todo wolfleong 这里有疑问, tieBreakOrder() 返回的值在整个生命周期中是否会变化, 如果没有变化,
                    // 则前面给定的 hash 相同则可以根据 tieBreakOrder() 方法重新还原一个顺序来定位, 不用左右树都查
                    //具体原因, 看 tieBreakOrder 方法注释
                    dir = tieBreakOrder(k, pk);
                }

                //缓存前一个节点
                TreeNode<K,V> xp = p;
                //获取根据左右获取子节点, 如果子节点为 null
                if ((p = (dir <= 0) ? p.left : p.right) == null) {
                    //获取上一个节点的 next 节点
                    Node<K,V> xpn = xp.next;
                    //根据给定的 kv 创建新 TreeNode
                    TreeNode<K,V> x = map.newTreeNode(h, k, v, xpn);
                    //如果 dir 小于等于0 , 表示在 xp 的左边
                    if (dir <= 0)
                        xp.left = x;
                    else
                        //否则在右边
                        xp.right = x;
                    // 维护双向链表的关系, 如: xp => x => xpn
                    //维护 xp => x
                    xp.next = x;
                    x.parent = x.prev = xp;
                    //维护 x => xpn
                    if (xpn != null)
                        ((TreeNode<K,V>)xpn).prev = x;
                    //把root节点移动到链表的第一个节点
                    moveRootToFront(tab, balanceInsertion(root, x));
                    //没有旧节点, 返回 null
                    return null;
                }
            }
        }

        /**
         * 删除树中的节点(当前节点), 这里主要是二叉树的删除, 树的平衡在另一个方法.
         *
         *
         * 将红黑树内的某一个节点删除。需要执行的操作依次是：
         *   - 将红黑树当作一颗二叉查找树，将该节点从二叉查找树中删除；
         *   - 通过"旋转和重新着色"等一系列来修正该树，使之重新成为一棵红黑树.
         *
         *
         *
         * 我们知道，对于一棵普通的二叉排序树来说，删除的节点情况可以分以下为3种：
         *  ① 被删除节点没有儿子，即为叶节点。
         *      - 那么，直接将该节点删除就OK了。
         *  ② 被删除节点只有一个儿子(左子树或右子树)。
         *      - 那么，直接删除该节点，并用该节点的唯一子节点顶替它的位置。
         *  ③ 被删除节点有两个儿子。
         *      - 我们知道对于一棵普通二叉树的情况3来说，要删除既有左子树又有右子树的节点，我们首先要找到该节点的直接后继节点，
         *        然后用后继节点替换该节点，最后按1或2中的方法删除后继节点即可。所以情况3可以转换成情况1或2处理。
         *      - 直接后继节点可以有两种
         *          1. 当前节点的左子节点的最右后继节点
         *          2. 当前节点的右子节点的最左后继节点(这个方法是以这种方式实现的)
         *
         * Removes the given node, that must be present before this call.
         * This is messier than typical red-black deletion code because we
         * cannot swap the contents of an interior node with a leaf
         * successor that is pinned by "next" pointers that are accessible
         * independently during traversal. So instead we swap the tree
         * linkages. If the current tree appears to have too few nodes,
         * the bin is converted back to a plain bin. (The test triggers
         * somewhere between 2 and 6 nodes, depending on tree structure).
         */
        final void removeTreeNode(HashMap<K,V> map, Node<K,V>[] tab,
                                  boolean movable) {
            int n;
            //数组没初始化或长度为 0 , 则直接返回
            if (tab == null || (n = tab.length) == 0)
                return;
            //当前 Node 的计算数组下标
            int index = (n - 1) & hash;
            //获取数组下标第一个节点, 并且作为根节点
            // rl 表示根左子节点
            TreeNode<K,V> first = (TreeNode<K,V>)tab[index], root = first, rl;
            //后继节点，前置节点
            TreeNode<K,V> succ = (TreeNode<K,V>)next, pred = prev;
            //如果前置节点为 null, 则表示当前节点是双向链表的首节点
            if (pred == null)
                //取下一个节点作为首节点放入数组
                tab[index] = first = succ;
            else
                //非首节点, 在双向链表中删除当前节点
                pred.next = succ;
            //维护后继节点与前继节点的关系
            if (succ != null)
                succ.prev = pred;
            //如果没有后继节点, 则表示, 数组槽位中只有一个节点, 删除就没有了, 直接返回
            if (first == null)
                return;
            //如果根节点的父节点不为空，则重新查找父节点
            if (root.parent != null)
                root = root.root();
            // 这个是判断当前红黑树的结构是否太小,当然这种情况下是不存在,如果太小的话,当前桶结构就是单链表了,而不是红黑树了.
            if (root == null || root.right == null ||
                (rl = root.left) == null || rl.left == null) {
                //反树化
                tab[index] = first.untreeify(map);  // too small
                return;
            }

            // 分割线，以上都是删除双向链表中的节点，下面才是直接删除红黑树的节点（因为TreeNode本身即是链表节点又是树节点）

            // p为当前树节点, pl为当前树节点的左子树节点, pr为右子树节点, replacement为要替代当前树节点的节点(下面简称为当前树节点，左子树节点,右子树节点和替换节点)
            TreeNode<K,V> p = this, pl = left, pr = right, replacement;
            // 如果左右子树节点都不为空, (左右子树都不为空的节点要直接删除是很麻烦的, 所以要找一个后继节点来替换, 再删除)
            if (pl != null && pr != null) {
                //获取当前节点的右子节点赋值给 s , sl 表示 s 的左子节点
                TreeNode<K,V> s = pr, sl;
                //从 s 开始一直往左遍历, 直到 sl 为 null, 也就是查找替换节点过程
                while ((sl = s.left) != null) // find successor
                    s = sl;
                //能到这一步就表示, 找到了 p 的替代节点, 也就是 s 节点
                // c 表示替代节点的颜色, 将当前节点和替换节点的颜色交换
                boolean c = s.red; s.red = p.red; p.red = c; // swap colors
                //获取替换节点的右节点(替换节点是没有左节点的, 它是最左的了)
                TreeNode<K,V> sr = s.right;
                //获取当前节点的父节点
                TreeNode<K,V> pp = p.parent;
                //下面这个判断是当前节点的右子树节点是否无左子树节点
                // 如果没有则将当前节点的父子树节点指针指向右子树节点,右子树节点的右子树指针指向当前节点,从而完成子树节点的替换
                if (s == pr) { // p was s's direct parent
                    p.parent = s;
                    s.right = p;
                }
                else {
                    // 如果右子树节点有子树节点,则将右子树最小节点与当前节点进行替换,
                    // 分别将当前节点的父子树节点的子树节点指针指向右子树最小节点,
                    // 原先右子树最小节点的父子树节点的子树节点指针指向当前节点,右子树最小节点的右子树节点指针指向右子树,
                    // (到这里是完成了当前节点的右子树的替换，下面就是左子树替换)
                    // 获取替换节点的父节点
                    TreeNode<K,V> sp = s.parent;
                    //将 p 的父节点引用指向 s 的父节点, 如果不为 null
                    if ((p.parent = sp) != null) {
                        //判断原来 s 是在 sp 的左边或右边, 然后再将 p 添加到sp 相应的左边或右边(感觉只能在左边, 因为 s 是一直往左遍历出来的, 你觉得呢?)
                        if (s == sp.left)
                            sp.left = p;
                        else
                            sp.right = p;
                    }
                    //将 pr 指向 s 的右节点
                    if ((s.right = pr) != null)
                        //重新设置 pr 的父节点
                        pr.parent = s;
                }
                //替换后, 重置相关引用
                //因为原s没有左子节点的, 所以将p左子节点置 null
                p.left = null;
                //重置 sr 的父节点为 p
                if ((p.right = sr) != null)
                    sr.parent = p;
                //重置 pl 的父节点为 s
                if ((s.left = pl) != null)
                    pl.parent = s;
                //重置 s 的父节点为 pp
                if ((s.parent = pp) == null)
                    //如果 s 的父节点为 null, 则将s设置为根节点
                    root = s;
                //置置  pp 的子节点引用
                else if (p == pp.left)
                    pp.left = s;
                else
                    pp.right = s;

                //如果 sr 不为空, 则 sr 将要替换 p 的位置
                //此时的sr是当前节点p的右子树节点，如果不为空则将replacement指向sr,否则指向p
                if (sr != null)
                    replacement = sr;
                else
                    replacement = p;
            }
            //如果 pl 不为 null , 则用 pl 替换删除的节点
            else if (pl != null)
                replacement = pl;
                //如果 pr 不为 null , 则用 pr 替换删除的节点
            else if (pr != null)
                replacement = pr;
            else
                //要删除的节点没有子节点, 直接用 p
                replacement = p;
            // 上面的三个else 如果当前节点有子树节点则将relpacement指向此子树节点，否则指向p
            //下面就开始使p脱离树了
            //如果 replacement 不为 p , 则表示要删除的节点有一个左或右子孩子
            if (replacement != p) {
                //重置 replacement 的父节点, 也就是将 p 删除
                TreeNode<K,V> pp = replacement.parent = p.parent;
                //如果没有父节点则重置 replacement 为根节点
                if (pp == null)
                    root = replacement;
                //根据 p 原来在 pp 的左或右进行重置引用
                else if (p == pp.left)
                    pp.left = replacement;
                else
                    pp.right = replacement;
                //清空 p 节点相关引用, 到此就表示 p 已经在二叉树中删除了
                p.left = p.right = p.parent = null;
            }

            //分界线, 删除完后, 要做红黑树的自平衡

            //r 表示根节点
            //如果要删除的 p 是红色, 则不用做任何处理, 直接返回根节点, 如果是黑色, 则做删除自平衡
            TreeNode<K,V> r = p.red ? root : balanceDeletion(root, replacement);

            //如果p无子树节点(无论是替换前还是替换后)，使此时的p的父子树节点的左/右子树节点指向null,此时的p也算是脱离出来了
            if (replacement == p) {  // detach
                //获取 p 的父节点
                TreeNode<K,V> pp = p.parent;
                //重置 p 上的引用为 null
                p.parent = null;
                //清空 p 的父节点对 p 的引用
                if (pp != null) {
                    if (p == pp.left)
                        pp.left = null;
                    else if (p == pp.right)
                        pp.right = null;
                }
            }
            //最后通过 moveRootToFront 重新定义双链表的头部节点使之与树的根节点相同
            if (movable)
                moveRootToFront(tab, r);
        }

        /**
         * Splits nodes in a tree bin into lower and upper tree bins,
         * or untreeifies if now too small. Called only from resize;
         * see above discussion about split bits and indices.
         *
         * @param map the map
         * @param tab the table for recording bin heads
         * @param index the index of the table being split
         * @param bit the bit of hash to split on
         */
        final void split(HashMap<K,V> map, Node<K,V>[] tab, int index, int bit) {
            TreeNode<K,V> b = this;
            // Relink into lo and hi lists, preserving order
            TreeNode<K,V> loHead = null, loTail = null;
            TreeNode<K,V> hiHead = null, hiTail = null;
            int lc = 0, hc = 0;
            for (TreeNode<K,V> e = b, next; e != null; e = next) {
                next = (TreeNode<K,V>)e.next;
                e.next = null;
                if ((e.hash & bit) == 0) {
                    if ((e.prev = loTail) == null)
                        loHead = e;
                    else
                        loTail.next = e;
                    loTail = e;
                    ++lc;
                }
                else {
                    if ((e.prev = hiTail) == null)
                        hiHead = e;
                    else
                        hiTail.next = e;
                    hiTail = e;
                    ++hc;
                }
            }

            if (loHead != null) {
                if (lc <= UNTREEIFY_THRESHOLD)
                    tab[index] = loHead.untreeify(map);
                else {
                    tab[index] = loHead;
                    if (hiHead != null) // (else is already treeified)
                        loHead.treeify(tab);
                }
            }
            if (hiHead != null) {
                if (hc <= UNTREEIFY_THRESHOLD)
                    tab[index + bit] = hiHead.untreeify(map);
                else {
                    tab[index + bit] = hiHead;
                    if (loHead != null)
                        hiHead.treeify(tab);
                }
            }
        }

        /* ------------------------------------------------------------ */
        // Red-black tree methods, all adapted from CLR

        /**
         * 左旋, 代码简单就不标记了
         */
        static <K,V> TreeNode<K,V> rotateLeft(TreeNode<K,V> root,
                                              TreeNode<K,V> p) {
            TreeNode<K,V> r, pp, rl;
            if (p != null && (r = p.right) != null) {
                if ((rl = p.right = r.left) != null)
                    rl.parent = p;
                if ((pp = r.parent = p.parent) == null)
                    (root = r).red = false;
                else if (pp.left == p)
                    pp.left = r;
                else
                    pp.right = r;
                r.left = p;
                p.parent = r;
            }
            return root;
        }

        /**
         * 右旋, 代码简单就不标记了
         */
        static <K,V> TreeNode<K,V> rotateRight(TreeNode<K,V> root,
                                               TreeNode<K,V> p) {
            TreeNode<K,V> l, pp, lr;
            if (p != null && (l = p.left) != null) {
                if ((lr = p.left = l.right) != null)
                    lr.parent = p;
                if ((pp = l.parent = p.parent) == null)
                    (root = l).red = false;
                else if (pp.right == p)
                    pp.right = l;
                else
                    pp.left = l;
                l.right = p;
                p.parent = l;
            }
            return root;
        }

        /**
         * 红黑树插入节点的自平衡
         * <br>
         * 红黑树的5大特性
         *  (1) 每个节点或者是黑色，或者是红色。
         *  (2) 根节点是黑色。
         *  (3) 每个叶子节点是黑色。 [注意：这里叶子节点，是指为空的叶子节点！]
         *  (4) 如果一个节点是红色的，则它的子节点必须是黑色的。
         *  (5) 从一个节点到该节点的子孙节点的所有路径上包含相同数目的黑节点。
         *
         * 插入平衡的核心思想: 将多出的红色的节点逐渐提升到根节点；然后，将根节点设为黑色
         *
         * 插入划分三种情况, 看文字如果难看懂, 则可以自己画图, 或者上网查相关的图来对比着看
         *  情况一
         *      说明: 被插入的节点是根节点
         *      处理: 直接把此节点涂为黑色。
         *  情况二
         *      说明: 被插入的节点的父节点是黑色。
         *      处理: 什么也不需要做。节点被插入后，仍然是红黑树。
         *  情况三
         *     说明: 被插入的节点的父节点是红色。
         *     处理: 那么，该情况与红黑树的“特性(5)”相冲突。这种情况下，被插入节点是一定存在非空祖父节点的；
         *          进一步的讲，被插入节点也一定存在叔叔节点(即使叔叔节点为空，我们也视之为存在，空节点本身就是黑色节点)。
         *          理解这点之后，我们依据"叔叔节点的情况"，将这种情况进一步划分为3种情况(Case)
         *
         *          Case 1
         *            说明: 当前节点的父节点是红色，且当前节点的祖父节点的另一个子节点（叔叔节点）也是红色。(可以推出祖父节点肯定是黑色)
         *            处理:
         *                 (01) 将“父节点”设为黑色。
         *                 (02) 将“叔叔节点”设为黑色。
         *                 (03) 将“祖父节点”设为“红色”。
         *                 (04) 将“祖父节点”设为“当前节点”(红色节点)；即，之后继续对“当前节点”进行操作
         *
         *         Case 2
         *            说明: 当前节点的父节点是红色，叔叔节点是黑色，且当前节点是其父节点的右孩子
         *            处理:
         *                 (01) 将“父节点”作为“新的当前节点”。
         *                 (02) 以“新的当前节点”为支点进行左旋。
         *
         *         Case 3
         *            说明: 当前节点的父节点是红色，叔叔节点是黑色，且当前节点是其父节点的左孩子
         *            处理:
         *                 (01) 将“父节点”设为“黑色”。
         *                 (02) 将“祖父节点”设为“红色”。
         *                 (03) 以“祖父节点”为支点进行右旋。
         *
         *
         */
        static <K,V> TreeNode<K,V> balanceInsertion(TreeNode<K,V> root,
                                                    TreeNode<K,V> x) {
            //红黑树新插入的节点都是红色的
            x.red = true;
            //xp：当前节点的父节点, xpp：爷爷节点, xppl：左叔叔节点, xppr：右叔叔节点
            for (TreeNode<K,V> xp, xpp, xppl, xppr;;) {
                //情况一, 新插入节点没有父节点, 即为根节点
                if ((xp = x.parent) == null) {
                    //变为黑色
                    x.red = false;
                    //返回根节点
                    return x;
                }
                //情况二,  如果父节点为黑色 或者 【（父节点为红色 但是 爷爷节点为空） -> 这种情况何时出现？】
                else if (!xp.red || (xpp = xp.parent) == null)
                    //直接返回根节点
                    return root;

                //下面都是情况三

                //如果当前节点的父节点是祖父节点的左孩子, 即叔节点为右孩子
                if (xp == (xppl = xpp.left)) {
                    //情况三 -> Case 1: 如果叔节点不为 null 且是红色
                    if ((xppr = xpp.right) != null && xppr.red) {
                        //父节点和叔节点都设置成黑色
                        xppr.red = false;
                        xp.red = false;
                        //祖父节点为黑色
                        xpp.red = true;
                        //将新增的红色节点提升到祖父节点, 继续处理
                        x = xpp;
                    }
                    //叔节点为黑色(null也是黑色)
                    else {
                        //情况三 -> Case 2 :  当前节点是右孩子
                        if (x == xp.right) {
                            //以父节点左旋, 并且以父节点为当前节点
                            root = rotateLeft(root, x = xp);
                            //重新设置祖父节点
                            xpp = (xp = x.parent) == null ? null : xp.parent;
                            //经过上面处理后, 就相当于将右孩子的新增节点变左孩子, 交给后面的程序继续处理
                        }
                        //能来这里, 则表示当前新加的节点 x 肯定是左孩子
                        //情况三 -> Case 3
                        if (xp != null) {
                            //父节点变黑色
                            xp.red = false;
                            //祖父节点不为 null, 理论上这里的祖父节点不会为 nul 的
                            if (xpp != null) {
                                //设置祖父节点为红色
                                xpp.red = true;
                                //祖父节点右旋
                                root = rotateRight(root, xpp);
                                //处理到这里就已经是完美的平衡树了
                            }
                        }
                    }
                }
                //父节点是右孩子, 处理跟上面相反就行了
                else {
                    //情况三 -> Case 1: 如果叔节点不为 null 且是红色,
                    if (xppl != null && xppl.red) {
                        xppl.red = false;
                        xp.red = false;
                        xpp.red = true;
                        x = xpp;
                    }
                    else {
                        //情况三 -> Case 2 :  当前节点是左孩子
                        if (x == xp.left) {
                            root = rotateRight(root, x = xp);
                            xpp = (xp = x.parent) == null ? null : xp.parent;
                        }
                        //情况三 -> Case 3 , 能到这里, 当前节点肯定是父节点的右孩子
                        if (xp != null) {
                            xp.red = false;
                            if (xpp != null) {
                                xpp.red = true;
                                root = rotateLeft(root, xpp);
                            }
                        }
                    }
                }
            }
        }

        /**
         * 红黑树删除节点的自平衡(PS: 这个删除平衡处理有点复杂, 看代码前一定要先了解理论)
         *  - 进来这个方法就确定, 要删除的位置肯定是黑色的
         *
         * 红黑树删除的平衡思想是将x所包含的额外的黑色不断沿树上移(向根方向移动)，直到出现下面的姿态:
         *  情况一：x是“红+黑”节点。
         *     处理方法：直接把x设为黑色，结束。此时红黑树性质全部恢复。
         *  情况二：x是“黑+黑”节点，且x是根。
         *     处理方法：什么都不做，结束。此时红黑树性质全部恢复。
         *  情况三：x是“黑+黑”节点，且x不是根。
         *     处理方法：这种情况又可以划分为4种子情况。这4种子情况如下表所示
         *       - Case 1:
         *          - 说明: x是"黑+黑"节点，x的兄弟节点是红色。(此时x的父节点和x的兄弟节点的子节点都是黑节点)。
         *          - 处理:
         *              (01) 将x的兄弟节点设为“黑色”。
         *              (02) 将x的父节点设为“红色”。
         *              (03) 对x的父节点进行左旋。
         *              (04) 左旋后，重新设置x的兄弟节点
         *       - Case 2:
         *          - 说明: x是“黑+黑”节点，x的兄弟节点是黑色，x的兄弟节点的两个孩子都是黑色。
         *          - 处理:
         *              (01) 将x的兄弟节点设为“红色”。
         *              (02) 设置“x的父节点”为“新的x节点”。
         *       - Case 3:
         *          - 说明: x是“黑+黑”节点，x的兄弟节点是黑色；x的兄弟节点的左孩子是红色，右孩子是黑色的。
         *          - 处理:
         *              (01) 将x兄弟节点的左孩子设为“黑色”。
         *              (02) 将x兄弟节点设为“红色”。
         *              (03) 对x的兄弟节点进行右旋。
         *              (04) 右旋后，重新设置x的兄弟节点。
         *       - Case 4:
         *          - 说明: x是“黑+黑”节点，x的兄弟节点是黑色；x的兄弟节点的右孩子是红色的，x的兄弟节点的左孩子任意颜色。
         *          - 处理:
         *              (01) 将x父节点颜色 赋值给 x的兄弟节点。
         *              (02) 将x父节点设为“黑色”。
         *              (03) 将x兄弟节点的右子节设为“黑色”。
         *              (04) 对x的父节点进行左旋。
         *              (05) 设置“x”为“根节点”。
         *
         *
         */
        static <K,V> TreeNode<K,V> balanceDeletion(TreeNode<K,V> root,
                                                   TreeNode<K,V> x) {
            // xp 表示 x 的父节点, xpl 表示 xp 的左子节点, xpr 表示 xp 的右子节点
            //死循环遍历
            for (TreeNode<K,V> xp, xpl, xpr;;)  {
                //代替的结点为空或者删除的是根结点，直接返回(理论上不存在吧?)
                if (x == null || x == root)
                    return root;
                //情况二: 删除后x成为根结点，x的颜色改为黑色即可, 不管 x 原来的颜色
                else if ((xp = x.parent) == null) {
                    x.red = false;
                    return x;
                }
                //情况一: 如果 x 是红色
                else if (x.red) {
                    //直接将红色节点变为黑色就可能保证平衡了
                    x.red = false;
                    //返回根节点
                    return root;
                }
                // 能下来则表示, x 为黑色
                // 如果 x 是 xp 左子节点, 则 x 的兄弟节点都是右子节点
                else if ((xpl = xp.left) == x) {
                    //情况三 -> Case 1: 如果兄弟节点不为 null 且是红色的, 此时 x 的父节点和兄弟节点的子节点都是黑色的
                    if ((xpr = xp.right) != null && xpr.red) {
                        // 将x的兄弟节点设为“黑色”。
                        xpr.red = false;
                        // 将x的父节点设为“红色”。
                        xp.red = true;
                        //对x的父节点进行左旋。
                        root = rotateLeft(root, xp);
                        // 重置 xpr , 也就是兄弟节点被重置
                        xpr = (xp = x.parent) == null ? null : xp.right;
                    }
                    //如果 xpr 为空, 则表明没有兄弟节点可以作转换, 则把 x 节点多出的黑气往上移一层, 再次处理
                    if (xpr == null)
                        x = xp;
                        //进入下面, 表示 xpr 不为 null 且是黑色, 也就是兄弟节点是黑色
                    else {
                        //获取兄弟节点的两个子节点, sl: 兄弟节点的左子节点, sr: 兄弟节点的右子节点
                        TreeNode<K,V> sl = xpr.left, sr = xpr.right;
                        //情况三 -> Case 2: 如果 sl 和 sr 都是黑色(null也是黑色)
                        if ((sr == null || !sr.red) &&
                            (sl == null || !sl.red)) {
                            //把兄弟节点变红, 就把当前 x 节点的黑色层数跟兄弟节点的一致了
                            xpr.red = true;
                            //xp 节点相当于多了一层黑色, 重置 xp 节点为 x, 再次处理
                            x = xp;
                        }
                        //能下来则表示兄弟节点有一个子节点为红色或两个子节点都为红
                        else {
                            //情况三 -> Case3: 如果 sr 是黑色, 则 sl 肯定为红色, 也就是兄弟节点是黑色, 兄弟节点左子节点为红, 右子节点为黑
                            if (sr == null || !sr.red) {
                                //如果 sl 不为 null, 重置 sl 为黑色(这个判断是多余的, 直接重置就行了)
                                if (sl != null)
                                    sl.red = false;
                                //将 sl 的父节点设置成红色, 相当于 颜色互换
                                xpr.red = true;
                                //将 xpr 右转, 相于当 sl 和 xpr 位置互换了
                                root = rotateRight(root, xpr);
                                //重置 xpr, 因为 xp 右节点变了
                                xpr = (xp = x.parent) == null ?
                                    null : xp.right;
                            }
                            //重置 xpr 后有可能为 null, 所以要判断一下
                            if (xpr != null) {
                                //情况三 -> Case4 : 这个的处理主要是给左边增加一个黑色, 右边保持不变
                                // x父节点(xp)的颜色设置给兄弟节点 (xpr), 下面会左旋将 xpr 变成父节点
                                xpr.red = (xp == null) ? false : xp.red;
                                // 设置兄弟节点(xpr)的右子节点为黑色, 主要是为了给右边增加一层黑色, 因为下面左旋后会少一层黑色, 以保证不变
                                if ((sr = xpr.right) != null)
                                    sr.red = false;
                            }
                            if (xp != null) {
                                //设置父节点为黑色, 以便将 xp 左旋成左树的黑
                                xp.red = false;
                                //对父节点进行左旋
                                root = rotateLeft(root, xp);
                            }
                            //设置x为根节点, 方便退出循环
                            x = root;
                        }
                    }
                }
                //下面是 x 节点是在右边的处理, 跟上面完全对称的, 逻辑一样
                else { // symmetric
                    if (xpl != null && xpl.red) {
                        xpl.red = false;
                        xp.red = true;
                        root = rotateRight(root, xp);
                        xpl = (xp = x.parent) == null ? null : xp.left;
                    }
                    if (xpl == null)
                        x = xp;
                    else {
                        TreeNode<K,V> sl = xpl.left, sr = xpl.right;
                        if ((sl == null || !sl.red) &&
                            (sr == null || !sr.red)) {
                            xpl.red = true;
                            x = xp;
                        }
                        else {
                            if (sl == null || !sl.red) {
                                if (sr != null)
                                    sr.red = false;
                                xpl.red = true;
                                root = rotateLeft(root, xpl);
                                xpl = (xp = x.parent) == null ?
                                    null : xp.left;
                            }
                            if (xpl != null) {
                                xpl.red = (xp == null) ? false : xp.red;
                                if ((sl = xpl.left) != null)
                                    sl.red = false;
                            }
                            if (xp != null) {
                                xp.red = false;
                                root = rotateRight(root, xp);
                            }
                            x = root;
                        }
                    }
                }
            }
        }

        /**
         * 递归做一些前后引用一致和红黑的校验
         * Recursive invariant check
         */
        static <K,V> boolean checkInvariants(TreeNode<K,V> t) {
            TreeNode<K,V> tp = t.parent, tl = t.left, tr = t.right,
                tb = t.prev, tn = (TreeNode<K,V>)t.next;
            //校验链表 t 和 t的前一个节点的引用是否正确
            if (tb != null && tb.next != t)
                return false;
            //校验链表 t 和 t的后一个节点的引用是否正确
            if (tn != null && tn.prev != t)
                return false;
            //校验树 t 和 t的父节点的关系
            if (tp != null && t != tp.left && t != tp.right)
                return false;
            //校验树 t 和 t的左节点的关系
            if (tl != null && (tl.parent != t || tl.hash > t.hash))
                return false;
            //校验树 t 和 t的右节点的关系
            if (tr != null && (tr.parent != t || tr.hash < t.hash))
                return false;
            //校验红黑树 t 和 t的子节点的颜色关系
            if (t.red && tl != null && tl.red && tr != null && tr.red)
                return false;
            //递归校验左子节点
            if (tl != null && !checkInvariants(tl))
                return false;
            //递归校验右子节点
            if (tr != null && !checkInvariants(tr))
                return false;
            //全部校验完则返回 true
            return true;
        }
    }

}
