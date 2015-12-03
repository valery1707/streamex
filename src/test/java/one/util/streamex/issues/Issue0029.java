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
package one.util.streamex.issues;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.BiPredicate;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.net.util.SubnetUtils;
import org.apache.commons.net.util.SubnetUtils.SubnetInfo;
import org.junit.Test;

import one.util.streamex.IntStreamEx;
import one.util.streamex.StreamEx;

/**
 * https://github.com/amaembo/streamex/issues/29
 * 
 * @author Tagir Valeev
 */
public class Issue0029 {
    private static final String _192_168_0_0_16 = "192.168.0.0/16";
    private static final String EXPECTED = "[T[" + _192_168_0_0_16 + "]]";

    static class IntervalMap<E> {
        private final BiPredicate<? super E, ? super E> isParent;
        private final NavigableSet<E> set;

        public IntervalMap(Comparator<? super E> comparator, BiPredicate<? super E, ? super E> isParent) {
            this.isParent = isParent;
            this.set = new TreeSet<>(comparator);
        }

        public void accept(E e) {
            E left = set.floor(Objects.requireNonNull(e));
            if (left != null && isParent.test(left, e)) {
                return;
            }
            Iterator<E> iterator = set.tailSet(e).iterator();
            while (iterator.hasNext()) {
                E right = iterator.next();
                if (!isParent.test(e, right))
                    break;
                iterator.remove();
            }
            set.add(e);
        }

        public IntervalMap<E> combine(IntervalMap<E> other) {
            if (set.isEmpty())
                return other;
            // More efficient implementation is possible
            other.set.forEach(this::accept);
            return this;
        }

        public List<E> asList() {
            return new ArrayList<>(set);
        }

        public static <E> Collector<E, ?, List<E>> collector(Comparator<? super E> comparator,
                BiPredicate<? super E, ? super E> isParent) {
            return Collector.<E, IntervalMap<E>, List<E>> of(() -> new IntervalMap<>(comparator, isParent),
                IntervalMap::accept, IntervalMap::combine, IntervalMap::asList);
        }

        public static <E extends Comparable<? super E>> Collector<E, ?, List<E>> collector(
                BiPredicate<? super E, ? super E> isParent) {
            return Collector.<E, IntervalMap<E>, List<E>> of(() -> new IntervalMap<>(Comparator.naturalOrder(),
                    isParent), IntervalMap::accept, IntervalMap::combine, IntervalMap::asList);
        }
    }

    static class T implements Comparable<T> {
        SubnetInfo info;

        public T(String cidr) {
            SubnetUtils tmp = new SubnetUtils(cidr);
            tmp.setInclusiveHostCount(true);
            this.info = tmp.getInfo();

        }

        public boolean isParent(T child) {
            return this.info.isInRange(child.info.getNetworkAddress()) && rPrefix() <= child.rPrefix();
        }

        private int rPrefix() {
            String cidrSignature = info.getCidrSignature();
            String iPrefix = cidrSignature.substring(cidrSignature.lastIndexOf('/') + 1);
            return Integer.parseInt(iPrefix);
        }

        @Override
        public String toString() {
            return "T[" + info.getCidrSignature() + "]";
        }

        @Override
        public int compareTo(T o) {
            int res = Integer.compare(info.asInteger(info.getAddress()), o.info.asInteger(o.info.getAddress()));
            if(res != 0)
                return res;
            return Integer.compare(rPrefix(), o.rPrefix());
        }
    }

    private Stream<T> getTestData() {
        // Stream of parent net 192.168.0.0/16 follow by the 50 first childs
        // 192.168.X.0/24
        List<T> list = IntStreamEx.range(50).mapToObj(String::valueOf).map(i -> "192.168." + i + ".0/24")
                .append(_192_168_0_0_16).map(T::new).toList();
        Collections.shuffle(list);
        return list.stream();
    }

    @Test
    public void testCollect() {
        List<T> result = StreamEx.of(getTestData()).collect(IntervalMap.collector(T::isParent));
        assertEquals(EXPECTED, result.toString());
        List<T> resultPar = StreamEx.of(getTestData()).parallel().collect(IntervalMap.collector(T::isParent));
        assertEquals(EXPECTED, resultPar.toString());
    }

    @Test
    public void testPlain() {
        List<T> tmp = getTestData().sorted().collect(Collectors.toList());
        Iterator<T> it = tmp.iterator();
        T curr, last;
        curr = last = null;
        while (it.hasNext()) {
            T oldLast = last;
            last = curr;
            curr = it.next();
            if (last != null && last.isParent(curr)) {
                it.remove();
                curr = last;
                last = oldLast;
            }
        }
        List<T> result = tmp.stream().collect(Collectors.toList());
        assertEquals(EXPECTED, result.toString());
    }
}
