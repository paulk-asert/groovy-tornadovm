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
import org.apache.flink.api.common.RuntimeExecutionMode
import org.apache.flink.api.common.eventtime.WatermarkStrategy
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.api.common.typeinfo.Types
import org.apache.flink.api.java.tuple.Tuple2
import org.apache.flink.api.java.tuple.Tuple3
import org.apache.flink.connector.file.src.FileSource
import org.apache.flink.connector.file.src.reader.TextLineInputFormat
import org.apache.flink.core.fs.Path
import org.apache.flink.runtime.minicluster.MiniCluster
import org.apache.flink.runtime.minicluster.MiniClusterConfiguration
import org.apache.flink.streaming.api.datastream.DataStream
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment

/**
 * K-means over the whisky flavour profiles, hand-written on the Flink 2.x DataStream API.
 * Each iteration is a bounded (batch) job: assign every whisky to its nearest centroid,
 * then average the members of each cluster to get the new centroids.
 * The parsed profiles are cached after the first job, so later iterations don't re-read the file
 * (pass 'nocache' to compare).
 * Pass 'gpu' to do the assignment step on the GPU with TornadoVM.
 */
@CompileStatic
class WhiskeyKMeans {
    static final TypeInformation<Tuple2<String, float[]>> POINT =
        Types.TUPLE(Types.STRING, Types.PRIMITIVE_ARRAY(Types.FLOAT))
    static final TypeInformation<Tuple3<Integer, String, float[]>> ASSIGNED =
        Types.TUPLE(Types.INT, Types.STRING, Types.PRIMITIVE_ARRAY(Types.FLOAT))
    static final TypeInformation<Tuple3<Integer, float[], Integer>> SUM =
        Types.TUPLE(Types.INT, Types.PRIMITIVE_ARRAY(Types.FLOAT), Types.INT)

    static void main(String[] args) {
        Assigner assigner = 'gpu' in args ? new GpuAssigner() : new CpuAssigner()
        boolean timing = 'timing' in args, cache = !('nocache' in args)
        // name=value options, e.g. k=20 iterations=5
        var options = args.findAll { it.contains('=') }.collectEntries { it.split('=') as List }
        int k = (options.k ?: 5) as int, maxIterations = (options.iterations ?: 20) as int
        // an optional file or directory of profiles, e.g. from WhiskeyGenerator
        var file = args.find { !(it in ['gpu', 'timing', 'nocache']) && !it.contains('=') } ?:
            WhiskeyKMeans.classLoader.getResource('whiskey.csv').file

        // start from k distinct whiskies, picked with a fixed seed so CPU and GPU runs are comparable
        var first = new File(file).directory ? new File(file).listFiles().sort()[0] : new File(file)
        var rows = first.readLines().findAll { !it.startsWith('RowID') }
        Collections.shuffle(rows, new Random(42))
        float[][] centroids = rows.take(k).collect { parse(it).f1 } as float[][]

        // A cached stream only survives between jobs run on the same cluster, and local execution
        // would otherwise start a new MiniCluster for every job, so we start one for all of them.
        try (var miniCluster = new MiniCluster(new MiniClusterConfiguration.Builder().setNumSlotsPerTaskManager(4).build())) {
            miniCluster.start()
            var uri = miniCluster.restAddress.get()
            var env = StreamExecutionEnvironment.createRemoteEnvironment(uri.host, uri.port)
            env.runtimeMode = RuntimeExecutionMode.BATCH
            env.parallelism = 4
            var points = load(env, file, cache)

            for (iteration in 1..maxIterations) {
                long start = System.nanoTime()
                var updated = assign(points, centroids, assigner, timing)
                    .map(t -> Tuple3.of(t.f0, t.f2, 1)).returns(SUM)
                    .keyBy(t -> t.f0, Types.INT)
                    .reduce((a, b) -> Tuple3.of(a.f0, plus(a.f1, b.f1), a.f2 + b.f2)).returns(SUM)
                    .executeAndCollect(k)
                float[][] next = centroids.collect { float[] c -> c.clone() } as float[][]
                updated.each { next[it.f0] = it.f1.collect { float v -> v / it.f2 } as float[] }
                boolean converged = (0..<k).every { int c -> distance(next[c], centroids[c]) < 1e-3d }
                centroids = next
                if (timing) printf 'iteration %d took %.2f s%n', iteration, (System.nanoTime() - start) / 1e9d
                if (converged) {
                    println "Converged after $iteration iterations"
                    break
                }
            }

            println 'Centroids:'
            centroids.eachWithIndex { float[] c, int i ->
                println "Cluster $i: ${c.collect { sprintf '%.2f', it }.join(', ')}"
            }

            if (rows.size() > 100) return // too many to list
            assign(points, centroids, assigner, timing).executeAndCollect(100)
                .groupBy { it.f0 }.sort { it.key }.each { cluster, members ->
                    println "Cluster $cluster (${members.size()} members): ${members*.f1.sort().join(', ')}"
                }
        }
    }

    static DataStream<Tuple2<String, float[]>> load(StreamExecutionEnvironment env, String file, boolean cache) {
        var source = FileSource.forRecordStreamFormat(new TextLineInputFormat(), new Path(file)).build()
        // fix the parallelism rather than let Flink infer it from the input size,
        // as the jobs reading the cache expect the same number of partitions
        var points = env.fromSource(source, WatermarkStrategy.noWatermarks(), 'whiskey').setParallelism(env.parallelism)
            .filter(line -> !line.startsWith('RowID'))
            .map(WhiskeyKMeans::parse).returns(POINT)
        cache ? points.cache() : points
    }

    static DataStream<Tuple3<Integer, String, float[]>> assign(DataStream<Tuple2<String, float[]>> points,
            float[][] centroids, Assigner assigner, boolean timing) {
        points.transform('assign', ASSIGNED, new AssignOperator(centroids, assigner, timing))
    }

    static Tuple2<String, float[]> parse(String line) {
        var cols = line.split(',')
        Tuple2.of(cols[1], cols[2..-1].collect { it as float } as float[])
    }

    static double distance(float[] a, float[] b) {
        Math.sqrt((0..<a.length).sum { int i -> (a[i] - b[i]) ** 2 } as double)
    }

    static float[] plus(float[] a, float[] b) {
        float[] sum = new float[a.length]
        for (int i = 0; i < a.length; i++) sum[i] = a[i] + b[i] as float
        sum
    }
}
