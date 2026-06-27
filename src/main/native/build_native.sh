#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
JAVA_HOME=${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}
mkdir -p build/native
clang++ -std=c++17 -shared \
  -o build/native/libmetalrender.dylib \
  ../resources/native/metalrender.mm \
  ../resources/native/meshshader.mm \
  -framework Cocoa -framework Metal -framework QuartzCore -framework IOSurface -framework OpenGL \
  -I"$JAVA_HOME/include" \
  -I"$JAVA_HOME/include/darwin" \
  -MJ ../resources/native/compile_commands.json
cp build/native/libmetalrender.dylib ../resources/
