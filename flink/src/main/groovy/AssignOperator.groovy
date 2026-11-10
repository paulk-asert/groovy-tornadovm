/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import groovy.transform.CompileStatic
import org.apache.flink.api.java.tuple.Tuple2
import org.apache.flink.api.java.tuple.Tuple3
import org.apache.flink.streaming.api.operators.AbstractStreamOperator
import org.apache.flink.streaming.api.operators.BoundedOneInput
import org.apache.flink.streaming.api.operators.OneInputStreamOperator
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord

/**
 * Buffers the whisky profiles arriving at this parallel instance and, once its
 * input ends, assigns them all to their nearest centroid in one batch.
 */
@CompileStatic
class AssignOperator extends AbstractStreamOperator<Tuple3<Integer, String, float[]>>
        implements OneInputStreamOperator<Tuple2<String, float[]>, Tuple3<Integer, String, float[]>>, BoundedOneInput {
    private final float[][] centroids
    private final Assigner assigner
    private final boolean timing
    private transient List<Tuple2<String, float[]>> batch

    AssignOperator(float[][] centroids, Assigner assigner, boolean timing) {
        this.centroids = centroids
        this.assigner = assigner
        this.timing = timing
    }

    void open() {
        super.open()
        batch = []
    }

    void processElement(StreamRecord<Tuple2<String, float[]>> element) {
        batch << element.value
    }

    void endInput() {
        if (!batch) return
        long start = System.nanoTime()
        // a plain loop rather than batch*.f1, see the README
        List<float[]> points = new ArrayList<>(batch.size())
        for (Tuple2<String, float[]> p : batch) points.add(p.f1)
        int[] clusters = assigner.nearest(points, centroids)
        if (timing) printf 'assigned %,d points in %.1f ms%n', batch.size(), (System.nanoTime() - start) / 1e6d
        batch.eachWithIndex { p, i ->
            output.collect(new StreamRecord<>(Tuple3.of(clusters[i], p.f0, p.f1)))
        }
    }
}
