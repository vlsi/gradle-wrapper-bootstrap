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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Unpacks the tar archives JDK vendors ship: ustar and GNU tar with long names, regular files, directories, and
 * symbolic links. The JDK has no tar reader of its own, and the wrapper jar takes no dependencies.
 */
final class TarUnpacker {
    private static final int BLOCK = 512;

    private TarUnpacker() {
    }

    static void unpack(InputStream in, File into) throws IOException {
        byte[] header = new byte[BLOCK];
        String longName = null;
        while (readFully(in, header)) {
            if (header[0] == 0) {
                break; // end-of-archive block
            }
            String name = longName != null ? longName : name(header);
            longName = null;
            long size = octal(header, 124, 12);
            char type = (char) header[156];
            switch (type) {
                case 'L': // GNU long name: the data is the name of the next entry
                    longName = new String(readData(in, size), StandardCharsets.UTF_8).trim();
                    break;
                case '5':
                    resolve(into, name).mkdirs();
                    skipEntry(in, size);
                    break;
                case '2': {
                    File link = resolve(into, name);
                    link.getParentFile().mkdirs();
                    Files.createSymbolicLink(link.toPath(), Paths.get(field(header, 157, 100)));
                    skipEntry(in, size);
                    break;
                }
                case '0':
                case '\0':
                case '7': {
                    File target = resolve(into, name);
                    target.getParentFile().mkdirs();
                    OutputStream out = new FileOutputStream(target);
                    try {
                        JvmProvisioner.copy(new LimitedInputStream(in, size), out);
                    } finally {
                        out.close();
                    }
                    skip(in, padding(size));
                    if ((octal(header, 100, 8) & 0111) != 0) {
                        target.setExecutable(true, false);
                    }
                    break;
                }
                default: // hard links, pax headers, and vendor extensions are not needed for a JDK
                    skipEntry(in, size);
            }
        }
    }

    private static String name(byte[] header) {
        String name = field(header, 0, 100);
        if ("ustar".equals(field(header, 257, 5))) {
            String prefix = field(header, 345, 155);
            if (!prefix.isEmpty()) {
                return prefix + "/" + name;
            }
        }
        return name;
    }

    private static String field(byte[] header, int offset, int length) {
        int end = offset;
        while (end < offset + length && header[end] != 0) {
            end++;
        }
        return new String(header, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static long octal(byte[] header, int offset, int length) {
        long value = 0;
        for (int i = offset; i < offset + length; i++) {
            byte b = header[i];
            if (b == 0 || b == ' ') {
                if (value != 0) {
                    break;
                }
                continue;
            }
            value = value * 8 + (b - '0');
        }
        return value;
    }

    /** Rejects an entry that would land outside {@code into}. */
    static File resolve(File into, String name) throws IOException {
        File target = new File(into, name);
        if (!target.getCanonicalPath().startsWith(into.getCanonicalPath() + File.separator) && !target.getCanonicalFile().equals(into.getCanonicalFile())) {
            throw new IOException("Archive entry '" + name + "' would be unpacked outside " + into);
        }
        return target;
    }

    private static byte[] readData(InputStream in, long size) throws IOException {
        byte[] data = new byte[(int) size];
        if (!readFully(in, data)) {
            throw new IOException("Truncated tar archive");
        }
        skip(in, padding(size));
        return data;
    }

    private static long padding(long size) {
        long rest = size % BLOCK;
        return rest == 0 ? 0 : BLOCK - rest;
    }

    /** Skips the data of an entry and the padding that rounds it up to whole blocks. */
    private static void skipEntry(InputStream in, long size) throws IOException {
        skip(in, size + padding(size));
    }

    private static void skip(InputStream in, long count) throws IOException {
        long remaining = count;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() < 0) {
                    throw new IOException("Truncated tar archive");
                }
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    private static boolean readFully(InputStream in, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int read = in.read(buffer, offset, buffer.length - offset);
            if (read < 0) {
                return offset == 0 ? false : fail();
            }
            offset += read;
        }
        return true;
    }

    private static boolean fail() throws IOException {
        throw new IOException("Truncated tar archive");
    }

    /** Reads at most {@code limit} bytes of the underlying stream and does not close it. */
    private static final class LimitedInputStream extends InputStream {
        private final InputStream in;
        private long remaining;

        LimitedInputStream(InputStream in, long limit) {
            this.in = in;
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int b = in.read();
            if (b >= 0) {
                remaining--;
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int read = in.read(buffer, offset, (int) Math.min(length, remaining));
            if (read > 0) {
                remaining -= read;
            }
            return read;
        }
    }
}
