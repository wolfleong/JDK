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

package java.lang.ref;

import sun.misc.Cleaner;

/**
 * java.lang.ref.Reference 为 软（soft）引用、弱（weak）引用、虚（phantom）引用的父类。
 *
 * 因为 Reference 对象和垃圾回收密切配合实现，该类可能不能被直接子类化。
 * 可以理解为 Reference 的直接子类都是由 jvm 定制化处理的,因此在代码中直接继承于Reference类型没有任何作用。
 * 但可以继承jvm定制的Reference的子类
 *
 * Abstract base class for reference objects.  This class defines the
 * operations common to all reference objects.  Because reference objects are
 * implemented in close cooperation with the garbage collector, this class may
 * not be subclassed directly.
 *
 * @author   Mark Reinhold
 * @since    1.2
 */

public abstract class Reference<T> {

    /* A Reference instance is in one of four possible internal states:
     *
     *     Active: Subject to special treatment by the garbage collector.  Some
     *     time after the collector detects that the reachability of the
     *     referent has changed to the appropriate state, it changes the
     *     instance's state to either Pending or Inactive, depending upon
     *     whether or not the instance was registered with a queue when it was
     *     created.  In the former case it also adds the instance to the
     *     pending-Reference list.  Newly-created instances are Active.
     *
     *     Pending: An element of the pending-Reference list, waiting to be
     *     enqueued by the Reference-handler thread.  Unregistered instances
     *     are never in this state.
     *
     *     Enqueued: An element of the queue with which the instance was
     *     registered when it was created.  When an instance is removed from
     *     its ReferenceQueue, it is made Inactive.  Unregistered instances are
     *     never in this state.
     *
     *     Inactive: Nothing more to do.  Once an instance becomes Inactive its
     *     state will never change again.
     *
     * The state is encoded in the queue and next fields as follows:
     *
     *     Active: queue = ReferenceQueue with which instance is registered, or
     *     ReferenceQueue.NULL if it was not registered with a queue; next =
     *     null.
     *
     *     Pending: queue = ReferenceQueue with which instance is registered;
     *     next = this
     *
     *     Enqueued: queue = ReferenceQueue.ENQUEUED; next = Following instance
     *     in queue, or this if at end of list.
     *
     *     Inactive: queue = ReferenceQueue.NULL; next = this.
     *
     * With this scheme the collector need only examine the next field in order
     * to determine whether a Reference instance requires special treatment: If
     * the next field is null then the instance is active; if it is non-null,
     * then the collector should treat the instance normally.
     *
     * To ensure that a concurrent collector can discover active Reference
     * objects without interfering with application threads that may apply
     * the enqueue() method to those objects, collectors should link
     * discovered objects through the discovered field. The discovered
     * field is also used for linking Reference objects in the pending list.
     */

    /**
     * 表示其引用的对象, 可以被 GC 回收
     */
    private T referent;         /* Treated specially by GC */

    /**
     * 引用队列, 被回收的对象会被加入到引用队列中
     */
    volatile ReferenceQueue<? super T> queue;

    /**
     * 引用队列中的用于维护链表的属性, 表示下一个节点
     */
    /* When active:   NULL
     *     pending:   this
     *    Enqueued:   next reference in queue (or this if last)
     *    Inactive:   this
     */
    @SuppressWarnings("rawtypes")
    Reference next;

    /**
     * JVM 维护的链表属性, 表示下一个节点
     */
    /* When active:   next element in a discovered reference list maintained by GC (or this if last)
     *     pending:   next element in the pending list (or null if last)
     *   otherwise:   NULL
     */
    transient private Reference<T> discovered;  /* used by VM */


    /**
     * 锁对象, 主要是给 pending 加锁
     */
    /* Object used to synchronize with the garbage collector.  The collector
     * must acquire this lock at the beginning of each collection cycle.  It is
     * therefore critical that any code holding this lock complete as quickly
     * as possible, allocate no new objects, and avoid calling user code.
     */
    static private class Lock { };
    private static Lock lock = new Lock();


    /**
     * 可以理解为 JVM 在 gc 时会将要处理的对象放到这个静态字段上面, 这个相当于 jvm 维护的一个已经被回收的引用对象链表的头节点
     */
    /* List of References waiting to be enqueued.  The collector adds
     * References to this list, while the Reference-handler thread removes
     * them.  This list is protected by the above lock object. The
     * list uses the discovered field to link its elements.
     */
    private static Reference<Object> pending = null;

