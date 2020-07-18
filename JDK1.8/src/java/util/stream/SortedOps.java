/*
 * Copyright (c) 2012, 2013, Oracle and/or its affiliates. All rights reserved.
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
package java.util.stream;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;
import java.util.Spliterator;
import java.util.function.IntFunction;


/**
 * Factory methods for transforming streams into sorted streams.
 *
 * @since 1.8
 */
final class SortedOps {

    private SortedOps() { }

    /**
     * Appends a "sorted" operation to the provided stream.
     *
     * @param <T> the type of both input and output elements
     * @param upstream a reference stream with element type T
     */
    static <T> Stream<T> makeRef(AbstractPipeline<?, T, ?> upstream) {
        return new OfRef<>(upstream);
    }

    /**
     * Appends a "sorted" operation to the provided stream.
     *
     * @param <T> the type of both input and output elements
     * @param upstream a reference stream with element type T
     * @param comparator the comparator to order elements by
     */
    static <T> Stream<T> makeRef(AbstractPipeline<?, T, ?> upstream,
                                Comparator<? super T> comparator) {
        return new OfRef<>(upstream, comparator);
    }

    /**
     * Appends a "sorted" operation to the provided stream.
     *
     * @param <T> the type of both input and output elements
     * @param upstream a reference stream with element type T
     */
    static <T> IntStream makeInt(AbstractPipeline<?, Integer, ?> upstream) {
        return new OfInt(upstream);
    }

    /**
     * Appends a "sorted" operation to the provided stream.
     *
     * @param <T> the type of both input and output elements
     * @param upstream a reference stream with element type T
     */
    static <T> LongStream makeLong(AbstractPipeline<?, Long, ?> upstream) {
        return new OfLong(upstream);
    }

    /**
     * Appends a "sorted" operation to the provided stream.
     *
     * @param <T> the type of both input and output elements
     * @param upstream a reference stream with element type T
     */
    static <T> DoubleStream makeDouble(AbstractPipeline<?, Double, ?> upstream) {
        return new OfDouble(upstream);
    }

    /**
     * Specialized subtype for sorting reference streams
     */
    private static final class OfRef<T> extends ReferencePipeline.StatefulOp<T, T> {
        /**
         * Comparator used for sorting
         */
        private final boolean isNaturalSort;
        private final Comparator<? super T> comparator;

        /**
         * Sort using natural order of {@literal <T>} which must be
         * {@code Comparable}.
         */
        OfRef(AbstractPipeline<?, T, ?> upstream) {
            super(upstream, StreamShape.REFERENCE,
                  StreamOpFlag.IS_ORDERED | StreamOpFlag.IS_SORTED);
            //设置自然排序
            this.isNaturalSort = true;
            // Will throw CCE when we try to sort if T is not Comparable
            //自然排序比较器
            @SuppressWarnings("unchecked")
            Comparator<? super T> comp = (Comparator<? super T>) Comparator.naturalOrder();
            this.comparator = comp;
        }

        /**
         * 指定比较器
         * Sort using the provided comparator.
         *
         * @param comparator The comparator to be used to evaluate ordering.
         */
        OfRef(AbstractPipeline<?, T, ?> upstream, Comparator<? super T> comparator) {
            super(upstream, StreamShape.REFERENCE,
                  StreamOpFlag.IS_ORDERED | StreamOpFlag.NOT_SORTED);
            //设置非自然顺序
            this.isNaturalSort = false;
            //设置排序器
            this.comparator = Objects.requireNonNull(comparator);
        }

        @Override
        public Sink<T> opWrapSink(int flags, Sink<T> sink) {
            Objects.requireNonNull(sink);

            // If the input is already naturally sorted and this operation
            // also naturally sorted then this is a no-op
            //如果 Sink 已经是有序的且是自然顺序, 则直接返回
            if (StreamOpFlag.SORTED.isKnown(flags) && isNaturalSort)
                return sink;
            //如果知道大小, 则返回 SizedRefSortingSink
            else if (StreamOpFlag.SIZED.isKnown(flags))
                return new SizedRefSortingSink<>(sink, comparator);
            else
                //默认返回
                return new RefSortingSink<>(sink, comparator);
        }

