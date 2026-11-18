package com.guanghe.backend.geo;

import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.AliasValue;
import com.guanghe.backend.catalog.CatalogAccess;
import com.guanghe.backend.catalog.CatalogNameNormalizer;
import com.guanghe.backend.catalog.PageResponse;
import com.guanghe.backend.catalog.competitor.CompetitorRecord;
import com.guanghe.backend.catalog.competitor.CompetitorService;
import com.guanghe.backend.catalog.product.ProductRecord;
import com.guanghe.backend.catalog.product.ProductService;
import com.guanghe.backend.catalog.space.BusinessSpaceService;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "zhixiaohe.geo", name = "enabled", havingValue = "true", matchIfMissing = true)
public class GeoService {
    private final GeoMapper mapper;
    private final BusinessSpaceService spaceService;
    private final ProductService productService;
    private final CompetitorService competitorService;
    private final SnowflakeIdGenerator ids;
    private final CatalogNameNormalizer normalizer;

    public GeoService(
            GeoMapper mapper,
            BusinessSpaceService spaceService,
            ProductService productService,
            CompetitorService competitorService,
            SnowflakeIdGenerator ids,
            CatalogNameNormalizer normalizer
    ) {
        this.mapper = mapper;
        this.spaceService = spaceService;
        this.productService = productService;
        this.competitorService = competitorService;
        this.ids = ids;
        this.normalizer = normalizer;
    }

    @Transactional(readOnly = true)
    public PageResponse<GeoDtos.TopicSummary> list(
            AuthenticatedAccount account,
            Long spaceId,
            Long productId,
            String keyword,
            int page,
            int size
    ) {
        long userId = CatalogAccess.requireUser(account);
        validateFilters(userId, spaceId, productId);
        String normalizedKeyword = normalizeFilter(keyword);
        long total = mapper.count(userId, spaceId, productId, normalizedKeyword);
        List<GeoDtos.TopicSummary> items = mapper.findPage(
                userId, spaceId, productId, normalizedKeyword, (long) (page - 1) * size, size
        ).stream().map(this::summary).toList();
        return PageResponse.of(items, page, size, total);
    }

    @Transactional(readOnly = true)
    public GeoDtos.TopicResponse get(AuthenticatedAccount account, long topicId) {
        long userId = CatalogAccess.requireUser(account);
        return detail(requireTopic(userId, topicId));
    }

