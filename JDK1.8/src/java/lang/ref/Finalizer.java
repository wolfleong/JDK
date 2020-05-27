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

import java.security.PrivilegedAction;
import java.security.AccessController;
import sun.misc.JavaLangAccess;
import sun.misc.SharedSecrets;
import sun.misc.VM;

/**
 * Finalizer 为什么要存在一个双向链表呢,
 * 这个队列的作用是保存全部的只存在FinalizerReference引用、且没有执行过finalize方法的f类的Finalizer对象，防止finalizer对象在其引用的对象之前被gc回收掉
 */
final class Finalizer extends FinalReference<Object> { /* Package-private; must be in
                                                          same package as the Reference
                                                          class */

    /**
     * 引用队列
     */
    private static ReferenceQueue<Object> queue = new ReferenceQueue<>();
    /**
     * Finalizer 对象链表的头节点
     */
    private static Finalizer unfinalized = null;
    /**
     * 锁对象
     */
    private static final Object lock = new Object();

    /**
     * Finalizer 对象之间的双向链表的属性
     */
    private Finalizer
        next = null,
        prev = null;

    /**
     * 判断是否已经被调用 finalize
     */
    private boolean hasBeenFinalized() {
        return (next == this);
    }

    private void add() {
        //上锁
        synchronized (lock) {
            //头节点不为 null
            if (unfinalized != null) {
                //将头节点设置成 this 的下一个节点
                this.next = unfinalized;
                unfinalized.prev = this;
            }
            //当前对象变头节点
            unfinalized = this;
        }
    }

    private void remove() {
        //上锁
        synchronized (lock) {
            //如果当前节点是头节点
            if (unfinalized == this) {
                //下一个节点不为 null, 则下一个节点为头节点
                if (this.next != null) {
                    unfinalized = this.next;
                } else {
                    //否则用上一个节点作为头节点
                    unfinalized = this.prev;
                }
            }
            //删除当前节点
            if (this.next != null) {
                this.next.prev = this.prev;
            }
            if (this.prev != null) {
                this.prev.next = this.next;
            }
            //将当前节点的 next pred 都设置为 this
            this.next = this;   /* Indicates that this has been finalized */
            this.prev = this;
        }
    }

    private Finalizer(Object finalizee) {
        super(finalizee, queue);
        //创建节点并添加
        add();
    }

    /**
     * jvm里其实可以让用户选择在这两个时机中的任意一个将当前对象传递给Finalizer.register方法来注册到Finalizer对象链里，
     * 这个选择依赖于RegisterFinalizersAtInit这个vm参数是否被设置，默认值为true，也就是在调用构造函数返回之前调用Finalizer.register方法，
     * 如果通过-XX:-RegisterFinalizersAtInit关闭了该参数，那将在对象空间分配好之后就将这个对象注册进去
     *
     * 另外需要提一点的是当我们通过clone的方式复制一个对象的时候，如果当前类是一个f类，那么在clone完成的时候将调用Finalizer.register方法进行注册
     *
     */
    /* Invoked by VM */
    static void register(Object finalizee) {
        new Finalizer(finalizee);
    }

    private void runFinalizer(JavaLangAccess jla) {
        //同步
        synchronized (this) {
            //如果已经被执行, 则不处理
            if (hasBeenFinalized()) return;
            //删除当前节点
            remove();
        }
        try {
            //获取待回收的对象
            Object finalizee = this.get();
            //执行 finalizee 方法
            if (finalizee != null && !(finalizee instanceof java.lang.Enum)) {
                jla.invokeFinalize(finalizee);

                /* Clear stack slot containing this variable, to decrease
                   the chances of false retention with a conservative GC */
                finalizee = null;
            }
        } catch (Throwable x) { }
        //清空引用, 代理可回收
        super.clear();
    }

    /* Create a privileged secondary finalizer thread in the system thread
       group for the given Runnable, and wait for it to complete.

       This method is used by both runFinalization and runFinalizersOnExit.
       The former method invokes all pending finalizers, while the latter
       invokes all uninvoked finalizers if on-exit finalization has been
       enabled.

       These two methods could have been implemented by offloading their work
       to the regular finalizer thread and waiting for that thread to finish.
       The advantage of creating a fresh thread, however, is that it insulates
       invokers of these methods from a stalled or deadlocked finalizer thread.
     */
    private static void forkSecondaryFinalizer(final Runnable proc) {
        AccessController.doPrivileged(
            new PrivilegedAction<Void>() {
                public Void run() {
                ThreadGroup tg = Thread.currentThread().getThreadGroup();
                for (ThreadGroup tgn = tg;
                     tgn != null;
                     tg = tgn, tgn = tg.getParent());
                Thread sft = new Thread(tg, proc, "Secondary finalizer");
                sft.start();
                try {
                    sft.join();
                } catch (InterruptedException x) {
                    /* Ignore */
                }
                return null;
                }});
    }

    /* Called by Runtime.runFinalization() */
    static void runFinalization() {
        if (!VM.isBooted()) {
            return;
        }

        forkSecondaryFinalizer(new Runnable() {
            private volatile boolean running;
            public void run() {
                if (running)
                    return;
                final JavaLangAccess jla = SharedSecrets.getJavaLangAccess();
                running = true;
                for (;;) {
                    Finalizer f = (Finalizer)queue.poll();
                    if (f == null) break;
                    f.runFinalizer(jla);
                }
            }
        });
    }

    /* Invoked by java.lang.Shutdown */
    static void runAllFinalizers() {
        if (!VM.isBooted()) {
            return;
        }

        forkSecondaryFinalizer(new Runnable() {
            private volatile boolean running;
            public void run() {
                if (running)
                    return;
                final JavaLangAccess jla = SharedSecrets.getJavaLangAccess();
                running = true;
                for (;;) {
                    Finalizer f;
                    synchronized (lock) {
                        f = unfinalized;
                        if (f == null) break;
                        unfinalized = f.next;
                    }
                    f.runFinalizer(jla);
                }}});
    }

    private static class FinalizerThread extends Thread {
        /**
         * 是否正在运行
         */
        private volatile boolean running;
        FinalizerThread(ThreadGroup g) {
            super(g, "Finalizer");
        }
        public void run() {
            //如果正在运行, 则不处理
            if (running)
                return;

            // Finalizer thread starts before System.initializeSystemClass
            // is called.  Wait until JavaLangAccess is available
            while (!VM.isBooted()) {
                // delay until VM completes initialization
                try {
                    VM.awaitBooted();
                } catch (InterruptedException x) {
                    // ignore and continue
                }
            }
            final JavaLangAccess jla = SharedSecrets.getJavaLangAccess();
            //设置正在运行
            running = true;
            for (;;) {
                try {
                    //获取被回收的对象
                    Finalizer f = (Finalizer)queue.remove();
                    //执行 finalizer 方法
                    f.runFinalizer(jla);
                } catch (InterruptedException x) {
                    // ignore and continue
                }
            }
        }
    }

    static {
        //获取当前线程的线程组
        ThreadGroup tg = Thread.currentThread().getThreadGroup();
        //遍历获取
        for (ThreadGroup tgn = tg;
             tgn != null;
             tg = tgn, tgn = tg.getParent());
        //创建 FinalizerThread 线程
        Thread finalizer = new FinalizerThread(tg);
        //设置低优先级
        finalizer.setPriority(Thread.MAX_PRIORITY - 2);
        //设置为守护线程
        finalizer.setDaemon(true);
        //启动线程
        finalizer.start();
    }

}
