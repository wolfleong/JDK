/*
 * Copyright (c) 2003, 2012, Oracle and/or its affiliates. All rights reserved.
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

/**
 * Private implementation class for EnumSet, for "regular sized" enum types
 * (i.e., those with 64 or fewer enum constants).
 *
 * 位操作是很神奇的, 水很深
 *
 * RegularEnumSet 通过二进制运算得到结果，直接使用 long 来存放元素。
 *
 * 位操作的一些知识:
 *  - 因为负数是以补码存的, 所以, 负数的位操作是操作补码的二进制
 *  - (左移/右移) 如果左侧操作数是 int 类型, 则确定位移的位数是由(右操数 & 0b11111)确定的, 也就是 5 个 1 做与操作, 与结果最多也就是 31
 *    如果左侧操作数是 long , 则确定位移的位数是由(右操数 & 0b111111)确定的, 也就是 6 个 1 做与操作, 与结果最多也就是 63
 *  - 按照上面的逻辑, 右侧操作数如果负数, 那么就相当于用补码执行上面的操作来确定位移数, 如:
 *      -1 >>> -5 就相当于 -1 >>> (-5 & 11111), 也就是 -1 >>> ( "-5 的补码" & 11111) , 也就是 -1 >>> 27
 *  - 可以简单这样理解, 右侧操作数是负数的话, 直接拿 (左侧操作的32或64 + 操作数) 就是要位移的位数
 *
 *
 * @author Josh Bloch
 * @since 1.5
 * @serial exclude
 */
class RegularEnumSet<E extends Enum<E>> extends EnumSet<E> {
    private static final long serialVersionUID = 3411599620347842686L;
    /**
     * 使用long的二进制位来存枚举, 最多能存 64 位, Enum.ordinal() 就是位的下标
     * Bit vector representation of this set.  The 2^k bit indicates the
     * presence of universe[k] in this set.
     */
    private long elements = 0L;

    RegularEnumSet(Class<E>elementType, Enum<?>[] universe) {
        // 构造方法也是调用抽象类的构造方法来实现
        super(elementType, universe);
    }

    /**
     * 添加从 from 到 to 之间的枚举, 添加之前必须保证 EnumSet 是空的, 否则会复盖原来的值
     */
    void addRange(E from, E to) {
        // 添加 from 到 to 的枚举, 包括 from 和 to , 假如 from 到 to 之间的个数是 len
        //-1L 补码的二进制是 64 个 1
        //from.ordinal() - to.ordinal() - 1 相当于 from 到 to 的个数的负数, 也就是 -len
        //如: from 为 1, to 为 3, 那么 from 到 to 的个数 len 就是 3, 那么 from.ordinal() - to.ordinal() - 1 结果也就是 -3
        // -1L >>>  (from.ordinal() - to.ordinal() - 1) 相当于, 右移了(64 - len)位, 还剩下最后 len 位二进制位1
        // len 个 1 再向左移 from 位, 就得出64位中, 只有from到to都是1, 其他是位是0的

        elements = (-1L >>>  (from.ordinal() - to.ordinal() - 1)) << from.ordinal();
    }

    void addAll() {
        // -1L 补码的二进制是 64 个 1
        // 右移的个数是 64 - universe.length
        // 右移过后, 最后剩余 universe.length 个 1 在最后, 前面 (64 - universe.length) 位都是 0
        // 也就是添加了所有
        if (universe.length != 0)
            elements = -1L >>> -universe.length;
    }

    /**
     * 获取当前集合的补集, 也就是返回 (所有枚举集合 - 当前集合)
     */
    void complement() {
        if (universe.length != 0) {
            //取反, 也就是原来1变成0, 0变成1
            elements = ~elements;
            //和 universe.length 个 1 做与操作
            elements &= -1L >>> -universe.length;  // Mask unused bits
        }
    }

    /**
     * Returns an iterator over the elements contained in this set.  The
     * iterator traverses the elements in their <i>natural order</i> (which is
     * the order in which the enum constants are declared). The returned
     * Iterator is a "snapshot" iterator that will never throw {@link
     * ConcurrentModificationException}; the elements are traversed as they
     * existed when this call was invoked.
     *
     * @return an iterator over the elements contained in this set
     */
    public Iterator<E> iterator() {
        return new EnumSetIterator<>();
    }

    private class EnumSetIterator<E extends Enum<E>> implements Iterator<E> {
        /**
         * elements 的值
         * A bit vector representing the elements in the set not yet
         * returned by this iterator.
         */
        long unseen;

        /**
         * 二进制最低位第一个非0位代表的十进制数
         * The bit representing the last element returned by this iterator
         * but not removed, or zero if no such element exists.
         */
        long lastReturned = 0;

        EnumSetIterator() {
            unseen = elements;
        }

        public boolean hasNext() {
            return unseen != 0;
        }

        /**
         * 这个方法很有意思
         */
        @SuppressWarnings("unchecked")
        public E next() {
            if (unseen == 0)
                throw new NoSuchElementException();
            //lastReturned = unseen & -unseen; 计算的是unseen的二进制最低位第一个非0位代表的十进制数。
            //如果unseen是0111，那返回的就是0001，十进制是1，如果unseen是0100，那返回的就是0100，十进制是4
            lastReturned = unseen & -unseen;
            //取完就删除缓存中当前的值
            unseen -= lastReturned;
            //返回数组对应的实例
            return (E) universe[Long.numberOfTrailingZeros(lastReturned)];
        }

        public void remove() {
            //最后一位的值不为0
            if (lastReturned == 0)
                throw new IllegalStateException();
            //将最后一位取反再 & 就可以删除
            elements &= ~lastReturned;
            lastReturned = 0;
        }
    }

