# Gradle wrapper bootstrap prototype

A `gradlew` that commits no jar and needs no preinstalled JVM. It is a prototype for two Gradle feature requests:
[gradle/gradle#2508](https://github.com/gradle/gradle/issues/2508) (run Gradle without a JDK on the machine) and
[gradle/gradle#11816](https://github.com/gradle/gradle/issues/11816) (bootstrap Gradle without a binary in git).

## Try it

```bash
git clone https://github.com/vlsi/gradle-wrapper-bootstrap.git
cd gradle-wrapper-bootstrap
./gradlew whichJvm -q
```

The `whichJvm` task prints the JVM the daemon runs on and the project directory the script passed to the wrapper.
Set `GRADLE_USER_HOME` to an empty directory to watch the cold path. To see the JVM download, run with a `PATH` that
has no `java` and no `JAVA_HOME`.

Windows users run `gradlew.bat`. It has not been tested yet: I wrote it on a Mac. Reports are welcome.

## What the script does

The script does only what has to happen before a JVM exists:

1. Locate the project directory. `$0` may be a chain of symlinks.
2. Find a JVM: `JAVA_HOME`, then `java` on `PATH`. If neither exists, download the JDK that
   `gradle/gradle-daemon-jvm.properties` names for this OS and CPU, verify its `toolchainSha256Sum.<OS>.<ARCH>`, and
   unpack it into `$GRADLE_USER_HOME/jdks/wrapper/<sha256>/`. The script does not check the version of the JVM it
   found; the jar does, see below.
3. Find the wrapper jar at `$GRADLE_USER_HOME/wrapper/jars/<sha256>/gradle-wrapper.jar`, where the sha256 is
   `wrapperSha256Sum` from `gradle/wrapper/gradle-wrapper.properties`. If it is missing, download `wrapperUrl`,
   verify the hash, and put it there.
4. `exec java -jar` with that jar.

Steps 2 and 3 are one `test -x` and one `test -f` on the hot path. Downloads need `curl` or `wget` and one of
`sha256sum`, `shasum`, or `openssl`; the JDK archive needs `tar` or `unzip`. On Windows the script uses `curl.exe`,
`tar.exe`, and `certutil`, which ship with Windows 10 1803 and later.

The root of trust is the hash committed in `gradle-wrapper.properties`: the script verifies the jar, the jar verifies
the distribution with `distributionSha256Sum`, as it does today.

## A JVM that is installed but too old

The script takes the first JVM it finds without asking its version, because asking costs a JVM start on every run.
The jar runs on that JVM, so the version is free there. `gradle-wrapper.properties` names `minimumJavaVersion=17`,
the minimum the Gradle client needs. When the running JVM is older, the jar downloads the JDK from
`gradle/gradle-daemon-jvm.properties` for this platform, verifies the hash, unpacks it into the same
`$GRADLE_USER_HOME/jdks/wrapper/<sha256>/` directory the script uses, and relaunches itself on it. Downloads go
through the wrapper's own `Download` class, so proxies and `systemProp.*` settings apply as they do for the
distribution. The tar.gz and zip archives are unpacked by the jar itself (`TarUnpacker`, `JvmProvisioner`).

A JVM that is new enough is used as it is. With JDK 17 installed and `toolchainVersion=25` in the daemon JVM
properties, the client runs on 17 and Gradle picks or downloads the daemon JVM with its own toolchain machinery. When
the wrapper did download a JDK, Gradle detects it as the client's JVM and runs the daemon on it, so there is no
second download.

Tested on macOS with `JAVA_HOME` pointing at Java 8: the jar reports the JVM as too old, downloads JDK 25, and the
build runs on it. `GRADLE_OPTS` with `-Xmx` are carried over into the relaunch.

## What moved from the scripts into the jar

The stock `gradlew` is generated from the `application` plugin's start script template, so it carries code for
launching any Java program. This prototype moves that code into the wrapper jar, where it is written once in Java
instead of twice in `sh` and `cmd`:

| Stock script | Here |
| --- | --- |
| Splits `JAVA_OPTS` and `GRADLE_OPTS` with `xargs`, `sed`, and `eval` | The jar reads both variables. A `-Dname=value` option becomes a system property in place. Any other option, such as `-Xmx` or `-javaagent`, makes the jar relaunch itself in a child JVM that carries the option. See `EnvironmentJvmOptions`. |
| Converts POSIX paths in the arguments with a `for` loop over `"$@"` and `cygpath` under Cygwin and MSYS | The script passes `-Dorg.gradle.wrapper.cygpath=true` and the jar runs `cygpath` on the project directory and the arguments. See `ScriptEnvironment`. |
| Finds `gradle-wrapper.properties` relative to the jar, three directories up | The script passes `-Dorg.gradle.wrapper.projectDir`. Without it the jar falls back to the old lookup, so it still works with a stock script. |
| Pads `gradlew.bat` with 20 lines of colons so that a script being overwritten in place lands on a safe `goto` | Dropped. The script changes rarely once the logic lives in the jar; whether to keep the padding is a decision for adoption. |

What stayed in the script: the symlink loop (the script needs the properties file before any JVM runs), the AIX
`jre/sh/java` location, `ulimit -n` (the daemon inherits the limit), and the `DEFAULT_JVM_OPTS` literals.

## What Gradle would need to change

- Publish `gradle-<version>-wrapper.jar` as a standalone artifact. Today `services.gradle.org` publishes only its
  `.sha256`, and the jar itself sits inside the distribution zip.
- Add `wrapperUrl`, `wrapperSha256Sum`, and `minimumJavaVersion` to `gradle-wrapper.properties`, written by the
  `wrapper` task.
- Add `toolchainSha256Sum.<OS>.<ARCH>` to `gradle-daemon-jvm.properties`, written by `updateDaemonJvm`. Without a
  hash the script refuses to download a JDK.
- Generate `gradlew` from a wrapper-specific template rather than the `application` plugin's.

## Layout

- `gradlew`, `gradlew.bat`: the bootstrap scripts.
- `gradle/wrapper/gradle-wrapper.properties`: pins the distribution and the wrapper jar by URL and sha256.
- `gradle/gradle-daemon-jvm.properties`: JDK download URLs per platform, as Gradle 8.13+ writes them, plus the
  hashes this prototype adds.
- `wrapper/src/main/java`: the wrapper sources, copied from Gradle master (`wrapper-main`, `wrapper-shared`, `cli`,
  and two helper classes) with the JSpecify annotations stripped, plus the `org.gradle.wrapper.bootstrap` package
  that is new here.
- `wrapper/build.sh`: builds the jar with `javac` and `jar` and prints its sha256. Building needs a JDK; running
  `./gradlew` does not.
- `build.gradle.kts`: the `whichJvm` task.

## Releasing a new jar

```bash
./wrapper/build.sh
```

Upload `wrapper/build/libs/gradle-wrapper.jar` as a release asset, then put the release URL in `wrapperUrl` and the
printed hash in `wrapperSha256Sum`.