        @Override
        public <P_IN> Node<T> opEvaluateParallel(PipelineHelper<T> helper,
                                                 Spliterator<P_IN> spliterator,
                                                 IntFunction<T[]> generator) {
            // If the input is already naturally sorted and this operation
            // naturally sorts then collect the output
            if (StreamOpFlag.SORTED.isKnown(helper.getStreamAndOpFlags()) && isNaturalSort) {
                return helper.evaluate(spliterator, false, generator);
            }
            else {
                // @@@ Weak two-pass parallel implementation; parallel collect, parallel sort
                T[] flattenedData = helper.evaluate(spliterator, true, generator).asArray(generator);
                Arrays.parallelSort(flattenedData, comparator);
                return Nodes.node(flattenedData);
            }
        }
    }

    /**
     * Specialized subtype for sorting int streams.
     */
    private static final class OfInt extends IntPipeline.StatefulOp<Integer> {
        OfInt(AbstractPipeline<?, Integer, ?> upstream) {
            super(upstream, StreamShape.INT_VALUE,
                  StreamOpFlag.IS_ORDERED | StreamOpFlag.IS_SORTED);
        }

        @Override
        public Sink<Integer> opWrapSink(int flags, Sink<Integer> sink) {
            Objects.requireNonNull(sink);

            if (StreamOpFlag.SORTED.isKnown(flags))
                return sink;
            else if (StreamOpFlag.SIZED.isKnown(flags))
                return new SizedIntSortingSink(sink);
            else
                return new IntSortingSink(sink);
        }

        @Override
        public <P_IN> Node<Integer> opEvaluateParallel(PipelineHelper<Integer> helper,
                                                       Spliterator<P_IN> spliterator,
                                                       IntFunction<Integer[]> generator) {
            if (StreamOpFlag.SORTED.isKnown(helper.getStreamAndOpFlags())) {
                return helper.evaluate(spliterator, false, generator);
            }
            else {
                Node.OfInt n = (Node.OfInt) helper.evaluate(spliterator, true, generator);

                int[] content = n.asPrimitiveArray();
                Arrays.parallelSort(content);

                return Nodes.node(content);
            }
        }
    }

    /**
     * Specialized subtype for sorting long streams.
     */
    private static final class OfLong extends LongPipeline.StatefulOp<Long> {
        OfLong(AbstractPipeline<?, Long, ?> upstream) {
            super(upstream, StreamShape.LONG_VALUE,
                  StreamOpFlag.IS_ORDERED | StreamOpFlag.IS_SORTED);
        }

        @Override
        public Sink<Long> opWrapSink(int flags, Sink<Long> sink) {
            Objects.requireNonNull(sink);

            if (StreamOpFlag.SORTED.isKnown(flags))
                return sink;
            else if (StreamOpFlag.SIZED.isKnown(flags))
                return new SizedLongSortingSink(sink);
            else
                return new LongSortingSink(sink);
        }

        @Override
        public <P_IN> Node<Long> opEvaluateParallel(PipelineHelper<Long> helper,
                                                    Spliterator<P_IN> spliterator,
                                                    IntFunction<Long[]> generator) {
            if (StreamOpFlag.SORTED.isKnown(helper.getStreamAndOpFlags())) {
                return helper.evaluate(spliterator, false, generator);
            }
            else {
                Node.OfLong n = (Node.OfLong) helper.evaluate(spliterator, true, generator);

                long[] content = n.asPrimitiveArray();
                Arrays.parallelSort(content);

                return Nodes.node(content);
            }
        }
    }

    /**
     * Specialized subtype for sorting double streams.
     */
    private static final class OfDouble extends DoublePipeline.StatefulOp<Double> {
        OfDouble(AbstractPipeline<?, Double, ?> upstream) {
            super(upstream, StreamShape.DOUBLE_VALUE,
                  StreamOpFlag.IS_ORDERED | StreamOpFlag.IS_SORTED);
        }

        @Override
        public Sink<Double> opWrapSink(int flags, Sink<Double> sink) {
            Objects.requireNonNull(sink);

            if (StreamOpFlag.SORTED.isKnown(flags))
                return sink;
            else if (StreamOpFlag.SIZED.isKnown(flags))
                return new SizedDoubleSortingSink(sink);
            else
                return new DoubleSortingSink(sink);
        }

