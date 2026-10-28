# Groovy on the GPU with TornadoVM

Companion code for the Apache Groovy blog post
[Running Groovy on the GPU with TornadoVM](https://groovy.apache.org/blog/groovy-tornadovm).

It shows Groovy code compiled to GPU kernels by [TornadoVM](https://www.tornadovm.org) 8.0.0:

* a vector add and a Mandelbrot renderer written with TornadoVM's `KernelContext` API,
  which work with Groovy 6.0.0 today
* the same programs written with TornadoVM's `@Parallel` loop annotation, which need
  the Groovy fixes [GROOVY-12540](https://issues.apache.org/jira/browse/GROOVY-12540),
  [GROOVY-12541](https://issues.apache.org/jira/browse/GROOVY-12541) and
  [GROOVY-12542](https://issues.apache.org/jira/browse/GROOVY-12542)
* `@GpuKernel`, a small AST transformation that makes float arithmetic in a kernel
  method compile the way javac would compile it

## Layout

| Path | What |
|---|---|
| `gpukernel/` | the `@GpuKernel` AST transformation |
| `examples/` | `VectorAdd` and `Mandelbrot` using `KernelContext` (Groovy 6.0.0) |
| `parallel/` | `VectorAddParallel` and `MandelbrotParallel` using `@Parallel` loops (needs the fixes above) |

## Setup

Requires JDK 21 (used via a Gradle toolchain) and a TornadoVM 8.0.0 SDK for your platform,
from the [TornadoVM releases page](https://github.com/beehive-lab/TornadoVM/releases/tag/v8.0.0).
The examples were developed on an Apple M4 Pro with the `jdk21` SDKs for macOS:

```
mkdir -p sdk && cd sdk
gh release download v8.0.0 -R beehive-lab/TornadoVM \
    -p 'tornadovm-8.0.0-jdk21-metal-mac-aarch64.tar.gz' \
    -p 'tornadovm-8.0.0-jdk21-opencl-mac-aarch64.tar.gz'
for f in *.tar.gz; do tar xzf $f; done
```

The build uses `TORNADOVM_HOME` if set, otherwise `sdk/tornadovm-8.0.0-metal`.
Check that TornadoVM can see your GPU with `$TORNADOVM_HOME/bin/tornado --devices`.

## Running

```
./gradlew :examples:runVectorAdd
./gradlew :examples:runMandelbrot          # writes examples/build/mandelbrot.png
TORNADOVM_HOME=$PWD/sdk/tornadovm-8.0.0-opencl ./gradlew :examples:runMandelbrot
./gradlew :examples:runMandelbrot -PtornadoFlags=--printKernel   # show the generated kernel
```

The `@Parallel` examples are only built with `-PwithParallel`. Groovy 6.0.0 fails to compile them
(GROOVY-12540). Until a Groovy release with the fixes is available, point the build at a local
Groovy build that includes them:

```
./gradlew -PwithParallel -PgroovyHome=/path/to/groovy/subprojects/groovy-binary/build/install \
    :parallel:runVectorAddParallel :parallel:runMandelbrotParallel
```

Once released, `-PwithParallel -PgroovyVersion=<version>` will do.

## NVIDIA GPUs on Colab

[`notebooks/tornadovm-cuda-colab.ipynb`](notebooks/tornadovm-cuda-colab.ipynb) runs the examples
on TornadoVM's CUDA backend in a Google Colab GPU runtime, using `bash` cells from the normal Python runtime:
it installs JDK 21 and the `jdk21-cuda-linux-amd64` SDK, then runs the Gradle tasks above.
[Open it in Colab](https://colab.research.google.com/github/paulk-asert/groovy-tornadovm/blob/main/notebooks/tornadovm-cuda-colab.ipynb).
It has been run on a Colab Tesla T4 runtime (CUDA 13.0), where the `KernelContext` examples work unchanged.

## License

Apache License 2.0, see [LICENSE](LICENSE).
