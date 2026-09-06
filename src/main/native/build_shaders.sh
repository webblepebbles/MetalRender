#!/bin/bash
echo "🔹 Starting Metal shader compilation..."
BUILD_DIR="build"
mkdir -p "$BUILD_DIR"
EXCLUDED="fragment.metal vertex.metal"
METAL_FILES=$(find . -name "*.metal" | grep -v -F -e "$(echo "$EXCLUDED" | tr ' ' '\n' | sed 's|^|./|' | paste -sd - -)" | sort)
is_excluded() {
  for ex in $EXCLUDED; do
    if [ "$1" = "./$ex" ]; then return 0; fi
  done
  return 1
}
for file in $(find . -name "*.metal" | sort); do
  if is_excluded "$file"; then
    echo "⏭️  Skipping stale duplicate shader: $file"
    continue
  fi
  BASENAME=$(basename "$file" .metal)
  AIR_FILE="$BUILD_DIR/${BASENAME}.air"
  echo "Compiling $file → $AIR_FILE"
  xcrun -sdk macosx metal -c "$file" -o "$AIR_FILE"
  if [ $? -ne 0 ]; then
    echo "Compilation failed for $file"
    exit 1
  fi
done
echo "Linking all .air files into shaders.metallib..."
xcrun -sdk macosx metallib "$BUILD_DIR"/*.air -o "$BUILD_DIR/shaders.metallib"
if [ $? -eq 0 ]; then
  echo "Compilation complete."
  echo "Metallib located at: $BUILD_DIR/shaders.metallib"
else
  echo "Metallib creation failed."
  exit 1
fi