        @Override
        public <P_IN> Node<Double> opEvaluateParallel(PipelineHelper<Double> helper,
                                                      Spliterator<P_IN> spliterator,
                                                      IntFunction<Double[]> generator) {
            if (StreamOpFlag.SORTED.isKnown(helper.getStreamAndOpFlags())) {
                return helper.evaluate(spliterator, false, generator);
            }
            else {
                Node.OfDouble n = (Node.OfDouble) helper.evaluate(spliterator, true, generator);

                double[] content = n.asPrimitiveArray();
                Arrays.parallelSort(content);

                return Nodes.node(content);
            }
        }
    }

    /**
     * Abstract {@link Sink} for implementing sort on reference streams.
     *
     * <p>
     * Note: documentation below applies to reference and all primitive sinks.
     * <p>
     * Sorting sinks first accept all elements, buffering then into an array
     * or a re-sizable data structure, if the size of the pipeline is known or
     * unknown respectively.  At the end of the sink protocol those elements are
     * sorted and then pushed downstream.
     * This class records if {@link #cancellationRequested} is called.  If so it
     * can be inferred that the source pushing source elements into the pipeline
     * knows that the pipeline is short-circuiting.  In such cases sub-classes
     * pushing elements downstream will preserve the short-circuiting protocol
     * by calling {@code downstream.cancellationRequested()} and checking the
     * result is {@code false} before an element is pushed.
     * <p>
     * Note that the above behaviour is an optimization for sorting with
     * sequential streams.  It is not an error that more elements, than strictly
     * required to produce a result, may flow through the pipeline.  This can
     * occur, in general (not restricted to just sorting), for short-circuiting
     * parallel pipelines.
     */
    private static abstract class AbstractRefSortingSink<T> extends Sink.ChainedReference<T, T> {
        protected final Comparator<? super T> comparator;
        // @@@ could be a lazy final value, if/when support is added
        protected boolean cancellationWasRequested;

        AbstractRefSortingSink(Sink<? super T> downstream, Comparator<? super T> comparator) {
            super(downstream);
            this.comparator = comparator;
        }

        /**
         * Records is cancellation is requested so short-circuiting behaviour
         * can be preserved when the sorted elements are pushed downstream.
         *
         * @return false, as this sink never short-circuits.
         */
        @Override
        public final boolean cancellationRequested() {
            cancellationWasRequested = true;
            return false;
        }
    }

    /**
     * 确定元素大小的排序
     * {@link Sink} for implementing sort on SIZED reference streams.
     */
    private static final class SizedRefSortingSink<T> extends AbstractRefSortingSink<T> {
        /**
         * 数组
         */
        private T[] array;
        /**
         * 偏移量, 也就是存
         */
        private int offset;

        SizedRefSortingSink(Sink<? super T> sink, Comparator<? super T> comparator) {
            super(sink, comparator);
        }

        @Override
        @SuppressWarnings("unchecked")
        public void begin(long size) {
            //校验大小
            if (size >= Nodes.MAX_ARRAY_SIZE)
                throw new IllegalArgumentException(Nodes.BAD_SIZE);
            //创建给定长度的数组
            array = (T[]) new Object[(int) size];
        }

        @Override
        public void end() {
            //排序
            Arrays.sort(array, 0, offset, comparator);
            //调用下游开始
            downstream.begin(offset);
            // 下游Sink不包含短路操作
            if (!cancellationWasRequested) {
                //遍历数组, 执行
                for (int i = 0; i < offset; i++)
                    downstream.accept(array[i]);
            }
            //下游有短路操作
            else {
                //遍历执行
                for (int i = 0; i < offset && !downstream.cancellationRequested(); i++)
                    downstream.accept(array[i]);
            }
            //结束
            downstream.end();
            //清空
            array = null;
        }

        @Override
        public void accept(T t) {
            //添加元素并且移动指针
            array[offset++] = t;
        }
    }

    /**
     * 不知道大小的排序
     * {@link Sink} for implementing sort on reference streams.
     */
    private static final class RefSortingSink<T> extends AbstractRefSortingSink<T> {
        /**
         * 用于存放排序元素的
         */
        private ArrayList<T> list;

        RefSortingSink(Sink<? super T> sink, Comparator<? super T> comparator) {
            super(sink, comparator);
        }

