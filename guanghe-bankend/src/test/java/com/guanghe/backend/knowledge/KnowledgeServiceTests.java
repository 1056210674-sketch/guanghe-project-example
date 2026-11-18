package com.guanghe.backend.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanghe.backend.agentplatform.AgentPlatformMapper;
import com.guanghe.backend.agentplatform.AgentPlatformRecords;
import com.guanghe.backend.asset.AssetIngestionProperties;
import com.guanghe.backend.asset.ObjectStorage;
import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import com.guanghe.backend.common.id.SnowflakeProperties;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KnowledgeServiceTests {
    static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-07T00:00:00Z");
    @Mock KnowledgeMapper mapper;
    @Mock AgentPlatformMapper projects;
    @Mock ObjectProvider<ObjectStorage> storageProvider;
    @Mock ObjectStorage storage;
    @Mock PlatformTransactionManager manager;
    KnowledgeProperties properties;
    KnowledgeService service;
    ObjectMapper json = new ObjectMapper();

    @BeforeEach void setup() {
        properties = new KnowledgeProperties();
        var asset = new AssetIngestionProperties();
        asset.setEnvironment("test");
        service = new KnowledgeService(mapper, projects, new SnowflakeIdGenerator(new SnowflakeProperties(1, 1)),
                json, new KnowledgeParser(properties), properties, storageProvider, asset, manager);
    }
    @Test void createTextUsesOwnedActiveProjectAndNoAssetTaskOrPayment() {
        when(mapper.lockProjectForWrite(41, 51)).thenReturn(Optional.of("ACTIVE"));
        when(mapper.insertDocument(any())).thenReturn(1);
        when(mapper.insertVersion(any())).thenReturn(1);
        var detail = service.createDocument(user(), 51, new KnowledgeDtos.CreateDocumentRequest("小行星资料", "咖啡、曲奇、蛋糕", null));
        assertThat(detail.projectId()).isEqualTo("51");
        assertThat(detail.status()).isEqualTo("READY");
        assertThat(detail.version()).isEqualTo(1);
        assertThat(detail.paragraphs().getFirst().text()).isEqualTo("咖啡、曲奇、蛋糕");
        verifyNoInteractions(storageProvider, storage);
    }
    @Test void foreignAndArchivedProjectsCannotWrite() {
        assertCode(() -> service.createDocument(user(), 51, new KnowledgeDtos.CreateDocumentRequest("x", "x", null)), ErrorCode.RESOURCE_NOT_FOUND);
        when(mapper.lockProjectForWrite(41, 51)).thenReturn(Optional.of("ARCHIVED"));
        assertCode(() -> service.createDocument(user(), 51, new KnowledgeDtos.CreateDocumentRequest("x", "x", null)), ErrorCode.RESOURCE_IN_USE);
        verify(mapper, never()).insertDocument(any());
    }
    @Test void administratorCannotReadUsersPrivateSources() {
        var admin = new AuthenticatedAccount(41, "admin", AccountRole.ADMIN, 1, AccountStatus.ACTIVE, null, "admin", null);
        assertCode(() -> service.documents(admin, 51, 1, 20), ErrorCode.FORBIDDEN);
        verifyNoInteractions(mapper, projects);
    }
    @Test void linkIsPastedEvidenceOnlyAndNeverFetches() {
        when(mapper.lockProjectForWrite(41, 51)).thenReturn(Optional.of("ACTIVE"));
        when(mapper.insertDocument(any())).thenReturn(1);
        when(mapper.insertVersion(any())).thenReturn(1);
        var detail = service.createDocument(user(), 51, new KnowledgeDtos.CreateDocumentRequest("网页摘录", "用户自己复制的网页内容", "https://example.com/article"));
        assertThat(detail.sourceType()).isEqualTo("LINK_PASTED_TEXT");
        assertThat(detail.sourceUrl()).isEqualTo("https://example.com/article");
        verifyNoInteractions(storageProvider, storage);
    }
    @Test void URLCannotContainCredentialsJavascriptOrFragments() {
        when(mapper.lockProjectForWrite(41, 51)).thenReturn(Optional.of("ACTIVE"));
        for (String value : List.of("javascript:alert(1)", "https://a:b@example.com/", "https://example.com/#x", "https://example.com/ a")) {
            assertCode(() -> service.createDocument(user(), 51, new KnowledgeDtos.CreateDocumentRequest("x", "x", value)), ErrorCode.VALIDATION_FAILED);
        }
        verify(mapper, never()).insertDocument(any());
    }
    @Test void editsAppendVersionAndDoNotOverwriteOldSource() {
        when(mapper.lockProjectForWrite(41, 51)).thenReturn(Optional.of("ACTIVE"));
        when(mapper.lockDocument(41, 51, 61)).thenReturn(Optional.of(document("READY", 3)));
        when(mapper.insertVersion(any())).thenReturn(1);
        when(mapper.updateDocument(any(), eq(3))).thenReturn(1);
        var detail = service.updateDocument(user(), 51, 61, new KnowledgeDtos.UpdateDocumentRequest(3, "最新菜单", "蛋糕暂时下架", null));
        assertThat(detail.version()).isEqualTo(4);
        var captor = ArgumentCaptor.forClass(KnowledgeRecords.DocumentVersion.class);
        verify(mapper).insertVersion(captor.capture());
        assertThat(captor.getValue().version()).isEqualTo(4);
        assertThat(captor.getValue().objectKey()).isNull();
        verify(mapper, never()).redactDocumentVersions(anyLong(), anyLong(), anyLong());
    }
    @Test void staleVersionAndParsingEditFailWithoutNewVersion() {
        when(mapper.lockProjectForWrite(41, 51)).thenReturn(Optional.of("ACTIVE"));
        when(mapper.lockDocument(41, 51, 61)).thenReturn(Optional.of(document("READY", 3)));
        assertCode(() -> service.updateDocument(user(), 51, 61, new KnowledgeDtos.UpdateDocumentRequest(2, "x", "x", null)), ErrorCode.RESOURCE_VERSION_CONFLICT);
        when(mapper.lockDocument(41, 51, 61)).thenReturn(Optional.of(document("PARSING", 3)));
        assertCode(() -> service.updateDocument(user(), 51, 61, new KnowledgeDtos.UpdateDocumentRequest(3, "x", "x", null)), ErrorCode.RESOURCE_IN_USE);
        verify(mapper, never()).insertVersion(any());
    }
    @Test void deletionRedactsEveryRevisionAndDisablesAllFutureRetrieval() {
        when(projects.project(41, 51)).thenReturn(Optional.of(project("ARCHIVED")));
        when(mapper.lockDocument(41, 51, 61)).thenReturn(Optional.of(document("READY", 3)));
        when(mapper.updateDocument(any(), eq(3))).thenReturn(1);
        service.deleteDocument(user(), 51, 61, 3);
        verify(mapper).redactDocumentVersions(41, 51, 61);
        var captor = ArgumentCaptor.forClass(KnowledgeRecords.Document.class);
        verify(mapper).updateDocument(captor.capture(), eq(3));
        assertThat(captor.getValue().status()).isEqualTo("DELETED");
        assertThat(captor.getValue().deletedAt()).isNotNull();
        verifyNoInteractions(storage);
    }
    @Test void contextUsesProjectDocumentsAndHistoryWithoutPromotingLegacyPreferences() throws Exception {
        when(projects.project(41, 51)).thenReturn(Optional.of(project("ACTIVE")));
        when(mapper.readyDocumentCount(41, 51)).thenReturn(1L);
        when(mapper.contextDocuments(eq(41L), eq(51L), anyList(), eq(12))).thenReturn(List.of(projection("咖啡、曲奇、蛋糕", "READY")));
        when(mapper.businessHistory(41, 51)).thenReturn(new KnowledgeRecords.Counts(3, 2, 5));
        var context = service.context(41, 51L, "卖什么");
        assertThat(context.documents()).hasSize(1);
        assertThat(context.preferences()).isEmpty();
        assertThat(context.businessHistory().writtenArticleCount()).isEqualTo(3);
        assertThat(context.businessHistory().publishedArticleCount()).isEqualTo(2);
        assertThat(context.businessHistory().publishedPlacementCount()).isEqualTo(5);
        assertThat(context.truncated()).isFalse();
        verify(mapper, never()).documentPage(anyLong(), anyLong(), anyLong(), anyInt());
        verify(mapper, never()).preferences(anyLong(), any());
    }
    @Test void noProjectContextNeverLeaksAnyProjectFactsOrCounts() {
        var context = service.context(41, null, "我是谁");
        assertThat(context.documents()).isEmpty();
        assertThat(context.businessHistory()).isNull();
        assertThat(context.preferences()).isEmpty();
        verifyNoInteractions(projects, mapper);
    }
    @Test void legacyPreferencesRemainEditableButDoNotConsumeExecutionContextBudget() {
        properties.setMaxContextCharacters(100);
        when(mapper.preferences(41, null)).thenReturn(List.of(preference(null, "x".repeat(90)), preference(null, "y".repeat(20))));
        assertThat(service.preferences(user(), null)).hasSize(2);
        var context = service.context(41, null, "你好");
        assertThat(context.preferences()).isEmpty();
        assertThat(context.returnedCharacters()).isZero();
        assertThat(context.truncated()).isFalse();
        verify(mapper, times(1)).preferences(41, null);
        verifyNoInteractions(projects);
    }
    @Test void contextBudgetCannotBeConfiguredLargerThanWorkerContract() {
        properties.setMaxContextCharacters(12001);
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);
    }
    @Test void shortAcknowledgementsRetrieveScopedKnowledgeWithoutSearchTerms() throws Exception {
        when(projects.project(41, 51)).thenReturn(Optional.of(project("ACTIVE")));
        when(mapper.readyDocumentCount(41, 51)).thenReturn(1L);
        when(mapper.contextDocuments(41, 51, List.of(), 12))
                .thenReturn(List.of(projection("咖啡、曲奇、蛋糕", "READY")));
        when(mapper.businessHistory(41, 51)).thenReturn(new KnowledgeRecords.Counts(0, 0, 0));

        for (String query : List.of("好", "嗯", "？", " ")) {
            var context = service.context(41, 51L, query);
            assertThat(context.documents()).as(query).hasSize(1);
            assertThat(context.documents().getFirst().paragraphs().getFirst().text()).isEqualTo("咖啡、曲奇、蛋糕");
        }

        verify(mapper, times(4)).contextDocuments(41, 51, List.of(), 12);
        verify(projects, times(4)).project(41, 51);
        verifyNoInteractions(storageProvider, storage);
    }
    @Test void shortAcknowledgementCannotRetrieveAnotherUsersProject() {
        assertCode(() -> service.context(42, 51L, "好"), ErrorCode.RESOURCE_NOT_FOUND);

        verify(projects).project(42, 51);
        verify(mapper, never()).contextDocuments(anyLong(), anyLong(), anyList(), anyInt());
    }
    @Test void retrievalLimitIsExplicitAndDoesNotInventWholeDocumentReading() throws Exception {
        properties.setMaxContextCharacters(100);
        when(projects.project(41, 51)).thenReturn(Optional.of(project("ACTIVE")));
        when(mapper.readyDocumentCount(41, 51)).thenReturn(20L);
        when(mapper.contextDocuments(eq(41L), eq(51L), anyList(), eq(12))).thenReturn(List.of(projection("x".repeat(101), "READY")));
        when(mapper.businessHistory(41, 51)).thenReturn(new KnowledgeRecords.Counts(0, 0, 0));
        var context = service.context(41, 51L, "x");
        assertThat(context.truncated()).isTrue();
        assertThat(context.sourceCount()).isEqualTo(20);
        assertThat(context.returnedCharacters()).isZero();
        assertThat(context.documents()).isEmpty();
    }
    @Test void futurePreferenceMustBeExactOriginalUserFragmentAndExplicit() {
        when(mapper.lockAccountForPreferenceWrite(41)).thenReturn(Optional.of(41L));
        when(mapper.originalUserText(41, 91)).thenReturn(Optional.of("以后先给结论"));
        when(mapper.insertPreference(any())).thenReturn(1);
        var result = service.rememberExpressedPreference(41, null, 91, "先给结论");
        assertThat(result.sourceType()).isEqualTo("USER_MESSAGE");
        assertThat(result.sourceMessageId()).isEqualTo("91");
        assertCode(() -> service.rememberExpressedPreference(41, null, 91, "用户性格急躁"), ErrorCode.VALIDATION_FAILED);
        when(mapper.originalUserText(41, 92)).thenReturn(Optional.of("生成一张图"));
        assertCode(() -> service.rememberExpressedPreference(41, null, 92, "生成一张图"), ErrorCode.VALIDATION_FAILED);
    }
    @Test void deletedMessagePreferenceNeverRevivesThroughReplay() {
        when(mapper.lockAccountForPreferenceWrite(41)).thenReturn(Optional.of(41L));
        when(mapper.originalUserText(41, 91)).thenReturn(Optional.of("以后先给结论"));
        var tombstone = new KnowledgeRecords.Preference(71, 41, null, "已删除", "a".repeat(64), "USER_MESSAGE", 91L, 2, NOW, NOW, NOW);
        when(mapper.preferenceFromMessage(eq(41L), eq(91L), anyString())).thenReturn(Optional.of(tombstone));
        assertThat(service.rememberExpressedPreference(41, null, 91, "先给结论")).isNull();
        verify(mapper, never()).insertPreference(any());
    }
    @Test void crossUserMessageCannotBecomeMemory() {
        when(mapper.lockAccountForPreferenceWrite(41)).thenReturn(Optional.of(41L));
        assertCode(() -> service.rememberExpressedPreference(41, null, 999, "以后先给结论"), ErrorCode.RESOURCE_NOT_FOUND);
        verify(mapper, never()).insertPreference(any());
    }
    @Test void personalAndProjectWritesEnforceDistinctCapacityAndScope() {
        when(mapper.lockAccountForPreferenceWrite(41)).thenReturn(Optional.of(41L));
        when(mapper.lockProjectForWrite(41, 51)).thenReturn(Optional.of("ARCHIVED"));
        assertCode(() -> service.createPreference(user(), new KnowledgeDtos.CreatePreferenceRequest("51", "少用夸张词")), ErrorCode.RESOURCE_IN_USE);
        when(mapper.preferenceCount(41, null)).thenReturn(50L);
        assertCode(() -> service.createPreference(user(), new KnowledgeDtos.CreatePreferenceRequest(null, "少用夸张词")), ErrorCode.VALIDATION_FAILED);
    }
    @Test void referenceRevocationAndVersionChangePreventFreshExecution() throws Exception {
        when(projects.project(41, 51)).thenReturn(Optional.of(project("ACTIVE")));
        var ref = new KnowledgeDtos.ContextDocument("61", 3, "资料", "a".repeat(64), "PASTED_TEXT", null, List.of());
        assertThat(service.referencesCurrent(41, 51, List.of(ref))).isFalse();
        when(mapper.document(41, 51, 61)).thenReturn(Optional.of(projection("文本", "READY")));
        assertThat(service.referencesCurrent(41, 51, List.of(ref))).isTrue();
        assertThat(service.referencesCurrent(41, 51, List.of(new KnowledgeDtos.ContextDocument("61", 2, "资料", "a".repeat(64), "PASTED_TEXT", null, List.of())))).isFalse();
        when(projects.project(41, 51)).thenReturn(Optional.of(project("ARCHIVED")));
        assertThat(service.referencesCurrent(41, 51, List.of(ref))).isFalse();
    }
    @Test void updatedPreferenceReturnsTheExactPersistedMicrosecondTimestamp() {
        when(mapper.lockAccountForPreferenceWrite(41)).thenReturn(Optional.of(41L));
        when(mapper.lockPreference(41, 71)).thenReturn(Optional.of(preference(null, "旧偏好")));
        when(mapper.updatePreference(eq(41L), eq(71L), eq(1), anyString(), anyString(), any())).thenReturn(1);
        var updated = service.updatePreference(user(), 71, new KnowledgeDtos.UpdatePreferenceRequest(1, "更简洁"));
        ArgumentCaptor<OffsetDateTime> timestamp = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(mapper).updatePreference(eq(41L), eq(71L), eq(1), eq("更简洁"), anyString(), timestamp.capture());
        assertThat(updated.updatedAt()).isEqualTo(timestamp.getValue());
        assertThat(updated.updatedAt().getNano() % 1000).isZero();
    }
    @Test void unsupportedUploadNeverCreatesDocumentOrCallsStorage() {
        var file = new MockMultipartFile("file", "x.docx", "application/octet-stream", new byte[]{1, 2});
        assertCode(() -> service.upload(user(), 51, null, file), ErrorCode.VALIDATION_FAILED);
        verifyNoInteractions(mapper, storage, storageProvider);
    }
    @Test void frozenContextDropsChangedDocumentAndDeletedPreferenceWithoutReplacingNewText() throws Exception {
        when(projects.project(41, 51)).thenReturn(Optional.of(project("ACTIVE")));
        var frozenDoc = new KnowledgeDtos.ContextDocument("61", 2, "旧资料", "b".repeat(64), "PASTED_TEXT", null,
                List.of(new KnowledgeDtos.Paragraph(1, null, "旧菜单")));
        var frozenPreference = new KnowledgeDtos.PreferenceView("71", null, "PERSONAL", "以前的偏好", "USER_INPUT", null, 1, NOW, NOW);
        when(mapper.document(41, 51, 61)).thenReturn(Optional.of(projection("新菜单", "READY")));
        var frozen = new KnowledgeDtos.KnowledgeContext(List.of(frozenDoc), List.of(frozenPreference),
                new KnowledgeDtos.BusinessHistory("51", 3, 2, 5, NOW), false, 1, 100);
        var result = service.revalidateFrozenContext(41, 51L, frozen);
        assertThat(result.documents()).isEmpty();
        assertThat(result.preferences()).isEmpty();
        assertThat(result.truncated()).isTrue();
        assertThat(result.businessHistory()).isSameAs(frozen.businessHistory());
        assertThat(result.returnedCharacters()).isZero();
    }
    @Test void frozenContextRetainsOnlyTheExactAuthorizedVersionAndParagraphSubset() throws Exception {
        when(projects.project(41, 51)).thenReturn(Optional.of(project("ACTIVE")));
        when(mapper.document(41, 51, 61)).thenReturn(Optional.of(projection("菜单", "READY")));
        var reference = new KnowledgeDtos.ContextDocument("61", 3, "资料", "a".repeat(64), "PASTED_TEXT", null,
                List.of(new KnowledgeDtos.Paragraph(1, null, "菜单")));
        var frozen = new KnowledgeDtos.KnowledgeContext(List.of(reference), List.of(), null, false, 1, 2);
        var result = service.revalidateFrozenContext(41, 51L, frozen);
        assertThat(result.documents()).containsExactly(reference);
        assertThat(result.truncated()).isFalse();
        var wrongContent = new KnowledgeDtos.ContextDocument("61", 3, "资料", "a".repeat(64), "PASTED_TEXT", null,
                List.of(new KnowledgeDtos.Paragraph(1, null, "伪造菜单")));
        assertThat(service.revalidateFrozenContext(41, 51L,
                new KnowledgeDtos.KnowledgeContext(List.of(wrongContent), List.of(), null, false, 1, 4)).documents()).isEmpty();
    }
    @Test void failedBinaryUploadLeavesExplicitFailedParsingStatus() {
        when(storageProvider.getIfAvailable()).thenReturn(storage);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(mapper.lockProjectForWrite(41, 51)).thenReturn(Optional.of("ACTIVE"));
        when(mapper.insertDocument(any())).thenReturn(1);
        when(mapper.lockDocument(eq(41L), eq(51L), anyLong())).thenAnswer(call -> Optional.of(new KnowledgeRecords.Document(
                call.getArgument(2), 41, 51, "bad.txt", "TXT", "PARSING", 1, null, NOW, NOW, null)));
        when(mapper.updateDocument(any(), eq(1))).thenReturn(1);
        var result = service.upload(user(), 51, null, new MockMultipartFile("file", "bad.txt", "text/plain", new byte[]{(byte) 0xff}));
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.failureCode()).isEqualTo("KNOWLEDGE_UTF8_REQUIRED");
        assertThat(result.paragraphs()).isEmpty();
        verifyNoInteractions(storage);
    }
    @Test void interruptedUploadsBecomeFailedWithoutReadingOrRetryingPrivateFiles() {
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var abandoned = new KnowledgeRecords.Document(61, 41, 51, "未完成上传", "TXT", "PARSING", 1, null, NOW, NOW, null);
        when(mapper.staleUploads(any())).thenReturn(List.of(abandoned));
        when(mapper.lockDocument(41, 51, 61)).thenReturn(Optional.of(abandoned));
        when(mapper.updateDocument(any(), eq(1))).thenReturn(1);
        service.recoverStaleUploads();
        ArgumentCaptor<KnowledgeRecords.Document> captured = ArgumentCaptor.forClass(KnowledgeRecords.Document.class);
        verify(mapper).updateDocument(captured.capture(), eq(1));
        assertThat(captured.getValue().status()).isEqualTo("FAILED");
        assertThat(captured.getValue().failureCode()).isEqualTo("KNOWLEDGE_UPLOAD_INTERRUPTED");
        assertThat(captured.getValue().currentVersion()).isEqualTo(1);
        verifyNoInteractions(storage, storageProvider, projects);
    }
    @Test void uploadRecoveryDoesNotOverwriteSuccessfullyCompletedConcurrentUpload() {
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(mapper.staleUploads(any())).thenReturn(List.of(document("PARSING", 1)));
        when(mapper.lockDocument(41, 51, 61)).thenReturn(Optional.of(document("READY", 1)));
        service.recoverStaleUploads();
        verify(mapper, never()).updateDocument(any(), anyInt());
    }
    @Test void disabledRecoveryPerformsNoQueries() {
        properties.setUploadRecoveryEnabled(false);
        service.recoverStaleUploads();
        verifyNoInteractions(mapper, manager);
    }
    @Test void linkLengthIsCheckedAfterUnicodeNormalization() {
        when(mapper.lockProjectForWrite(41, 51)).thenReturn(Optional.of("ACTIVE"));
        assertCode(() -> service.createDocument(user(), 51, new KnowledgeDtos.CreateDocumentRequest("链接", "内容",
                "https://example.com/" + "中".repeat(300))), ErrorCode.VALIDATION_FAILED);
        verify(mapper, never()).insertDocument(any());
    }
    @Test void uppercaseSchemeIsNormalizedForValidatedDatabaseLinkConstraint() {
        when(mapper.lockProjectForWrite(41, 51)).thenReturn(Optional.of("ACTIVE"));
        when(mapper.insertDocument(any())).thenReturn(1);
        when(mapper.insertVersion(any())).thenReturn(1);
        var result = service.createDocument(user(), 51, new KnowledgeDtos.CreateDocumentRequest("资料", "菜单", "HTTPS://example.com/menu"));
        assertThat(result.sourceUrl()).isEqualTo("https://example.com/menu");
        verifyNoInteractions(storageProvider);
    }
    @Test void correctingFailedUploadRemovesDeterministicOriginalBeforeNewTextVersion() {
        when(storageProvider.getIfAvailable()).thenReturn(storage);
        when(mapper.lockProjectForWrite(41, 51)).thenReturn(Optional.of("ACTIVE"));
        when(mapper.lockDocument(41, 51, 61)).thenReturn(Optional.of(new KnowledgeRecords.Document(
                61, 41, 51, "失败文件", "TXT", "FAILED", 1, "KNOWLEDGE_UPLOAD_INTERRUPTED", NOW, NOW, null)));
        when(mapper.insertVersion(any())).thenReturn(1);
        when(mapper.updateDocument(any(), eq(1))).thenReturn(1);
        var result = service.updateDocument(user(), 51, 61, new KnowledgeDtos.UpdateDocumentRequest(1, "重新输入", "完整菜单", null));
        assertThat(result.status()).isEqualTo("READY");
        assertThat(result.version()).isEqualTo(2);
        verify(storage).delete("zhixiaohe/test/users/41/knowledge/51/61/original.txt");
    }
    private KnowledgeRecords.Document document(String status, int version) {
        return new KnowledgeRecords.Document(61, 41, 51, "资料", "PASTED_TEXT", status, version, null, NOW, NOW, null);
    }
    private KnowledgeRecords.DocumentProjection projection(String text, String status) throws Exception {
        return new KnowledgeRecords.DocumentProjection(61, 41, 51, "资料", "PASTED_TEXT", status, 3, null, NOW, NOW,
                "a".repeat(64), "text/plain", text.length(), null, json.writeValueAsString(List.of(new KnowledgeDtos.Paragraph(1, null, text))), null);
    }
    private AgentPlatformRecords.Project project(String status) { return new AgentPlatformRecords.Project(51, 41, "小行星", "小行星", status, 1, NOW, NOW); }
    private KnowledgeRecords.Preference preference(Long projectId, String text) {
        return new KnowledgeRecords.Preference(projectId == null ? 71 : 72, 41, projectId, text, "a".repeat(64), "USER_INPUT", null, 1, NOW, NOW, null);
    }
    private AuthenticatedAccount user() { return new AuthenticatedAccount(41, "user", AccountRole.USER, 1, AccountStatus.ACTIVE, null, "user", null); }
    private void assertCode(ThrowingCallable action, ErrorCode code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BusinessException.class, value -> assertThat(value.errorCode()).isEqualTo(code));
    }
}
