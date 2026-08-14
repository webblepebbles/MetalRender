#!/bin/bash
set -e

echo "Compiling shaders..."
SHADER_DIR="src/main/resources/native/shaders"

xcrun -sdk macosx metal -c $SHADER_DIR/metalrender.metal -o $SHADER_DIR/metalrender.air
xcrun -sdk macosx metal -c $SHADER_DIR/entity.metal -o $SHADER_DIR/entity.air
xcrun -sdk macosx metal -c $SHADER_DIR/culling.metal -o $SHADER_DIR/culling.air
xcrun -sdk macosx metal -c $SHADER_DIR/hiz.metal -o $SHADER_DIR/hiz.air
xcrun -sdk macosx metal -c $SHADER_DIR/occlusion_culling.metal -o $SHADER_DIR/occlusion_culling.air
xcrun -sdk macosx metal -c $SHADER_DIR/visibility_buffer.metal -o $SHADER_DIR/visibility_buffer.air
xcrun -sdk macosx metal -c $SHADER_DIR/oit_transparency.metal -o $SHADER_DIR/oit_transparency.air
xcrun -sdk macosx metal -c $SHADER_DIR/cull_and_encode.metal -o $SHADER_DIR/cull_and_encode.air
xcrun -sdk macosx metal -c $SHADER_DIR/translucency_sort.metal -o $SHADER_DIR/translucency_sort.air
xcrun -sdk macosx metal -c $SHADER_DIR/motion_vectors.metal -o $SHADER_DIR/motion_vectors.air

echo "compelling shaders"
xcrun -sdk macosx metal -std=metal3.0 -c $SHADER_DIR/mesh_terrain.metal -o $SHADER_DIR/mesh_terrain.air

xcrun -sdk macosx metallib \
    $SHADER_DIR/metalrender.air \
    $SHADER_DIR/entity.air \
    $SHADER_DIR/culling.air \
    $SHADER_DIR/hiz.air \
    $SHADER_DIR/occlusion_culling.air \
    $SHADER_DIR/visibility_buffer.air \
    $SHADER_DIR/oit_transparency.air \
    $SHADER_DIR/cull_and_encode.air \
    $SHADER_DIR/translucency_sort.air \
    $SHADER_DIR/motion_vectors.air \
    $SHADER_DIR/mesh_terrain.air \
    -o src/main/resources/shaders.metallib
echo "Shaders compiled to src/main/resources/shaders.metallib"

echo "Compiling native library..."
JAVA_HOME=$(/usr/libexec/java_home)
echo "Using JAVA_HOME: $JAVA_HOME"

clang++ -O3 -std=c++17 -dynamiclib \
    -DMETALRENDER_HAS_METALFX=1 \
    -framework Metal -framework MetalFX -framework Foundation -framework Cocoa -framework IOKit -framework IOSurface -framework OpenGL -framework QuartzCore \
    -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/darwin" \
    -I"src/main/resources/native" \
    src/main/resources/native/metalrender.mm \
    src/main/resources/native/meshshader.mm \
    -o src/main/resources/libmetalrender_debug_v2.dylib

cp src/main/resources/libmetalrender_debug_v2.dylib src/main/resources/libmetalrender.dylib

echo "Native library compiled to src/main/resources/libmetalrender_debug_v2.dylib"
echo "Copied to src/main/resources/libmetalrender.dylib"
