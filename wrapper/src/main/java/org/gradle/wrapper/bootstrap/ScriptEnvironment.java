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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the start script tells the wrapper about where and how it was started.
 *
 * <p>The script passes the project directory as {@value #PROJECT_DIR_PROPERTY}, and
 * {@value #CYGPATH_PROPERTY}{@code =true} when it runs under Cygwin or MSYS. In that case the script
 * gives the wrapper POSIX paths and the wrapper converts them to Windows paths with {@code cygpath},
 * which the script used to do itself with a {@code for} loop over {@code "$@"}.
 */
public final class ScriptEnvironment {
    public static final String PROJECT_DIR_PROPERTY = "org.gradle.wrapper.projectDir";
    public static final String CYGPATH_PROPERTY = "org.gradle.wrapper.cygpath";

    private final String projectDir;
    private final boolean cygpath;

    ScriptEnvironment(String projectDir, boolean cygpath) {
        this.projectDir = projectDir;
        this.cygpath = cygpath;
    }

    public static ScriptEnvironment fromSystemProperties(Map<String, String> properties) {
        return new ScriptEnvironment(properties.get(PROJECT_DIR_PROPERTY), Boolean.parseBoolean(properties.get(CYGPATH_PROPERTY)));
    }

    /** The project directory the script computed, converted to a Windows path under Cygwin, or null when the script passed none. */
    public File projectDir() throws IOException {
        if (projectDir == null) {
            return null;
        }
        if (!cygpath) {
            return new File(projectDir);
        }
        return new File(cygpath(Arrays.asList(projectDir), "--path", "--mixed").get(projectDir));
    }

    /**
     * Converts the arguments that look like absolute POSIX paths to Windows paths, as the script did.
     * An argument counts as a path when it does not start with {@code -}, starts with {@code /},
     * and its first path component exists in the Cygwin file system.
     */
    public String[] convertArguments(String[] args) throws IOException {
        if (!cygpath) {
            return args;
        }
        List<String> roots = new ArrayList<String>();
        for (String arg : args) {
            String root = firstComponent(arg);
            if (root != null && !roots.contains(root)) {
                roots.add(root);
            }
        }
        if (roots.isEmpty()) {
            return args;
        }
        Map<String, String> windowsRoots = cygpath(roots, "--windows");
        List<String> paths = new ArrayList<String>();
        for (String arg : args) {
            String root = firstComponent(arg);
            if (root != null && new File(windowsRoots.get(root)).exists() && !paths.contains(arg)) {
                paths.add(arg);
            }
        }
        if (paths.isEmpty()) {
            return args;
        }
        Map<String, String> converted = cygpath(paths, "--path", "--ignore", "--mixed");
        String[] result = args.clone();
        for (int i = 0; i < result.length; i++) {
            String windows = converted.get(result[i]);
            if (windows != null) {
                result[i] = windows;
            }
        }
        return result;
    }

    /** Returns {@code /first} for an argument of the form {@code /first/...}, or null for anything else. */
    static String firstComponent(String arg) {
        if (arg.startsWith("-") || arg.length() < 2 || arg.charAt(0) != '/') {
            return null;
        }
        int slash = arg.indexOf('/', 1);
        return slash < 0 ? arg : arg.substring(0, slash);
    }

    /** Runs {@code cygpath} once for all inputs and maps each input to the line it printed for it. */
    private static Map<String, String> cygpath(List<String> inputs, String... flags) throws IOException {
        List<String> command = new ArrayList<String>();
        command.add("cygpath");
        command.addAll(Arrays.asList(flags));
        command.addAll(inputs);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        List<String> lines = new ArrayList<String>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        } finally {
            reader.close();
        }
        int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for cygpath", e);
        }
        if (exitCode != 0 || lines.size() != inputs.size()) {
            throw new IOException("cygpath " + command.subList(1, command.size()) + " failed with exit code " + exitCode + ": " + lines);
        }
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (int i = 0; i < inputs.size(); i++) {
            result.put(inputs.get(i), lines.get(i));
        }
        return result;
    }
}
