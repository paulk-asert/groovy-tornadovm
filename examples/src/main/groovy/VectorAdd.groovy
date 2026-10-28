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
import uk.ac.manchester.tornado.api.GridScheduler
import uk.ac.manchester.tornado.api.KernelContext
import uk.ac.manchester.tornado.api.TaskGraph
import uk.ac.manchester.tornado.api.TornadoExecutionPlan
import uk.ac.manchester.tornado.api.WorkerGrid1D
import uk.ac.manchester.tornado.api.types.arrays.FloatArray

import static uk.ac.manchester.tornado.api.enums.DataTransferMode.EVERY_EXECUTION
import static uk.ac.manchester.tornado.api.enums.DataTransferMode.FIRST_EXECUTION

@CompileStatic
class VectorAdd {
    static void vectorAdd(KernelContext ctx, FloatArray a, FloatArray b, FloatArray c) {
        int i = ctx.globalIdx
        c.set(i, a.get(i) + b.get(i))
    }

    static void main(String[] args) {
        int size = 4096
        def a = new FloatArray(size), b = new FloatArray(size), c = new FloatArray(size)
        def r = new Random(42)
        for (int i = 0; i < size; i++) { a.set(i, r.nextFloat()); b.set(i, r.nextFloat()) }

        def ctx = new KernelContext()
        def graph = new TaskGraph('s0')
            .transferToDevice(FIRST_EXECUTION, a, b)
            .task('t0', VectorAdd::vectorAdd, ctx, a, b, c)
            .transferToHost(EVERY_EXECUTION, c)
        def grid = new GridScheduler('s0.t0', new WorkerGrid1D(size))
        try (def plan = new TornadoExecutionPlan(graph.snapshot())) {
            plan.withGridScheduler(grid).execute()
        }
        int bad = (0..<size).count { int i -> Math.abs(c.get(i) - (a.get(i) + b.get(i))) > 1e-6f } as int
        println "c[0..3] = ${(0..3).collect { int i -> c.get(i) }}, mismatches = $bad"
    }
}
