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

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JVM options a user placed in {@code JAVA_OPTS} and {@code GRADLE_OPTS}.
 *
 * <p>The start scripts used to split these variables with {@code xargs}, {@code sed} and {@code eval}
 * and pass the result to {@code java}. Now the scripts pass nothing and this class does the split.
 * A {@code -Dname=value} option becomes a system property of the running JVM. Any other option,
 * such as {@code -Xmx} or {@code -javaagent}, cannot be applied after the JVM has started, so the
 * wrapper relaunches itself in a child JVM that carries the option; see {@link #relaunch}.
 */
public final class EnvironmentJvmOptions {
    /** System property set on the child JVM so it does not relaunch again. */
    public static final String RELAUNCHED_PROPERTY = "org.gradle.wrapper.relaunched";

    /**
     * System properties the JVM reads once at startup. Setting them later has no effect,
     * so an option that names one of them with a value different from the current one forces a relaunch.
     */
    private static final Set<String> STARTUP_ONLY_PROPERTIES = new HashSet<String>(Arrays.asList(
        "file.encoding", "stdout.encoding", "stderr.encoding", "native.encoding",
        "sun.jnu.encoding", "sun.stdout.encoding", "sun.stderr.encoding",
        "user.language", "user.country", "user.variant", "user.script"
    ));

    private final List<String> options;

    private EnvironmentJvmOptions(List<String> options) {
        this.options = options;
    }

    /**
     * Reads {@code JAVA_OPTS} then {@code GRADLE_OPTS}, in the order the start scripts passed them to {@code java},
     * so that a later option wins over an earlier one.
     */
    public static EnvironmentJvmOptions fromEnvironment(Map<String, String> environment) {
        List<String> options = new ArrayList<String>();
        for (String variable : new String[]{"JAVA_OPTS", "GRADLE_OPTS"}) {
            String value = environment.get(variable);
            if (value != null) {
                options.addAll(split(value));
            }
        }
        return new EnvironmentJvmOptions(options);
    }

    public static EnvironmentJvmOptions of(String... options) {
        return new EnvironmentJvmOptions(Arrays.asList(options));
    }

    public List<String> getOptions() {
        return Collections.unmodifiableList(options);
    }

    /**
     * Splits a variable the way {@code xargs} does: on unquoted whitespace, honoring single quotes,
     * double quotes, and a backslash before any character outside quotes.
     */
    static List<String> split(String value) {
        List<String> result = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        boolean hasToken = false;
        char quote = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    current.append(c);
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
                hasToken = true;
            } else if (c == '\\' && i + 1 < value.length()) {
                current.append(value.charAt(++i));
                hasToken = true;
            } else if (Character.isWhitespace(c)) {
                if (hasToken) {
                    result.add(current.toString());
                    current.setLength(0);
                    hasToken = false;
                }
            } else {
                current.append(c);
                hasToken = true;
            }
        }
        if (hasToken) {
            result.add(current.toString());
        }
        return result;
    }

    /**
     * Whether some option cannot take effect in the running JVM.
     *
     * @param current the running JVM's system properties, used to skip a startup-only property that already has the requested value
     */
    public boolean requiresRelaunch(Map<String, String> current) {
        for (String option : options) {
            String[] property = systemProperty(option);
            if (property == null) {
                return true;
            }
            if (STARTUP_ONLY_PROPERTIES.contains(property[0]) && !property[1].equals(current.get(property[0]))) {
                return true;
            }
        }
        return false;
    }

    /** Applies every {@code -D} option to the running JVM. Call only when {@link #requiresRelaunch} is false. */
    public void applyTo(java.util.Properties systemProperties) {
        for (String option : options) {
            String[] property = systemProperty(option);
            if (property == null) {
                throw new IllegalStateException("JVM option '" + option + "' cannot be applied to a running JVM");
            }
            systemProperties.setProperty(property[0], property[1]);
        }
    }

    /** Returns {@code {name, value}} for a {@code -Dname=value} option, or null for any other option. */
    static String[] systemProperty(String option) {
        if (!option.startsWith("-D") || option.length() == 2) {
            return null;
        }
        String body = option.substring(2);
        int eq = body.indexOf('=');
        if (eq < 0) {
            return new String[]{body, ""};
        }
        return new String[]{body.substring(0, eq), body.substring(eq + 1)};
    }

    /**
     * Starts {@code javaExecutable} with the options of the running JVM, these options, and the same main jar and
     * arguments, then returns its exit code. The child inherits stdin, stdout, and stderr.
     */
    public int relaunch(File javaExecutable, File mainJar, String[] args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<String>();
        command.add(javaExecutable.getPath());
        // The options the script gave this JVM: the defaults, and the -D properties that describe the script.
        command.addAll(ManagementFactory.getRuntimeMXBean().getInputArguments());
        command.addAll(options);
        command.add("-D" + RELAUNCHED_PROPERTY + "=true");
        command.add("-jar");
        command.add(mainJar.getPath());
        command.addAll(Arrays.asList(args));
        Process process = new ProcessBuilder(command).inheritIO().start();
        return process.waitFor();
    }
}
