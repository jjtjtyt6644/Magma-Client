#!/usr/bin/env bash
# Builds Magma for NeoForge and Fabric, then keeps latest-jars/ holding only the newest jar of each.
set -e
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-$HOME/.jdks/jdk-21.0.13+9}"
v=$(grep '^mod_version=' gradle.properties | cut -d= -f2)
./gradlew build --no-daemon -q
(cd fabric && ./gradlew build --no-daemon -q)
mkdir -p latest-jars
rm -f latest-jars/magmaclient-*.jar
cp "build/libs/magmaclient-1.21.1-$v.jar" latest-jars/
cp "fabric/build/libs/magmaclient-fabric-1.21.1-$v.jar" latest-jars/
ls latest-jars
