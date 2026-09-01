package io.github.ande1922.moduvera.storage;

import java.util.Objects;

public record ObjectRef(BucketAlias bucketAlias, String objectKey, String versionId) {

    public ObjectRef {
        Objects.requireNonNull(bucketAlias, "bucketAlias");
        Objects.requireNonNull(objectKey, "objectKey");
        if (objectKey.isBlank() || objectKey.length() > 1024 || objectKey.startsWith("/")) {
            throw new IllegalArgumentException("objectKey must be a relative, non-blank storage key");
        }
        if (versionId != null && versionId.isBlank()) {
            throw new IllegalArgumentException("versionId must be null or non-blank");
        }
    }

    public ObjectRef(BucketAlias bucketAlias, String objectKey) {
        this(bucketAlias, objectKey, null);
    }
}
