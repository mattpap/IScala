#!/bin/bash

function usage {
    echo "Usage: `basename $0` [OPTION]... -- [JAVA OPTION]..."
    echo ""
    echo "  -d        Enable remote debugging (e.g. for IntelliJ)"
    echo "  -R        Don't load JRebel when JVM starts"
    echo ""
    echo "After -- you can specify options directly to JVM. For example:"
    echo ""
    echo "  ./sbt -d -- -Drun.mode=test -Xmx5G"
    echo ""
    echo "This will run sbt in debug mode, set run.mode system property"
    echo "to 'test' and set JVM max heap size to 5 GiB. These arguments"
    echo "are stored at the and of JVM's command line, allowing you to"
    echo "override any hard-coded defaults."
}

DEBUG=0
JREBEL=1

while getopts hdR OPT;
do
    case "$OPT" in
        h)
            usage
            exit 0
            ;;
        d)
            DEBUG=1
            ;;
        R)
            JREBEL=0
            ;;
        \?)
            usage
            exit 1;
            ;;
    esac
done

shift `expr $OPTIND - 1`

SEP=" -- "
OPTS=$@

SBT_OPTS="${OPTS%$SEP*}"

if [ "$SBT_OPTS" != "$OPTS" ]; then
    JVM_OPTS="${OPTS#*$SEP}"
else
    JVM_OPTS=""
fi

JAVA_OPTS="-Dfile.encoding=UTF-8 -Xss8M -Xmx2G -XX:MaxPermSize=1024M -XX:ReservedCodeCacheSize=64M -XX:+UseConcMarkSweepGC -XX:+CMSClassUnloadingEnabled"

if [ $DEBUG = 1 ];
then
    JAVA_OPTS="$JAVA_OPTS -Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=127.0.0.1:5005"
fi

if [ $JREBEL = 1 -a -e "$HOME/.jrebel/jrebel/jrebel.jar" ];
then
    JREBEL_DIR="project/target/jrebel"

    if [ ! -e $JREBEL_DIR ]; then
        mkdir -p $JREBEL_DIR
    fi

    JREBEL_OPTS="-noverify -javaagent:$HOME/.jrebel/jrebel/jrebel.jar"

    JREBEL_OPTS="$JREBEL_OPTS -Drebel.usage_reporting=false"
    JREBEL_OPTS="$JREBEL_OPTS -Drebel.stats=true"
    JREBEL_OPTS="$JREBEL_OPTS -Drebel.log=true"
    JREBEL_OPTS="$JREBEL_OPTS -Drebel.log.perf=false"
    JREBEL_OPTS="$JREBEL_OPTS -Drebel.log.trace=false"
    JREBEL_OPTS="$JREBEL_OPTS -Drebel.log.stdout=false"
    JREBEL_OPTS="$JREBEL_OPTS -Drebel.log.file=$JREBEL_DIR/jrebel.log"
    JREBEL_OPTS="$JREBEL_OPTS -Drebel.temp.dir=$JREBEL_DIR"

    JAVA_OPTS="$JAVA_OPTS $JREBEL_OPTS"
fi

JAVA_OPTS="$JAVA_OPTS $JVM_OPTS"

SBT_VERSION="0.13.0-RC5"
SBT_LAUNCHER="$(dirname $0)/project/sbt-launch-$SBT_VERSION.jar"

if [ ! -e "$SBT_LAUNCHER" ];
then
    URL="http://repo.typesafe.com/typesafe/ivy-releases/org.scala-sbt/sbt-launch/$SBT_VERSION/sbt-launch.jar"
    wget -O $SBT_LAUNCHER $URL
fi

java $JAVA_OPTS -jar $SBT_LAUNCHER $SBT_OPTS
echo
