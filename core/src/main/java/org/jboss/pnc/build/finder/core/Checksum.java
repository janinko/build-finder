/*
 * Copyright (C) 2017 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.pnc.build.finder.core;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.vfs2.FileObject;
import org.eclipse.packager.rpm.RpmSignatureTag;
import org.eclipse.packager.rpm.parse.RpmInputStream;

import com.fasterxml.jackson.annotation.JsonIgnore;

public class Checksum {
    private static final int BUFFER_SIZE = 1024;

    private ChecksumType type;

    private String value;

    @JsonIgnore
    private String filename;

    public Checksum() {

    }

    public Checksum(ChecksumType type, String value, String filename) {
        this.type = type;
        this.value = value;
        this.filename = filename;
    }

    public static Set<Checksum> checksum(FileObject fo, Set<ChecksumType> checksumTypes, String root)
            throws IOException {
        Map<ChecksumType, MessageDigest> mds = new EnumMap<>(ChecksumType.class);

        for (ChecksumType checksumType : checksumTypes) {
            try {
                mds.put(checksumType, MessageDigest.getInstance(checksumType.getAlgorithm()));
            } catch (NoSuchAlgorithmException e) {
                throw new IOException(e);
            }
        }

        int checksumTypesSize = checksumTypes.size();
        List<CompletableFuture<Void>> futures = new ArrayList<>(checksumTypesSize);
        Map<ChecksumType, CompletableFuture<Checksum>> futures2 = new EnumMap<>(ChecksumType.class);

        if (!fo.getName().getExtension().equals("rpm")) {
            int len1;
            InputStream input = fo.getContent().getInputStream();
            byte[] buffer = new byte[BUFFER_SIZE];

            while ((len1 = input.read(buffer)) > 0) {
                final int len = len1;

                for (ChecksumType checksumType : checksumTypes) {
                    CompletableFuture<Void> future = CompletableFuture.supplyAsync(() -> {
                        MessageDigest md = mds.get(checksumType);
                        md.update(buffer, 0, len);
                        return null;
                    });

                    futures.add(future);
                }

                for (CompletableFuture<Void> future : futures) {
                    future.join();
                }

                futures.clear();
            }

            for (ChecksumType checksumType : checksumTypes) {
                CompletableFuture<Checksum> future = CompletableFuture.supplyAsync(() -> {
                    MessageDigest md = mds.get(checksumType);
                    return new Checksum(checksumType, Hex.encodeHexString(md.digest()), Utils.normalizePath(fo, root));
                });

                futures2.put(checksumType, future);
            }
        } else {
            try (RpmInputStream in = new RpmInputStream(new BufferedInputStream(fo.getContent().getInputStream()))) {
                for (ChecksumType checksumType : checksumTypes) {
                    CompletableFuture<Checksum> future;

                    switch (checksumType) {
                        case md5:
                            Object md5 = in.getSignatureHeader().getTag(RpmSignatureTag.MD5);

                            if (!(md5 instanceof byte[])) {
                                throw new IOException("Missing MD5 for " + fo);
                            }

                            final String sigmd5 = Hex.encodeHexString((byte[]) md5);

                            future = CompletableFuture.supplyAsync(
                                    () -> new Checksum(checksumType, sigmd5, Utils.normalizePath(fo, root)));

                            futures2.put(checksumType, future);
                            break;
                        case sha1:
                            Object sha1 = in.getSignatureHeader().getTag(RpmSignatureTag.SHA1HEADER);

                            if (!(sha1 instanceof byte[])) {
                                break;
                            }

                            final String sigsha1 = Hex.encodeHexString((byte[]) sha1);

                            future = CompletableFuture.supplyAsync(
                                    () -> new Checksum(checksumType, sigsha1, Utils.normalizePath(fo, root)));

                            futures2.put(checksumType, future);
                            break;
                        case sha256:
                            Object sha256 = in.getSignatureHeader().getTag(RpmSignatureTag.SHA256HEADER);

                            if (!(sha256 instanceof byte[])) {
                                break;
                            }

                            final String sigsha256 = Hex.encodeHexString((byte[]) sha256);

                            future = CompletableFuture.supplyAsync(
                                    () -> new Checksum(checksumType, sigsha256, Utils.normalizePath(fo, root)));

                            futures2.put(checksumType, future);
                            break;
                        default:
                    }
                }
            }
        }

        Set<Checksum> results = new HashSet<>(checksumTypesSize);

        for (ChecksumType checksumType : checksumTypes) {
            try {
                results.add(futures2.get(checksumType).get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                throw new IOException(e);
            }
        }

        return results;
    }

    public static Optional<Checksum> findByType(Set<Checksum> checksums, ChecksumType type) {
        List<Checksum> list = checksums.stream()
                .filter(checksum -> checksum.getType().equals(type))
                .collect(Collectors.toList());
        int size = list.size();

        if (size == 0) {
            return Optional.empty();
        }

        Checksum checksum = list.get(0);

        return Optional.of(checksum);
    }

    public ChecksumType getType() {
        return type;
    }

    public void setType(ChecksumType type) {
        this.type = type;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    @Override
    public String toString() {
        return "Checksum [type=" + type + ", value=" + value + ", filename=" + filename + "]";
    }
}
