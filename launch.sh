#!/bin/bash
set -e
cd "$(dirname "$0")"
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home
rm -f run/logs/latest.log
rm -f run/logs/*.gz
./compile_native.sh

./gradlew runClient -Pjfr -Pcsv