    /**
     * ReferenceHandler线程内部的 run 方法会不断地从 Reference 构成的 pending 链表上获取 Reference 对象，
     * 如果能获取则根据 Reference 的具体类型进行不同的处理，不能则调用 wait 方法等待 GC 回收对象处理 pending 链表的通知
     */
    /* High-priority thread to enqueue pending References
     */
    private static class ReferenceHandler extends Thread {

        ReferenceHandler(ThreadGroup g, String name) {
            super(g, name);
        }

        public void run() {
            //死循环, 后台运行
            for (;;) {
                Reference<Object> r;
                synchronized (lock) {
                    //如果头节点不为 null
                    if (pending != null) {
                        //获取头节点
                        r = pending;
                        //设置下一个头节点
                        pending = r.discovered;
                        //置 null
                        r.discovered = null;
                    } else {
                        //没有回收对象
                        // The waiting on the lock may cause an OOME because it may try to allocate
                        // exception objects, so also catch OOME here to avoid silent exit of the
                        // reference handler thread.
                        //
                        // Explicitly define the order of the two exceptions we catch here
                        // when waiting for the lock.
                        //
                        // We do not want to try to potentially load the InterruptedException class
                        // (which would be done if this was its first use, and InterruptedException
                        // were checked first) in this situation.
                        //
                        // This may lead to the VM not ever trying to load the InterruptedException
                        // class again.
                        try {
                            try {
                                //等待
                                lock.wait();
                            } catch (OutOfMemoryError x) { }
                        } catch (InterruptedException x) { }
                        continue;
                    }
                }

                //如果是 Cleaner
                // Fast path for cleaners
                if (r instanceof Cleaner) {
                    //回调
                    ((Cleaner)r).clean();
                    continue;
                }

                //获取队列
                ReferenceQueue<Object> q = r.queue;
                //入队
                if (q != ReferenceQueue.NULL) q.enqueue(r);
            }
        }
    }

    static {
        //获取线程组
        ThreadGroup tg = Thread.currentThread().getThreadGroup();
        //遍历线程组
        for (ThreadGroup tgn = tg;
             tgn != null;
             tg = tgn, tgn = tg.getParent());
        //创建 ReferenceHandler 线程
        Thread handler = new ReferenceHandler(tg, "Reference Handler");
        /* If there were a special system-only priority greater than
         * MAX_PRIORITY, it would be used here
         */
        //设置最高优先级
        handler.setPriority(Thread.MAX_PRIORITY);
        //设置成守护线程
        handler.setDaemon(true);
        //启动线程
        handler.start();
    }


    /* -- Referent accessor and setters -- */

    /**
     * Returns this reference object's referent.  If this reference object has
     * been cleared, either by the program or by the garbage collector, then
     * this method returns <code>null</code>.
     *
     * @return   The object to which this reference refers, or
     *           <code>null</code> if this reference object has been cleared
     */
    public T get() {
        return this.referent;
    }

    /**
     * Clears this reference object.  Invoking this method will not cause this
     * object to be enqueued.
     *
     * <p> This method is invoked only by Java code; when the garbage collector
     * clears references it does so directly, without invoking this method.
     */
    public void clear() {
        //清空引用对象
        this.referent = null;
    }


    /* -- Queue operations -- */

    /**
     * Tells whether or not this reference object has been enqueued, either by
     * the program or by the garbage collector.  If this reference object was
     * not registered with a queue when it was created, then this method will
     * always return <code>false</code>.
     *
     * @return   <code>true</code> if and only if this reference object has
     *           been enqueued
     */
    public boolean isEnqueued() {
        return (this.queue == ReferenceQueue.ENQUEUED);
    }

    /**
     * Adds this reference object to the queue with which it is registered,
     * if any.
     *
     * <p> This method is invoked only by Java code; when the garbage collector
     * enqueues references it does so directly, without invoking this method.
     *
     * @return   <code>true</code> if this reference object was successfully
     *           enqueued; <code>false</code> if it was already enqueued or if
     *           it was not registered with a queue when it was created
     */
    public boolean enqueue() {
        return this.queue.enqueue(this);
    }


    /* -- Constructors -- */

    Reference(T referent) {
        this(referent, null);
    }

    Reference(T referent, ReferenceQueue<? super T> queue) {
        this.referent = referent;
        //如果引用队列为 null, 则用 ReferenceQueue.NULL
        this.queue = (queue == null) ? ReferenceQueue.NULL : queue;
    }

}
