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
import uk.ac.manchester.tornado.api.WorkerGrid2D
import uk.ac.manchester.tornado.api.types.arrays.IntArray

import javax.imageio.ImageIO
import java.awt.image.BufferedImage

import static uk.ac.manchester.tornado.api.enums.DataTransferMode.EVERY_EXECUTION

@CompileStatic
class Mandelbrot {
    static final int MAX_ITER = 1000

    @GpuKernel
    static void mandel(KernelContext ctx, IntArray out, int w, int h) {
        int px = ctx.globalIdx
        int py = ctx.globalIdy
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

    static void mandelCpu(IntArray out, int w, int h) {
        for (int py = 0; py < h; py++) mandelRow(out, w, h, py)
    }

    @GpuKernel
    static void mandelRow(IntArray out, int w, int h, int py) {
        float cy = -1.25f + py * 2.5f / h
        for (int px = 0; px < w; px++) {
            float cx = -2.0f + px * 3.0f / w
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

    static void main(String[] args) {
        int w = 3840, h = 3200
        def gpu = new IntArray(w * h), cpu = new IntArray(w * h)
        def ctx = new KernelContext()
        def graph = new TaskGraph('s0')
            .task('t0', Mandelbrot::mandel, ctx, gpu, w, h)
            .transferToHost(EVERY_EXECUTION, gpu)
        def grid = new GridScheduler('s0.t0', new WorkerGrid2D(w, h).tap { setLocalWork(16, 16, 1) })
        try (def plan = new TornadoExecutionPlan(graph.snapshot()).withGridScheduler(grid)) {
            plan.execute() // warm up: includes kernel compilation
            long t0 = System.nanoTime()
            5.times { plan.execute() }
            printf 'GPU: %.1f ms/frame%n', (System.nanoTime() - t0) / 5e6
        }
        long t0 = System.nanoTime()
        mandelCpu(cpu, w, h)
        printf 'CPU (single thread): %.1f ms%n', (System.nanoTime() - t0) / 1e6
        def par = new IntArray(w * h)
        t0 = System.nanoTime()
        java.util.stream.IntStream.range(0, h).parallel().forEach { int py -> mandelRow(par, w, h, py) }
        printf 'CPU (parallel stream, %d cores): %.1f ms%n', Runtime.runtime.availableProcessors(), (System.nanoTime() - t0) / 1e6

        int diff = (0..<(w * h)).count { int i -> gpu.get(i) != cpu.get(i) } as int
        printf 'pixels differing GPU vs CPU: %d of %d (%.3f%%)%n', diff, w * h, 100.0d * diff / (w * h)
        long total = 0
        for (int i = 0; i < w * h; i++) total += gpu.get(i)
        println "total iterations: $total"

        def img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (int i = 0; i < w * h; i++) {
            int n = gpu.get(i)
            int rgb = n == MAX_ITER ? 0 : java.awt.Color.HSBtoRGB(0.6f + 10f * n / MAX_ITER as float, 0.8f, Math.min(1f, n / 30f) as float)
            img.setRGB(i % w, i.intdiv(w), rgb)
        }
        ImageIO.write(img, 'png', new File('mandelbrot.png'))
    }
}
