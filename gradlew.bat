@rem
@rem Copyright 2015 the original author or authors.
@rem
@rem Licensed under the Apache License, Version 2.0 (the "License");
@rem you may not use this file except in compliance with the License.
@rem You may obtain a copy of the License at
@rem
@rem      https://www.apache.org/licenses/LICENSE-2.0
@rem
@rem Unless required by applicable law or agreed to in writing, software
@rem distributed under the License is distributed on an "AS IS" BASIS,
@rem WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
@rem See the License for the specific language governing permissions and
@rem limitations under the License.
@rem
@rem SPDX-License-Identifier: Apache-2.0
@rem

@if "%DEBUG%"=="" @echo off
@rem ##########################################################################
@rem
@rem  gradlew bootstrap script for Windows (prototype).
@rem
@rem  Finds a JVM (or downloads the one gradle\gradle-daemon-jvm.properties
@rem  names), finds the wrapper jar by its pinned sha256 (or downloads it),
@rem  and runs java -jar. Downloads use curl.exe, tar.exe, and certutil,
@rem  which ship with Windows 10 1803 and later.
@rem
@rem ##########################################################################

setlocal EnableExtensions

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%

@rem Resolve any "." and ".." in APP_HOME to make it shorter.
for %%i in ("%APP_HOME%") do set APP_HOME=%%~fi

set GUH=%GRADLE_USER_HOME%
if "%GUH%"=="" set GUH=%USERPROFILE%\.gradle

@rem Find java.exe
if defined JAVA_HOME goto findJavaFromJavaHome

set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% equ 0 goto findWrapperJar
call :provisionJava || goto fail
goto findWrapperJar

:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%/bin/java.exe
if exist "%JAVA_EXE%" goto findWrapperJar

1>&2 echo.
1>&2 echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME%
1>&2 echo.
1>&2 echo Please set the JAVA_HOME variable in your environment to match the
1>&2 echo location of your Java installation.
goto fail

:findWrapperJar
set WRAPPER_PROPERTIES=%APP_HOME%\gradle\wrapper\gradle-wrapper.properties
call :property wrapperSha256Sum "%WRAPPER_PROPERTIES%" JAR_SUM
if not defined JAR_SUM (
    1>&2 echo ERROR: %WRAPPER_PROPERTIES% has no wrapperSha256Sum
    goto fail
)
set WRAPPER_JAR=%GUH%\wrapper\jars\%JAR_SUM%\gradle-wrapper.jar
if exist "%WRAPPER_JAR%" goto execute
call :property wrapperUrl "%WRAPPER_PROPERTIES%" JAR_URL
if not defined JAR_URL (
    1>&2 echo ERROR: %WRAPPER_PROPERTIES% has no wrapperUrl
    goto fail
)
call :fetch "%JAR_URL%" %JAR_SUM% "%WRAPPER_JAR%" || goto fail

:execute
@rem JAVA_OPTS and GRADLE_OPTS are read by the wrapper from the environment.
@rem endlocal doesn't take effect until after the line is parsed and variables are expanded
@rem which allows us to clear the local environment before executing the java command
endlocal & "%JAVA_EXE%" -Dfile.encoding=UTF-8 -Xmx64m -Xms64m "-Dorg.gradle.appname=%APP_BASE_NAME%" "-Dorg.gradle.wrapper.projectDir=%APP_HOME%" -jar "%WRAPPER_JAR%" %* & call :exitWithErrorLevel & goto exitWithErrorLevel

:fail
"%COMSPEC%" /c exit 1

:exitWithErrorLevel
@rem Use "%COMSPEC%" /c exit to allow operators to work properly in scripts
"%COMSPEC%" /c exit %ERRORLEVEL%

@rem property NAME FILE OUTVAR: sets OUTVAR to the value of NAME in a .properties file, with "\:" unescaped.
:property
set "%~3="
for /f "usebackq tokens=1,* delims==" %%a in ("%~2") do if "%%a"=="%~1" set "%~3=%%b"
if defined %~3 call set "%~3=%%%~3:\:=:%%"
goto :eof