    @Transactional
    public GeoDtos.TopicResponse create(
            AuthenticatedAccount account,
            GeoDtos.CreateTopicRequest request
    ) {
        long userId = CatalogAccess.requireUser(account);
        long spaceId = positiveId(request.spaceId(), "spaceId");
        Long productId = optionalId(request.productId(), "productId");
        spaceService.requireProjectSpace(userId, spaceId);
        requireProductInSpace(userId, spaceId, productId);
        AliasValue name = normalizer.requiredName(request.name(), 100, "name");
        ensureNameAvailable(userId, spaceId, name.normalized(), 0L);
        OffsetDateTime now = now();
        GeoRecords.Topic topic = new GeoRecords.Topic(
                ids.nextId(), userId, spaceId, productId, name.value(), name.normalized(),
                normalizer.nullableText(request.region(), 100, "region"), 1, now, now
        );
        try {
            if (mapper.insertTopic(topic) != 1) {
                throw new IllegalStateException("Unable to create GEO topic");
            }
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.RESOURCE_NAME_CONFLICT);
        }
        return response(topic, List.of(), List.of(), List.of(), null);
    }

    @Transactional
    public GeoDtos.TopicResponse update(
            AuthenticatedAccount account,
            long topicId,
            GeoDtos.UpdateTopicRequest request
    ) {
        long userId = CatalogAccess.requireUser(account);
        if (!request.hasChanges()) {
            throw normalizer.invalid("request");
        }
        GeoRecords.Topic current = requireTopic(userId, topicId);
        requireVersion(current, request.getVersion());
        AliasValue name = request.isNamePresent()
                ? normalizer.requiredName(request.getName(), 100, "name")
                : new AliasValue(current.name(), current.nameNormalized());
        Long productId = request.isProductIdPresent()
                ? optionalId(request.getProductId(), "productId") : current.productId();
        requireProductInSpace(userId, current.spaceId(), productId);
        if (request.isNamePresent()) {
            ensureNameAvailable(userId, current.spaceId(), name.normalized(), topicId);
        }
        String region = request.isRegionPresent()
                ? normalizer.nullableText(request.getRegion(), 100, "region") : current.region();
        OffsetDateTime now = now();
        GeoRecords.Topic updated = new GeoRecords.Topic(
                current.id(), userId, current.spaceId(), productId, name.value(), name.normalized(),
                region, current.version() + 1, current.createdAt(), now
        );
        try {
            if (mapper.updateTopic(updated, current.version()) != 1) {
                throw new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT);
            }
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.RESOURCE_NAME_CONFLICT);
        }
        return detail(updated);
    }

    @Transactional
    public void delete(AuthenticatedAccount account, long topicId) {
        long userId = CatalogAccess.requireUser(account);
        requireTopic(userId, topicId);
        try {
            if (mapper.deleteTopic(topicId, userId) != 1) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
            }
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(
                    ErrorCode.RESOURCE_IN_USE,
                    "GEO 专题已被草稿、计划、运行或报告引用，不能删除",
                    Map.of("reason", "GEO_TOPIC_HAS_PROTECTED_REFERENCES")
            );
        }
    }

    @Transactional
    public GeoDtos.TopicResponse replaceKeywords(
            AuthenticatedAccount account,
            long topicId,
            GeoDtos.ReplaceKeywordsRequest request
    ) {
        long userId = CatalogAccess.requireUser(account);
        GeoRecords.Topic current = requireTopic(userId, topicId);
        requireVersion(current, request.version());
        List<GeoRecords.Keyword> keywords = prepareKeywords(current, request.keywords());
        OffsetDateTime now = now();
        incrementVersion(current, now);
        mapper.deleteKeywords(topicId, userId);
        try {
            for (GeoRecords.Keyword keyword : keywords) {
                if (mapper.insertKeyword(keyword) != 1) {
                    throw new IllegalStateException("Unable to persist GEO keyword");
                }
            }
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.RESOURCE_NAME_CONFLICT);
        }
        GeoRecords.Topic updated = withVersion(current, now);
        CompetitorSelection competitorSelection = currentCompetitors(topicId, userId);
        return response(updated, keywords, mapper.findQuestions(topicId, userId),
                competitorSelection.ids(), competitorSelection.version());
    }

    @Transactional
    public GeoDtos.TopicResponse replaceQuestions(
            AuthenticatedAccount account,
            long topicId,
            GeoDtos.ReplaceQuestionsRequest request
    ) {
        long userId = CatalogAccess.requireUser(account);
        GeoRecords.Topic current = requireTopic(userId, topicId);
        requireVersion(current, request.version());
        List<GeoRecords.Question> questions = prepareQuestions(current, request.questions());
        OffsetDateTime now = now();
        incrementVersion(current, now);
        mapper.deleteQuestions(topicId, userId);
        try {
            for (GeoRecords.Question question : questions) {
                if (mapper.insertQuestion(question) != 1) {
                    throw new IllegalStateException("Unable to persist GEO question");
                }
            }
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.RESOURCE_NAME_CONFLICT);
        }
        GeoRecords.Topic updated = withVersion(current, now);
        CompetitorSelection competitorSelection = currentCompetitors(topicId, userId);
        return response(updated, mapper.findKeywords(topicId, userId), questions,
                competitorSelection.ids(), competitorSelection.version());
    }

    @Transactional
    public GeoDtos.TopicResponse replaceCompetitors(
            AuthenticatedAccount account,
            long topicId,
            GeoDtos.ReplaceCompetitorsRequest request
    ) {
        long userId = CatalogAccess.requireUser(account);
        GeoRecords.Topic current = requireTopic(userId, topicId);
        requireVersion(current, request.version());
        List<CompetitorRecord> competitors = prepareCompetitors(
                userId, current.spaceId(), request.competitorIds()
        );
        OffsetDateTime now = now();
        incrementVersion(current, now);
        int setVersion = mapper.nextCompetitorSetVersion(topicId);
        GeoRecords.CompetitorSet set = new GeoRecords.CompetitorSet(
                ids.nextId(), userId, current.spaceId(), topicId, setVersion, now
        );
        if (mapper.insertCompetitorSet(set) != 1) {
            throw new IllegalStateException("Unable to persist GEO competitor set");
        }
        for (int index = 0; index < competitors.size(); index++) {
            CompetitorRecord competitor = competitors.get(index);
            GeoRecords.CompetitorSetMember member = new GeoRecords.CompetitorSetMember(
                    ids.nextId(), userId, current.spaceId(), topicId, set.id(), competitor.id(), index + 1,
                    competitor.name(), competitor.type(), now
            );
            if (mapper.insertCompetitorSetMember(member) != 1) {
                throw new IllegalStateException("Unable to persist GEO competitor set member");
            }
        }
        GeoRecords.Topic updated = withVersion(current, now);
        return response(updated, mapper.findKeywords(topicId, userId), mapper.findQuestions(topicId, userId),
                competitors.stream().map(record -> Long.toString(record.id())).toList(), setVersion);
    }

    private void validateFilters(long userId, Long spaceId, Long productId) {
        if (spaceId != null) {
            spaceService.requireProjectSpace(userId, spaceId);
        }
        if (productId != null) {
            ProductRecord product = productService.requireProduct(userId, productId);
            if (spaceId != null && product.spaceId() != spaceId) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
            }
        }
    }

    private String normalizeFilter(String keyword) {
        if (keyword == null) return null;
        return normalizer.requiredName(keyword, 200, "keyword").normalized();
    }

    private ProductRecord requireProductInSpace(long userId, long spaceId, Long productId) {
        if (productId == null) return null;
        ProductRecord product = productService.requireProduct(userId, productId);
        if (product.spaceId() != spaceId) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return product;
    }

    private List<GeoRecords.Keyword> prepareKeywords(
            GeoRecords.Topic topic,
            List<GeoDtos.KeywordInput> inputs
    ) {
        if (inputs == null) throw normalizer.invalid("keywords");
        OffsetDateTime now = now();
        Set<String> normalizedValues = new HashSet<>();
        List<GeoRecords.Keyword> result = new ArrayList<>(inputs.size());
        for (int index = 0; index < inputs.size(); index++) {
            GeoDtos.KeywordInput input = inputs.get(index);
            if (input == null || input.type() == null) throw normalizer.invalid("keywords");
            AliasValue text = normalizer.requiredName(input.text(), 200, "keywords.text");
            if (!normalizedValues.add(text.normalized())) {
                throw new BusinessException(ErrorCode.RESOURCE_NAME_CONFLICT);
            }
            result.add(new GeoRecords.Keyword(
                    ids.nextId(), topic.userId(), topic.spaceId(), topic.id(), index + 1,
                    input.type(), text.value(), text.normalized(), now, now
            ));
        }
        return List.copyOf(result);
    }

    private List<GeoRecords.Question> prepareQuestions(
            GeoRecords.Topic topic,
            List<GeoDtos.QuestionInput> inputs
    ) {
        if (inputs == null) throw normalizer.invalid("questions");
        // Replacement edits the question bank, not its identity. Frozen reports and
        // plans retain question IDs; adding/reordering questions must not sever them.
        Map<String, GeoRecords.Question> existing = new HashMap<>();
        for (GeoRecords.Question question : mapper.findQuestions(topic.id(), topic.userId())) {
            existing.put(question.textNormalized(), question);
        }
        OffsetDateTime now = now();
        Set<String> normalizedValues = new HashSet<>();
        List<GeoRecords.Question> result = new ArrayList<>(inputs.size());
        for (int index = 0; index < inputs.size(); index++) {
            GeoDtos.QuestionInput input = inputs.get(index);
            if (input == null || input.category() != GeoDtos.QuestionCategory.BRAND_DISCOVERY || input.source() == null || input.enabled() == null) {
                throw normalizer.invalid("questions");
            }
            AliasValue text = normalizer.requiredName(input.text(), 1000, "questions.text");
            if (!normalizedValues.add(text.normalized())) {
                throw new BusinessException(ErrorCode.RESOURCE_NAME_CONFLICT);
            }
            GeoRecords.Question previous = existing.get(text.normalized());
            result.add(new GeoRecords.Question(
                    previous == null ? ids.nextId() : previous.id(),
                    topic.userId(), topic.spaceId(), topic.id(), index + 1,
                    input.category(), text.value(), text.normalized(), input.source(), input.enabled(),
                    previous == null ? now : previous.createdAt(), now
            ));
        }
        return List.copyOf(result);
    }

    private List<CompetitorRecord> prepareCompetitors(
            long userId,
            long spaceId,
            List<String> idsToParse
    ) {
        if (idsToParse == null || idsToParse.size() > 20) {
            throw normalizer.invalid("competitorIds");
        }
        Set<Long> unique = new HashSet<>();
        List<CompetitorRecord> result = new ArrayList<>(idsToParse.size());
        for (String rawId : idsToParse) {
            long competitorId = positiveId(rawId, "competitorIds");
            if (!unique.add(competitorId)) {
                throw new BusinessException(ErrorCode.RESOURCE_NAME_CONFLICT);
            }
            CompetitorRecord competitor = competitorService.requireCompetitor(userId, competitorId);
            if (competitor.spaceId() != spaceId) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
            }
            result.add(competitor);
        }
        return List.copyOf(result);
    }

    private void incrementVersion(GeoRecords.Topic topic, OffsetDateTime now) {
        if (mapper.incrementTopicVersion(
                topic.id(), topic.userId(), topic.spaceId(), topic.version(), now
        ) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT);
        }
    }

    private GeoRecords.Topic withVersion(GeoRecords.Topic topic, OffsetDateTime now) {
        return new GeoRecords.Topic(
                topic.id(), topic.userId(), topic.spaceId(), topic.productId(), topic.name(),
                topic.nameNormalized(), topic.region(), topic.version() + 1, topic.createdAt(), now
        );
    }

    private void requireVersion(GeoRecords.Topic topic, Integer version) {
        if (version == null || topic.version() != version) {
            throw new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT);
        }
    }

    private void ensureNameAvailable(long userId, long spaceId, String normalized, long excludedId) {
        if (mapper.topicNameExists(userId, spaceId, normalized, excludedId)) {
            throw new BusinessException(ErrorCode.RESOURCE_NAME_CONFLICT);
        }
    }

    private GeoRecords.Topic requireTopic(long userId, long topicId) {
        GeoRecords.Topic topic=mapper.findTopic(topicId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        spaceService.requireProjectSpace(userId,topic.spaceId());
        return topic;
    }

    private GeoDtos.TopicResponse detail(GeoRecords.Topic topic) {
        CompetitorSelection competitorSelection = currentCompetitors(topic.id(), topic.userId());
        return response(topic, mapper.findKeywords(topic.id(), topic.userId()),
                mapper.findQuestions(topic.id(), topic.userId()),
                competitorSelection.ids(), competitorSelection.version());
    }

    private CompetitorSelection currentCompetitors(long topicId, long userId) {
        return mapper.findLatestCompetitorSet(topicId, userId)
                .map(set -> new CompetitorSelection(
                        mapper.findCompetitorSetMembers(set.id()).stream()
                                .map(member -> Long.toString(member.competitorId())).toList(),
                        set.version()
                ))
                .orElseGet(() -> new CompetitorSelection(List.of(), null));
    }

    private GeoDtos.TopicResponse response(
            GeoRecords.Topic topic,
            List<GeoRecords.Keyword> keywords,
            List<GeoRecords.Question> questions,
            List<String> competitorIds,
            Integer setVersion
    ) {
        return new GeoDtos.TopicResponse(
                Long.toString(topic.id()), Long.toString(topic.spaceId()),
                topic.productId() == null ? null : Long.toString(topic.productId()),
                topic.name(), topic.region(),
                keywords.stream().map(keyword -> new GeoDtos.KeywordResponse(
                        Long.toString(keyword.id()), keyword.sequence(), keyword.type(), keyword.text()
                )).toList(),
                questions.stream().map(question -> new GeoDtos.QuestionResponse(
                        Long.toString(question.id()), question.sequence(), question.category(), question.text(),
                        question.source(), question.enabled(), question.createdAt(), question.updatedAt()
                )).toList(), competitorIds, setVersion, topic.version(), topic.createdAt(), topic.updatedAt()
        );
    }

    private GeoDtos.TopicSummary summary(GeoRecords.Topic topic) {
        return new GeoDtos.TopicSummary(
                Long.toString(topic.id()), Long.toString(topic.spaceId()),
                topic.productId() == null ? null : Long.toString(topic.productId()),
                topic.name(), topic.region(), topic.version(), topic.createdAt(), topic.updatedAt()
        );
    }

    private long positiveId(String raw, String field) {
        try {
            long value = Long.parseLong(raw);
            if (value <= 0) throw new NumberFormatException();
            return value;
        } catch (RuntimeException exception) {
            throw normalizer.invalid(field);
        }
    }

    private Long optionalId(String raw, String field) {
        return raw == null ? null : positiveId(raw, field);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    private record CompetitorSelection(List<String> ids, Integer version) {
    }
}
