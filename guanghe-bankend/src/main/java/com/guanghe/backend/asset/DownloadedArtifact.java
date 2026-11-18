package com.guanghe.backend.asset;

import java.nio.file.Path;

public record DownloadedArtifact(Path path, String contentType, long sizeBytes, String sha256,
                                 int width, int height, String durationSeconds, String videoCodec) {
    public DownloadedArtifact(Path path, String contentType, long sizeBytes, String sha256,
                              int width, int height) {
        this(path, contentType, sizeBytes, sha256, width, height, null, null);
    }
}
