package com.guanghe.backend.asset;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.OSSObject;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AlibabaOssReadTests {
    @TempDir Path temp;

    @Test
    void readsImmutableOriginalAndVerifiesActualBytes() throws Exception {
        byte[] body = new byte[]{1, 2, 3};
        Path target = temp.resolve("original");
        storage(body).readVerified("owned-original", target, body.length, hash(body));
        assertThat(Files.readAllBytes(target)).isEqualTo(body);
    }

    @Test
    void refusesOversizeOrTruncatedOriginalRatherThanCompositingIt() throws Exception {
        for (long expected : new long[]{2, 4}) {
            Path target = temp.resolve("original-" + expected);
            assertThatThrownBy(() -> storage(new byte[]{1, 2, 3}).readVerified(
                    "owned-original", target, expected, "0".repeat(64)))
                    .isInstanceOf(AssetIngestionException.class);
        }
    }

    @Test
    void refusesSameLengthChangedObject() {
        assertThatThrownBy(() -> storage(new byte[]{1, 2, 3}).readVerified(
                "owned-original", temp.resolve("changed"), 3, "0".repeat(64)))
                .isInstanceOf(AssetIngestionException.class)
                .extracting(error -> ((AssetIngestionException) error).errorCode())
                .isEqualTo("OSS_OBJECT_VERIFICATION_FAILED");
    }

    private AlibabaOssObjectStorage storage(byte[] body) {
        OSS client = mock(OSS.class);
        OSSObject object = new OSSObject();
        object.setObjectContent(new ByteArrayInputStream(body));
        when(client.getObject("test-bucket", "owned-original")).thenReturn(object);
        AssetIngestionProperties properties = new AssetIngestionProperties();
        properties.getOss().setBucket("test-bucket");
        return new AlibabaOssObjectStorage(client, properties);
    }

    private String hash(byte[] body) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
    }
}
