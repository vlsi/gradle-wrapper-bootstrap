#!/bin/sh
# Builds gradle-wrapper.jar from wrapper/src with javac and jar, and prints its sha256.
# Needs a JDK 17 or later on PATH. The jar itself targets Java 8, like Gradle's own wrapper jar.
set -e
cd "$( dirname "$0" )"
rm -rf build
mkdir -p build/classes build/libs
javac --release 8 -Xlint:-options -d build/classes $( find src/main/java -name '*.java' )
mkdir -p build/classes/META-INF
cp LICENSE build/classes/META-INF/LICENSE
printf 'Main-Class: org.gradle.wrapper.GradleWrapperMain\nImplementation-Title: Gradle Wrapper\nSPDX-License-Identifier: Apache-2.0\nEnable-Native-Access: ALL-UNNAMED\n' > build/manifest.txt
jar --create --file build/libs/gradle-wrapper.jar --manifest build/manifest.txt -C build/classes .
if command -v sha256sum > /dev/null 2>&1; then sha256sum build/libs/gradle-wrapper.jar; else shasum -a 256 build/libs/gradle-wrapper.jar; fi
