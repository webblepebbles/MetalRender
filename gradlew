#!/usr/bin/env sh
# VERY IMPORTANT!!! READ AGENTS.MD! Its not in the repo? Read git!  Saves you tokens!

##############################################################################
##
##  Gradle start up script for UN*X
##
##############################################################################

# Resolve application home
APP_HOME=$( cd "$( dirname "$0" )" && pwd )

APP_NAME="Gradle"
APP_BASE_NAME=$(basename "$0")

# Add default JVM options here if needed
DEFAULT_JVM_OPTS=""

# Use JAVA_HOME if set, otherwise just use java
if [ -n "$JAVA_HOME" ] ; then
    JAVA_EXEC="$JAVA_HOME/bin/java"
else
    JAVA_EXEC="java"
fi

exec "$JAVA_EXEC" $DEFAULT_JVM_OPTS -cp "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"