    /**
     * Returns the number of elements in this set.
     *
     * @return the number of elements in this set
     */
    public int size() {
        //有多少位, 就有多少个
        return Long.bitCount(elements);
    }

    /**
     * Returns <tt>true</tt> if this set contains no elements.
     *
     * @return <tt>true</tt> if this set contains no elements
     */
    public boolean isEmpty() {
        return elements == 0;
    }

    /**
     * Returns <tt>true</tt> if this set contains the specified element.
     *
     * @param e element to be checked for containment in this collection
     * @return <tt>true</tt> if this set contains the specified element
     */
    public boolean contains(Object e) {
        if (e == null)
            return false;
        Class<?> eClass = e.getClass();
        if (eClass != elementType && eClass.getSuperclass() != elementType)
            return false;

        //取 e 所在的二进制位, 再与判断是否存在
        return (elements & (1L << ((Enum<?>)e).ordinal())) != 0;
    }

    // Modification Operations

    /**
     * 如果元素不存在，添加
     *
     * Adds the specified element to this set if it is not already present.
     *
     * @param e element to be added to this set
     * @return <tt>true</tt> if the set changed as a result of the call
     *
     * @throws NullPointerException if <tt>e</tt> is null
     */
    public boolean add(E e) {

        //校验枚举类型
        typeCheck(e);

        long oldElements = elements;
        // Enum 的下标是多少, 就往左移多少位 , 也就是
        elements |= (1L << ((Enum<?>)e).ordinal());
        //如果重复添加, elements 的数组是不会变的
        return elements != oldElements;
    }

    /**
     * Removes the specified element from this set if it is present.
     *
     * @param e element to be removed from this set, if present
     * @return <tt>true</tt> if the set contained the specified element
     */
    public boolean remove(Object e) {
        if (e == null)
            return false;
        Class<?> eClass = e.getClass();
        if (eClass != elementType && eClass.getSuperclass() != elementType)
            return false;

        long oldElements = elements;
        //1L << ((Enum<?>)e).ordinal() 相当于找出当前枚举的二进制位
        //取反再与就是删除
        elements &= ~(1L << ((Enum<?>)e).ordinal());
        //如果值已经不存在, 重复删除是不会改变值的, 用于判断是否重复删除
        return elements != oldElements;
    }

    // Bulk Operations

    /**
     * Returns <tt>true</tt> if this set contains all of the elements
     * in the specified collection.
     *
     * @param c collection to be checked for containment in this set
     * @return <tt>true</tt> if this set contains all of the elements
     *        in the specified collection
     * @throws NullPointerException if the specified collection is null
     */
    public boolean containsAll(Collection<?> c) {
        if (!(c instanceof RegularEnumSet))
            return super.containsAll(c);

        RegularEnumSet<?> es = (RegularEnumSet<?>)c;
        if (es.elementType != elementType)
            return es.isEmpty();

        return (es.elements & ~elements) == 0;
    }

    /**
     * Adds all of the elements in the specified collection to this set.
     *
     * @param c collection whose elements are to be added to this set
     * @return <tt>true</tt> if this set changed as a result of the call
     * @throws NullPointerException if the specified collection or any
     *     of its elements are null
     */
    public boolean addAll(Collection<? extends E> c) {
        if (!(c instanceof RegularEnumSet))
            return super.addAll(c);

        RegularEnumSet<?> es = (RegularEnumSet<?>)c;
        if (es.elementType != elementType) {
            if (es.isEmpty())
                return false;
            else
                throw new ClassCastException(
                    es.elementType + " != " + elementType);
        }

        long oldElements = elements;
        elements |= es.elements;
        return elements != oldElements;
    }

    /**
     * Removes from this set all of its elements that are contained in
     * the specified collection.
     *
     * @param c elements to be removed from this set
     * @return <tt>true</tt> if this set changed as a result of the call
     * @throws NullPointerException if the specified collection is null
     */
    public boolean removeAll(Collection<?> c) {
        if (!(c instanceof RegularEnumSet))
            return super.removeAll(c);

        RegularEnumSet<?> es = (RegularEnumSet<?>)c;
        if (es.elementType != elementType)
            return false;

        long oldElements = elements;
        elements &= ~es.elements;
        return elements != oldElements;
    }

    /**
     * Retains only the elements in this set that are contained in the
     * specified collection.
     *
     * @param c elements to be retained in this set
     * @return <tt>true</tt> if this set changed as a result of the call
     * @throws NullPointerException if the specified collection is null
     */
    public boolean retainAll(Collection<?> c) {
        if (!(c instanceof RegularEnumSet))
            return super.retainAll(c);

        RegularEnumSet<?> es = (RegularEnumSet<?>)c;
        if (es.elementType != elementType) {
            boolean changed = (elements != 0);
            elements = 0;
            return changed;
        }

        long oldElements = elements;
        elements &= es.elements;
        return elements != oldElements;
    }

    /**
     * Removes all of the elements from this set.
     */
    public void clear() {
        elements = 0;
    }

    /**
     * Compares the specified object with this set for equality.  Returns
     * <tt>true</tt> if the given object is also a set, the two sets have
     * the same size, and every member of the given set is contained in
     * this set.
     *
     * @param o object to be compared for equality with this set
     * @return <tt>true</tt> if the specified object is equal to this set
     */
    public boolean equals(Object o) {
        if (!(o instanceof RegularEnumSet))
            return super.equals(o);

        RegularEnumSet<?> es = (RegularEnumSet<?>)o;
        if (es.elementType != elementType)
            return elements == 0 && es.elements == 0;
        return es.elements == elements;
    }
}
