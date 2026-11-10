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

@CompileStatic
class CpuAssigner implements Assigner {
    int[] nearest(List<float[]> points, float[][] centroids) {
        int[] result = new int[points.size()]
        for (int i = 0; i < result.length; i++) result[i] = nearest(points[i], centroids)
        result
    }

    static int nearest(float[] point, float[][] centroids) {
        int best = 0
        float bestDist = Float.MAX_VALUE
        for (int c = 0; c < centroids.length; c++) {
            float dist = 0
            for (int j = 0; j < point.length; j++) {
                float diff = point[j] - centroids[c][j]
                dist += diff * diff
            }
            if (dist < bestDist) { bestDist = dist; best = c }
        }
        best
    }
}
