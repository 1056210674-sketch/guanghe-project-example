package com.guanghe.backend.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guanghe.backend.article.ArticleMapper;
import com.guanghe.backend.article.ArticleRecords;
import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import com.guanghe.backend.catalog.product.ProductService;
import com.guanghe.backend.task.PaidTaskProperties;
import com.guanghe.backend.task.TaskControlMapper;
import com.guanghe.backend.task.TaskDtos;
import com.guanghe.backend.task.TaskRecords;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;

class MediaProtocolAndServiceTests {
    private static final String TEST_KEY = "0123456789abcdef0123456789abcdef";
    private static final String FIXED_RESPONSE = "eyJpdiI6IkFBRUNBd1FGQmdjSUNRb0xEQTBPRHc9PSIsInZhbHVlIjoi"
            + "M2c2MTF0aThHZVRHNG5aaGFxQW9lRDZIcHlMRGNERFlXakVZVUxrSGRuZmlFMXdnWmJURTV1VmpHR1ViakxlQzc3"
            + "RXJFM09SYWFkQXlCdjdRL0VVSDRxUEp6QmhlRGk5eFV2TkI3dlE1dk1sUUZYUUFBa2YrVlJwdytic1M0MEttS2RQ"
            + "Ylg5YWlxN0tXMHN2ME95Wm5BPT0ifQ==";
    private static final String FIXED_SIGN = "aaae04fbc29bd39f04faa775fbdf1631";

    private MediaMapper media;
    private TaskControlMapper tasks;
    private ArticleMapper articles;
    private ProductService products;
    private MediaProviderAdapter provider;
    private MediaProperties properties;
    private MediaSubmissionService service;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final AtomicLong ids = new AtomicLong(9_900);

    @BeforeEach
    void setUp() {
        media = mock(MediaMapper.class);
        tasks = mock(TaskControlMapper.class);
        articles = mock(ArticleMapper.class);
        products = mock(ProductService.class);
        provider = mock(MediaProviderAdapter.class);
        properties = properties();
        SnowflakeIdGenerator idGenerator = mock(SnowflakeIdGenerator.class);
        when(idGenerator.nextId()).thenAnswer(ignored -> ids.incrementAndGet());
        PaidTaskProperties taskProperties = new PaidTaskProperties();
        taskProperties.setTokenSecret(TEST_KEY);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(mock(TransactionStatus.class));
        service = new MediaSubmissionService(media, tasks, articles, products, provider, properties,
                taskProperties, idGenerator, json, transactionManager);
    }

    @Test
    void legacyPhpAesVectorIsByteForByteCompatibleAndRoundTrips() {
        MediaProtocolCodec codec = new MediaProtocolCodec(json, new FixedSecureRandom());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model_id", "1");
        payload.put("resource_id", "44990");
        payload.put("page", 1);
        payload.put("limit", 100);

        Map<String, String> envelope = codec.encrypt(payload, TEST_KEY);

        assertThat(envelope.get("response")).isEqualTo(FIXED_RESPONSE);
        assertThat(envelope.get("sign")).isEqualTo(FIXED_SIGN);
        assertThat(codec.decrypt(FIXED_RESPONSE, FIXED_SIGN, TEST_KEY))
                .isEqualTo(json.valueToTree(Map.of("model_id", "1", "resource_id", "44990",
                        "page", "1", "limit", "100")));
    }

    @Test
    void protocolRejectsTamperedSignatureAndNonAsciiKey() {
        MediaProtocolCodec codec = new MediaProtocolCodec(json, new FixedSecureRandom());
        assertThatThrownBy(() -> codec.decrypt(FIXED_RESPONSE, "0".repeat(32), TEST_KEY))
                .isInstanceOf(MediaProviderException.class)
                .hasMessage("MEDIA_CALLBACK_SIGNATURE_INVALID");
        assertThatThrownBy(() -> codec.encrypt(Map.of("model_id", "1"), "测".repeat(32)))
                .isInstanceOf(MediaProviderException.class)
                .hasMessage("MEDIA_PROVIDER_NOT_CONFIGURED");
    }

