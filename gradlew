#!/bin/sh
##############################################################################
# Gradle start up script for UN*X
##############################################################################
APP_NAME="Gradle"
APP_BASE_NAME=`basename "$0"`
DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'
die () { echo; echo "ERROR: $*"; echo; exit 1; }
warn () { echo "$*"; }
# OS specific support (must be 'true' or 'false').
cygwin=false
msys=false
darwin=false
nonstop=false
case "`uname`" in CYGWIN* ) cygwin=true ;; Darwin* ) darwin=true ;; MINGW* ) msys=true ;; NONSTOP* ) nonstop=true ;; esac
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar
# Determine the Java command to use to start the JVM.
if [ -n "$JAVA_HOME" ] ; then JAVACMD="$JAVA_HOME/bin/java"; else JAVACMD="java"; fi
# Increase the maximum file descriptors if we can.
MAX_FD="maximum"
if [ "$cygwin" = "false" -a "$darwin" = "false" -a "$nonstop" = "false" ] ; then
    MAX_FD_LIMIT=`ulimit -H -n` 2>/dev/null || warn "Could not query maximum file descriptor limit"
    if [ $? -eq 0 ] ; then
        if [ "$MAX_FD" = "maximum" -o "$MAX_FD" = "max" ] ; then MAX_FD="$MAX_FD_LIMIT"
        fi
        ulimit -n $MAX_FD
        if [ $? -ne 0 ] ; then warn "Could not set maximum file descriptor limit: $MAX_FD"; fi
    fi
fi
# For Cygwin or MSYS, switch paths to Windows format before running java
if [ "$cygwin" = "true" -o "$msys" = "true" ] ; then
    APP_HOME=`cygpath --path --mixed "$APP_HOME"`
    CLASSPATH=`cygpath --path --mixed "$CLASSPATH"`
fi
exec "$JAVACMD" "${DEFAULT_JVM_OPTS}" $JAVA_OPTS $GRADLE_OPTS "-Dorg.gradle.appname=$APP_BASE_NAME" -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
