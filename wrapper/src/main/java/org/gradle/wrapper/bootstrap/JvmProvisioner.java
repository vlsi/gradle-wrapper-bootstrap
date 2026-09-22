/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.wrapper.bootstrap;

import org.gradle.wrapper.IDownload;
import org.gradle.wrapper.Logger;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Properties;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Replaces a JVM that is too old for the Gradle distribution with one downloaded for the project.
 *
 * <p>The start script picks whatever JVM it finds first and never asks its version, because asking costs a JVM
 * start. The wrapper already runs on that JVM, so the version is free here. When it is below the
 * {@value #MINIMUM_JAVA_VERSION_PROPERTY} that {@code gradle-wrapper.properties} names, the wrapper downloads the
 * JDK that {@code gradle/gradle-daemon-jvm.properties} names for this OS and CPU, verifies its
 * {@code toolchainSha256Sum.<OS>.<ARCH>}, unpacks it, and reports the {@code java} executable to relaunch on.
 *
 * <p>The unpacked JDK lives at {@code <gradle user home>/jdks/wrapper/<sha256>/}, the same directory the start
 * script uses when there is no JVM at all, so both paths share one download.
 */
public final class JvmProvisioner {
    public static final String MINIMUM_JAVA_VERSION_PROPERTY = "minimumJavaVersion";
    public static final String MAXIMUM_JAVA_VERSION_PROPERTY = "maximumJavaVersion";

    private final Logger logger;
    private final IDownload download;
    private final File gradleUserHome;

    public JvmProvisioner(Logger logger, IDownload download, File gradleUserHome) {
        this.logger = logger;
        this.download = download;
        this.gradleUserHome = gradleUserHome;
    }

    /**
     * Whether the running JVM is within the range the wrapper properties name.
     * A missing bound is not checked, so without either property any JVM is accepted, as before.
     * The maximum exists because a Gradle release is tested against the JDKs that exist at the time, and a later JDK
     * has broken the daemon more than once.
     */
    public static boolean currentJvmIsSuitable(Properties wrapperProperties, String specificationVersion) {
        int version = featureVersion(specificationVersion);
        Integer minimum = bound(wrapperProperties, MINIMUM_JAVA_VERSION_PROPERTY);
        Integer maximum = bound(wrapperProperties, MAXIMUM_JAVA_VERSION_PROPERTY);
        return (minimum == null || version >= minimum) && (maximum == null || version <= maximum);
    }

    /** The bound the wrapper properties name, in the words for the message: {@code "below the minimum Java 17"}. */
    public static String violatedBound(Properties wrapperProperties, String specificationVersion) {
        int version = featureVersion(specificationVersion);
        Integer minimum = bound(wrapperProperties, MINIMUM_JAVA_VERSION_PROPERTY);
        if (minimum != null && version < minimum) {
            return "below the minimum Java " + minimum;
        }
        return "above the maximum Java " + bound(wrapperProperties, MAXIMUM_JAVA_VERSION_PROPERTY);
    }

    private static Integer bound(Properties wrapperProperties, String property) {
        String value = wrapperProperties.getProperty(property);
        return value == null || value.trim().isEmpty() ? null : Integer.valueOf(value.trim());
    }

    /** {@code 8} for {@code "1.8"}, {@code 17} for {@code "17"}. */
    static int featureVersion(String specificationVersion) {
        String version = specificationVersion.startsWith("1.") ? specificationVersion.substring(2) : specificationVersion;
        int dot = version.indexOf('.');
        return Integer.parseInt(dot < 0 ? version : version.substring(0, dot));
    }

    /**
     * Downloads and unpacks the JDK for this platform unless it is already present, and returns its {@code java}
     * executable.
     *
     * @param daemonJvmProperties the parsed {@code gradle/gradle-daemon-jvm.properties}
     */
    public File provision(Properties daemonJvmProperties) throws Exception {
        String platform = operatingSystem() + "." + architecture();
        String url = daemonJvmProperties.getProperty("toolchainUrl." + platform);
        String sha256 = daemonJvmProperties.getProperty("toolchainSha256Sum." + platform);
        if (url == null) {
            throw new RuntimeException("gradle-daemon-jvm.properties has no toolchainUrl." + platform + " to download a JVM from.");
        }
        if (sha256 == null) {
            throw new RuntimeException("gradle-daemon-jvm.properties has no toolchainSha256Sum." + platform + ", so a JVM download cannot be verified.");
        }
        File jdkDir = new File(new File(gradleUserHome, "jdks/wrapper"), sha256);
        if (!jdkDir.isDirectory()) {
            File archive = new File(jdkDir.getPath() + ".archive");
            fetch(new URI(url), sha256, archive);
            File unpackDir = new File(jdkDir.getPath() + "." + System.nanoTime() + ".unpack");
            logger.log("Unpacking " + archive);
            unpack(archive, unpackDir);
            // A concurrent run that finishes first wins; this run's copy is discarded.
            if (!unpackDir.renameTo(jdkDir)) {
                deleteRecursively(unpackDir);
                if (!jdkDir.isDirectory()) {
                    throw new IOException("Could not move " + unpackDir + " to " + jdkDir);
                }
            }
            archive.delete();
        }
        File java = findJava(jdkDir);
        if (java == null) {
            throw new RuntimeException("No bin/java found under " + jdkDir);
        }
        return java;
    }

    private void fetch(URI url, String sha256, File target) throws Exception {
        File tmp = new File(target.getPath() + "." + System.nanoTime() + ".part");
        target.getParentFile().mkdirs();
        download.download(url, tmp);
        String actual = sha256(tmp);
        if (!actual.equalsIgnoreCase(sha256)) {
            tmp.delete();
            throw new RuntimeException("Checksum mismatch for " + url + "\n  expected: " + sha256 + "\n  actual:   " + actual);
        }
        if (!tmp.renameTo(target) && !target.isFile()) {
            throw new IOException("Could not move " + tmp + " to " + target);
        }
    }

    static String operatingSystem() {
        String name = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (name.contains("windows")) {
            return "WINDOWS";
        }
        if (name.contains("mac") || name.contains("darwin")) {
            return "MAC_OS";
        }
        if (name.contains("linux")) {
            return "LINUX";
        }
        if (name.contains("freebsd")) {
            return "FREE_BSD";
        }
        return "UNIX";
    }

    static String architecture() {
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        if (arch.equals("amd64") || arch.equals("x86_64")) {
            return "X86_64";
        }
        if (arch.equals("aarch64") || arch.equals("arm64")) {
            return "AARCH64";
        }
        throw new RuntimeException("No JVM is available for CPU '" + arch + "'");
    }

    /** The JDK's {@code java}: at the root of the archive, one directory down, or in a macOS {@code Contents/Home}. */
    static File findJava(File jdkDir) {
        String executable = operatingSystem().equals("WINDOWS") ? "java.exe" : "java";
        File direct = new File(jdkDir, "bin/" + executable);
        if (direct.isFile()) {
            return direct;
        }
        File[] children = jdkDir.listFiles();
        if (children != null) {
            for (File child : children) {
                for (String home : new String[]{"", "Contents/Home/"}) {
                    File java = new File(child, home + "bin/" + executable);
                    if (java.isFile()) {
                        return java;
                    }
                }
            }
        }
        return null;
    }

    static String sha256(File file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        InputStream in = new BufferedInputStream(new FileInputStream(file));
        try {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        } finally {
            in.close();
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private static void unpack(File archive, File into) throws IOException {
        InputStream in = new BufferedInputStream(new FileInputStream(archive));
        try {
            in.mark(2);
            boolean zip = in.read() == 'P' && in.read() == 'K';
            in.reset();
            if (zip) {
                unzip(in, into);
            } else {
                TarUnpacker.unpack(new GZIPInputStream(in), into);
            }
        } finally {
            in.close();
        }
    }

    private static void unzip(InputStream in, File into) throws IOException {
        ZipInputStream zip = new ZipInputStream(in);
        ZipEntry entry;
        while ((entry = zip.getNextEntry()) != null) {
            File target = TarUnpacker.resolve(into, entry.getName());
            if (entry.isDirectory()) {
                target.mkdirs();
                continue;
            }
            target.getParentFile().mkdirs();
            OutputStream out = new FileOutputStream(target);
            try {
                copy(zip, out);
            } finally {
                out.close();
            }
            // Zip entries carry no POSIX mode here; the executables of a JDK all live under bin/.
            if (target.getParentFile().getName().equals("bin")) {
                target.setExecutable(true, false);
            }
        }
    }

    static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
    }

    static void deleteRecursively(File file) {
        if (file.isDirectory() && !Files.isSymbolicLink(file.toPath())) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }
}