    @Test
    void priceUsesCeilingInYuanBeforeConvertingToPoints() {
        assertThat(MediaSubmissionService.saleAmountCents(new BigDecimal("368.0000"))).isEqualTo(49_700);
        assertThat(MediaSubmissionService.saleAmountCents(new BigDecimal("0.0100"))).isEqualTo(100);
        assertThat(MediaSubmissionService.saleAmountCents(new BigDecimal("1.0000"))).isEqualTo(200);
        assertThatThrownBy(() -> MediaSubmissionService.saleAmountCents(BigDecimal.ZERO))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void configurationAllowsOnlyVerifiedModelAndAsciiKey() {
        properties.validate();
        properties.setSupportedModelIds(java.util.Set.of("2"));
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("verified model 1");
        properties.setSupportedModelIds(java.util.Set.of("1"));
        properties.setAppKey("测".repeat(32));
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32-byte");
    }

    @Test
    void providerGatewayFreezesAllFourVerifiedRoutes() {
        assertThat(MediaProcurementClient.PATHS).containsExactlyInAnyOrderEntriesOf(Map.of(
                "resources", "/api/companyResource/syncResource",
                "submit", "/api/companyOrder/receive",
                "status", "/api/companyOrder/getOrderStatusNew",
                "wallet", "/api/companyWallet/walletBalance"));
    }

    @Test
    void providerHttpRejectionDoesNotFreezePointsAsAnUnknownSubmission() {
        assertThat(MediaProcurementClient.ambiguousSubmissionHttpStatus(400)).isFalse();
        assertThat(MediaProcurementClient.ambiguousSubmissionHttpStatus(422)).isFalse();
        assertThat(MediaProcurementClient.ambiguousSubmissionHttpStatus(408)).isTrue();
        assertThat(MediaProcurementClient.ambiguousSubmissionHttpStatus(409)).isTrue();
        assertThat(MediaProcurementClient.ambiguousSubmissionHttpStatus(500)).isTrue();
        assertThat(MediaProcurementClient.ambiguousSubmissionHttpStatus(503)).isTrue();
    }

    @Test
    void statusLookupRequiresTheUpstreamOrderIdAndNeverFallsBackToTheCustomerOrderId() throws Exception {
        MediaProcurementClient client = new MediaProcurementClient(properties, json);
        assertThatThrownBy(() -> client.order("1", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("upstream order id");
        Select select = MediaMapper.class.getMethod("findPollCandidate", OffsetDateTime.class)
                .getAnnotation(Select.class);
        assertThat(String.join(" ", select.value())).contains("s.upstream_order_id IS NOT NULL");

        MediaRecords.Submission unknown = submission("SUBMIT_UNKNOWN", "UNKNOWN");
        when(media.findPollCandidate(any())).thenReturn(Optional.of(unknown));
        service.pollOneSubmission();
        verify(provider, never()).order(anyString(), anyString());
    }

    @Test
    void taskFilteredSubmissionListStillRequiresTheCurrentOwner() throws Exception {
        Select list = MediaMapper.class.getMethod("listSubmissions", long.class, String.class,
                Long.class, int.class, long.class).getAnnotation(Select.class);
        Select count = MediaMapper.class.getMethod("countSubmissions", long.class, String.class,
                Long.class).getAnnotation(Select.class);
        assertThat(String.join(" ", list.value())).contains("s.user_id=#{userId}", "s.task_id=#{taskId}");
        assertThat(String.join(" ", count.value())).contains("s.user_id=#{userId}", "s.task_id=#{taskId}");
    }

    @Test
    void receiptUsesArticleLinksInsteadOfAttachmentAndAcceptsSeveralHttpLinks() {
        List<String> links = MediaPublishedLinks.publicLinksFromReceipt(
                "Http://publisher.example.test/article?id=1;https://publisher.example.test/article/2#part");
        assertThat(links).containsExactly("Http://publisher.example.test/article?id=1",
                "https://publisher.example.test/article/2#part");
        assertThat(MediaPublishedLinks.fromStored(json, MediaPublishedLinks.toJson(json, links), links.getFirst()))
                .containsExactlyElementsOf(links);
        assertThatThrownBy(() -> MediaPublishedLinks.fromStored(json,
                "[\"https://publisher.example.test/a\",\"javascript:alert(1)\"]",
                "https://publisher.example.test/a"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(MediaPublishedLinks.publicLinksFromReceipt("javascript:alert(1)")).isEmpty();
        assertThat(MediaPublishedLinks.publicLinksFromReceipt("https://user@publisher.example.test/a"))
                .isEmpty();
        assertThat(MediaPublishedLinks.publicLinksFromReceipt("已刊发，详情请联系媒体")).isEmpty();
        assertThat(MediaPublishedLinks.publicLinksFromReceipt(null)).isEmpty();
        assertThat(MediaPublishedLinks.publicLinksFromReceipt(
                "https://publisher.example.test/article/1;回执待补;javascript:alert(1)"))
                .containsExactly("https://publisher.example.test/article/1");
    }

    @Test
    void resourceSyncPaginatesPersistsAndRecordsPerModelCompletion() {
        properties.setExecutionEnabled(false);
        properties.setResourceSyncEnabled(true);
        properties.setSyncPageSize(1);
        when(provider.resources("1", "", 1, 1)).thenReturn(
                new MediaProviderAdapter.ResourcePage(2, List.of(resourcePayload())));
        when(provider.resources("1", "", 2, 1)).thenReturn(
                new MediaProviderAdapter.ResourcePage(2,
                        List.of(resourcePayload("369.0000").put("id", "44991"))));
        when(media.upsertResource(any())).thenReturn(1);

        assertThat(service.synchronizeResources()).isEqualTo(2);

        verify(provider).resources("1", "", 1, 1);
        verify(provider).resources("1", "", 2, 1);
        verify(media, times(2)).upsertResource(any());
        verify(media).inactivateMissingResources(eq("MEDIA_PROCUREMENT"), eq("1"), any(), any());
        verify(media).updateSyncState(eq("MEDIA_PROCUREMENT"), eq("1"), eq("SUCCEEDED"),
                eq(null), eq(2), any());
        verify(provider, never()).submit(any());
        verify(provider, never()).order(anyString(), anyString());
    }

    @Test
    void resourceSyncInactivatesExistingUnpricedResourceAndSkipsNewUnpricedResource() {
        ObjectNode existing = resourcePayload("").put("id", 44990).put("model_id", 1).put("status", 1);
        ObjectNode newResource = resourcePayload("").put("id", 44991).put("model_id", 1).put("status", 1);
        when(provider.resources("1", "", 1, 100)).thenReturn(
                new MediaProviderAdapter.ResourcePage(2, List.of(existing, newResource)));
        when(media.findResourceByUpstream("MEDIA_PROCUREMENT", "1", "44990"))
                .thenReturn(Optional.of(resource()));
        when(media.findResourceByUpstream("MEDIA_PROCUREMENT", "1", "44991"))
                .thenReturn(Optional.empty());
        when(media.upsertResource(any())).thenReturn(1);

        assertThat(service.synchronizeResources()).isEqualTo(2);

        ArgumentCaptor<MediaRecords.Resource> captured = ArgumentCaptor.forClass(MediaRecords.Resource.class);
        verify(media).upsertResource(captured.capture());
        assertThat(captured.getValue().upstreamResourceId()).isEqualTo("44990");
        assertThat(captured.getValue().status()).isEqualTo("INACTIVE");
        assertThat(captured.getValue().upstreamPrice()).isEqualByComparingTo(resource().upstreamPrice());
        verify(media).inactivateMissingResources(eq("MEDIA_PROCUREMENT"), eq("1"), any(), any());
        verify(media).updateSyncState(eq("MEDIA_PROCUREMENT"), eq("1"), eq("SUCCEEDED"),
                eq(null), eq(2), any());
    }

    @Test
    void resourceSyncKeepsStableFailureAfterACompletedPage() {
        properties.setResourceSyncEnabled(true);
        when(provider.resources("1", "", 1, 100)).thenReturn(
                new MediaProviderAdapter.ResourcePage(101, List.of(resourcePayload())));
        when(provider.resources("1", "", 2, 100)).thenThrow(
                new MediaProviderException("MEDIA_PROVIDER_SCHEDULE_RESTRICTED", false));
        when(media.upsertResource(any())).thenReturn(1);

        assertThatThrownBy(service::synchronizeResources)
                .isInstanceOf(MediaProviderException.class)
                .hasMessage("MEDIA_PROVIDER_SCHEDULE_RESTRICTED");

        verify(media).updateSyncState(eq("MEDIA_PROCUREMENT"), eq("1"), eq("FAILED"),
                eq("MEDIA_PROVIDER_SCHEDULE_RESTRICTED"), eq(1), any());
        verify(media, never()).inactivateMissingResources(anyString(), anyString(), any(), any());
        verify(provider, never()).submit(any());
    }

    @Test
    void incompleteResourceSyncCannotMarkUnseenResourcesInactive() {
        properties.setMaxSyncPages(100);
        when(provider.resources("1", "", 1, 100)).thenReturn(
                new MediaProviderAdapter.ResourcePage(10_001, List.of(resourcePayload())));

        assertThatThrownBy(service::synchronizeResources)
                .isInstanceOf(MediaProviderException.class)
                .hasMessage("MEDIA_RESOURCE_SYNC_INCOMPLETE");

        verify(media, never()).upsertResource(any());
        verify(media, never()).inactivateMissingResources(anyString(), anyString(), any(), any());
        verify(media).updateSyncState(eq("MEDIA_PROCUREMENT"), eq("1"), eq("FAILED"),
                eq("MEDIA_RESOURCE_SYNC_INCOMPLETE"), eq(0), any());
    }

    @Test
    void defaultSyncCapacityAcceptsCatalogueLargerThanTenThousand() {
        int total = 10_001;
        when(provider.resources(eq("1"), eq(""), anyInt(), eq(100))).thenAnswer(invocation -> {
            int page = invocation.getArgument(2);
            var entries = java.util.stream.IntStream.range((page - 1) * 100, Math.min(page * 100, total))
                    .mapToObj(index -> (com.fasterxml.jackson.databind.JsonNode)
                            resourcePayload().put("id", Integer.toString(index + 1))).toList();
            return new MediaProviderAdapter.ResourcePage(total, entries);
        });
        when(media.upsertResource(any())).thenReturn(1);
        assertThat(service.synchronizeResources()).isEqualTo(total);
        verify(provider).resources("1", "", 101, 100);
        verify(media).updateSyncState(eq("MEDIA_PROCUREMENT"), eq("1"), eq("SUCCEEDED"),
                eq(null), eq(total), any());
    }

    @Test
    void shortOrDuplicatedPagesCannotSweepLocalSnapshot() {
        properties.setSyncPageSize(1);
        when(provider.resources("1", "", 1, 1)).thenReturn(
                new MediaProviderAdapter.ResourcePage(2, List.of(resourcePayload())));
        when(provider.resources("1", "", 2, 1)).thenReturn(
                new MediaProviderAdapter.ResourcePage(2, List.of()));
        when(media.upsertResource(any())).thenReturn(1);

        assertThatThrownBy(service::synchronizeResources)
                .isInstanceOf(MediaProviderException.class)
                .hasMessage("MEDIA_RESOURCE_SYNC_INCOMPLETE");
        verify(media, never()).inactivateMissingResources(anyString(), anyString(), any(), any());

        when(provider.resources("1", "", 2, 1)).thenReturn(
                new MediaProviderAdapter.ResourcePage(2, List.of(resourcePayload())));
        assertThatThrownBy(service::synchronizeResources)
                .isInstanceOf(MediaProviderException.class)
                .hasMessage("MEDIA_RESOURCE_SYNC_INCOMPLETE");
        verify(media, never()).inactivateMissingResources(anyString(), anyString(), any(), any());
    }

    @Test
    void changingProviderTotalCannotSweepLocalSnapshot() {
        properties.setSyncPageSize(1);
        when(provider.resources("1", "", 1, 1)).thenReturn(
                new MediaProviderAdapter.ResourcePage(2, List.of(resourcePayload())));
        when(provider.resources("1", "", 2, 1)).thenReturn(
                new MediaProviderAdapter.ResourcePage(3,
                        List.of(resourcePayload("369.0000").put("id", "44991"))));
        when(media.upsertResource(any())).thenReturn(1);

        assertThatThrownBy(service::synchronizeResources)
                .isInstanceOf(MediaProviderException.class)
                .hasMessage("MEDIA_PROVIDER_RESPONSE_INVALID");
        verify(media, never()).inactivateMissingResources(anyString(), anyString(), any(), any());
    }

    @Test
    void olderSyncPageLosingToResourceCallbackIsNotTreatedAsFailure() {
        when(provider.resources("1", "", 1, 100)).thenReturn(
                new MediaProviderAdapter.ResourcePage(1, List.of(resourcePayload())));
        when(media.upsertResource(any())).thenReturn(0);

        assertThat(service.synchronizeResources()).isEqualTo(1);

        verify(media).inactivateMissingResources(eq("MEDIA_PROCUREMENT"), eq("1"), any(), any());
        verify(media).updateSyncState(eq("MEDIA_PROCUREMENT"), eq("1"), eq("SUCCEEDED"),
                eq(null), eq(1), any());
    }

    @Test
    void unchangedResourceUpsertPreservesBusinessVersion() throws Exception {
        Insert insert = MediaMapper.class.getMethod("upsertResource", MediaRecords.Resource.class)
                .getAnnotation(Insert.class);
        String sql = String.join(" ", insert.value()).replaceAll("\\s+", " ");

        assertThat(sql).contains("IS DISTINCT FROM", "THEN business.media_resource.version+1",
                "ELSE business.media_resource.version END",
                "WHERE GREATEST(business.media_resource.last_seen_at,business.media_resource.synced_at)"
                        + " < EXCLUDED.last_seen_at");
        assertThat(sql).doesNotContain("version=business.media_resource.version+1,");
    }

    @Test
    void customerResourceStatusFiltersUseTheSameFreshnessAsDisplayedStatus() throws Exception {
        Select list = MediaMapper.class.getMethod("listResources", String.class, String.class,
                String.class, String.class, Integer.class, Integer.class, OffsetDateTime.class,
                String.class, String.class, int.class, long.class).getAnnotation(Select.class);
        Select count = MediaMapper.class.getMethod("countResources", String.class, String.class,
                String.class, String.class, Integer.class, Integer.class, OffsetDateTime.class,
                String.class, String.class)
                .getAnnotation(Select.class);
        for (Select query : List.of(list, count)) {
            String sql = String.join(" ", query.value());
            assertThat(sql).contains("r.status='ACTIVE' AND r.synced_at&gt;=#{freshAfter}",
                    "r.status='INACTIVE' OR r.synced_at&lt;#{freshAfter}");
        }
    }

    @Test
    void quoteUsesLocalSnapshotWithoutProviderAndConfirmationRejectsResourceChange() {
        AtomicReference<TaskRecords.Draft> persisted = new AtomicReference<>();
        AtomicReference<MediaRecords.Resource> currentResource = new AtomicReference<>(resource());
        when(tasks.findDraftByIdempotency(eq(41L), eq("MEDIA_QUOTE_CREATE"), any(UUID.class)))
                .thenAnswer(ignored -> Optional.ofNullable(persisted.get()));
        when(articles.find(6_001, 41)).thenReturn(Optional.of(article()));
        when(articles.findWorkflow(6_001, 41)).thenReturn(Optional.of(readyWorkflow()));
        when(articles.findSnapshot(6_002, 6_001, 41)).thenReturn(Optional.of(snapshot()));
        when(media.findResource(7_001)).thenAnswer(ignored -> Optional.of(currentResource.get()));
        when(media.lockResourceForConfirmation(7_001))
                .thenAnswer(ignored -> Optional.of(currentResource.get()));
        when(media.insertPrice(any())).thenReturn(1);
        when(tasks.insertDraft(any())).thenAnswer(invocation -> {
            persisted.set(invocation.getArgument(0));
            return 1;
        });
        when(tasks.insertConfirmation(any())).thenReturn(1);
        MediaDtos.QuoteRequest request = new MediaDtos.QuoteRequest("6001", "6002",
                List.of(new MediaDtos.QuoteItem("7001", " 工作日发布 ")));
        String idempotencyKey = "00000000-0000-0000-0000-000000000013";

        TaskDtos.DraftResponse created = service.quote(user(), idempotencyKey, request);
        TaskDtos.DraftResponse replay = service.quote(user(), idempotencyKey, request);

        assertThat(created.taskType()).isEqualTo("MEDIA");
        assertThat(created.quotedPoints()).isEqualTo(49_700);
        assertThat(created.parameters().toString()).doesNotContain(
                "upstreamPrice", "markupRate", "providerCode", "upstreamResourceId");
        assertThat(replay.id()).isEqualTo(created.id());
        assertThat(replay.confirmationToken()).isEqualTo(created.confirmationToken());
        verifyNoInteractions(provider);
        verify(media, never()).upsertResource(any());

        currentResource.set(new MediaRecords.Resource(7_001, "MEDIA_PROCUREMENT", "1", "44990",
                "媒体名称", "ACTIVE", "NEWS", "综合", "全国", 3, "{}",
                new BigDecimal("368.0000"), "{}", now(), currentResource.get().syncedAt(), 2));
        assertThatThrownBy(() -> service.revalidateForConfirmation(persisted.get()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.MEDIA_QUOTE_CHANGED));
        verifyNoInteractions(provider);
    }

    @Test
    void imageAssetsAndBodyPlaceholdersAreRejectedBeforeDraftOrPointsReservation() {
        when(articles.find(6_001, 41)).thenReturn(Optional.of(article()));
        when(articles.findWorkflow(6_001, 41)).thenReturn(Optional.of(readyWorkflow()));
        MediaDtos.QuoteRequest request = new MediaDtos.QuoteRequest("6001", "6002",
                List.of(new MediaDtos.QuoteItem("7001", null)));
        when(articles.findSnapshot(6_002, 6_001, 41)).thenReturn(Optional.of(
                snapshotWith("<p>正文</p>", "[{\"assetId\":\"8001\"}]")));

        assertThatThrownBy(() -> service.quote(user(),
                "00000000-0000-0000-0000-000000000021", request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.MEDIA_IMAGE_UNSUPPORTED));

        when(articles.findSnapshot(6_002, 6_001, 41)).thenReturn(Optional.of(
                snapshotWith("<figure data-asset-id=\"8001\">配图</figure>", "[]")));
        assertThatThrownBy(() -> service.quote(user(),
                "00000000-0000-0000-0000-000000000022", request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.MEDIA_IMAGE_UNSUPPORTED));
        verify(tasks, never()).insertDraft(any());
        verifyNoInteractions(provider);
    }

    @Test
    void mediaWithoutExplicitLinkSupportCannotQuoteAnArticleWithExternalLinks() {
        when(articles.find(6_001, 41)).thenReturn(Optional.of(article()));
        when(articles.findWorkflow(6_001, 41)).thenReturn(Optional.of(readyWorkflow()));
        when(articles.findSnapshot(6_002, 6_001, 41)).thenReturn(Optional.of(
                snapshotWith("<p>正文 <a href=\"https://brand.example/product\">商品</a></p>", "[]")));
        MediaRecords.Resource base = resource();
        when(media.findResource(7_001)).thenReturn(Optional.of(new MediaRecords.Resource(
                base.id(), base.providerCode(), base.upstreamModelId(), base.upstreamResourceId(),
                base.name(), base.status(), base.resourceType(), base.channelCategory(), base.region(),
                base.estimatedDays(), "{\"linkPolicy\":\"不可带网址\"}", base.upstreamPrice(),
                base.rawJson(), base.lastSeenAt(), base.syncedAt(), base.version())));

        assertThatThrownBy(() -> service.quote(user(),
                "00000000-0000-0000-0000-000000000023",
                new MediaDtos.QuoteRequest("6001", "6002", List.of(new MediaDtos.QuoteItem("7001", null)))))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.MEDIA_LINK_UNSUPPORTED));
        verify(tasks, never()).insertDraft(any());
        verifyNoInteractions(provider);
    }

    @Test
    void confirmedBatchIncludesNumericExecutionAttemptWithoutSubmittingToProvider() throws Exception {
        AtomicReference<TaskRecords.Draft> quoted = new AtomicReference<>();
        when(articles.find(6_001, 41)).thenReturn(Optional.of(article()));
        when(articles.findWorkflow(6_001, 41)).thenReturn(Optional.of(readyWorkflow()));
        when(articles.findSnapshot(6_002, 6_001, 41)).thenReturn(Optional.of(snapshot()));
        when(media.findResource(7_001)).thenReturn(Optional.of(resource()));
        when(media.insertPrice(any())).thenReturn(1);
        when(tasks.insertDraft(any())).thenAnswer(invocation -> {
            quoted.set(invocation.getArgument(0));
            return 1;
        });
        when(tasks.insertConfirmation(any())).thenReturn(1);
        service.quote(user(), "00000000-0000-0000-0000-000000000025",
                new MediaDtos.QuoteRequest("6001", "6002", List.of(new MediaDtos.QuoteItem("7001", null))));

        TaskRecords.Draft draft = quoted.get();
        TaskRecords.Task task = new TaskRecords.Task(9_903, 41, 5_001, null, null, draft.id(),
                "MEDIA", "QUEUED", "QUEUED", 1, draft.inputSnapshotJson(), draft.inputHash(),
                draft.pricingVersionId(), draft.quotedPoints(), 0, "{}", null, "TASK_CONFIRM",
                UUID.fromString("00000000-0000-0000-0000-000000000026"), "a".repeat(64),
                now(), null, null, now(), now(), null, null);
        when(media.insertBatch(any())).thenReturn(1);
        when(media.insertSubmission(any())).thenReturn(1);
        when(media.insertLocalOutbox(anyLong(), anyLong(), anyLong(), anyString(), any(), any()))
                .thenReturn(1);

        service.createConfirmedBatch(task, draft, now());

        ArgumentCaptor<MediaRecords.Batch> batch = ArgumentCaptor.forClass(MediaRecords.Batch.class);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(media).insertBatch(batch.capture());
        verify(media).insertSubmission(any());
        verify(media).insertLocalOutbox(anyLong(), eq(41L), eq(task.id()), payload.capture(), any(), eq(now()));
        assertThat(json.readTree(payload.getValue())).isEqualTo(json.createObjectNode()
                .put("taskId", Long.toString(task.id()))
                .put("batchId", Long.toString(batch.getValue().id())).put("attempt", 1));
        verifyNoInteractions(provider);
    }

    @Test
    void confirmationRechecksTextOnlyAndLinkPolicyBeforePointsCanBeFrozen() {
        AtomicReference<TaskRecords.Draft> draft = new AtomicReference<>();
        AtomicReference<ArticleRecords.SubmissionSnapshot> currentSnapshot = new AtomicReference<>(snapshot());
        when(articles.find(6_001, 41)).thenReturn(Optional.of(article()));
        when(articles.findWorkflow(6_001, 41)).thenReturn(Optional.of(readyWorkflow()));
        when(articles.findSnapshot(6_002, 6_001, 41))
                .thenAnswer(ignored -> Optional.of(currentSnapshot.get()));
        when(media.findResource(7_001)).thenReturn(Optional.of(resource()));
        when(media.insertPrice(any())).thenReturn(1);
        when(tasks.insertDraft(any())).thenAnswer(invocation -> {
            draft.set(invocation.getArgument(0));
            return 1;
        });
        when(tasks.insertConfirmation(any())).thenReturn(1);
        service.quote(user(), "00000000-0000-0000-0000-000000000024",
                new MediaDtos.QuoteRequest("6001", "6002", List.of(new MediaDtos.QuoteItem("7001", null))));

        currentSnapshot.set(snapshotWith("<figure data-asset-id=\"8001\">配图</figure>", "[]"));
        assertThatThrownBy(() -> service.revalidateForConfirmation(draft.get()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.MEDIA_IMAGE_UNSUPPORTED));

        currentSnapshot.set(snapshotWith("<p>https://brand.example/product</p>", "[]"));
        MediaRecords.Resource base = resource();
        when(media.lockResourceForConfirmation(7_001)).thenReturn(Optional.of(new MediaRecords.Resource(
                base.id(), base.providerCode(), base.upstreamModelId(), base.upstreamResourceId(),
                base.name(), base.status(), base.resourceType(), base.channelCategory(), base.region(),
                base.estimatedDays(), "{\"linkPolicy\":\"不可带网址\"}", base.upstreamPrice(),
                base.rawJson(), base.lastSeenAt(), base.syncedAt(), base.version())));
        assertThatThrownBy(() -> service.revalidateForConfirmation(draft.get()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.MEDIA_LINK_UNSUPPORTED));
        verifyNoInteractions(provider);
    }

    @Test
    void staleOrWrongProviderSnapshotCannotBeQuoted() {
        when(articles.find(6_001, 41)).thenReturn(Optional.of(article()));
        when(articles.findWorkflow(6_001, 41)).thenReturn(Optional.of(readyWorkflow()));
        when(articles.findSnapshot(6_002, 6_001, 41)).thenReturn(Optional.of(snapshot()));
        MediaDtos.QuoteRequest request = new MediaDtos.QuoteRequest("6001", "6002",
                List.of(new MediaDtos.QuoteItem("7001", null)));
        MediaRecords.Resource fresh = resource();
        MediaRecords.Resource stale = new MediaRecords.Resource(fresh.id(), fresh.providerCode(),
                fresh.upstreamModelId(), fresh.upstreamResourceId(), fresh.name(), fresh.status(),
                fresh.resourceType(), fresh.channelCategory(), fresh.region(), fresh.estimatedDays(),
                fresh.capabilitiesJson(), fresh.upstreamPrice(), fresh.rawJson(), fresh.lastSeenAt(),
                OffsetDateTime.now(ZoneOffset.UTC).minusHours(37), fresh.version());
        when(media.findResource(7_001)).thenReturn(Optional.of(stale));

        assertThatThrownBy(() -> service.quote(user(),
                "00000000-0000-0000-0000-000000000015", request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.MEDIA_QUOTE_CHANGED));

        MediaRecords.Resource wrongProvider = new MediaRecords.Resource(fresh.id(), "OTHER_PROVIDER",
                fresh.upstreamModelId(), fresh.upstreamResourceId(), fresh.name(), fresh.status(),
                fresh.resourceType(), fresh.channelCategory(), fresh.region(), fresh.estimatedDays(),
                fresh.capabilitiesJson(), fresh.upstreamPrice(), fresh.rawJson(), fresh.lastSeenAt(),
                fresh.syncedAt(), fresh.version());
        when(media.findResource(7_001)).thenReturn(Optional.of(wrongProvider));
        assertThatThrownBy(() -> service.quote(user(),
                "00000000-0000-0000-0000-000000000016", request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.MEDIA_QUOTE_CHANGED));
        verifyNoInteractions(provider);
    }

    @Test
    void recommendationsRequireCompletedBodyAndPreferConfirmedTargetPlatform() {
        MediaRecords.Resource fallback = new MediaRecords.Resource(7_002, "MEDIA_PROCUREMENT", "1", "44991",
                "综合媒体", "ACTIVE", "NEWS", "综合", "全国", 1, "{}",
                new BigDecimal("10.0000"), "{}", now(), OffsetDateTime.now(ZoneOffset.UTC), 1);
        when(articles.find(6_001, 41)).thenReturn(Optional.of(article()));
        when(articles.findWorkflow(6_001, 41)).thenReturn(Optional.of(readyWorkflow()));
        when(articles.findPlanRevision(6_001, 41, 2)).thenReturn(Optional.of(
                new ArticleRecords.CreativePlanRevision(6_003, 6_001, 41, 5_001, 2, 1,
                        "{\"targetPlatform\":\"小红书\",\"coreMessage\":\"咖啡消费场景\"}",
                        "EDITED", null, now(), now(), now())));
        when(articles.findSubmissionPreference(6_001, 41)).thenReturn(Optional.of(
                new ArticleRecords.SubmissionPreference(6_001, 41, 5_001, 1,
                        "INDUSTRY_MATCH", "全国", 60_000, false, now(), now())));
        MediaRecords.Resource matched = new MediaRecords.Resource(7_001, "MEDIA_PROCUREMENT", "1", "44990",
                "餐饮观察", "ACTIVE", "NEWS", "食品行业", "全国", 3, "{}",
                new BigDecimal("368.0000"), "{}", now(), OffsetDateTime.now(ZoneOffset.UTC), 1);
        when(media.listRecommendationCandidates(any(), any(), any(), eq(20_000)))
                .thenReturn(List.of(fallback, matched));

        MediaDtos.RecommendationSet result = service.recommendations(user(), 6_001, 2);

        assertThat(result.articleId()).isEqualTo("6001");
        assertThat(result.items()).extracting(MediaDtos.Recommendation::mediaResourceId)
                .containsExactly("7001", "7002");
        assertThat(result.items().getFirst().reasonCode()).isEqualTo("INDUSTRY_CATEGORY_MATCH");
        assertThat(result.items().getFirst().evidence()).isNotEmpty();
        assertThat(result.strategy().detectedCategories()).contains("食品餐饮");
        assertThat(result.items().getFirst().resource().chargedPoints()).isEqualTo(49_700);
    }

    @Test
    void linkRequirementRejectsAnExplicitNoLinkPolicy() {
        when(articles.find(6_001, 41)).thenReturn(Optional.of(article()));
        when(articles.findWorkflow(6_001, 41)).thenReturn(Optional.of(readyWorkflow()));
        when(articles.findSubmissionPreference(6_001, 41)).thenReturn(Optional.of(
                new ArticleRecords.SubmissionPreference(6_001, 41, 5_001, 1,
                        "BALANCED", null, null, true, now(), now())));
        MediaRecords.Resource noLink = new MediaRecords.Resource(7_001, "MEDIA_PROCUREMENT", "1", "44990",
                "不可带链媒体", "ACTIVE", "NEWS", "食品行业", "全国", 3,
                "{\"linkPolicy\":\"不可带网址\"}", new BigDecimal("10.0000"), "{}", now(),
                OffsetDateTime.now(ZoneOffset.UTC), 1);
        MediaRecords.Resource supportsLink = new MediaRecords.Resource(7_002, "MEDIA_PROCUREMENT", "1", "44991",
                "可带链媒体", "ACTIVE", "NEWS", "食品行业", "全国", 3,
                "{\"linkPolicy\":\"可带网址\"}", new BigDecimal("10.0000"), "{}", now(),
                OffsetDateTime.now(ZoneOffset.UTC), 1);
        when(media.listRecommendationCandidates(any(), any(), any(), eq(20_000)))
                .thenReturn(List.of(noLink, supportsLink));

        MediaDtos.RecommendationSet result = service.recommendations(user(), 6_001, 5);

        assertThat(result.items()).extracting(MediaDtos.Recommendation::mediaResourceId)
                .containsExactly("7002");
    }

    @Test
    void linkedArticleSnapshotExcludesMediaThatCannotCarryLinksEvenWithoutLinkPreference() {
        when(articles.find(6_001, 41)).thenReturn(Optional.of(article()));
        when(articles.findWorkflow(6_001, 41)).thenReturn(Optional.of(readyWorkflow()));
        when(articles.findSubmissionPreference(6_001, 41)).thenReturn(Optional.of(
                new ArticleRecords.SubmissionPreference(6_001, 41, 5_001, 1,
                        "BALANCED", null, null, false, now(), now())));
        when(articles.findSnapshotByVersion(6_001, 1, 41)).thenReturn(Optional.of(
                snapshotWith("<p>详见 <a href=\"https://brand.example/product\">商品页</a></p>", "[]")));
        MediaRecords.Resource base = resource();
        MediaRecords.Resource noLink = new MediaRecords.Resource(base.id(), base.providerCode(),
                base.upstreamModelId(), base.upstreamResourceId(), base.name(), base.status(),
                base.resourceType(), base.channelCategory(), base.region(), base.estimatedDays(),
                "{\"linkPolicy\":\"不可带网址\"}", base.upstreamPrice(), base.rawJson(),
                base.lastSeenAt(), base.syncedAt(), base.version());
        MediaRecords.Resource withLink = new MediaRecords.Resource(7_002, base.providerCode(),
                base.upstreamModelId(), "44991", "可带链媒体", base.status(), base.resourceType(),
                base.channelCategory(), base.region(), base.estimatedDays(),
                "{\"linkPolicy\":\"可带网址\"}", base.upstreamPrice(), base.rawJson(),
                base.lastSeenAt(), base.syncedAt(), base.version());
        when(media.listRecommendationCandidates(any(), any(), any(), eq(20_000)))
                .thenReturn(List.of(noLink, withLink));

        MediaDtos.RecommendationSet result = service.recommendations(user(), 6_001, 5);

        assertThat(result.items()).extracting(MediaDtos.Recommendation::mediaResourceId)
                .containsExactly("7002");
        assertThat(result.strategy().requiresLink()).isTrue();
        assertThat(result.strategy().dataLimitations()).anyMatch(text -> text.contains("正文包含外链"));
        verify(provider, never()).resources(anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    void recommendationsBeforeSnapshotCreationUseCurrentArticleBodyForLinkSafety() {
        ArticleRecords.Article base = article();
        when(articles.find(6_001, 41)).thenReturn(Optional.of(new ArticleRecords.Article(
                base.id(), base.userId(), base.spaceId(), base.productId(), base.title(),
                "<p>https://brand.example/product</p>", base.bodyText(), base.outlineJson(),
                base.status(), base.sourceTaskId(), base.version(), base.createdAt(), base.updatedAt())));
        when(articles.findWorkflow(6_001, 41)).thenReturn(Optional.of(readyWorkflow()));
        when(articles.findSubmissionPreference(6_001, 41)).thenReturn(Optional.of(
                new ArticleRecords.SubmissionPreference(6_001, 41, 5_001, 1,
                        "BALANCED", null, null, false, now(), now())));
        MediaRecords.Resource baseResource = resource();
        when(media.listRecommendationCandidates(any(), any(), any(), eq(20_000)))
                .thenReturn(List.of(baseResource));

        MediaDtos.RecommendationSet result = service.recommendations(user(), 6_001, 5);

        assertThat(result.items()).isEmpty();
        verify(articles).findSnapshotByVersion(6_001, 1, 41);
        verifyNoInteractions(provider);
    }

    @Test
    void bodyReadyPreparationRecommendsActualMediaWithoutPublicationOrPreferenceWrites() {
        when(articles.find(6_001,41)).thenReturn(Optional.of(article()));
        when(articles.findWorkflow(6_001,41)).thenReturn(Optional.of(new ArticleRecords.Workflow(
                6_001,41,"BODY_READY",1,"标题","AUTO_POLICY",1,1,null,2,2)));
        when(media.listRecommendationCandidates(any(),any(),any(),eq(20_000))).thenReturn(List.of(resource()));
        var result=service.preparationRecommendations(user(),6_001,5);
        assertThat(result.submissionEnabled()).isFalse();
        assertThat(result.items()).extracting(MediaDtos.Recommendation::mediaResourceId).containsExactly("7001");
        assertThat(result.items().getFirst().resource().chargedPoints()).isPositive();
        verify(articles,never()).confirmBody(anyLong(),anyLong(),anyInt(),any());
        verify(articles,never()).upsertSubmissionPreference(any());
        verify(articles,never()).insertSnapshot(any());
        verifyNoInteractions(provider,tasks);
    }

    @Test
    void incompleteArticleCannotBeRecommendedOrQuoted() {
        ArticleRecords.Article incomplete = new ArticleRecords.Article(6_001, 41, 5_001, null,
                "标题", "", "", "[]", "DRAFT", null, 1, now(), now());
        ArticleRecords.Workflow workflow = new ArticleRecords.Workflow(6_001, 41,
                "BODY_READY_TO_GENERATE", 1, "标题", "SELECTED", 1, 1, now(), 2, 2);
        when(articles.find(6_001, 41)).thenReturn(Optional.of(incomplete));
        when(articles.findWorkflow(6_001, 41)).thenReturn(Optional.of(workflow));

        assertThatThrownBy(() -> service.recommendations(user(), 6_001, 5))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.TASK_STATE_CONFLICT));
        assertThatThrownBy(() -> service.preparationRecommendations(user(), 6_001, 5))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.TASK_STATE_CONFLICT));
        MediaDtos.QuoteRequest request = new MediaDtos.QuoteRequest("6001", "6002",
                List.of(new MediaDtos.QuoteItem("7001", null)));
        assertThatThrownBy(() -> service.quote(user(),
                "00000000-0000-0000-0000-000000000014", request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.TASK_STATE_CONFLICT));
        verify(provider, never()).resources(anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    void uncertainSubmitIsPersistedForStatusOnlyRecoveryAndIsNotBlindlyResubmitted() {
        MediaRecords.Submission queued = submission("QUEUED", "NOT_SUBMITTED");
        MediaRecords.Submission claimed = submission("SUBMITTING", "NOT_SUBMITTED");
        when(media.claimLocalOutbox(any(), any())).thenReturn(Optional.of(
                new MediaRecords.LocalOutbox(8_001, 41, queued.taskId(), "{}", null, 1, now())));
        when(media.findByTask(queued.taskId())).thenReturn(List.of(queued));
        when(media.claimSubmission(eq(queued.id()), any())).thenReturn(1);
        when(media.lockSubmission(queued.id())).thenReturn(Optional.of(claimed));
        when(articles.findSnapshot(claimed.articleSnapshotId(), claimed.articleId(), claimed.userId()))
                .thenReturn(Optional.of(snapshot()));
        when(provider.submit(any())).thenThrow(new MediaProviderException("MEDIA_SUBMISSION_UNKNOWN", true));
        when(media.markUnknown(eq(claimed.id()), eq("MEDIA_SUBMISSION_UNKNOWN"), eq(null), any())).thenReturn(1);
        when(media.nextEventSequence(claimed.id())).thenReturn(2L);
        when(media.insertEvent(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any(),
                anyString(), any(), anyString(), anyString(), any(), any())).thenReturn(1);
        when(media.finishLocalOutbox(anyLong(), anyInt(), anyString(), any(), any(), any())).thenReturn(1);

        service.processOneOutbox();

        verify(provider).submit(any());
        verify(provider, never()).order(anyString(), anyString());
        verify(media).markUnknown(eq(claimed.id()), eq("MEDIA_SUBMISSION_UNKNOWN"), eq(null), any());
        verify(media).finishLocalOutbox(eq(8_001L), eq(1), eq("PUBLISHED"), any(), any(), any());
    }

    @Test
    void earlyNumericArrangedProgressIsHydratedFromTheSubmitResponse() {
        assertEarlyProgressHydratesOrder(1, "ACCEPTED", "1001");
    }

    @Test
    void earlyNumericPendingProgressIsHydratedForReconciliation() {
        assertEarlyProgressHydratesOrder(0, "RECONCILING", "1000");
    }

    @Test
    void uncertainSubmitFailureAfterEarlyProgressIsVisibleForReviewWithoutReleasingPoints() {
        assertEarlyProgressFailureNeedsReview(1, "ACCEPTED", true);
    }

    @Test
    void definiteSubmitFailureAfterEarlyProgressIsVisibleForReviewWithoutReleasingPoints() {
        assertEarlyProgressFailureNeedsReview(0, "RECONCILING", false);
    }

    private void assertEarlyProgressHydratesOrder(int progress, String callbackStatus, String upstreamStatus) {
        MediaRecords.Submission queued = submission("QUEUED", "NOT_SUBMITTED");
        MediaRecords.Submission claimed = submission("SUBMITTING", "NOT_SUBMITTED");
        MediaRecords.Submission afterCallback = withOrderState(claimed, callbackStatus, "CONFIRMED", null);
        arrangeSubmissionOutbox(queued, claimed, afterCallback);
        when(media.insertCallback(anyLong(), anyString(), anyString(), any(), any(), anyString(),
                anyString(), any())).thenReturn(1);
        when(media.lockByCustomerOrder(claimed.customerOrderId())).thenReturn(Optional.of(claimed));
        when(media.transition(anyLong(), anyString(), any(), anyString(), anyString(),
                any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(media.hydrateSubmittedOrder(eq(claimed.id()), eq("provider-order"), eq("provider-number"),
                any(OffsetDateTime.class), any(OffsetDateTime.class))).thenReturn(1);
        when(provider.submit(any())).thenAnswer(ignored -> {
            ObjectNode callback = json.createObjectNode().put("customer_order_id", claimed.customerOrderId())
                    .put("order_progress", progress);
            assertThat(service.processCallback("PROGRESS", callback)).isTrue();
            return new MediaProviderAdapter.SubmitResult("provider-order", "provider-number",
                    json.createObjectNode());
        });

        service.processOneOutbox();

        verify(media).transition(eq(claimed.id()), eq("SUBMITTING"), eq(upstreamStatus),
                eq(callbackStatus), eq("CONFIRMED"), eq(null), eq(null), eq(null), any(), eq(null), any());
        verify(media).hydrateSubmittedOrder(eq(claimed.id()), eq("provider-order"),
                eq("provider-number"), any(OffsetDateTime.class), any(OffsetDateTime.class));
        verify(media).finishLocalOutbox(eq(8_001L), eq(1), eq("PUBLISHED"), any(), any(), any());
        verify(tasks, never()).settlePoints(anyLong(), anyInt(), any());
        verify(tasks, never()).releasePoints(anyLong(), anyInt(), any());
    }

    private void assertEarlyProgressFailureNeedsReview(int progress, String callbackStatus,
                                                       boolean uncertain) {
        MediaRecords.Submission queued = submission("QUEUED", "NOT_SUBMITTED");
        MediaRecords.Submission claimed = submission("SUBMITTING", "NOT_SUBMITTED");
        arrangeSubmissionOutbox(queued, claimed,
                withOrderState(claimed, callbackStatus, "CONFIRMED", null));
        when(media.insertCallback(anyLong(), anyString(), anyString(), any(), any(), anyString(),
                anyString(), any())).thenReturn(1);
        when(media.lockByCustomerOrder(claimed.customerOrderId())).thenReturn(Optional.of(claimed));
        when(media.transition(anyLong(), anyString(), any(), anyString(), anyString(),
                any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(provider.submit(any())).thenAnswer(ignored -> {
            ObjectNode callback = json.createObjectNode().put("customer_order_id", claimed.customerOrderId())
                    .put("order_progress", progress);
            assertThat(service.processCallback("PROGRESS", callback)).isTrue();
            throw new MediaProviderException(uncertain ? "MEDIA_SUBMISSION_UNKNOWN" : "MEDIA_PROVIDER_REJECTED",
                    uncertain);
        });

        service.processOneOutbox();

        verify(media).transition(eq(claimed.id()), eq(callbackStatus), any(), eq("RECONCILING"),
                eq("CONFIRMED"), eq(null), eq(null), eq(null), eq("MEDIA_SUBMISSION_UNKNOWN"),
                eq(null), any());
        verify(media, times(progress == 0 ? 2 : 1))
                .updateTaskStage(eq(claimed.taskId()), eq("MEDIA_RECONCILING"), any());
        verify(media).finishLocalOutbox(eq(8_001L), eq(1), eq("PUBLISHED"), any(), any(), any());
        verify(tasks, never()).settlePoints(anyLong(), anyInt(), any());
        verify(tasks, never()).releasePoints(anyLong(), anyInt(), any());
    }

    @Test
    void submitResponseCannotReplaceAConflictingCallbackOrderId() throws Exception {
        MediaRecords.Submission queued = submission("QUEUED", "NOT_SUBMITTED");
        MediaRecords.Submission claimed = submission("SUBMITTING", "NOT_SUBMITTED");
        arrangeSubmissionOutbox(queued, claimed,
                withOrderState(claimed, "ACCEPTED", "CONFIRMED", "callback-order"));
        when(provider.submit(any())).thenReturn(new MediaProviderAdapter.SubmitResult(
                "different-order", "provider-number", json.createObjectNode()));
        when(media.transition(anyLong(), anyString(), any(), anyString(), anyString(),
                any(), any(), any(), any(), any(), any())).thenReturn(1);

        service.processOneOutbox();

        verify(media, never()).hydrateSubmittedOrder(anyLong(), any(), any(), any(), any());
        verify(media).transition(eq(claimed.id()), eq("ACCEPTED"), any(), eq("RECONCILING"),
                eq("CONFIRMED"), eq("callback-order"), eq(null), eq(null),
                eq("MEDIA_SUBMISSION_ORDER_CONFLICT"), eq(null), any());
        verify(media).finishLocalOutbox(eq(8_001L), eq(1), eq("PUBLISHED"), any(), any(), any());
        verify(tasks, never()).settlePoints(anyLong(), anyInt(), any());
        verify(tasks, never()).releasePoints(anyLong(), anyInt(), any());
        Update update = MediaMapper.class.getMethod("hydrateSubmittedOrder", long.class, String.class,
                String.class, OffsetDateTime.class, OffsetDateTime.class).getAnnotation(Update.class);
        assertThat(String.join(" ", update.value()))
                .contains("status IN ('ACCEPTED','RECONCILING')", "submission_state='CONFIRMED'",
                        "upstream_order_id IS NULL OR upstream_order_id=#{orderId}",
                        "next_poll_at=COALESCE(next_poll_at,#{nextPoll})");
    }

    @Test
    void disputedOrderRejectsLaterReceiptRefundAndProgressWithoutSettlingPoints() {
        MediaRecords.Submission disputed = withOrderState(submission("ACCEPTED", "CONFIRMED"),
                "RECONCILING", "CONFIRMED", "callback-order", "MEDIA_SUBMISSION_ORDER_CONFLICT");
        when(media.insertCallback(anyLong(), anyString(), anyString(), any(), any(), anyString(),
                anyString(), any())).thenReturn(1);
        when(media.lockByCustomerOrder(disputed.customerOrderId())).thenReturn(Optional.of(disputed));
        ObjectNode receipt = json.createObjectNode().put("customer_order_id", disputed.customerOrderId())
                .put("resource_order_id", "callback-order")
                .put("return_info", "https://publisher.example.test/article/1");
        ObjectNode refund = json.createObjectNode().put("customer_order_id", disputed.customerOrderId())
                .put("resource_order_id", "callback-order").put("type", 1);
        ObjectNode progress = json.createObjectNode().put("customer_order_id", disputed.customerOrderId())
                .put("resource_order_id", "callback-order").put("order_progress", 1);

        for (Map.Entry<String, ObjectNode> callback : Map.of(
                "RECEIPT", receipt, "REFUND", refund, "PROGRESS", progress).entrySet()) {
            assertThatThrownBy(() -> service.processCallback(callback.getKey(), callback.getValue()))
                    .isInstanceOfSatisfying(BusinessException.class,
                            exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.TASK_STATE_CONFLICT));
        }
        verify(media, never()).transition(anyLong(), anyString(), anyString(), anyString(), anyString(),
                any(), any(), any(), any(), any(), any());
        verify(tasks, never()).settlePoints(anyLong(), anyInt(), any());
        verify(tasks, never()).releasePoints(anyLong(), anyInt(), any());
    }

    @Test
    void submitResponseCannotReopenAnAlreadyTerminalCallbackOrder() {
        MediaRecords.Submission queued = submission("QUEUED", "NOT_SUBMITTED");
        MediaRecords.Submission claimed = submission("SUBMITTING", "NOT_SUBMITTED");
        arrangeSubmissionOutbox(queued, claimed, submission("PUBLISHED", "CONFIRMED"));
        when(provider.submit(any())).thenReturn(new MediaProviderAdapter.SubmitResult(
                "different-order", "provider-number", json.createObjectNode()));

        service.processOneOutbox();

        verify(media, never()).hydrateSubmittedOrder(anyLong(), any(), any(), any(), any());
        verify(media).finishLocalOutbox(eq(8_001L), eq(1), eq("PUBLISHED"), any(), any(), any());
        verify(tasks, never()).settlePoints(anyLong(), anyInt(), any());
        verify(tasks, never()).releasePoints(anyLong(), anyInt(), any());
    }

    private void arrangeSubmissionOutbox(MediaRecords.Submission queued, MediaRecords.Submission claimed,
                                         MediaRecords.Submission afterSubmit) {
        when(media.claimLocalOutbox(any(), any())).thenReturn(Optional.of(
                new MediaRecords.LocalOutbox(8_001, 41, queued.taskId(), "{}", null, 1, now())));
        when(media.findByTask(queued.taskId())).thenReturn(List.of(queued));
        when(media.claimSubmission(eq(queued.id()), any())).thenReturn(1);
        when(media.lockSubmission(queued.id()))
                .thenReturn(Optional.of(claimed), Optional.of(afterSubmit));
        when(articles.findSnapshot(claimed.articleSnapshotId(), claimed.articleId(), claimed.userId()))
                .thenReturn(Optional.of(snapshot()));
    }

    @Test
    void processedCallbackReplayDoesNotTouchSubmissionState() {
        ObjectNode payload = json.createObjectNode().put("customer_order_id", "ZXH9901")
                .put("customer_model_id", "1").put("order_progress", "1");
        when(media.insertCallback(anyLong(), anyString(), anyString(), any(), any(), anyString(),
                anyString(), any())).thenReturn(0);
        when(media.callbackStatus(anyString(), anyString(), any(), any(), anyString(), anyString()))
                .thenReturn(Optional.of("PROCESSED"));

        assertThat(service.processCallback("progress", payload)).isFalse();

        verify(media, never()).lockByCustomerOrder(anyString());
    }

    @Test
    void oldGeoOrderCallbackCannotEnterZhixiaoheLedgerOrArticleFlow() {
        ObjectNode oldGeo = json.createObjectNode().put("customer_order_id", "GH123456")
                .put("order_progress", 1);

        assertThatThrownBy(() -> service.processCallback("PROGRESS", oldGeo))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).errorCode())
                        .isEqualTo(ErrorCode.VALIDATION_FAILED));

        verifyNoInteractions(media, articles);
    }

    @Test
    void lateProgressAfterPublicationIsAcknowledgedWithoutRegressingTheTerminalState() {
        ObjectNode progress = json.createObjectNode().put("customer_order_id", "ZXH9901")
                .put("customer_model_id", "1").put("order_progress", "1");
        when(media.insertCallback(anyLong(), anyString(), anyString(), any(), any(), anyString(),
                anyString(), any())).thenReturn(1);
        when(media.lockByCustomerOrder("ZXH9901"))
                .thenReturn(Optional.of(submission("PUBLISHED", "CONFIRMED")));

        assertThat(service.processCallback("PROGRESS", progress)).isTrue();

        verify(media, never()).transition(anyLong(), anyString(), anyString(), anyString(), anyString(),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void resourceCallbackIsAuditedAndUsesStableLocalIdentity() {
        ObjectNode payload = resourcePayload();
        when(media.insertCallback(anyLong(), anyString(), any(), anyString(), anyString(),
                anyString(), anyString(), any())).thenReturn(1);
        when(media.findResourceByUpstream("MEDIA_PROCUREMENT", "1", "44990"))
                .thenReturn(Optional.of(resource()));
        when(media.upsertResource(any())).thenReturn(1);
        when(media.finishCallback(anyString(), any(), anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), any())).thenReturn(1);

        assertThat(service.processResourceCallback(payload)).isTrue();

        ArgumentCaptor<MediaRecords.Resource> captured = ArgumentCaptor.forClass(MediaRecords.Resource.class);
        verify(media).upsertResource(captured.capture());
        assertThat(captured.getValue().id()).isEqualTo(resource().id());
        assertThat(captured.getValue().upstreamPrice()).isEqualByComparingTo("368.0000");
        verify(media).finishCallback(eq("MEDIA_PROCUREMENT"), eq(null), eq("1"), eq("44990"),
                eq("RESOURCE_UPDATE"), org.mockito.ArgumentMatchers.matches("[0-9a-f]{64}"),
                eq("PROCESSED"), eq(null), any());
    }

    @Test
    void resourceCallbackAcceptsIntegerModelAndResourceIds() {
        ObjectNode payload = resourcePayload().put("id", 44990).put("model_id", 1).put("status", 1);
        when(media.insertCallback(anyLong(), anyString(), any(), anyString(), anyString(),
                anyString(), anyString(), any())).thenReturn(1);
        when(media.upsertResource(any())).thenReturn(1);

        assertThat(service.processResourceCallback(payload)).isTrue();

        ArgumentCaptor<MediaRecords.Resource> captured = ArgumentCaptor.forClass(MediaRecords.Resource.class);
        verify(media).upsertResource(captured.capture());
        assertThat(captured.getValue().upstreamModelId()).isEqualTo("1");
        assertThat(captured.getValue().upstreamResourceId()).isEqualTo("44990");
        assertThat(captured.getValue().status()).isEqualTo("ACTIVE");
        verify(media).finishCallback(eq("MEDIA_PROCUREMENT"), eq(null), eq("1"), eq("44990"),
                eq("RESOURCE_UPDATE"), org.mockito.ArgumentMatchers.matches("[0-9a-f]{64}"),
                eq("PROCESSED"), eq(null), any());
    }

    @Test
    void validUnsupportedProcurementModelIsAcknowledgedWithoutEnteringArticleCatalogue() {
        ObjectNode unsupported = resourcePayload().put("id", 44990).put("model_id", 2);

        assertThat(service.processResourceCallback(unsupported)).isFalse();

        verifyNoInteractions(media);
        verify(provider, never()).resources(anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    void mixedModelCallbackImportsOnlyTheSupportedArticleResource() {
        ArrayNode batch = json.createArrayNode();
        batch.add(resourcePayload().put("id", 44991).put("model_id", 2));
        batch.add(resourcePayload().put("id", 44990).put("model_id", 1));
        when(media.insertCallback(anyLong(), anyString(), any(), anyString(), anyString(),
                anyString(), anyString(), any())).thenReturn(1);
        when(media.upsertResource(any())).thenReturn(1);

        assertThat(service.processResourceCallback(batch)).isTrue();

        ArgumentCaptor<MediaRecords.Resource> captured = ArgumentCaptor.forClass(MediaRecords.Resource.class);
        verify(media).upsertResource(captured.capture());
        assertThat(captured.getValue().upstreamModelId()).isEqualTo("1");
        assertThat(captured.getValue().upstreamResourceId()).isEqualTo("44990");
    }

    @Test
    void unpricedResourceCallbackInactivatesExistingResourceWithoutInventingAPrice() throws Exception {
        ObjectNode payload = resourcePayload("").put("id", 44990).put("model_id", 1);
        when(media.insertCallback(anyLong(), anyString(), any(), anyString(), anyString(),
                anyString(), anyString(), any())).thenReturn(1);
        when(media.findResourceByUpstream("MEDIA_PROCUREMENT", "1", "44990"))
                .thenReturn(Optional.of(resource()));
        when(media.upsertResource(any())).thenReturn(1);

        assertThat(service.processResourceCallback(payload)).isTrue();

        ArgumentCaptor<MediaRecords.Resource> captured = ArgumentCaptor.forClass(MediaRecords.Resource.class);
        verify(media).upsertResource(captured.capture());
        assertThat(captured.getValue().id()).isEqualTo(resource().id());
        assertThat(captured.getValue().status()).isEqualTo("INACTIVE");
        assertThat(captured.getValue().upstreamPrice()).isEqualByComparingTo(resource().upstreamPrice());
        assertThat(json.readTree(captured.getValue().rawJson()).path("price_assign").asText()).isEmpty();
        verify(media).finishCallback(eq("MEDIA_PROCUREMENT"), eq(null), eq("1"), eq("44990"),
                eq("RESOURCE_UPDATE"), org.mockito.ArgumentMatchers.matches("[0-9a-f]{64}"),
                eq("PROCESSED"), eq("MEDIA_RESOURCE_PRICE_UNAVAILABLE"), any());
    }

    @Test
    void newUnpricedResourceIsAcknowledgedButNotAddedToOrderableCatalogue() {
        ObjectNode payload = resourcePayload("").put("id", 44991).put("model_id", 1);
        when(media.insertCallback(anyLong(), anyString(), any(), anyString(), anyString(),
                anyString(), anyString(), any())).thenReturn(1);
        when(media.findResourceByUpstream("MEDIA_PROCUREMENT", "1", "44991"))
                .thenReturn(Optional.empty());

        assertThat(service.processResourceCallback(payload)).isTrue();

        verify(media, never()).upsertResource(any());
        verify(media).finishCallback(eq("MEDIA_PROCUREMENT"), eq(null), eq("1"), eq("44991"),
                eq("RESOURCE_UPDATE"), org.mockito.ArgumentMatchers.matches("[0-9a-f]{64}"),
                eq("PROCESSED"), eq("MEDIA_RESOURCE_PRICE_UNAVAILABLE"), any());
    }

    @Test
    void malformedStructuredPriceIsRejectedRatherThanTreatedAsUnavailable() {
        ObjectNode payload = resourcePayload().put("id", 44992).put("model_id", 1);
        payload.set("price_assign", json.createObjectNode());
        when(media.insertCallback(anyLong(), anyString(), any(), anyString(), anyString(),
                anyString(), anyString(), any())).thenReturn(1);

        assertThatThrownBy(() -> service.processResourceCallback(payload))
                .isInstanceOf(MediaProviderException.class)
                .hasMessage("MEDIA_PROVIDER_RESPONSE_INVALID");

        verify(media, never()).upsertResource(any());
        verify(media).finishCallback(eq("MEDIA_PROCUREMENT"), eq(null), eq("1"), eq("44992"),
                eq("RESOURCE_UPDATE"), org.mockito.ArgumentMatchers.matches("[0-9a-f]{64}"),
                eq("FAILED"), eq("MEDIA_CALLBACK_PROCESSING_FAILED"), any());
    }

    @Test
    void deletedUpstreamResourceBecomesInactiveInsteadOfRemainingOrderable() {
        ObjectNode payload = resourcePayload().put("status", "-1");
        when(media.insertCallback(anyLong(), anyString(), any(), anyString(), anyString(),
                anyString(), anyString(), any())).thenReturn(1);
        when(media.findResourceByUpstream("MEDIA_PROCUREMENT", "1", "44990"))
                .thenReturn(Optional.of(resource()));
        when(media.upsertResource(any())).thenReturn(1);

        assertThat(service.processResourceCallback(payload)).isTrue();

        ArgumentCaptor<MediaRecords.Resource> captured = ArgumentCaptor.forClass(MediaRecords.Resource.class);
        verify(media).upsertResource(captured.capture());
        assertThat(captured.getValue().status()).isEqualTo("INACTIVE");
    }

    @Test
    void resourceCallbackAcceptsLegacyBatchWithNumericKeys() {
        ObjectNode batch = json.createObjectNode();
        batch.set("0", resourcePayload().put("id", 44990).put("model_id", 1).put("status", 1));
        batch.set("1", resourcePayload().put("id", 44991).put("model_id", 1).put("status", -1));
        when(media.insertCallback(anyLong(), anyString(), any(), anyString(), anyString(),
                anyString(), anyString(), any())).thenReturn(1);
        when(media.upsertResource(any())).thenReturn(1);

        assertThat(service.processResourceCallback(batch)).isTrue();

        ArgumentCaptor<MediaRecords.Resource> captured = ArgumentCaptor.forClass(MediaRecords.Resource.class);
        verify(media, times(2)).upsertResource(captured.capture());
        assertThat(captured.getAllValues()).extracting(MediaRecords.Resource::upstreamResourceId)
                .containsExactly("44990", "44991");
        assertThat(captured.getAllValues()).extracting(MediaRecords.Resource::status)
                .containsExactly("ACTIVE", "INACTIVE");
    }

    @Test
    void resourceCallbackRejectsFractionalAndNegativeNumericIds() {
        List<ObjectNode> invalid = List.of(
                resourcePayload().put("id", 44990.5).put("model_id", 1),
                resourcePayload().put("id", -1).put("model_id", 1),
                resourcePayload().put("id", 44990).put("model_id", 1.5),
                resourcePayload().put("id", 44990).put("model_id", -1),
                resourcePayload().put("id", 44990).put("model_id", 0),
                resourcePayload().put("id", 44990).put("model_id", "unsupported"));

        for (ObjectNode payload : invalid) {
            assertThatThrownBy(() -> service.processResourceCallback(payload))
                    .isInstanceOfSatisfying(BusinessException.class,
                            exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        }
        verifyNoInteractions(media);
    }

    @Test
    void mixedPublishedAndNumericRefundCallbacksSettlePartiallyAndReplayDoesNothing() {
        OffsetDateTime beforeMidnight = OffsetDateTime.parse("2026-09-24T15:59:59Z");
        OffsetDateTime afterMidnight = beforeMidnight.plusSeconds(2);
        AtomicReference<OffsetDateTime> clockTime = new AtomicReference<>(beforeMidnight);
        Clock accountingClock = mock(Clock.class);
        when(accountingClock.instant()).thenAnswer(ignored -> clockTime.get().toInstant());
        service.setAccountingClock(accountingClock);
        var notifications = mock(com.guanghe.backend.task.TaskNotificationService.class);
        service.setTaskNotifications(notifications);
        MediaRecords.Submission first = submission(9_901, "ZXH9901", 30_000, "ACCEPTED", "CONFIRMED");
        MediaRecords.Submission second = submission(9_911, "ZXH9911", 19_700, "ACCEPTED", "CONFIRMED");
        MediaRecords.Submission published = submission(9_901, "ZXH9901", 30_000, "PUBLISHED", "CONFIRMED");
        MediaRecords.Submission rejected = submission(9_911, "ZXH9911", 19_700, "REJECTED", "CONFIRMED");
        ObjectNode receipt = json.createObjectNode().put("customer_order_id", first.customerOrderId())
                .put("customer_model_id", "1").put("resource_order_id", "upstream-9901")
                .put("return_info", "https://publisher.example.test/article/1")
                .put("return_file", "https://publisher.example.test/attachment/1");
        ObjectNode refund = json.createObjectNode().put("customer_order_id", second.customerOrderId())
                .put("customer_model_id", "1").put("resource_order_id", "upstream-9911").put("type", 1);
        when(media.insertCallback(anyLong(), anyString(), anyString(), any(), any(), anyString(),
                anyString(), any())).thenReturn(1, 1, 0);
        when(media.callbackStatus(anyString(), anyString(), any(), any(), anyString(), anyString()))
                .thenReturn(Optional.of("PROCESSED"));
        when(media.lockByCustomerOrder(first.customerOrderId())).thenReturn(Optional.of(first));
        when(media.lockByCustomerOrder(second.customerOrderId())).thenReturn(Optional.of(second));
        when(media.transition(anyLong(), anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), any(), any(), any(), any())).thenReturn(1);
        when(tasks.settlePoints(eq(41L), eq(30_000), any())).thenReturn(1);
        when(tasks.lockPointAccount(41L)).thenAnswer(ignored -> {
            clockTime.set(afterMidnight);
            return Optional.of(new TaskRecords.PointAccount(41, 100_000, 49_700, 1,
                    beforeMidnight, beforeMidnight));
        });
        when(media.settleReservationItem(eq(first.taskId()), eq(30_000), any())).thenReturn(1);
        when(media.addTaskSettled(eq(first.taskId()), eq(30_000), any())).thenReturn(1);
        when(tasks.releasePoints(eq(41L), eq(19_700), any())).thenReturn(1);
        when(media.releaseReservationItem(eq(second.taskId()), eq(19_700), any())).thenReturn(1);
        when(tasks.findPointAccount(41)).thenReturn(Optional.of(
                new TaskRecords.PointAccount(41, 100_000, 19_700, 2, now(), now())), Optional.of(
                new TaskRecords.PointAccount(41, 119_700, 0, 3, now(), now())));
        when(tasks.insertLedger(anyLong(), eq(41L), anyInt(), anyInt(), anyInt(), anyInt(),
                anyString(), anyLong(), anyString(), anyString(), any())).thenReturn(1);
        when(media.updateBatchPoints(eq(first.batchId()), anyInt(), anyInt(), any())).thenReturn(1);
        when(media.nextEventSequence(anyLong())).thenReturn(2L);
        when(media.insertEvent(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any(),
                anyString(), any(), anyString(), anyString(), any(), any())).thenReturn(1);
        when(media.lockBatch(first.batchId())).thenReturn(Optional.of(new MediaRecords.Batch(
                first.batchId(), first.taskId(), 9_904, 41, 5_001, null, 6_001, 6_002,
                "QUEUED", 49_700, 0, 0, now(), now(), null)));
        when(media.findByTask(first.taskId())).thenReturn(List.of(published, second),
                List.of(published, rejected));
        when(media.finishBatch(eq(first.batchId()), eq("PARTIAL"), any())).thenReturn(1);
        when(media.finishTask(eq(first.taskId()), eq("PARTIAL"), eq("PARTIAL_COMPLETED"),
                eq(null), anyString(), any())).thenReturn(1);
        when(tasks.insertTaskEvent(anyLong(), anyLong(), anyLong(), any(), any(), any(), any(),
                anyString(), anyString(), anyString(), any(), any(), anyString(), any())).thenReturn(1);
        when(media.finishCallback(anyString(), anyString(), any(), any(), anyString(), anyString(),
                anyString(), any(), any())).thenReturn(1);

        assertThat(service.processCallback("RECEIPT", receipt)).isTrue();
        verify(media).transition(eq(first.id()), eq("ACCEPTED"), eq("1002"), eq("PUBLISHED"),
                eq("CONFIRMED"), eq("upstream-9901"),
                eq("https://publisher.example.test/article/1"),
                eq("[\"https://publisher.example.test/article/1\"]"), eq(null), eq(null), any());
        org.mockito.Mockito.verifyNoInteractions(notifications);
        assertThat(service.processCallback("REFUND", refund)).isTrue();
        assertThat(service.processCallback("REFUND", refund)).isFalse();

        verify(tasks, times(1)).settlePoints(eq(41L), eq(30_000), org.mockito.ArgumentMatchers.any());
        verify(tasks).lockPointAccount(41L);
        verify(accountingClock).instant();
        verify(tasks).settlePoints(41L, 30_000, afterMidnight);
        verify(tasks).insertLedger(anyLong(), eq(41L), eq(0), eq(-30_000), eq(100_000), eq(19_700),
                eq("TASK_SETTLE"), eq(first.taskId()), eq("media-settle:" + first.id()),
                anyString(), eq(afterMidnight));
        var ordered = org.mockito.Mockito.inOrder(media, tasks);
        ordered.verify(media).lockByCustomerOrder(first.customerOrderId());
        ordered.verify(tasks).lockPointAccount(41L);
        ordered.verify(tasks).settlePoints(41L, 30_000, afterMidnight);
        ordered.verify(media).settleReservationItem(eq(first.taskId()), eq(30_000), any());
        ordered.verify(media).addTaskSettled(eq(first.taskId()), eq(30_000), any());
        ordered.verify(media).lockBatch(first.batchId());
        verify(tasks, times(1)).releasePoints(eq(41L), eq(19_700), org.mockito.ArgumentMatchers.any());
        verify(media, times(1)).finishTask(eq(first.taskId()), eq("PARTIAL"),
                eq("PARTIAL_COMPLETED"), eq(null), anyString(), any());
        verify(media, times(1)).lockByCustomerOrder(second.customerOrderId());
        verify(notifications).publishTerminal(first.taskId(), first.userId());
    }

    @Test
    void publishedReceiptWithoutPublicUrlStillSettlesOnceAndNeverExposesFreeText() {
        MediaRecords.Submission submitting = submission("SUBMITTING", "NOT_SUBMITTED");
        MediaRecords.Submission accepted = submission("ACCEPTED", "CONFIRMED");
        MediaRecords.Submission published = submission("PUBLISHED", "CONFIRMED");
        ObjectNode receipt = json.createObjectNode().put("customer_order_id", accepted.customerOrderId())
                .put("customer_model_id", "1").put("resource_order_id", accepted.upstreamOrderId())
                .put("return_info", "文章已发布，链接稍后提供")
                .put("return_file", "https://publisher.example.test/attachment/1");
        when(media.insertCallback(anyLong(), anyString(), anyString(), any(), any(), anyString(),
                anyString(), any())).thenReturn(1, 0, 0);
        when(media.callbackStatus(anyString(), anyString(), any(), any(), anyString(), anyString()))
                .thenReturn(Optional.of("FAILED"), Optional.of("PROCESSED"));
        when(media.lockByCustomerOrder(accepted.customerOrderId()))
                .thenReturn(Optional.of(submitting), Optional.of(accepted));
        when(media.transition(anyLong(), anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), any(), any(), any(), any())).thenReturn(1);
        when(tasks.settlePoints(eq(41L), eq(accepted.chargedPoints()), any())).thenReturn(1);
        when(tasks.lockPointAccount(41L)).thenReturn(Optional.of(
                new TaskRecords.PointAccount(41, 100_000, accepted.chargedPoints(), 1, now(), now())));
        when(media.settleReservationItem(eq(accepted.taskId()), eq(accepted.chargedPoints()), any())).thenReturn(1);
        when(media.addTaskSettled(eq(accepted.taskId()), eq(accepted.chargedPoints()), any())).thenReturn(1);
        when(tasks.findPointAccount(41)).thenReturn(Optional.of(
                new TaskRecords.PointAccount(41, 100_000, 0, 2, now(), now())));
        when(tasks.insertLedger(anyLong(), eq(41L), anyInt(), anyInt(), anyInt(), anyInt(),
                anyString(), anyLong(), anyString(), anyString(), any())).thenReturn(1);
        when(media.updateBatchPoints(eq(accepted.batchId()), eq(accepted.chargedPoints()), eq(0), any()))
                .thenReturn(1);
        when(media.lockBatch(accepted.batchId())).thenReturn(Optional.of(new MediaRecords.Batch(
                accepted.batchId(), accepted.taskId(), 9_904, 41, 5_001, null, 6_001, 6_002,
                "QUEUED", accepted.chargedPoints(), 0, 0, now(), now(), null)));
        when(media.findByTask(accepted.taskId())).thenReturn(List.of(published));
        when(media.finishBatch(eq(accepted.batchId()), eq("PUBLISHED"), any())).thenReturn(1);
        when(media.finishTask(eq(accepted.taskId()), eq("SUCCEEDED"), eq("COMPLETED"),
                eq(null), anyString(), any())).thenReturn(1);
        when(tasks.insertTaskEvent(anyLong(), anyLong(), anyLong(), any(), any(), any(), any(),
                anyString(), anyString(), anyString(), any(), any(), anyString(), any())).thenReturn(1);

        assertThatThrownBy(() -> service.processCallback("RECEIPT", receipt))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.TASK_STATE_CONFLICT));
        verify(tasks, never()).settlePoints(anyLong(), anyInt(), any());
        verify(media).finishCallback(anyString(), eq(accepted.customerOrderId()), any(), any(),
                eq("RECEIPT"), anyString(), eq("FAILED"), eq("MEDIA_CALLBACK_PROCESSING_FAILED"), any());

        assertThat(service.processCallback("RECEIPT", receipt)).isTrue();
        assertThat(service.processCallback("RECEIPT", receipt)).isFalse();

        verify(media).transition(eq(accepted.id()), eq("ACCEPTED"), eq("1002"), eq("PUBLISHED"),
                eq("CONFIRMED"), eq(accepted.upstreamOrderId()), eq(null), eq("[]"),
                eq(null), eq(null), any());
        verify(tasks, times(1)).settlePoints(eq(41L), eq(accepted.chargedPoints()), any());
        verify(media, times(1)).finishTask(eq(accepted.taskId()), eq("SUCCEEDED"),
                eq("COMPLETED"), eq(null), anyString(), any());
    }

    @Test
    void signedProgressOrRefundEnvelopeReplayedToReceiptRouteCannotSettlePoints() {
        ObjectNode progress = json.createObjectNode().put("customer_order_id", "ZXH9901")
                .put("resource_order_id", "upstream-9901").put("order_progress", "1");
        ObjectNode progressWithExtraReceiptText = progress.deepCopy()
                .put("return_info", "进度备注，尚未发布");
        ObjectNode refund = json.createObjectNode().put("customer_order_id", "ZXH9901")
                .put("resource_order_id", "upstream-9901").put("type", "1")
                .put("return_info", "退款说明");
        ObjectNode bareOrder = json.createObjectNode().put("customer_order_id", "ZXH9901")
                .put("resource_order_id", "upstream-9901");

        for (ObjectNode payload : List.of(progress, progressWithExtraReceiptText, refund, bareOrder)) {
            assertThatThrownBy(() -> service.processCallback("RECEIPT", payload))
                    .isInstanceOfSatisfying(BusinessException.class,
                            exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        }
        verifyNoInteractions(media, tasks, provider);
    }

    @Test
    void receiptForAnotherUpstreamOrderCannotSettleAnOwnedSubmission() {
        MediaRecords.Submission submitting = submission("SUBMITTING", "NOT_SUBMITTED");
        MediaRecords.Submission owned = submission("ACCEPTED", "CONFIRMED");
        ObjectNode receipt = json.createObjectNode().put("customer_order_id", owned.customerOrderId())
                .put("customer_model_id", "1").put("resource_order_id", "different-upstream-order")
                .put("return_info", "https://publisher.example.test/article/other");
        when(media.insertCallback(anyLong(), anyString(), anyString(), any(), any(), anyString(),
                anyString(), any())).thenReturn(1, 0);
        when(media.callbackStatus(anyString(), anyString(), any(), any(), anyString(), anyString()))
                .thenReturn(Optional.of("FAILED"));
        when(media.lockByCustomerOrder(owned.customerOrderId()))
                .thenReturn(Optional.of(submitting), Optional.of(owned));

        assertThatThrownBy(() -> service.processCallback("RECEIPT", receipt))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.TASK_STATE_CONFLICT));
        assertThatThrownBy(() -> service.processCallback("RECEIPT", receipt))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        verify(media, never()).transition(anyLong(), anyString(), anyString(), anyString(), anyString(),
                any(), any(), any(), any(), any(), any());
        verify(tasks, never()).settlePoints(anyLong(), anyInt(), any());
    }

    @Test
    void receiptAndRefundWaitForSubmitConfirmationFromQueuedOrSubmitting() {
        ObjectNode receipt = json.createObjectNode().put("customer_order_id", "ZXH9901")
                .put("resource_order_id", "upstream-9901")
                .put("return_info", "https://publisher.example.test/article/1");
        ObjectNode refund = json.createObjectNode().put("customer_order_id", "ZXH9901")
                .put("resource_order_id", "upstream-9901").put("type", 1);
        when(media.insertCallback(anyLong(), anyString(), anyString(), any(), any(), anyString(),
                anyString(), any())).thenReturn(1);

        for (String status : List.of("QUEUED", "SUBMITTING")) {
            when(media.lockByCustomerOrder("ZXH9901"))
                    .thenReturn(Optional.of(submission(status, "NOT_SUBMITTED")));
            for (Map.Entry<String, ObjectNode> callback : Map.of("RECEIPT", receipt, "REFUND", refund)
                    .entrySet()) {
                assertThatThrownBy(() -> service.processCallback(callback.getKey(), callback.getValue()))
                        .isInstanceOfSatisfying(BusinessException.class,
                                exception -> assertThat(exception.errorCode())
                                        .isEqualTo(ErrorCode.TASK_STATE_CONFLICT));
            }
        }

        verify(media, times(4)).finishCallback(anyString(), eq("ZXH9901"), any(), any(), anyString(),
                anyString(), eq("FAILED"), eq("MEDIA_CALLBACK_PROCESSING_FAILED"), any());
        verify(media, never()).transition(anyLong(), anyString(), anyString(), anyString(), anyString(),
                any(), any(), any(), any(), any(), any());
        verify(tasks, never()).settlePoints(anyLong(), anyInt(), any());
        verify(tasks, never()).releasePoints(anyLong(), anyInt(), any());
    }

    private MediaProperties properties() {
        MediaProperties result = new MediaProperties();
        result.setEnabled(true);
        result.setExecutionEnabled(true);
        result.setOrderPollEnabled(true);
        result.setBaseUrl("https://media.example.test");
        result.setAppKey(TEST_KEY);
        result.setSupportedModelIds(java.util.Set.of("1"));
        return result;
    }

    private MediaRecords.Resource resource() {
        return new MediaRecords.Resource(7_001, "MEDIA_PROCUREMENT", "1", "44990", "媒体名称",
                "ACTIVE", "NEWS", "综合", "全国", 3, "{}", new BigDecimal("368.0000"),
                "{}", now(), OffsetDateTime.now(ZoneOffset.UTC), 1);
    }

    private ObjectNode resourcePayload() {
        return resourcePayload("368.0000");
    }

    private ObjectNode resourcePayload(String price) {
        return json.createObjectNode().put("id", "44990").put("model_id", "1")
                .put("name", "媒体名称").put("status", "1").put("price_assign", price)
                .put("type", "NEWS");
    }

    private ArticleRecords.Article article() {
        return new ArticleRecords.Article(6_001, 41, 5_001, null, "标题", "<p>正文</p>", "正文",
                "[]", "COMPLETED", null, 1, now(), now());
    }

    private ArticleRecords.Workflow readyWorkflow() {
        return new ArticleRecords.Workflow(6_001, 41, "READY_TO_SUBMIT", 1, "标题", "SELECTED",
                1, 1, now(), 2, 2);
    }

    private AuthenticatedAccount user() {
        return new AuthenticatedAccount(41, "test001", AccountRole.USER, 1,
                AccountStatus.ACTIVE, null, null, null);
    }

    private MediaRecords.Submission submission(String status, String submissionState) {
        return submission(9_901, "ZXH9901", 49_700, status, submissionState);
    }

    private MediaRecords.Submission submission(long id, String customerOrderId, int points,
                                                String status, String submissionState) {
        boolean published = "PUBLISHED".equals(status);
        boolean rejected = Set.of("REJECTED", "FAILED").contains(status);
        return new MediaRecords.Submission(id, 9_902, 9_903, 41, 5_001, 6_001, 6_002,
                7_001 + id - 9_901, 7_002 + id - 9_901, "MEDIA_PROCUREMENT", "1",
                Long.toString(44_990 + id - 9_901), "媒体名称", new BigDecimal("368.0000"),
                MediaSubmissionService.MARKUP_RATE, points, points, null, customerOrderId,
                submissionState.equals("CONFIRMED") ? "upstream-" + id : null, null, null,
                status, submissionState,
                published ? "https://publisher.example.test/article/" + id : null,
                published ? "[\"https://publisher.example.test/article/" + id + "\"]" : "[]",
                rejected ? "MEDIA_REJECTED" : null, null, now(), published ? now() : null,
                rejected ? now() : null, now(), now());
    }

    private MediaRecords.Submission withOrderState(MediaRecords.Submission row, String status,
                                                   String submissionState, String upstreamOrderId) {
        return withOrderState(row, status, submissionState, upstreamOrderId, row.stableErrorCode());
    }

    private MediaRecords.Submission withOrderState(MediaRecords.Submission row, String status,
                                                   String submissionState, String upstreamOrderId,
                                                   String stableErrorCode) {
        return new MediaRecords.Submission(row.id(), row.batchId(), row.taskId(), row.userId(), row.spaceId(),
                row.articleId(), row.articleSnapshotId(), row.mediaResourceId(), row.priceSnapshotId(),
                row.providerCode(), row.upstreamModelId(), row.upstreamResourceId(), row.mediaName(),
                row.upstreamPrice(), row.markupRate(), row.saleAmountCents(), row.chargedPoints(), row.remark(),
                row.customerOrderId(), upstreamOrderId, row.upstreamOrderNo(), row.upstreamStatus(), status,
                submissionState, row.publishedUrl(), row.publishedUrlsJson(), stableErrorCode,
                row.submitAttemptedAt(), row.nextPollAt(), row.publishedAt(), row.rejectedAt(),
                row.createdAt(), row.updatedAt());
    }

    private ArticleRecords.SubmissionSnapshot snapshot() {
        return snapshotWith("<p>正文</p>", "[]");
    }

    private ArticleRecords.SubmissionSnapshot snapshotWith(String bodyHtml, String assetManifest) {
        return new ArticleRecords.SubmissionSnapshot(6_002, 6_001, 41, 5_001, null, 1,
                "标题", bodyHtml, "正文", "a".repeat(64), "[]", assetManifest, now());
    }

    private OffsetDateTime now() {
        return OffsetDateTime.of(2026, 9, 9, 0, 0, 0, 0, ZoneOffset.UTC);
    }

    private static final class FixedSecureRandom extends SecureRandom {
        @Override public void nextBytes(byte[] bytes) {
            for (int index = 0; index < bytes.length; index++) bytes[index] = (byte) index;
        }
    }
}
