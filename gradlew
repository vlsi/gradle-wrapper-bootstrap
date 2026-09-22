#!/bin/sh

#
# Copyright © 2015 the original authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# SPDX-License-Identifier: Apache-2.0
#

##############################################################################
#
#   gradlew bootstrap script for POSIX (prototype).
#
#   The script does only what has to happen before a JVM exists:
#
#     1. locate the project directory ($0 may be a chain of symlinks);
#     2. find a JVM, or download the one gradle/gradle-daemon-jvm.properties
#        names for this OS and CPU and verify its checksum;
#     3. find the wrapper jar under GRADLE_USER_HOME by the sha256 that
#        gradle/wrapper/gradle-wrapper.properties pins, or download and
#        verify it;
#     4. exec java -jar with that jar.
#
#   JAVA_OPTS and GRADLE_OPTS, Cygwin path conversion, and the Gradle
#   distribution itself are handled by the wrapper jar, not here.
#
#   The script needs a POSIX shell, `uname`, `sed`, and, for the downloads,
#   `curl` or `wget` plus `sha256sum`, `shasum`, or `openssl`.
#
##############################################################################

die () {
    printf '\n%s\n\n' "$*" >&2
    exit 1
}

# Resolve links: $0 may be a link, possibly daisy-chained.
app_path=$0
while
    APP_HOME=${app_path%"${app_path##*/}"}  # leaves a trailing /; empty if no leading path
    [ -h "$app_path" ]
