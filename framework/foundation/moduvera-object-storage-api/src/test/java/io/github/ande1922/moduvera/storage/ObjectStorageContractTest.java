package io.github.ande1922.moduvera.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Set;
import org.junit.jupiter.api.Test;

class ObjectStorageContractTest {

    @Test
    void objectReferenceContainsOnlyLogicalIdentity() {
        ObjectRef reference = new ObjectRef(new BucketAlias("private"), "tenant-a/order/42/invoice.pdf");

        assertThat(reference.bucketAlias().value()).isEqualTo("private");
        assertThat(reference.objectKey()).doesNotContain("http://", "https://");
        assertThat(reference.versionId()).isNull();
    }

    @Test
    void uploadPolicyRejectsUntrustedSizeTypeAndMissingChecksum() {
        UploadPolicy policy = new UploadPolicy(
                "invoice", 1024, Set.of("application/pdf"), ObjectVisibility.PRIVATE, true);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> policy.validate(1025, "application/pdf", null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> policy.validate(100, "text/html", null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> policy.validate(100, "application/pdf", null));
    }
}
