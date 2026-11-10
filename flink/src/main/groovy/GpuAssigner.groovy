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

import gpukernel.GpuKernel
import groovy.transform.CompileStatic
import uk.ac.manchester.tornado.api.GridScheduler
import uk.ac.manchester.tornado.api.KernelContext
import uk.ac.manchester.tornado.api.TaskGraph
import uk.ac.manchester.tornado.api.TornadoExecutionPlan
import uk.ac.manchester.tornado.api.WorkerGrid1D
import uk.ac.manchester.tornado.api.types.arrays.FloatArray
import uk.ac.manchester.tornado.api.types.arrays.IntArray

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

import static uk.ac.manchester.tornado.api.enums.DataTransferMode.EVERY_EXECUTION

/** Assigns points to their nearest centroid with TornadoVM, one GPU thread per point. */
@CompileStatic
class GpuAssigner implements Assigner {
    @GpuKernel
    static void nearest(KernelContext ctx, FloatArray points, FloatArray centroids, IntArray out, int n, int k, int d) {
        int i = ctx.globalIdx
        if (i < n) {
            int best = 0
            float bestDist = 0.0f
            for (int c = 0; c < k; c++) {
                float dist = 0.0f
                for (int j = 0; j < d; j++) {
                    float diff = points.get(i * d + j) - centroids.get(c * d + j)
                    dist = dist + diff * diff
                }
                if (c == 0 || dist < bestDist) {
                    bestDist = dist
                    best = c
                }
            }
            out.set(i, best)
        }
    }

    // Each K-means iteration is a new Flink job, so we keep compiled plans (and their buffers)
    // for reuse by later jobs running in the same JVM, keyed by batch shape.
    private static final Map<String, Queue<Plan>> PLANS = new ConcurrentHashMap<>()

    int[] nearest(List<float[]> points, float[][] centroids) {
        var plans = PLANS.computeIfAbsent("${points.size()}x${centroids.length}x${centroids[0].length}".toString()) {
            new ConcurrentLinkedQueue<Plan>()
        }
        var plan = plans.poll() ?: new Plan(points.size(), centroids.length, centroids[0].length)
        try {
            plan.run(points, centroids)
        } finally {
            plans.offer(plan)
        }
    }

    private static class Plan {
        private static final AtomicInteger COUNT = new AtomicInteger()
        final int n, k, d
        final FloatArray points, centroids
        final IntArray assigned
        final TornadoExecutionPlan executionPlan

        Plan(int n, int k, int d) {
            this.n = n
            this.k = k
            this.d = d
            points = new FloatArray(n * d)
            centroids = new FloatArray(k * d)
            assigned = new IntArray(n)
            var name = "assign${COUNT.incrementAndGet()}"
            var graph = new TaskGraph(name)
                .transferToDevice(EVERY_EXECUTION, points, centroids)
                .task('nearest', GpuAssigner::nearest, new KernelContext(), points, centroids, assigned, n, k, d)
                .transferToHost(EVERY_EXECUTION, assigned)
            executionPlan = new TornadoExecutionPlan(graph.snapshot())
                .withGridScheduler(new GridScheduler("${name}.nearest".toString(), new WorkerGrid1D(n)))
        }

        int[] run(List<float[]> pts, float[][] cs) {
            for (int i = 0; i < n; i++) for (int j = 0; j < d; j++) points.set(i * d + j, pts[i][j])
            for (int c = 0; c < k; c++) for (int j = 0; j < d; j++) centroids.set(c * d + j, cs[c][j])
            executionPlan.execute()
            assigned.toHeapArray()
        }
    }
}
