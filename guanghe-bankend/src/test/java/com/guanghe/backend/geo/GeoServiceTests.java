package com.guanghe.backend.geo;

import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.CatalogNameNormalizer;
import com.guanghe.backend.catalog.competitor.CompetitorRecord;
import com.guanghe.backend.catalog.competitor.CompetitorService;
import com.guanghe.backend.catalog.competitor.CompetitorStatus;
import com.guanghe.backend.catalog.competitor.CompetitorType;
import com.guanghe.backend.catalog.product.ProductRecord;
import com.guanghe.backend.catalog.product.ProductService;
import com.guanghe.backend.catalog.space.BusinessSpaceRecord;
import com.guanghe.backend.catalog.space.BusinessSpaceService;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import com.guanghe.backend.common.id.SnowflakeProperties;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GeoServiceTests {
    @Mock private GeoMapper mapper;
    @Mock private BusinessSpaceService spaceService;
    @Mock private ProductService productService;
    @Mock private CompetitorService competitorService;
    private GeoService service;

    @BeforeEach
    void setUp() {
        service = new GeoService(
                mapper, spaceService, productService, competitorService,
                new SnowflakeIdGenerator(new SnowflakeProperties(3, 2)),
                new CatalogNameNormalizer()
        );
    }

    @Test
    void topicCrudUsesOwnedSpaceProductNormalizationAndOptimisticVersion() {
        when(spaceService.requireProjectSpace(101, 21)).thenReturn(space(21, 101));
        when(productService.requireProduct(101, 31)).thenReturn(product(31, 101, 21));
        when(mapper.insertTopic(any())).thenReturn(1);

        GeoDtos.TopicResponse created = service.create(user(101), new GeoDtos.CreateTopicRequest(
                "21", "31", "  Air Fryer  ", " 中国大陆 "
        ));
        ArgumentCaptor<GeoRecords.Topic> inserted = ArgumentCaptor.forClass(GeoRecords.Topic.class);
        verify(mapper).insertTopic(inserted.capture());
        assertThat(inserted.getValue().name()).isEqualTo("Air Fryer");
        assertThat(inserted.getValue().nameNormalized()).isEqualTo("air fryer");
        assertThat(created.id()).containsOnlyDigits();
        assertThat(created.productId()).isEqualTo("31");

        GeoRecords.Topic current = topic(41, 101, 21, 31L, 1);
        when(mapper.findTopic(41, 101)).thenReturn(Optional.of(current));
        GeoDtos.UpdateTopicRequest update = new GeoDtos.UpdateTopicRequest();
        update.setVersion(1);
        update.setName("  New Topic  ");
        when(mapper.updateTopic(any(), anyInt())).thenReturn(1);
        GeoDtos.TopicResponse updated = service.update(user(101), 41, update);
        assertThat(updated.name()).isEqualTo("New Topic");
        assertThat(updated.version()).isEqualTo(2);

        GeoDtos.UpdateTopicRequest stale = new GeoDtos.UpdateTopicRequest();
        stale.setVersion(9);
        stale.setRegion("华南");
        assertCode(() -> service.update(user(101), 41, stale), ErrorCode.RESOURCE_VERSION_CONFLICT);

        when(mapper.deleteTopic(41, 101)).thenReturn(1);
        service.delete(user(101), 41);
        verify(mapper).deleteTopic(41, 101);
    }

    @Test
    void normalizedTopicAndProductSpaceConflictsAreRejectedBeforeWrite() {
        when(spaceService.requireProjectSpace(101, 21)).thenReturn(space(21, 101));
        when(mapper.topicNameExists(101, 21, "topic", 0)).thenReturn(true);
        assertCode(() -> service.create(user(101), new GeoDtos.CreateTopicRequest(
                "21", null, "  TOPIC  ", null
        )), ErrorCode.RESOURCE_NAME_CONFLICT);

        when(productService.requireProduct(101, 31)).thenReturn(product(31, 101, 99));
        assertCode(() -> service.create(user(101), new GeoDtos.CreateTopicRequest(
                "21", "31", "another", null
        )), ErrorCode.RESOURCE_NOT_FOUND);
        verify(mapper, never()).insertTopic(any());
    }

    @Test
    void topicReferencedByARunOrReportCannotBeDeleted() {
        when(mapper.findTopic(41, 101)).thenReturn(Optional.of(topic(41, 101, 21, 31L, 1)));
        when(mapper.deleteTopic(41, 101))
                .thenThrow(new DataIntegrityViolationException("protected reference"));

        assertCode(() -> service.delete(user(101), 41), ErrorCode.RESOURCE_IN_USE);
    }

    @Test
    void keywordAndQuestionReplacementPreserveOrderAndRejectNormalizedDuplicatesAtomically() {
        GeoRecords.Topic current = topic(41, 101, 21, null, 1);
        when(mapper.findTopic(41, 101)).thenReturn(Optional.of(current));
        when(mapper.incrementTopicVersion(anyLong(), anyLong(), anyLong(), anyInt(), any())).thenReturn(1);
        when(mapper.insertKeyword(any())).thenReturn(1);

        GeoDtos.TopicResponse withKeywords = service.replaceKeywords(
                user(101), 41, new GeoDtos.ReplaceKeywordsRequest(1, List.of(
                        new GeoDtos.KeywordInput(GeoDtos.KeywordType.CORE, " 核心词 "),
                        new GeoDtos.KeywordInput(GeoDtos.KeywordType.SCENE, " 使用场景 ")
                ))
        );
        assertThat(withKeywords.keywords()).extracting(GeoDtos.KeywordResponse::sequence)
                .containsExactly(1, 2);
        assertThat(withKeywords.keywords()).extracting(GeoDtos.KeywordResponse::text)
                .containsExactly("核心词", "使用场景");

        when(mapper.findTopic(41, 101)).thenReturn(Optional.of(topic(41, 101, 21, null, 2)));
        assertCode(() -> service.replaceKeywords(user(101), 41,
                new GeoDtos.ReplaceKeywordsRequest(2, List.of(
                        new GeoDtos.KeywordInput(GeoDtos.KeywordType.CORE, "Alpha"),
                        new GeoDtos.KeywordInput(GeoDtos.KeywordType.BRAND, " alpha ")
                ))), ErrorCode.RESOURCE_NAME_CONFLICT);
        verify(mapper, times(1)).deleteKeywords(41, 101);

        when(mapper.findTopic(41, 101)).thenReturn(Optional.of(topic(41, 101, 21, null, 2)));
        when(mapper.insertQuestion(any())).thenReturn(1);
        GeoDtos.TopicResponse withQuestions = service.replaceQuestions(
                user(101), 41, new GeoDtos.ReplaceQuestionsRequest(2, List.of(
                        new GeoDtos.QuestionInput(GeoDtos.QuestionCategory.BRAND_DISCOVERY,
                                " 哪些品牌值得考虑？ ", GeoDtos.QuestionSource.MANUAL, true),
                        new GeoDtos.QuestionInput(GeoDtos.QuestionCategory.BRAND_DISCOVERY,
                                " 哪个适合家庭？ ", GeoDtos.QuestionSource.MANUAL, false)
                ))
        );
        assertThat(withQuestions.questions()).extracting(GeoDtos.QuestionResponse::sequence)
                .containsExactly(1, 2);
        assertThat(withQuestions.questions()).extracting(GeoDtos.QuestionResponse::enabled)
                .containsExactly(true, false);
    }

    @Test
    void competitorReplacementCreatesImmutableVersionsAndRejectsCrossSpaceReferences() {
        when(mapper.findTopic(41, 101)).thenReturn(
                Optional.of(topic(41, 101, 21, null, 1)),
                Optional.of(topic(41, 101, 21, null, 2))
        );
        when(mapper.incrementTopicVersion(anyLong(), anyLong(), anyLong(), anyInt(), any())).thenReturn(1);
        when(mapper.nextCompetitorSetVersion(41)).thenReturn(1, 2);
        when(mapper.insertCompetitorSet(any())).thenReturn(1);
        when(mapper.insertCompetitorSetMember(any())).thenReturn(1);
        when(competitorService.requireCompetitor(101, 51)).thenReturn(competitor(51, 101, 21));
        when(competitorService.requireCompetitor(101, 52)).thenReturn(competitor(52, 101, 21));

        GeoDtos.TopicResponse first = service.replaceCompetitors(
                user(101), 41, new GeoDtos.ReplaceCompetitorsRequest(1, List.of("51", "52"))
        );
        GeoDtos.TopicResponse second = service.replaceCompetitors(
                user(101), 41, new GeoDtos.ReplaceCompetitorsRequest(2, List.of("52"))
        );
        assertThat(first.competitorSetVersion()).isEqualTo(1);
        assertThat(first.competitorIds()).containsExactly("51", "52");
        assertThat(second.competitorSetVersion()).isEqualTo(2);
        verify(mapper, times(2)).insertCompetitorSet(any());
        verify(mapper, times(3)).insertCompetitorSetMember(any());

        when(mapper.findTopic(41, 101)).thenReturn(Optional.of(topic(41, 101, 21, null, 3)));
        when(competitorService.requireCompetitor(101, 99)).thenReturn(competitor(99, 101, 88));
        assertCode(() -> service.replaceCompetitors(user(101), 41,
                new GeoDtos.ReplaceCompetitorsRequest(3, List.of("99"))), ErrorCode.RESOURCE_NOT_FOUND);
        verify(mapper, times(2)).insertCompetitorSet(any());
    }

    @Test
    void addingAndReorderingQuestionsRetainsOwnedIdentitiesAndCreationTimes() {
        prepareQuestionReplacement(List.of(question(51, "通勤怎么选？", 1), question(52, "家用怎么选？", 2)));
        var result = service.replaceQuestions(user(101), 41, new GeoDtos.ReplaceQuestionsRequest(1, List.of(
                questionInput("家用怎么选？", false), questionInput(" 通勤怎么选？ ", true),
                questionInput("露营怎么选？", true))));
        assertThat(result.questions()).extracting(GeoDtos.QuestionResponse::id)
                .startsWith("52", "51");
        assertThat(result.questions().get(2).id()).isNotIn("51", "52");
        assertThat(result.questions()).extracting(GeoDtos.QuestionResponse::sequence).containsExactly(1, 2, 3);
        ArgumentCaptor<GeoRecords.Question> rows = ArgumentCaptor.forClass(GeoRecords.Question.class);
        verify(mapper, times(3)).insertQuestion(rows.capture());
        assertThat(rows.getAllValues().get(0).createdAt()).isEqualTo(now());
        assertThat(rows.getAllValues().get(1).createdAt()).isEqualTo(now());
        assertThat(rows.getAllValues().get(0).enabled()).isFalse();
        verify(mapper).findQuestions(41, 101);
    }

    @Test
    void editingQuestionTextGetsNewIdentityInsteadOfPretendingToBeSameSample() {
        prepareQuestionReplacement(List.of(question(51, "通勤怎么选？", 1)));
        var result = service.replaceQuestions(user(101), 41, new GeoDtos.ReplaceQuestionsRequest(1,
                List.of(questionInput("家用怎么选？", true))));
        assertThat(result.questions().getFirst().id()).isNotEqualTo("51");
    }

    @Test
    void repeatedQuestionSaveRetainsIdentity() {
        prepareQuestionReplacement(List.of(question(51, "通勤怎么选？", 1)));
        var result = service.replaceQuestions(user(101), 41, new GeoDtos.ReplaceQuestionsRequest(1,
                List.of(questionInput("通勤怎么选？", true))));
        assertThat(result.questions().getFirst().id()).isEqualTo("51");
    }

    @Test
    void duplicateQuestionFailsBeforeDeletingOrIncrementingVersion() {
        when(mapper.findTopic(41, 101)).thenReturn(Optional.of(topic(41, 101, 21, null, 1)));
        when(mapper.findQuestions(41, 101)).thenReturn(List.of(question(51, "通勤怎么选？", 1)));
        assertCode(() -> service.replaceQuestions(user(101), 41, new GeoDtos.ReplaceQuestionsRequest(1,
                List.of(questionInput("通勤怎么选？", true), questionInput(" 通勤怎么选？ ", true)))),
                ErrorCode.RESOURCE_NAME_CONFLICT);
        verify(mapper, never()).deleteQuestions(anyLong(), anyLong());
        verify(mapper, never()).incrementTopicVersion(anyLong(), anyLong(), anyLong(), anyInt(), any());
    }

    @Test
    void staleQuestionReplacementCannotDeleteExistingQuestions() {
        when(mapper.findTopic(41, 101)).thenReturn(Optional.of(topic(41, 101, 21, null, 2)));
        assertCode(() -> service.replaceQuestions(user(101), 41, new GeoDtos.ReplaceQuestionsRequest(1,
                List.of(questionInput("通勤怎么选？", true)))), ErrorCode.RESOURCE_VERSION_CONFLICT);
        verify(mapper, never()).deleteQuestions(anyLong(), anyLong());
        verify(mapper, never()).findQuestions(anyLong(), anyLong());
    }

    private void prepareQuestionReplacement(List<GeoRecords.Question> questions) {
        when(mapper.findTopic(41, 101)).thenReturn(Optional.of(topic(41, 101, 21, null, 1)));
        when(mapper.findQuestions(41, 101)).thenReturn(questions);
        when(mapper.incrementTopicVersion(anyLong(), anyLong(), anyLong(), anyInt(), any())).thenReturn(1);
        when(mapper.insertQuestion(any())).thenReturn(1);
    }

    private GeoRecords.Question question(long id, String text, int sequence) {
        return new GeoRecords.Question(id, 101, 21, 41, sequence,
                GeoDtos.QuestionCategory.BRAND_DISCOVERY, text,
                new CatalogNameNormalizer().requiredName(text, 1000, "questions.text").normalized(),
                GeoDtos.QuestionSource.MANUAL, true, now(), now());
    }

    private GeoDtos.QuestionInput questionInput(String text, boolean enabled) {
        return new GeoDtos.QuestionInput(GeoDtos.QuestionCategory.BRAND_DISCOVERY,
                text, GeoDtos.QuestionSource.MANUAL, enabled);
    }

    @Test
    void ownershipAndAdminAccessFailClosed() {
        when(mapper.findTopic(41, 101)).thenReturn(Optional.empty());
        assertCode(() -> service.get(user(101), 41), ErrorCode.RESOURCE_NOT_FOUND);
        AuthenticatedAccount admin = new AuthenticatedAccount(
                1, "admin", AccountRole.ADMIN, 1, AccountStatus.ACTIVE, null, null, null
        );
        assertCode(() -> service.list(admin, null, null, null, 1, 20), ErrorCode.FORBIDDEN);
    }

    private GeoRecords.Topic topic(long id, long userId, long spaceId, Long productId, int version) {
        OffsetDateTime now = now();
        return new GeoRecords.Topic(id, userId, spaceId, productId, "Topic", "topic", null,
                version, now, now);
    }

    private ProductRecord product(long id, long userId, long spaceId) {
        return new ProductRecord(id, userId, spaceId, "Product", "product", null, null,
                "[]", "{}", "[]", "[]", "[]", null, 1, now(), now(), 0);
    }

    private CompetitorRecord competitor(long id, long userId, long spaceId) {
        return new CompetitorRecord(id, userId, spaceId, "Competitor " + id, "competitor " + id,
                CompetitorType.DIRECT, null, CompetitorStatus.ACTIVE, 1, now(), now(), 0);
    }

    private BusinessSpaceRecord space(long id, long userId) {
        return new BusinessSpaceRecord(id, userId, "Space", "space", "Brand", "{}",
                null, 1, now(), now(), 0, 0);
    }

    private AuthenticatedAccount user(long id) {
        return new AuthenticatedAccount(id, "user", AccountRole.USER, 1,
                AccountStatus.ACTIVE, null, null, null);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.of(2026, 9, 8, 0, 0, 0, 0, ZoneOffset.UTC);
    }

    private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, ErrorCode code) {
        assertThatThrownBy(action)
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).errorCode()).isEqualTo(code));
    }
}
