package com.guanghe.backend.asset;

import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.space.BusinessSpaceService;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssetUploadServiceTests {
    @TempDir
    Path tempDirectory;

    private AssetMapper mapper;
    private ObjectStorage storage;
    private PlatformTransactionManager transactionManager;
    private SnowflakeIdGenerator ids;
    private AssetUploadService service;
    private BusinessSpaceService spaces;
    private AssetIngestionProperties properties;
    private ContentSafetyPolicy safety;

    @BeforeEach
    void setUp() {
        mapper = mock(AssetMapper.class);
        storage = mock(ObjectStorage.class);
        transactionManager = mock(PlatformTransactionManager.class);
        ids = mock(SnowflakeIdGenerator.class);
        spaces = mock(BusinessSpaceService.class);
        safety = mock(ContentSafetyPolicy.class);
        properties = new AssetIngestionProperties();
        properties.setEnvironment("test");
        properties.setTempDirectory(tempDirectory);
        properties.setSafetyMode(AssetIngestionProperties.SafetyMode.TEST_BYPASS);
        properties.setTestBypassDownloadEnabled(true);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(mock(TransactionStatus.class));
        when(ids.nextId()).thenReturn(1001L, 1002L);
        when(safety.inspectUpload(anyLong(), anyLong(), any()))
                .thenReturn(new ContentSafetyPolicy.SafetyDecision(true, "TEST_BYPASS"));
        when(mapper.findUploadReceipt(anyLong(), any())).thenReturn(Optional.empty());
        when(mapper.insertAsset(any())).thenReturn(1);
        when(mapper.insertUploadReceipt(any())).thenReturn(1);
        when(storage.storeOrConfirm(any(), any(), any(), anyLong(), any())).thenAnswer(invocation ->
                new ObjectStorage.StoredObject(invocation.getArgument(0), invocation.getArgument(2),
                        invocation.getArgument(3), invocation.getArgument(4), true));
        service = new AssetUploadService(mapper, spaces, ids, storage, properties, safety, transactionManager);
    }

    @Test
    void validImageBecomesOneReadyAssetWithoutReturningAUrl() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "main.png", "image/png", png(32, 24));

        AssetDtos.UploadResponse response = service.upload(
                user(), 2001, UUID.randomUUID().toString(), null, file);

        assertThat(response.id()).isEqualTo("1001");
        assertThat(response.status()).isEqualTo("READY");
        assertThat(response.safetyStatus()).isEqualTo("TEST_BYPASS");
        assertThat(response.width()).isEqualTo(32);
        assertThat(response.height()).isEqualTo(24);
        assertThat(response.toString()).doesNotContain("http", "base64");
        verify(spaces).requireOwnedStorageSpace(41, 2001);
        verify(mapper).insertAsset(any());
        verify(mapper).insertUploadReceipt(any());
    }

    @Test
    void productionUploadIsReadyWithExplicitNotReviewedStatus() throws Exception {
        properties.setEnvironment("production");
        properties.setSafetyMode(AssetIngestionProperties.SafetyMode.NOT_REVIEWED);
        properties.setTestBypassDownloadEnabled(false);
        service = new AssetUploadService(mapper, spaces, ids, storage, properties,
                new NotReviewedContentSafetyPolicy(), transactionManager);

        AssetDtos.UploadResponse response = service.upload(user(), 2001,
                UUID.randomUUID().toString(), null,
                new MockMultipartFile("file", "main.png", "image/png", png(32, 24)));

        assertThat(response.status()).isEqualTo("READY");
        assertThat(response.safetyStatus()).isEqualTo("NOT_REVIEWED");
        verify(mapper).insertAsset(any());
    }

    @Test
    void transactionCommitFailureDeletesTheNewStorageObject() throws Exception {
        doThrow(new IllegalStateException("database commit failed"))
                .when(transactionManager).commit(any(TransactionStatus.class));
        MockMultipartFile file = new MockMultipartFile(
                "file", "main.png", "image/png", png(16, 16));

        assertThatThrownBy(() -> service.upload(
                user(), 2001, UUID.randomUUID().toString(), null, file))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database commit failed");

        verify(storage).delete("zhixiaohe/test/users/41/assets/1001/original.png");
    }

    @Test
    void concurrentIdempotentUploadReturnsTheCommittedWinnerAndDeletesItsOwnObject() throws Exception {
        UUID key = UUID.randomUUID();
        AtomicReference<AssetRecords.UploadReceipt> attempted = new AtomicReference<>();
        when(mapper.findUploadReceipt(41, key)).thenAnswer(ignored ->
                Optional.ofNullable(attempted.get()));
        when(mapper.insertUploadReceipt(any())).thenAnswer(invocation -> {
            attempted.set(invocation.getArgument(0));
            throw new DuplicateKeyException("concurrent winner");
        });
        when(mapper.findAsset(1001, 41)).thenReturn(Optional.of(new AssetRecords.Asset(
                1001, 41, 2001, null, "IMAGE", "UPLOADED", null, "main.png",
                "zhixiaohe/test/users/41/assets/1001/original.png", "image/png",
                82, "a".repeat(64), 16, 16, null, "READY", "TEST_BYPASS",
                java.time.OffsetDateTime.now(), java.time.OffsetDateTime.now())));

        AssetDtos.UploadResponse response = service.upload(user(), 2001, key.toString(), null,
                new MockMultipartFile("file", "main.png", "image/png", png(16, 16)));

        assertThat(response.id()).isEqualTo("1001");
        verify(storage).delete("zhixiaohe/test/users/41/assets/1001/original.png");
    }

    private byte[] png(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private AuthenticatedAccount user() {
        return new AuthenticatedAccount(41, "user", AccountRole.USER, 1,
                AccountStatus.ACTIVE, null, null, null);
    }
}
