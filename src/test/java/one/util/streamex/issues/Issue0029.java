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

import static java.lang.Integer.parseInt;
import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Map.Entry;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.function.Function;
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

    static class IntervalMap<E, U extends Comparable<U>> {
        private final Function<E, U> endExtractor;
        private final Function<E, U> startExtractor;
        private final NavigableMap<U, E> map = new TreeMap<>();

        public IntervalMap(Function<E, U> startExtractor, Function<E, U> endExtractor) {
            this.startExtractor = startExtractor;
            this.endExtractor = endExtractor;
        }

        public void accept(E element) {
            U start = startExtractor.apply(element);
            U end = endExtractor.apply(element);
            Entry<U, E> floorEntry = map.floorEntry(start);
            if (floorEntry != null) {
                Entry<U, E> higherEntry = map.higherEntry(start);
                if (higherEntry != null && higherEntry.getValue() == floorEntry.getValue())
                    return;
            }
            Entry<U, E> ceilingEntry = map.ceilingEntry(end);
            if (ceilingEntry != null) {
                Entry<U, E> lowerEntry = map.lowerEntry(end);
                if (lowerEntry != null && lowerEntry.getValue() == ceilingEntry.getValue())
                    return;
            }
            map.subMap(start, true, end, true).clear();
            map.put(start, element);
            map.put(end, element);
        }

        public IntervalMap<E, U> combine(IntervalMap<E, U> other) {
            if (map.isEmpty())
                return other;
            // More efficient implementation is possible
            for (E t : other.asCollection()) {
                accept(t);
            }
            return this;
        }

        public List<E> asCollection() {
            List<E> result = new ArrayList<>(map.size() / 2);
            Iterator<E> iterator = map.values().iterator();
            while (iterator.hasNext()) {
                E val = iterator.next();
                if (!Objects.equals(val, iterator.next()))
                    throw new InternalError(map.toString());
                result.add(val);
            }
            return result;
        }

        public static <E, U extends Comparable<U>> Collector<E, ?, List<E>> collector(Function<E, U> startExtractor,
                Function<E, U> endExtractor) {
            return Collector.<E, IntervalMap<E, U>, List<E>> of(() -> new IntervalMap<>(startExtractor, endExtractor),
                IntervalMap::accept, IntervalMap::combine, IntervalMap::asCollection);
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

        private int getLowerKey() {
            return info.asInteger(info.getLowAddress());
        }

        private int getHigherKey() {
            return info.asInteger(info.getHighAddress());
        }

        @Override
        public String toString() {
            return "T[" + info.getCidrSignature() + "]";
        }

        @Override
        public int compareTo(T o) {
            String[] b1 = info.getNetworkAddress().split("\\.");
            String[] b2 = o.info.getNetworkAddress().split("\\.");
            int res;
            for (int i = 0; i < 4; i++) {
                res = parseInt(b1[0]) - parseInt(b2[0]);
                if (res != 0)
                    return res;
            }
            return rPrefix() - o.rPrefix();
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
        List<T> result = StreamEx.of(getTestData()).collect(IntervalMap.collector(T::getLowerKey, T::getHigherKey));
        assertEquals(EXPECTED, result.toString());
        List<T> resultPar = StreamEx.of(getTestData()).parallel()
                .collect(IntervalMap.collector(T::getLowerKey, T::getHigherKey));
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
