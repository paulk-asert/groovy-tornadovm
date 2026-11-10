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

/**
 * Writes a larger synthetic data set: each row is one of the 86 real profiles
 * with some Gaussian noise added, as if a few more tasters had a go.
 * Usage: WhiskeyGenerator dir rows files
 */
@CompileStatic
class WhiskeyGenerator {
    static void main(String[] args) {
        var dir = new File(args[0])
        int total = args[1] as int, files = args[2] as int
        var base = WhiskeyGenerator.classLoader.getResource('whiskey.csv').readLines().tail()*.split(',')
        dir.mkdirs()
        dir.listFiles()*.delete()
        var r = new Random(42)
        for (f in 0..<files) {
            new File(dir, "part-${f}.csv").withWriter { w ->
                for (i in 0..<total.intdiv(files)) {
                    var row = base[r.nextInt(base.size())]
                    var flavours = row[2..-1].collect { String v ->
                        sprintf '%.2f', Math.min(4d, Math.max(0d, (v as double) + r.nextGaussian() * 0.5d))
                    }
                    w.writeLine "$f-$i,${row[1]},${flavours.join(',')}"
                }
            }
        }
    }
}
