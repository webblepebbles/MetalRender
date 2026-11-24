#!/bin/bash
# READ AGENTS.MD BEFORE EDITING THIS FILE - it saves you a lot of tokens!
set -e
cd "$(dirname "$0")"
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home
rm -f run/logs/latest.log
rm -f run/logs/*.gz
STAMP=build/.native_stamp
NATIVE_HASH=$( { find src/main/resources/native -type f \( -name '*.mm' -o -name '*.cpp' -o -name '*.h' -o -name '*.hpp' -o -name '*.metal' \) -print0 | sort -z | xargs -0 shasum; shasum compile_native.sh compile_shaders.sh; } | shasum | cut -d' ' -f1)
if [ -f src/main/resources/libmetalrender.dylib ] && [ -f "$STAMP" ] && [ "$(cat "$STAMP")" = "$NATIVE_HASH" ]; then
    echo "native side unchanged, no need rebuild"
else
    ./compile_native.sh
    mkdir -p build
    echo "$NATIVE_HASH" > "$STAMP"
fi

./gradlew runClient -Pjfr -Pcsv
