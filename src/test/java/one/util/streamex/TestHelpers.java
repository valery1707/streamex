/*
 * Copyright 2015 Tagir Valeev
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package one.util.streamex;

import static one.util.streamex.StreamExInternals.*;
import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Spliterator;
import java.util.Map.Entry;
import java.util.Spliterators;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import one.util.streamex.IntStreamEx;
import one.util.streamex.StreamEx;

/**
 * @author Tagir Valeev
 */
public class TestHelpers {
    static enum Mode {
        NORMAL, PARALLEL, APPEND, PREPEND, RANDOM
    }

    static class StreamExSupplier<T> {
        private final Mode mode;
        private final Supplier<Stream<T>> base;

        public StreamExSupplier(Supplier<Stream<T>> base, Mode mode) {
            this.base = base;
            this.mode = mode;
        }

        public StreamEx<T> get() {
            Stream<T> res = base.get();
            switch (mode) {
            case NORMAL:
                return StreamEx.of(res.sequential());
            case PARALLEL:
                return StreamEx.of(res.parallel());
            case APPEND:
                // using Stream.empty() here makes the resulting stream
                // unordered which is undesired
                return StreamEx.of(res.parallel()).append(Arrays.<T> asList().stream());
            case PREPEND:
                return StreamEx.of(res.parallel()).prepend(Arrays.<T> asList().stream());
            case RANDOM:
                return StreamEx.of(new EmptyingSpliterator<>(res.parallel().spliterator())).parallel();
            default:
                throw new InternalError("Unsupported mode: "+mode);
            }
        }

        @Override
        public String toString() {
            return super.toString() + "/" + mode;
        }
    }

    static <T> List<StreamExSupplier<T>> streamEx(Supplier<Stream<T>> base) {
        return StreamEx.of(Mode.values())
                .map(mode -> new StreamExSupplier<>(base, mode)).toList();
    }
    
    /**
     * Spliterator which randomly inserts empty spliterators on splitting
     * 
     * @author Tagir Valeev
     *
     * @param <T> type of the elements
     */
    private static class EmptyingSpliterator<T> implements Spliterator<T> {
        private Spliterator<T> source;

        public EmptyingSpliterator(Spliterator<T> source) {
            this.source = Objects.requireNonNull(source);
        }

        @Override
        public boolean tryAdvance(Consumer<? super T> action) {
            return source.tryAdvance(action);
        }

        @Override
        public void forEachRemaining(Consumer<? super T> action) {
            source.forEachRemaining(action);
        }
        
        @Override
        public Comparator<? super T> getComparator() {
            return source.getComparator();
        }

        @Override
        public Spliterator<T> trySplit() {
            Spliterator<T> source = this.source;
            switch(ThreadLocalRandom.current().nextInt(3)) {
            case 0:
                return Spliterators.emptySpliterator();
            case 1:
                this.source = Spliterators.emptySpliterator();
                return source;
            default:
                Spliterator<T> split = source.trySplit();
                return split == null ? null : new EmptyingSpliterator<>(split);
            }
        }

        @Override
        public long estimateSize() {
            return source.estimateSize();
        }

        @Override
        public int characteristics() {
            return source.characteristics();
        }
    }

    static <T, R> void checkCollectorEmpty(String message, R expected, Collector<T, ?, R> collector) {
        if (finished(collector) != null)
            checkShortCircuitCollector(message, expected, 0, Stream::empty, collector);
        else
            checkCollector(message, expected, Stream::empty, collector);
    }

    static <T, TT extends T, R> void checkShortCircuitCollector(String message, R expected,
            int expectedConsumedElements, Supplier<Stream<TT>> base, Collector<T, ?, R> collector) {
        checkShortCircuitCollector(message, expected, expectedConsumedElements, base, collector, false);
    }

    static <T, TT extends T, R> void checkShortCircuitCollector(String message, R expected,
            int expectedConsumedElements, Supplier<Stream<TT>> base, Collector<T, ?, R> collector, boolean skipIdentity) {
        assertNotNull(message, finished(collector));
        Collector<T, ?, R> withIdentity = Collectors.collectingAndThen(collector, Function.identity());
        for (StreamExSupplier<TT> supplier : streamEx(base)) {
            AtomicInteger counter = new AtomicInteger();
            assertEquals(message + ": " + supplier, expected, supplier.get().peek(t -> counter.incrementAndGet())
                    .collect(collector));
            if (!supplier.get().isParallel())
                assertEquals(message + ": " + supplier + ": consumed: ", expectedConsumedElements, counter.get());
            if (!skipIdentity)
                assertEquals(message + ": " + supplier, expected, supplier.get().collect(withIdentity));
        }
    }

    static <T, TT extends T, R> void checkCollector(String message, R expected, Supplier<Stream<TT>> base,
            Collector<T, ?, R> collector) {
        // use checkShortCircuitCollector for CancellableCollector
        assertNull(message, finished(collector));
        for (StreamExSupplier<TT> supplier : streamEx(base)) {
            assertEquals(message + ": " + supplier, expected, supplier.get().collect(collector));
        }
    }

