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

/**
 * 引用队列，在检测到适当的可到达性更改后，垃圾回收器将已注册的引用对象添加到该队列中
 *  - ReferenceQueue名义上是一个队列，但实际内部并非有实际的存储结构，它的存储是依赖于内部节点之间的关系来表达
 *  - queue为一个链表的容器，其自己仅存储当前的head节点，而后面的节点由每个reference节点自己通过next来保持即可
 *  - queue为一个后进先出的队列
 *
 * Reference queues, to which registered reference objects are appended by the
 * garbage collector after the appropriate reachability changes are detected.
 *
 * @author   Mark Reinhold
 * @since    1.2
 */

public class ReferenceQueue<T> {

    /**
     * Constructs a new reference-object queue.
     */
    public ReferenceQueue() { }

    private static class Null<S> extends ReferenceQueue<S> {
        boolean enqueue(Reference<? extends S> r) {
            return false;
        }
    }

    static ReferenceQueue<Object> NULL = new Null<>();
    static ReferenceQueue<Object> ENQUEUED = new Null<>();

    /**
     * 链表的锁对象
     */
    static private class Lock { };
    private Lock lock = new Lock();
    /**
     * 引用队列链表头节点
     */
    private volatile Reference<? extends T> head = null;
    /**
     * 队列长度
     */
    private long queueLength = 0;

    boolean enqueue(Reference<? extends T> r) { /* Called only by Reference class */
        synchronized (lock) {
            // Check that since getting the lock this reference hasn't already been
            // enqueued (and even then removed)
            //获取引用对象的队列
            ReferenceQueue<?> queue = r.queue;
            //如果队列为 NULL 或 ENQUEUED , 则不入队
            if ((queue == NULL) || (queue == ENQUEUED)) {
                return false;
            }
            assert queue == this;
            //设置引用对象的队列为 ENQUEUED , 表示已经入队
            r.queue = ENQUEUED;
            //设置引用对象 r 下一个节点, 默认为  head, 如果 head 为 null 则 下一个节点为自己
            r.next = (head == null) ? r : head;
            //设置 r 为头节点
            head = r;
            //增加统计
            queueLength++;
            //如果引用对象为 FinalReference 类型
            if (r instanceof FinalReference) {
                //增加 refCoount
                sun.misc.VM.addFinalRefCount(1);
            }
            //唤醒当前锁对象的排队的线程
            lock.notifyAll();
            return true;
        }
    }

    @SuppressWarnings("unchecked")
    private Reference<? extends T> reallyPoll() {       /* Must hold lock */
        //获取头节点
        Reference<? extends T> r = head;
        if (r != null) {
            //取下一个节点为头节点, 没有则头节点为 null
            head = (r.next == r) ?
                null :
                r.next; // Unchecked due to the next field having a raw type in Reference
            //设置引用对象的队列为 NULL, 表示已经出队
            r.queue = NULL;
            //设置引用对象 next 指向自己
            r.next = r;
            //减少队列数量
            queueLength--;
            //如果是 FinalReference
            if (r instanceof FinalReference) {
                //减少计数
                sun.misc.VM.addFinalRefCount(-1);
            }
            //返回
            return r;
        }
        //没有, 默认返回 null
        return null;
    }

    /**
     * 获取队列的引用对象, 没有则返回 null
     * Polls this queue to see if a reference object is available.  If one is
     * available without further delay then it is removed from the queue and
     * returned.  Otherwise this method immediately returns <tt>null</tt>.
     *
     * @return  A reference object, if one was immediately available,
     *          otherwise <code>null</code>
     */
    public Reference<? extends T> poll() {
        //没有头节点, 默认返回 null
        if (head == null)
            return null;
        //上锁
        synchronized (lock) {
            return reallyPoll();
        }
    }

    /**
     * 获取引用对象, 没有则等待
     * Removes the next reference object in this queue, blocking until either
     * one becomes available or the given timeout period expires.
     *
     * <p> This method does not offer real-time guarantees: It schedules the
     * timeout as if by invoking the {@link Object#wait(long)} method.
     *
     * @param  timeout  If positive, block for up to <code>timeout</code>
     *                  milliseconds while waiting for a reference to be
     *                  added to this queue.  If zero, block indefinitely.
     *
     * @return  A reference object, if one was available within the specified
     *          timeout period, otherwise <code>null</code>
     *
     * @throws  IllegalArgumentException
     *          If the value of the timeout argument is negative
     *
     * @throws  InterruptedException
     *          If the timeout wait is interrupted
     */
    public Reference<? extends T> remove(long timeout)
        throws IllegalArgumentException, InterruptedException
    {
        //校验等待时间
        if (timeout < 0) {
            throw new IllegalArgumentException("Negative timeout value");
        }
        //上锁
        synchronized (lock) {
            //获取引用对象
            Reference<? extends T> r = reallyPoll();
            //有, 则直接返回
            if (r != null) return r;
            //计算等待时间
            long start = (timeout == 0) ? 0 : System.nanoTime();
            for (;;) {
                //阻塞等待
                lock.wait(timeout);
                //被唤醒, 再次获取
                r = reallyPoll();
                //有则返回
                if (r != null) return r;
                //再次计算剩余等待时间
                if (timeout != 0) {
                    long end = System.nanoTime();
                    timeout -= (end - start) / 1000_000;
                    //超时, 则返回 null
                    if (timeout <= 0) return null;
                    start = end;
                }
            }
        }
    }

    /**
     * 等待直到有元素
     * Removes the next reference object in this queue, blocking until one
     * becomes available.
     *
     * @return A reference object, blocking until one becomes available
     * @throws  InterruptedException  If the wait is interrupted
     */
    public Reference<? extends T> remove() throws InterruptedException {
        return remove(0);
    }

}
