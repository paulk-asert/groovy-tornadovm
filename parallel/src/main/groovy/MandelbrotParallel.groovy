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
import uk.ac.manchester.tornado.api.TaskGraph
import uk.ac.manchester.tornado.api.TornadoExecutionPlan
import uk.ac.manchester.tornado.api.annotations.Parallel
import uk.ac.manchester.tornado.api.types.arrays.IntArray

import static uk.ac.manchester.tornado.api.enums.DataTransferMode.EVERY_EXECUTION

@CompileStatic
class MandelbrotParallel {
    static final int MAX_ITER = 1000

    @GpuKernel
    static void mandel(IntArray out, int w, int h) {
        for (@Parallel int py = 0; py < h; py++) {
            for (@Parallel int px = 0; px < w; px++) {
                float cx = -2.0f + px * 3.0f / w
                float cy = -1.25f + py * 2.5f / h
                float x = 0.0f, y = 0.0f
                int n = 0
                while (n < MAX_ITER && x * x + y * y <= 4.0f) {
                    float xt = x * x - y * y + cx
                    y = 2.0f * x * y + cy
                    x = xt
                    n++
                }
                out.set(py * w + px, n)
            }
        }
    }

    static void main(String[] args) {
        int w = 3840, h = 3200
        def out = new IntArray(w * h)
        def graph = new TaskGraph('s0')
            .task('t0', MandelbrotParallel::mandel, out, w, h)
            .transferToHost(EVERY_EXECUTION, out)
        try (def plan = new TornadoExecutionPlan(graph.snapshot())) {
            plan.execute()
            long t0 = System.nanoTime()
            5.times { plan.execute() }
            printf 'GPU (@Parallel loops): %.1f ms/frame%n', (System.nanoTime() - t0) / 5e6
        }
        long sum = 0
        for (int i = 0; i < w * h; i++) sum += out.get(i)
        println "total iterations: $sum"
    }
}