@rem sha256 FILE OUTVAR: sets OUTVAR to the lower-case hex digest.
:sha256
set "%~2="
for /f "skip=1 tokens=*" %%h in ('certutil -hashfile "%~1" SHA256') do if not defined %~2 set "%~2=%%h"
call set "%~2=%%%~2: =%%"
goto :eof

@rem fetch URL SHA256 FILE: downloads URL to FILE. The download must hash to SHA256 or FILE is not written.
:fetch
set "FETCH_TMP=%~3.%RANDOM%.part"
if not exist "%~dp3" mkdir "%~dp3"
1>&2 echo Downloading %~1
curl.exe -fsSL --retry 3 -o "%FETCH_TMP%" "%~1"
if errorlevel 1 (
    del /q "%FETCH_TMP%" 2>NUL
    1>&2 echo ERROR: Could not download %~1
    exit /b 1
)
call :sha256 "%FETCH_TMP%" FETCH_SUM
if /i not "%FETCH_SUM%"=="%~2" (
    del /q "%FETCH_TMP%" 2>NUL
    1>&2 echo ERROR: Checksum mismatch for %~1
    1>&2 echo   expected: %~2
    1>&2 echo   actual:   %FETCH_SUM%
    exit /b 1
)
move /y "%FETCH_TMP%" "%~3" >NUL
exit /b 0

@rem provisionJava: downloads the JVM gradle\gradle-daemon-jvm.properties names for this CPU and sets JAVA_EXE.
:provisionJava
set JVM_PROPERTIES=%APP_HOME%\gradle\gradle-daemon-jvm.properties
if not exist "%JVM_PROPERTIES%" (
    1>&2 echo ERROR: JAVA_HOME is not set, no 'java' command could be found in your PATH,
    1>&2 echo and %JVM_PROPERTIES% does not exist to download a JVM from.
    exit /b 1
)
set ARCH=X86_64
if /i "%PROCESSOR_ARCHITECTURE%"=="ARM64" set ARCH=AARCH64
call :property toolchainUrl.WINDOWS.%ARCH% "%JVM_PROPERTIES%" JDK_URL
call :property toolchainSha256Sum.WINDOWS.%ARCH% "%JVM_PROPERTIES%" JDK_SUM
if not defined JDK_URL (
    1>&2 echo ERROR: %JVM_PROPERTIES% has no toolchainUrl.WINDOWS.%ARCH% to download a JVM from
    exit /b 1
)
if not defined JDK_SUM (
    1>&2 echo ERROR: %JVM_PROPERTIES% has no toolchainSha256Sum.WINDOWS.%ARCH%, so a download cannot be verified
    exit /b 1
)
set JDK_DIR=%GUH%\jdks\wrapper\%JDK_SUM%
if exist "%JDK_DIR%\" goto findJavaInJdkDir
call :fetch "%JDK_URL%" %JDK_SUM% "%JDK_DIR%.archive" || exit /b 1
set UNPACK_DIR=%JDK_DIR%.%RANDOM%.unpack
mkdir "%UNPACK_DIR%"
1>&2 echo Unpacking %JDK_DIR%.archive
tar.exe -xf "%JDK_DIR%.archive" -C "%UNPACK_DIR%"
if errorlevel 1 (
    rmdir /s /q "%UNPACK_DIR%"
    1>&2 echo ERROR: Could not unpack %JDK_DIR%.archive
    exit /b 1
)
@rem A concurrent run that finishes first wins; this run's copy is discarded.
move "%UNPACK_DIR%" "%JDK_DIR%" >NUL 2>&1 || rmdir /s /q "%UNPACK_DIR%"
del /q "%JDK_DIR%.archive"

:findJavaInJdkDir
for /d %%d in ("%JDK_DIR%\*") do if exist "%%~d\bin\java.exe" set "JAVA_EXE=%%~d\bin\java.exe"
if exist "%JDK_DIR%\bin\java.exe" set "JAVA_EXE=%JDK_DIR%\bin\java.exe"
if not defined JAVA_EXE (
    1>&2 echo ERROR: No bin\java.exe found under %JDK_DIR%
    exit /b 1
)
exit /b 0