    static <T> List<StreamExSupplier<T>> emptyStreamEx(Class<T> clazz) {
        return streamEx(() -> Stream.<T> empty());
    }

    static <T> void checkSpliterator(String msg, Supplier<Spliterator<T>> supplier) {
        List<T> expected = new ArrayList<>();
        supplier.get().forEachRemaining(expected::add);
        checkSpliterator(msg, expected, supplier);
    }

    /*
     * Tests whether spliterators produced by given supplier produce the
     * expected result under various splittings
     * 
     * This test is single-threaded and its results are stable
     */
    static <T> void checkSpliterator(String msg, List<T> expected, Supplier<Spliterator<T>> supplier) {
        List<T> seq = new ArrayList<>();
        // Test forEachRemaining
        Spliterator<T> sequential = supplier.get();
        sequential.forEachRemaining(seq::add);
        assertFalse(msg, sequential.tryAdvance(t -> fail(msg + ": Advance called with " + t)));
        sequential.forEachRemaining(t -> fail(msg + ": Advance called with " + t));
        assertEquals(msg, expected, seq);
        
        // Test tryAdvance
        seq.clear();
        sequential = supplier.get();
        while(true) {
            AtomicBoolean called = new AtomicBoolean();
            boolean res = sequential.tryAdvance(t -> {
                seq.add(t);
                called.set(true);
            });
            if(res != called.get()) {
                fail(msg+ (res ? ": Consumer not called, but spliterator returned true" : ": Consumer called, but spliterator returned false"));
            }
            if(!res)
                break;
        }
        assertFalse(msg, sequential.tryAdvance(t -> fail(msg + ": Advance called with " + t)));
        assertEquals(msg, expected, seq);
        
        // Test trySplit
        Random r = new Random(1);
        for (int n = 1; n < 500; n++) {
            Spliterator<T> spliterator = supplier.get();
            List<Spliterator<T>> spliterators = new ArrayList<>();
            spliterators.add(spliterator);
            int p = r.nextInt(10) + 2;
            for (int i = 0; i < p; i++) {
                int idx = r.nextInt(spliterators.size());
                Spliterator<T> split = spliterators.get(idx).trySplit();
                if (split != null)
                    spliterators.add(idx, split);
            }
            List<Integer> order = IntStreamEx.ofIndices(spliterators).boxed().toList();
            Collections.shuffle(order, r);
            List<T> list = StreamEx.of(order).mapToEntry(idx -> {
                Spliterator<T> s = spliterators.get(idx);
                Stream.Builder<T> builder = Stream.builder();
                s.forEachRemaining(builder);
                assertFalse(msg, s.tryAdvance(t -> fail(msg + ": Advance called with " + t)));
                s.forEachRemaining(t -> fail(msg + ": Advance called with " + t));
                return builder.build();
            }).sortedBy(Entry::getKey).values().flatMap(Function.identity()).toList();
            assertEquals(msg + ":#" + n, expected, list);
        }
        for (int n = 1; n < 500; n++) {
            Spliterator<T> spliterator = supplier.get();
            List<Spliterator<T>> spliterators = new ArrayList<>();
            spliterators.add(spliterator);
            int p = r.nextInt(30) + 2;
            for (int i = 0; i < p; i++) {
                int idx = r.nextInt(spliterators.size());
                Spliterator<T> split = spliterators.get(idx).trySplit();
                if (split != null)
                    spliterators.add(idx, split);
            }
            List<List<T>> results = StreamEx.<List<T>> generate(() -> new ArrayList<>()).limit(spliterators.size())
                    .toList();
            int count = spliterators.size();
            while (count > 0) {
                int i;
                do {
                    i = r.nextInt(spliterators.size());
                    spliterator = spliterators.get(i);
                } while (spliterator == null);
                if (!spliterator.tryAdvance(results.get(i)::add)) {
                    spliterators.set(i, null);
                    count--;
                }
            }
            List<T> list = StreamEx.of(results).flatMap(List::stream).toList();
            assertEquals(msg + ":#" + n, expected, list);
        }
    }

    static void checkIllegalStateException(String message, Runnable r, String key, String value1, String value2) {
        try {
            r.run();
            fail(message+": no exception");
        }
        catch(IllegalStateException ex) {
            String exmsg = ex.getMessage();
            if (!exmsg.equals("Duplicate entry for key '" + key + "' (attempt to merge values '" + value1 + "' and '"
                + value2 + "')")
                    && !exmsg.equals("Duplicate entry for key '" + key + "' (attempt to merge values '" + value2 + "' and '"
                    + value1 + "')")
                    && !exmsg.equals("java.lang.IllegalStateException: Duplicate entry for key '" + key + "' (attempt to merge values '"
                    + value1 + "' and '" + value2 + "')")
                    && !exmsg.equals("java.lang.IllegalStateException: Duplicate entry for key '" + key + "' (attempt to merge values '"
                    + value2 + "' and '" + value1 + "')"))
                fail(message + ": wrong exception message: " + exmsg);
        }
    }
}
