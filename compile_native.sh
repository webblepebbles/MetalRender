#!/bin/bash
set -e

./compile_shaders.sh

echo "Compiling native library..."
JAVA_HOME=${JAVA_HOME:-$(/usr/libexec/java_home)}
echo "Using JAVA_HOME: $JAVA_HOME"

clang++ -O3 -std=c++17 -dynamiclib \
    -DMETALRENDER_HAS_METALFX=1 \
    -framework Metal -framework MetalFX -framework Foundation -framework Cocoa -framework IOKit -framework IOSurface -framework OpenGL -framework QuartzCore -framework CoreVideo \
    -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/darwin" \
    -I"src/main/resources/native" \
    src/main/resources/native/metalrender.mm \
    src/main/resources/native/meshshader.mm \
    src/main/resources/native/metal_backend.mm \
    src/main/resources/native/far_terrain.mm \
    -o src/main/resources/libmetalrender_debug_v2.dylib

rm -f src/main/resources/libmetalrender.dylib
cp src/main/resources/libmetalrender_debug_v2.dylib src/main/resources/libmetalrender.dylib

echo "Native library compiled to src/main/resources/libmetalrender_debug_v2.dylib"
echo "Copied to src/main/resources/libmetalrender.dylib"
