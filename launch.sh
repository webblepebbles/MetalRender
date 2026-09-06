#!/bin/bash
cd "$(dirname "$0")"
rm -f run/logs/latest.log
./compile_native.sh

export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home && ./gradlew runClient
