#!/bin/bash
cd "$(dirname "$0")"
rm -f run/logs/latest.log

# Use Java 25 for Fabric Loom
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home

