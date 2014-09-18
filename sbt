#!/bin/bash

function usage {
    echo "Usage: `basename $0` [OPTION|COMMAND]... -- [JVM OPTION]..."
}

while getopts h OPT;
do
    case "$OPT" in
        h)
            usage
            exit 0
            ;;
        \?)
            usage
            exit 1
            ;;
    esac
done

shift `expr $OPTIND - 1`

SEP=" -- "
OPTS=$@

SBT_OPTS="${OPTS%$SEP*}"

if [ "$SBT_OPTS" != "$OPTS" ];
then
    JVM_OPTS="${OPTS#*$SEP}"
else
    JVM_OPTS=""
fi

function get_property {
    echo $(cat project/build.properties | grep "^$1" | cut -d'=' -f2-)
}

JVM_DEFAULTS="                     \
    -Dfile.encoding=UTF-8          \
    -Xss8M                         \
    -Xmx2G                         \
    -XX:MaxPermSize=1024M          \
    -XX:ReservedCodeCacheSize=64M  \
    -XX:+UseConcMarkSweepGC        \
    -XX:+CMSClassUnloadingEnabled"

JVM_OPTS="$JVM_DEFAULTS $JVM_OPTS"

SBT_VERSION="$(get_property sbt.version)"
SBT_LAUNCHER="$(dirname $0)/project/sbt-launch-$SBT_VERSION.jar"

if [ ! -e "$SBT_LAUNCHER" ];
then
    URL="http://repo.typesafe.com/typesafe/ivy-releases/org.scala-sbt/sbt-launch/$SBT_VERSION/sbt-launch.jar"
    wget -O $SBT_LAUNCHER $URL
fi

EXPECTED_MD5="$(get_property sbt.launcher.md5)"
COMPUTED_MD5="$(openssl md5 -r < $SBT_LAUNCHER | cut -d' ' -f1)"

if [ "$EXPECTED_MD5" != "$COMPUTED_MD5" ];
then
    echo "$SBT_LAUNCHER has invalid MD5 signature: expected $EXPECTED_MD5, got $COMPUTED_MD5"
    exit 1
fi

java $JVM_OPTS -jar $SBT_LAUNCHER $SBT_OPTS