do
    ls=$( ls -ld "$app_path" )
    link=${ls#*' -> '}
    case $link in             #(
      /*)   app_path=$link ;; #(
      *)    app_path=$APP_HOME$link ;;
    esac
done
# Discard cd standard output in case $CDPATH is set (https://github.com/gradle/gradle/issues/25036)
APP_HOME=$( cd -P "${APP_HOME:-./}" > /dev/null && printf '%s\n' "$PWD" ) || exit

GRADLE_USER_HOME=${GRADLE_USER_HOME:-$HOME/.gradle}

# property NAME FILE: the value of NAME in a .properties file, with backslash escapes removed.
property () {
    sed -n "s/^$1=//p" "$2" | sed 's/\\\(.\)/\1/g' | head -n 1
}

# download URL FILE
download () {
    if command -v curl > /dev/null 2>&1 ; then
        curl -fsSL --retry 3 -o "$2" "$1"
    elif command -v wget > /dev/null 2>&1 ; then
        wget -q -O "$2" "$1"
    else
        die "ERROR: Neither curl nor wget is available to download $1"
    fi
}

# sha256 FILE: prints the hex digest.
sha256 () {
    if command -v sha256sum > /dev/null 2>&1 ; then
        sha256sum "$1" | cut -d ' ' -f 1
    elif command -v shasum > /dev/null 2>&1 ; then
        shasum -a 256 "$1" | cut -d ' ' -f 1
    elif command -v openssl > /dev/null 2>&1 ; then
        openssl dgst -sha256 "$1" | sed 's/.*= //'
    else
        die "ERROR: None of sha256sum, shasum, or openssl is available to verify a download"
    fi
}

# fetch URL SHA256 FILE: downloads URL to FILE. The download must hash to SHA256 or FILE is not written.
fetch () {
    tmp=$3.$$.part
    mkdir -p "${3%/*}" || die "ERROR: Cannot create ${3%/*}"
    echo "Downloading $1" >&2
    download "$1" "$tmp" || { rm -f "$tmp"; rmdir "${3%/*}" 2> /dev/null; die "ERROR: Could not download $1"; }
    actual=$( sha256 "$tmp" )
    [ "$actual" = "$2" ] || { rm -f "$tmp"; rmdir "${3%/*}" 2> /dev/null; die "ERROR: Checksum mismatch for $1
  expected: $2
  actual:   $actual"; }
    # An atomic rename: a concurrent run that finishes first leaves identical content.
    mv -f "$tmp" "$3"
}

# Download the JVM that gradle/gradle-daemon-jvm.properties names for this OS and CPU, unless it is already there.
provision_java () {
    jvm_properties=$APP_HOME/gradle/gradle-daemon-jvm.properties
    [ -f "$jvm_properties" ] || die "ERROR: JAVA_HOME is not set, no 'java' command could be found in your PATH,
and $jvm_properties does not exist to download a JVM from."
    case "$( uname -s )" in                    #(
      Linux* )                    os=LINUX ;;   #(
      Darwin* )                   os=MAC_OS ;;  #(
      FreeBSD* )                  os=FREE_BSD ;; #(
      CYGWIN* | MSYS* | MINGW* )  os=WINDOWS ;; #(
      * )                         os=UNIX ;;
    esac
    case "$( uname -m )" in         #(
      x86_64 | amd64 )    arch=X86_64 ;;  #(
      aarch64 | arm64 )   arch=AARCH64 ;; #(
      * )                 die "ERROR: No JVM is available for CPU '$( uname -m )'" ;;
    esac
    url=$( property "toolchainUrl.$os.$arch" "$jvm_properties" )
    sum=$( property "toolchainSha256Sum.$os.$arch" "$jvm_properties" )
    [ -n "$url" ] || die "ERROR: $jvm_properties has no toolchainUrl.$os.$arch to download a JVM from"
    [ -n "$sum" ] || die "ERROR: $jvm_properties has no toolchainSha256Sum.$os.$arch, so a download cannot be verified"
    jdk_dir=$GRADLE_USER_HOME/jdks/wrapper/$sum
    if [ ! -d "$jdk_dir" ] ; then
        archive=$jdk_dir.archive
        fetch "$url" "$sum" "$archive"
        unpack_dir=$jdk_dir.$$.unpack
        mkdir -p "$unpack_dir" || die "ERROR: Cannot create $unpack_dir"
        echo "Unpacking $archive" >&2
        if [ "$( head -c 2 "$archive" )" = PK ] ; then
            unzip -q "$archive" -d "$unpack_dir"
        else
            tar -xzf "$archive" -C "$unpack_dir"
        fi || { rm -rf "$unpack_dir"; die "ERROR: Could not unpack $archive"; }
        # A concurrent run that finishes first wins; this run's copy is discarded.
        mv "$unpack_dir" "$jdk_dir" 2> /dev/null || rm -rf "$unpack_dir"
        rm -f "$archive"
    fi
    for JAVACMD in "$jdk_dir"/*/bin/java "$jdk_dir"/*/Contents/Home/bin/java "$jdk_dir"/bin/java ; do
        [ -x "$JAVACMD" ] && return
    done
    die "ERROR: No bin/java found under $jdk_dir"
}

# Determine the Java command to use to start the JVM.
if [ -n "$JAVA_HOME" ] ; then
    if [ -x "$JAVA_HOME/jre/sh/java" ] ; then
        # IBM's JDK on AIX uses strange locations for the executables
        JAVACMD=$JAVA_HOME/jre/sh/java
    else
        JAVACMD=$JAVA_HOME/bin/java
    fi
    if [ ! -x "$JAVACMD" ] ; then
        die "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME

Please set the JAVA_HOME variable in your environment to match the
location of your Java installation."
    fi
elif command -v java > /dev/null 2>&1 ; then
    JAVACMD=java
else
    provision_java
fi

# Find the wrapper jar by the checksum the project pins, or download it.
wrapper_properties=$APP_HOME/gradle/wrapper/gradle-wrapper.properties
jar_sum=$( property wrapperSha256Sum "$wrapper_properties" )
[ -n "$jar_sum" ] || die "ERROR: $wrapper_properties has no wrapperSha256Sum"
WRAPPER_JAR=$GRADLE_USER_HOME/wrapper/jars/$jar_sum/gradle-wrapper.jar
if [ ! -f "$WRAPPER_JAR" ] ; then
    jar_url=$( property wrapperUrl "$wrapper_properties" )
    [ -n "$jar_url" ] || die "ERROR: $wrapper_properties has no wrapperUrl"
    fetch "$jar_url" "$jar_sum" "$WRAPPER_JAR"
fi

# Increase the maximum file descriptors if we can. The daemon inherits the limit.
case "$( uname )" in                    #(
  CYGWIN* | Darwin* | NONSTOP* ) ;;     #(
  * )
    # In POSIX sh, ulimit -H is undefined. That's why the result is checked to see if it worked.
    # shellcheck disable=SC2039,SC3045
    MAX_FD=$( ulimit -H -n 2> /dev/null ) && [ "$MAX_FD" != unlimited ] && ulimit -n "$MAX_FD" 2> /dev/null
    ;;
esac

# For Cygwin or MSYS, java is a Windows program: it needs a Windows path to the jar.
# The wrapper converts the project directory and the arguments itself with cygpath.
cygpath=false
case "$( uname )" in                #(
  CYGWIN* | MSYS* | MINGW* )
    cygpath=true
    JAVACMD=$( cygpath --unix "$JAVACMD" )
    WRAPPER_JAR=$( cygpath --mixed "$WRAPPER_JAR" )
    ;;
esac

# JAVA_OPTS and GRADLE_OPTS are read by the wrapper from the environment.
exec "$JAVACMD" \
    -Dfile.encoding=UTF-8 -Xmx64m -Xms64m \
    "-Dorg.gradle.appname=${0##*/}" \
    "-Dorg.gradle.wrapper.projectDir=$APP_HOME" \
    "-Dorg.gradle.wrapper.cygpath=$cygpath" \
    -jar "$WRAPPER_JAR" \
    "$@"
