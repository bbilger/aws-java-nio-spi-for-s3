/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.nio.spi.s3;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.assertj.core.api.BDDAssertions.then;
import static software.amazon.nio.spi.s3.Containers.localStackConnectionEndpoint;
import static software.amazon.nio.spi.s3.Containers.putObject;

@DisplayName("Files$exists()")
public class FilesExistsTest {

    @Nested
    @DisplayName("should be false")
    class FileDoesNotExist {

        @Test
        @DisplayName("when bucket does not exist")
        public void fileExistsShouldReturnFalseWhenBucketNotFound() {
            final var path = Paths.get(URI.create(localStackConnectionEndpoint() + "/does-not-exist"));
            then(Files.exists(path)).isFalse();
        }

        @Test
        @DisplayName("when bucket exists but file doesn't")
        public void fileExistsShouldReturnFalseWhenBucketExistsAndFileNotFound() {
            Containers.createBucket("sink");
            final var path = Paths.get(URI.create(localStackConnectionEndpoint() + "/sink/missing-file"));
            then(Files.exists(path)).isFalse();
        }
    }

    @Nested
    @DisplayName("should be true")
    class FileExists {

        @BeforeEach
        public void createBucket() {
            Containers.createBucket("sink");
        }

        @Test
        @DisplayName("when bucket and file exist")
        public void fileExistsShouldReturnTrueWhenBucketExistsAndFileFound() {
            final var path = putObject("sink", "sample-file.txt");
            then(Files.exists(path)).isTrue();
        }

        @Test
        @DisplayName("for bucket path when it exists")
        public void fileExistsShouldReturnTrueWhenBucketExists() {
            final var path = Paths.get(URI.create(localStackConnectionEndpoint() + "/sink/"));
            then(Files.exists(path)).isTrue();
        }
    }
}