        @Override
        public void begin(long size) {
            //元素长度不能超过最大值
            if (size >= Nodes.MAX_ARRAY_SIZE)
                throw new IllegalArgumentException(Nodes.BAD_SIZE);
            //创建一个存放排序元素的列表
            list = (size >= 0) ? new ArrayList<T>((int) size) : new ArrayList<T>();
        }

        @Override
        public void end() {
            // 只有元素全部接收之后才能开始排序
            list.sort(comparator);
            //执行下游(后面)的
            downstream.begin(list.size());
            // 下游Sink不包含短路操作
            if (!cancellationWasRequested) {
                // 将处理结果传递给流水线下游的Sink
                list.forEach(downstream::accept);
            }
            // 下游Sink包含短路操作
            else {
                for (T t : list) {
                    // 每次都调用cancellationRequested()询问是否可以结束处理。
                    if (downstream.cancellationRequested()) break;
                    //将处理结果传递给流水线下游的Sink
                    downstream.accept(t);
                }
            }
            //下游执行完成
            downstream.end();
            list = null;
        }

        @Override
        public void accept(T t) {
            //添加元素
            list.add(t);
        }
    }

    /**
     * Abstract {@link Sink} for implementing sort on int streams.
     */
    private static abstract class AbstractIntSortingSink extends Sink.ChainedInt<Integer> {
        protected boolean cancellationWasRequested;

        AbstractIntSortingSink(Sink<? super Integer> downstream) {
            super(downstream);
        }

        @Override
        public final boolean cancellationRequested() {
            cancellationWasRequested = true;
            return false;
        }
    }

    /**
     * {@link Sink} for implementing sort on SIZED int streams.
     */
    private static final class SizedIntSortingSink extends AbstractIntSortingSink {
        private int[] array;
        private int offset;

        SizedIntSortingSink(Sink<? super Integer> downstream) {
            super(downstream);
        }

        @Override
        public void begin(long size) {
            if (size >= Nodes.MAX_ARRAY_SIZE)
                throw new IllegalArgumentException(Nodes.BAD_SIZE);
            array = new int[(int) size];
        }

        @Override
        public void end() {
            Arrays.sort(array, 0, offset);
            downstream.begin(offset);
            if (!cancellationWasRequested) {
                for (int i = 0; i < offset; i++)
                    downstream.accept(array[i]);
            }
            else {
                for (int i = 0; i < offset && !downstream.cancellationRequested(); i++)
                    downstream.accept(array[i]);
            }
            downstream.end();
            array = null;
        }

        @Override
        public void accept(int t) {
            array[offset++] = t;
        }
    }

    /**
     * {@link Sink} for implementing sort on int streams.
     */
    private static final class IntSortingSink extends AbstractIntSortingSink {
        private SpinedBuffer.OfInt b;

        IntSortingSink(Sink<? super Integer> sink) {
            super(sink);
        }

        @Override
        public void begin(long size) {
            if (size >= Nodes.MAX_ARRAY_SIZE)
                throw new IllegalArgumentException(Nodes.BAD_SIZE);
            b = (size > 0) ? new SpinedBuffer.OfInt((int) size) : new SpinedBuffer.OfInt();
        }

        @Override
        public void end() {
            int[] ints = b.asPrimitiveArray();
            Arrays.sort(ints);
            downstream.begin(ints.length);
            if (!cancellationWasRequested) {
                for (int anInt : ints)
                    downstream.accept(anInt);
            }
            else {
                for (int anInt : ints) {
                    if (downstream.cancellationRequested()) break;
                    downstream.accept(anInt);
                }
            }
            downstream.end();
        }

        @Override
        public void accept(int t) {
            b.accept(t);
        }
    }

    /**
     * Abstract {@link Sink} for implementing sort on long streams.
     */
    private static abstract class AbstractLongSortingSink extends Sink.ChainedLong<Long> {
        protected boolean cancellationWasRequested;

        AbstractLongSortingSink(Sink<? super Long> downstream) {
            super(downstream);
        }

        @Override
        public final boolean cancellationRequested() {
            cancellationWasRequested = true;
            return false;
        }
    }

    /**
     * {@link Sink} for implementing sort on SIZED long streams.
     */
    private static final class SizedLongSortingSink extends AbstractLongSortingSink {
        private long[] array;
        private int offset;

        SizedLongSortingSink(Sink<? super Long> downstream) {
            super(downstream);
        }

