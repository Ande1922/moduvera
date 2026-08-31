package io.github.ande1922.moduvera.storage;

import java.io.InputStream;
import java.time.Duration;
import java.util.Map;

public interface ObjectStorage {

    StoredObject put(
            ObjectRef target,
            InputStream content,
            long contentLength,
            String mediaType,
            Checksum checksum,
            Map<String, String> metadata);

    InputStream get(ObjectRef reference);

    StoredObject head(ObjectRef reference);

    void delete(ObjectRef reference);

    PreparedUpload prepare(
            ObjectRef target,
            long contentLength,
            String mediaType,
            Checksum checksum,
            Map<String, String> metadata,
            Duration validity);

    StoredObject confirm(ObjectRef reference, UploadPolicy policy);
}