        @Override
        public void begin(long size) {
            if (size >= Nodes.MAX_ARRAY_SIZE)
                throw new IllegalArgumentException(Nodes.BAD_SIZE);
            array = new long[(int) size];
        }

        @Override
        public void end() {
            Arrays.sort(array, 0, offset);
            downstream.begin(offset);
            if (!cancellationWasRequested) {
                for (int i = 0; i < offset; i++)
                    downstream.accept(array[i]);
            }
            else {
                for (int i = 0; i < offset && !downstream.cancellationRequested(); i++)
                    downstream.accept(array[i]);
            }
            downstream.end();
            array = null;
        }

        @Override
        public void accept(long t) {
            array[offset++] = t;
        }
    }

    /**
     * {@link Sink} for implementing sort on long streams.
     */
    private static final class LongSortingSink extends AbstractLongSortingSink {
        private SpinedBuffer.OfLong b;

        LongSortingSink(Sink<? super Long> sink) {
            super(sink);
        }

        @Override
        public void begin(long size) {
            if (size >= Nodes.MAX_ARRAY_SIZE)
                throw new IllegalArgumentException(Nodes.BAD_SIZE);
            b = (size > 0) ? new SpinedBuffer.OfLong((int) size) : new SpinedBuffer.OfLong();
        }

        @Override
        public void end() {
            long[] longs = b.asPrimitiveArray();
            Arrays.sort(longs);
            downstream.begin(longs.length);
            if (!cancellationWasRequested) {
                for (long aLong : longs)
                    downstream.accept(aLong);
            }
            else {
                for (long aLong : longs) {
                    if (downstream.cancellationRequested()) break;
                    downstream.accept(aLong);
                }
            }
            downstream.end();
        }

        @Override
        public void accept(long t) {
            b.accept(t);
        }
    }

    /**
     * Abstract {@link Sink} for implementing sort on long streams.
     */
    private static abstract class AbstractDoubleSortingSink extends Sink.ChainedDouble<Double> {
        protected boolean cancellationWasRequested;

        AbstractDoubleSortingSink(Sink<? super Double> downstream) {
            super(downstream);
        }

        @Override
        public final boolean cancellationRequested() {
            cancellationWasRequested = true;
            return false;
        }
    }

    /**
     * {@link Sink} for implementing sort on SIZED double streams.
     */
    private static final class SizedDoubleSortingSink extends AbstractDoubleSortingSink {
        private double[] array;
        private int offset;

        SizedDoubleSortingSink(Sink<? super Double> downstream) {
            super(downstream);
        }

        @Override
        public void begin(long size) {
            if (size >= Nodes.MAX_ARRAY_SIZE)
                throw new IllegalArgumentException(Nodes.BAD_SIZE);
            array = new double[(int) size];
        }

        @Override
        public void end() {
            Arrays.sort(array, 0, offset);
            downstream.begin(offset);
            if (!cancellationWasRequested) {
                for (int i = 0; i < offset; i++)
                    downstream.accept(array[i]);
            }
            else {
                for (int i = 0; i < offset && !downstream.cancellationRequested(); i++)
                    downstream.accept(array[i]);
            }
            downstream.end();
            array = null;
        }

        @Override
        public void accept(double t) {
            array[offset++] = t;
        }
    }

    /**
     * {@link Sink} for implementing sort on double streams.
     */
    private static final class DoubleSortingSink extends AbstractDoubleSortingSink {
        private SpinedBuffer.OfDouble b;

        DoubleSortingSink(Sink<? super Double> sink) {
            super(sink);
        }

        @Override
        public void begin(long size) {
            if (size >= Nodes.MAX_ARRAY_SIZE)
                throw new IllegalArgumentException(Nodes.BAD_SIZE);
            b = (size > 0) ? new SpinedBuffer.OfDouble((int) size) : new SpinedBuffer.OfDouble();
        }

        @Override
        public void end() {
            double[] doubles = b.asPrimitiveArray();
            Arrays.sort(doubles);
            downstream.begin(doubles.length);
            if (!cancellationWasRequested) {
                for (double aDouble : doubles)
                    downstream.accept(aDouble);
            }
            else {
                for (double aDouble : doubles) {
                    if (downstream.cancellationRequested()) break;
                    downstream.accept(aDouble);
                }
            }
            downstream.end();
        }

        @Override
        public void accept(double t) {
            b.accept(t);
        }
    }
}
