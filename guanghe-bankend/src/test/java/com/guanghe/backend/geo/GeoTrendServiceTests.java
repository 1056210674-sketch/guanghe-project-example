package com.guanghe.backend.geo;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.common.api.BusinessException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GeoTrendServiceTests {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-28T04:00:00Z");
    private static final AuthenticatedAccount USER = new AuthenticatedAccount(41, "merchant", AccountRole.USER,
            1, AccountStatus.ACTIVE, null, null, null);
    private final GeoMapper topics = mock(GeoMapper.class);
    private final GeoTrendMapper mapper = mock(GeoTrendMapper.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private final GeoTrendService service = new GeoTrendService(topics, mapper, json);

    @BeforeEach
    void ownership() {
        when(topics.findTopic(201, 41)).thenReturn(Optional.of(new GeoRecords.Topic(201, 41, 101,
                102L, "当前可编辑专题", "topic", "中国大陆", 9, NOW, NOW)));
    }

    @Test
    void emptyHistoryIsARealEmptyStateWithoutEvidenceRead() {
        var result = result();
        assertThat(result.platforms()).isEmpty();
        assertThat(result.history()).isEmpty();
        assertThat(result.historyTruncated()).isFalse();
        verify(mapper, never()).evidence(anyLong(), any(), anyLong(), anyInt());
    }

    @Test
    void onlyOnePointDoesNotInventPreviousZeroOrGrowth() throws Exception {
        var batch = batch(1, snapshot(2), 1);
        arrange(List.of(batch), List.of(evidence(1, 301, "无推荐", 1), evidence(1, 302, "星河T7", 1)));
        var point = points().getFirst();
        assertThat(point.baselineMentionRate()).isEqualTo(50.0);
        assertThat(point.comparisonStatus()).isEqualTo("FIRST_OBSERVATION");
        assertThat(point.comparison()).isNull();
        assertThat(point.connectToPrevious()).isFalse();
        assertThat(point.target().name()).isEqualTo("星河T7");
    }

    @Test
    void missingPlatformIdentifiesPreviousCurrentAndBothWithoutInventingZero() throws Exception {
        ObjectNode noDoubao = snapshot(2);
        noDoubao.withObject("/parameters").putArray("platformCodes").add("DEEPSEEK");
        arrange(List.of(batch(1, snapshot(2), 4), batch(2, noDoubao, 3),
                batch(3, noDoubao, 2), batch(4, snapshot(2), 1)), List.of(
                evidence(1, 301, "星河T7", 1), evidence(1, 302, "无", 1),
                evidence(4, 301, "星河T7", 1), evidence(4, 302, "无", 1)));
        var points = result().platforms().stream().filter(p -> p.platformCode().equals("DOUBAO"))
                .findFirst().orElseThrow().points();
        assertThat(points.get(1).missingPeriod()).isEqualTo("CURRENT");
        assertThat(points.get(2).missingPeriod()).isEqualTo("BOTH");
        assertThat(points.get(3).missingPeriod()).isEqualTo("PREVIOUS");
        assertThat(points.get(1).mentionRate()).isNull();
        assertThat(points.get(2).comparison()).isNull();
        for (int index = 1; index < points.size(); index++) {
            var point = points.get(index);
            assertThat(point.comparisonReason()).isEqualTo("PLATFORM_NOT_SAMPLED");
            assertThat(point.questionChanges()).allSatisfy(change ->
                    assertThat(change.missingPeriod()).isEqualTo(point.missingPeriod()));
        }
    }

    @Test
    void identicalFrozenQuestionsCanRiseThenFallWithExactPercentagePoints() throws Exception {
        arrange(List.of(batch(1, snapshot(2), 3), batch(2, snapshot(2), 2), batch(3, snapshot(2), 1)), List.of(
                evidence(1, 301, "无推荐", 1), evidence(1, 302, "无推荐", 1),
                evidence(2, 301, "星河T7", 1), evidence(2, 302, "星河T7", 1),
                evidence(3, 301, "星河T7", 1), evidence(3, 302, "无推荐", 1)));
        var points = points();
        assertThat(points.get(1).comparison().deltaPercentagePoints()).isEqualTo(100.0);
        assertThat(points.get(2).comparison().deltaPercentagePoints()).isEqualTo(-50.0);
        assertThat(points.get(1).connectToPrevious()).isTrue();
        assertThat(points.get(2).questionChanges()).extracting(GeoTrendDtos.QuestionChange::change)
                .containsExactly("CONTINUED_MENTION", "NO_LONGER_MENTIONED");
    }

    @Test
    void expansionNeverMovesOriginalBaselineEvenOnSecondSampling() throws Exception {
        arrange(List.of(batch(1, snapshot(2), 3), batch(2, snapshot(3), 2), batch(3, snapshot(3), 1)), List.of(
                evidence(1, 301, "星河T7", 1), evidence(1, 302, "无", 1),
                evidence(2, 301, "星河T7", 1), evidence(2, 302, "星河T7", 1), evidence(2, 303, "无", 1),
                evidence(3, 301, "星河T7", 1), evidence(3, 302, "星河T7", 1), evidence(3, 303, "星河T7", 1)));
        var result = result();
        assertThat(result.platforms().getFirst().baselineQuestionCount()).isEqualTo(2);
        var points = result.platforms().getFirst().points();
        assertThat(points.get(1).mentionRate()).isEqualTo(66.67);
        assertThat(points.get(1).baselineMentionRate()).isEqualTo(100.0);
        assertThat(points.get(2).explorationQuestionCount()).isEqualTo(1);
        assertThat(points.get(2).comparison().commonSamples()).isEqualTo(3);
        assertThat(points.get(2).comparison().deltaPercentagePoints()).isEqualTo(33.33);
        assertThat(points.get(1).questionChanges().getLast().inBaseline()).isFalse();
        assertThat(result.history().get(1).addedQuestionCount()).isEqualTo(1);
        assertThat(result.history().get(2).addedQuestionCount()).isZero();
    }

    @Test
    void laterAdjacentRunsRemainComparableAfterAnOldQuestionIdRewrite() throws Exception {
        ObjectNode rewrittenFour = snapshot(4);
        ObjectNode expandedFive = snapshot(5);
        for (int index = 0; index < 4; index++) {
            ((ObjectNode) rewrittenFour.path("authorizedContext").path("questions").get(index))
                    .put("id", Integer.toString(401 + index));
            ((ObjectNode) expandedFive.path("authorizedContext").path("questions").get(index))
                    .put("id", Integer.toString(401 + index));
        }
        ((ObjectNode) expandedFive.path("authorizedContext").path("questions").get(4))
                .put("id", "405");
        arrange(List.of(batch(1, snapshot(3), 3), batch(2, rewrittenFour, 2), batch(3, expandedFive, 1)), List.of(
                evidence(1, 301, "无", 1), evidence(1, 302, "无", 1), evidence(1, 303, "无", 1),
                rewrittenEvidence(2, 401, 301, "星河T7"), rewrittenEvidence(2, 402, 302, "无"),
                rewrittenEvidence(2, 403, 303, "无"), rewrittenEvidence(2, 404, 304, "无"),
                rewrittenEvidence(3, 401, 301, "星河T7"), rewrittenEvidence(3, 402, 302, "星河T7"),
                rewrittenEvidence(3, 403, 303, "无"), rewrittenEvidence(3, 404, 304, "无"),
                rewrittenEvidence(3, 405, 305, "无")));
        var point = points().getLast();
        assertThat(point.baselineExpectedSamples()).isEqualTo(3);
        assertThat(point.baselineValidSamples()).isZero();
        assertThat(point.baselineMentionRate()).isNull();
        assertThat(point.explorationQuestionCount()).isEqualTo(5);
        assertThat(point.comparisonStatus()).isEqualTo("COMPARABLE");
        assertThat(point.comparison().commonQuestions()).isEqualTo(4);
        assertThat(point.comparison().commonSamples()).isEqualTo(4);
        assertThat(point.comparison().previousMentionRate()).isEqualTo(25.0);
        assertThat(point.comparison().currentMentionRate()).isEqualTo(50.0);
        assertThat(point.comparison().deltaPercentagePoints()).isEqualTo(25.0);
        assertThat(point.connectToPrevious()).isFalse();
        assertThat(point.questionChanges().getLast().change()).isEqualTo("NEW_EXPLORATION");
    }

    @Test
    void commonValidRoundsRecomputePairButDifferentCoverageCannotConnectRawPoints() throws Exception {
        arrange(List.of(batch(1, snapshot(3), 2), batch(2, snapshot(3), 1)), List.of(
                evidence(1, 301, "星河T7", 1), evidence(1, 302, "无", 1), failed(1, 303),
                failed(2, 301), evidence(2, 302, "星河T7", 1), evidence(2, 303, "无", 1)));
        var point = points().getLast();
        assertThat(point.baselineMentionRate()).isEqualTo(50.0);
        assertThat(point.connectToPrevious()).isFalse();
        assertThat(point.comparison().commonQuestions()).isEqualTo(1);
        assertThat(point.comparison().previousMentionRate()).isZero();
        assertThat(point.comparison().currentMentionRate()).isEqualTo(100.0);
        assertThat(point.comparison().deltaPercentagePoints()).isEqualTo(100.0);
        assertThat(point.questionChanges().get(1).previousValidSamples()).isEqualTo(1);
        assertThat(point.questionChanges().get(0).change()).isEqualTo("NOT_COMPARABLE");
    }

    @Test
    void allFailureIsGapWithNoReportAndDoesNotBridgeFollowingSuccess() throws Exception {
        var failedBatch = batch(2, snapshot(2), 2);
        failedBatch = new GeoTrendMapper.Batch(2, null, 101, 102L, failedBatch.contextSnapshotJson(),
                "geo-observation-v1", failedBatch.observedAt());
        arrange(List.of(batch(1, snapshot(2), 3), failedBatch, batch(3, snapshot(2), 1)), List.of(
                evidence(1, 301, "星河T7", 1), evidence(1, 302, "星河T7", 1), failed(2, 301), failed(2, 302),
                evidence(3, 301, "星河T7", 1), evidence(3, 302, "星河T7", 1)));
        var points = points();
        assertThat(points.get(1).reportId()).isNull();
        assertThat(points.get(1).mentionRate()).isNull();
        assertThat(points.get(1).failedSamples()).isEqualTo(2);
        assertThat(points.get(2).connectToPrevious()).isFalse();
        assertThat(points.get(2).comparison()).isNull();
    }

    @Test
    void legacyMissingActualMethodShowsActualPointButCannotClaimChange() throws Exception {
        var old = evidence(1, 301, "无", 1);
        old = new GeoTrendMapper.Evidence(old.runId(), old.unitKey(), old.questionId(), old.questionText(),
                old.platformCode(), old.sampleNo(), old.status(), true, old.answerText(), old.provider(), old.model(), null, false);
        arrange(List.of(batch(1, snapshot(1), 2), batch(2, snapshot(1), 1)), List.of(old, evidence(2, 301, "星河T7", 1)));
        var point = points().getLast();
        assertThat(point.mentionRate()).isEqualTo(100.0);
        assertThat(point.comparisonReason()).isEqualTo("MISSING_SAMPLING_METADATA");
        assertThat(point.comparison()).isNull();
        assertThat(point.questionChanges().getFirst().change()).isEqualTo("NOT_COMPARABLE");
    }

    @Test
    void editedSameIdIsNotTheSameVersionAndRemovedBaselineDoesNotShrinkExpectedCount() throws Exception {
        ObjectNode edited = snapshot(2);
        ((ObjectNode) edited.path("authorizedContext").path("questions").get(0)).put("text", "已修改的问题");
        arrange(List.of(batch(1, snapshot(2), 2), batch(2, edited, 1)), List.of(
                evidence(1, 301, "星河T7", 1), evidence(1, 302, "无", 1), evidence(2, 302, "星河T7", 1)));
        var result = result();
        var point = result.platforms().getFirst().points().getLast();
        assertThat(point.baselineExpectedSamples()).isEqualTo(2);
        assertThat(point.baselineQuestionCount()).isEqualTo(1);
        assertThat(point.explorationQuestionCount()).isEqualTo(1);
        assertThat(point.connectToPrevious()).isFalse();
        assertThat(point.questionChanges().getFirst().reason()).isEqualTo("QUESTION_CHANGED");
        assertThat(result.history().getLast().changedQuestionCount()).isEqualTo(1);
    }

    @Test
    void reenabledBaselineQuestionIsNotFirstExplorationAndDoesNotBridge() throws Exception {
        ObjectNode removed = snapshot(2);
        ((com.fasterxml.jackson.databind.node.ArrayNode) removed.path("authorizedContext").path("questions")).remove(0);
        arrange(List.of(batch(1, snapshot(2), 3), batch(2, removed, 2), batch(3, snapshot(2), 1)), List.of(
                evidence(1, 301, "星河T7", 1), evidence(1, 302, "星河T7", 1), evidence(2, 302, "星河T7", 1),
                evidence(3, 301, "星河T7", 1), evidence(3, 302, "星河T7", 1)));
        var point = points().getLast();
        assertThat(point.baselineExpectedSamples()).isEqualTo(2);
        assertThat(point.explorationQuestionCount()).isZero();
        assertThat(point.connectToPrevious()).isFalse();
        assertThat(point.questionChanges().getFirst().change()).isEqualTo("NOT_COMPARABLE");
    }

    @Test
    void frozenTargetAliasesRegionModelAndCollectionChangesEachBreakComparison() throws Exception {
        for (String change : List.of("alias", "region", "model", "method")) {
            ObjectNode after = snapshot(1);
            if (change.equals("alias")) ((ObjectNode) after.path("catalogSnapshot").path("product")).putArray("aliases").add("新别名");
            if (change.equals("region")) ((ObjectNode) after.path("parameters")).put("region", "华南");
            var row = evidence(2, 301, "星河T7", 1);
            if (change.equals("model") || change.equals("method")) row = new GeoTrendMapper.Evidence(row.runId(), row.unitKey(),
                    row.questionId(), row.questionText(), row.platformCode(), 1, "SUCCEEDED", true, "星河T7", "DOUBAO",
                    change.equals("model") ? "other-model" : "model-v1", change.equals("method") ? "other-path-v2" : "native-search-v1", false);
            arrange(List.of(batch(1, snapshot(1), 2), batch(2, after, 1)), List.of(evidence(1, 301, "无", 1), row));
            var point = points().getLast();
            assertThat(point.comparison()).as(change).isNull();
            assertThat(point.connectToPrevious()).isFalse();
        }
    }

    @Test
    void naturalDiscoveryExcludesQuestionsNamingFrozenBrandOrProduct() throws Exception {
        ObjectNode snapshot = snapshot(2);
        ((ObjectNode) snapshot.path("authorizedContext").path("questions").get(1)).put("text", "星河T7值得买吗");
        arrange(List.of(batch(1, snapshot, 1)), List.of(evidence(1, 301, "星河T7", 1)));
        assertThat(result().platforms().getFirst().baselineQuestionCount()).isEqualTo(1);
        var validation = service.trend(USER, 201, 30, "BRAND_VALIDATION", NOW);
        assertThat(validation.platforms().getFirst().baselineQuestionCount()).isEqualTo(1);
        assertThat(validation.platforms().getFirst().points().getFirst().questionChanges().getFirst().questionText())
                .isEqualTo("星河T7值得买吗");
    }

    @Test
    void dateRangeNeverRebasesAndUsesImmediatePredecessor() throws Exception {
        var first = batch(1, snapshot(1), 60);
        var previous = batch(2, snapshot(2), 8);
        var current = batch(3, snapshot(2), 1);
        when(mapper.firstBatches(41, 201, 101)).thenReturn(List.of(first, previous, current));
        when(mapper.recentBatches(eq(41L), eq(201L), any(), any(), eq(101))).thenReturn(List.of(current));
        when(mapper.precedingBatch(41, 201, current.observedAt(), 3)).thenReturn(List.of(previous));
        when(mapper.evidence(eq(41L), any(), anyLong(), anyInt())).thenReturn(List.of(
                evidence(2, 301, "无", 1), evidence(3, 301, "星河T7", 1), evidence(3, 302, "无", 1)));
        var result = service.trend(USER, 201, 7, "DISCOVERY", NOW);
        assertThat(result.platforms().getFirst().baselineReportId()).isEqualTo("1001");
        assertThat(result.platforms().getFirst().points()).hasSize(1);
        assertThat(result.platforms().getFirst().points().getFirst().comparison().previousReportId()).isEqualTo("1002");
    }

    @Test
    void retriesCannotDuplicateAUnitAndMissingRowsStayMissing() throws Exception {
        var row = evidence(1, 301, "星河T7", 1);
        arrange(List.of(batch(1, snapshot(2), 1)), List.of(row, row));
        var point = points().getFirst();
        assertThat(point.validSamples()).isEqualTo(1);
        assertThat(point.missingSamples()).isEqualTo(1);
        assertThat(point.mentionedSamples()).isEqualTo(1);
    }

    @Test
    void newlyExploredQuestionDevelopsItsOwnComparisonWhenAllOriginalQuestionsAreAbsent() throws Exception {
        ObjectNode exploration = snapshot(2);
        ((com.fasterxml.jackson.databind.node.ArrayNode) exploration.path("authorizedContext").path("questions")).remove(0);
        arrange(List.of(batch(1, snapshot(1), 3), batch(2, exploration, 2), batch(3, exploration, 1)), List.of(
                evidence(1, 301, "无", 1), evidence(2, 302, "无", 1), evidence(3, 302, "星河T7", 1)));
        var points = points();
        assertThat(points.get(1).questionChanges().getFirst().change()).isEqualTo("NEW_EXPLORATION");
        var point = points.getLast();
        assertThat(point.baselineExpectedSamples()).isEqualTo(1);
        assertThat(point.baselineValidSamples()).isZero();
        assertThat(point.comparison().commonQuestions()).isEqualTo(1);
        assertThat(point.comparison().deltaPercentagePoints()).isEqualTo(100.0);
        assertThat(point.connectToPrevious()).isFalse();
        assertThat(point.questionChanges().getFirst().change()).isEqualTo("NEWLY_MENTIONED");
        assertThat(point.questionChanges().getFirst().inBaseline()).isFalse();
    }

    @Test
    void returningExplorationIsNotClaimedAsFirstEverSampling() throws Exception {
        arrange(List.of(batch(1, snapshot(1), 4), batch(2, snapshot(2), 3), batch(3, snapshot(1), 2),
                batch(4, snapshot(2), 1)), List.of(evidence(1, 301, "无", 1), evidence(2, 301, "无", 1),
                evidence(2, 302, "无", 1), evidence(3, 301, "无", 1),
                evidence(4, 301, "无", 1), evidence(4, 302, "星河T7", 1)));
        var point = points().getLast();
        assertThat(point.explorationQuestionCount()).isEqualTo(1);
        assertThat(point.questionChanges().getLast().change()).isEqualTo("NOT_COMPARABLE");
        assertThat(point.questionChanges().getLast().reason()).isEqualTo("QUESTION_CHANGED");
    }

    @Test
    void fixedRoundsAndQuestionRowsStayConsistentAfterSamplingModeChanges() throws Exception {
        ObjectNode deep = snapshot(1);
        ((ObjectNode) deep.path("parameters")).put("sampleMode", "DEEP");
        arrange(List.of(batch(1, snapshot(1), 3), batch(2, deep, 2), batch(3, deep, 1)), List.of(
                evidence(1, 301, "无", 1), evidence(2, 301, "无", 1), evidence(2, 301, "星河T7", 2), evidence(2, 301, "无", 3),
                evidence(3, 301, "星河T7", 1), evidence(3, 301, "星河T7", 2), evidence(3, 301, "无", 3)));
        var points = points();
        assertThat(points.get(1).comparisonReason()).isEqualTo("SAMPLING_CHANGED");
        var point = points.getLast();
        assertThat(point.baselineExpectedSamples()).isEqualTo(1);
        assertThat(point.comparison().commonSamples()).isEqualTo(3);
        assertThat(point.baselineMentionRate()).isEqualTo(100.0);
        assertThat(point.mentionRate()).isEqualTo(66.67);
        assertThat(point.questionChanges().getFirst().previousValidSamples()).isEqualTo(1);
        assertThat(point.questionChanges().getFirst().currentValidSamples()).isEqualTo(1);
    }

    @Test
    void anchoredTrendUsesOwnedRunAndBoundsBothBaselineAndRecentBatchQueries() throws Exception {
        var before = batch(1, snapshot(1), 2);
        var selected = batch(2, snapshot(1), 1);
        when(mapper.anchorBatch(41, 201, 2)).thenReturn(Optional.of(selected));
        when(mapper.firstBatchesThrough(41, 201, selected.observedAt(), 2, 101))
                .thenReturn(List.of(before, selected));
        when(mapper.recentBatchesThrough(eq(41L), eq(201L), any(), eq(selected.observedAt()), eq(2L), eq(101)))
                .thenReturn(List.of(selected, before));
        when(mapper.evidence(eq(41L), any(), anyLong(), anyInt())).thenReturn(List.of(
                evidence(1, 301, "无", 1), evidence(2, 301, "星河T7", 1)));
        var result = service.trendAtRun(USER, 201, 30, "DISCOVERY", 2);
        assertThat(result.platforms().getFirst().points()).extracting(GeoTrendDtos.Point::runId)
                .containsExactly("1", "2");
        assertThat(result.platforms().getFirst().baselineReportId()).isEqualTo("1001");
        verify(mapper, never()).firstBatches(anyLong(), anyLong(), anyInt());
        verify(mapper, never()).recentBatches(anyLong(), anyLong(), any(), any(), anyInt());
        var firstSql = String.join(" ", GeoTrendMapper.class.getMethod("firstBatchesThrough",
                long.class, long.class, OffsetDateTime.class, long.class, int.class).getAnnotation(Select.class).value());
        var recentSql = String.join(" ", GeoTrendMapper.class.getMethod("recentBatchesThrough",
                long.class, long.class, OffsetDateTime.class, OffsetDateTime.class, long.class, int.class)
                .getAnnotation(Select.class).value());
        assertThat(firstSql).contains("r.user_id=#{userId}", "r.topic_id=#{topicId}",
                "(COALESCE(r.started_at,r.created_at),r.id) <= (#{through},#{runId})");
        assertThat(recentSql).contains("r.user_id=#{userId}", "r.topic_id=#{topicId}",
                "(COALESCE(r.started_at,r.created_at),r.id) <= (#{through},#{runId})");
    }

    @Test
    void missingOrForeignAnchorCannotReadAnyHistory() {
        when(mapper.anchorBatch(41, 201, 99)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.trendAtRun(USER, 201, 30, "DISCOVERY", 99))
                .isInstanceOf(BusinessException.class);
        verify(mapper, never()).firstBatchesThrough(anyLong(), anyLong(), any(), anyLong(), anyInt());
        verify(mapper, never()).recentBatchesThrough(anyLong(), anyLong(), any(), any(), anyLong(), anyInt());
    }

    @Test
    void oneHundredPointLimitKeepsPredecessorForComparisonWithoutReturningPointOneHundredOne() throws Exception {
        List<GeoTrendMapper.Batch> batches = new ArrayList<>();
        ObjectNode frozen = snapshot(1);
        for (int i = 1; i <= 101; i++) batches.add(batch(i, frozen, 0));
        when(mapper.firstBatches(41, 201, 101)).thenReturn(batches);
        when(mapper.recentBatches(eq(41L), eq(201L), any(), any(), eq(101))).thenReturn(batches.reversed());
        when(mapper.precedingBatch(41, 201, NOW, 2)).thenReturn(List.of(batches.getFirst()));
        var result = result();
        assertThat(result.historyTruncated()).isTrue();
        assertThat(result.history()).hasSize(100);
        assertThat(result.platforms().getFirst().points()).hasSize(100);
        assertThat(result.platforms().getFirst().points().getFirst().runId()).isEqualTo("2");
        assertThat(result.platforms().getFirst().baselineReportId()).isEqualTo("1001");
    }

    @Test
    void evidenceTruncationDoesNotComputeAnUnderCountedRate() throws Exception {
        var row = evidence(1, 301, "星河T7", 1);
        row = new GeoTrendMapper.Evidence(row.runId(), row.unitKey(), row.questionId(), row.questionText(),
                "DOUBAO", 1, "SUCCEEDED", true, null, "DOUBAO", "model-v1", "native-search-v1", true);
        arrange(List.of(batch(1, snapshot(1), 1)), List.of(row));
        var result = result();
        assertThat(result.historyTruncated()).isTrue();
        var point = result.platforms().getFirst().points().getFirst();
        assertThat(point.evidenceTruncated()).isTrue();
        assertThat(point.validSamples()).isEqualTo(1);
        assertThat(point.mentionRate()).isNull();
    }

    @Test
    void ownershipAndInputValidationPrecedeAllHistoryReads() {
        assertThatThrownBy(() -> service.trend(USER, 201, 8, "DISCOVERY", NOW)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.trend(USER, 201, 30, "ALL", NOW)).isInstanceOf(BusinessException.class);
        when(topics.findTopic(201, 41)).thenReturn(Optional.empty());
        assertThatThrownBy(this::result).isInstanceOf(BusinessException.class);
        verifyNoInteractions(mapper);
    }

    private GeoTrendDtos.Response result() { return service.trend(USER, 201, 30, "DISCOVERY", NOW); }
    private List<GeoTrendDtos.Point> points() { return result().platforms().getFirst().points(); }

    private void arrange(List<GeoTrendMapper.Batch> batches, List<GeoTrendMapper.Evidence> rows) {
        when(mapper.firstBatches(41, 201, 101)).thenReturn(batches);
        when(mapper.recentBatches(eq(41L), eq(201L), any(), any(), eq(101))).thenReturn(
                batches.stream().sorted(Comparator.comparing(GeoTrendMapper.Batch::observedAt).reversed()).toList());
        when(mapper.evidence(eq(41L), any(), anyLong(), anyInt())).thenReturn(rows);
    }
    private GeoTrendMapper.Batch batch(long id, ObjectNode snapshot, int age) {
        return new GeoTrendMapper.Batch(id, 1000 + id, 101, 102L, snapshot.toString(), "geo-observation-v1", NOW.minusDays(age));
    }
    private ObjectNode snapshot(int questionCount) throws Exception {
        ObjectNode snapshot = (ObjectNode) json.readTree("""
                {"parameters":{"sampleMode":"STANDARD","region":"中国大陆","platformCodes":["DOUBAO"]},
                 "authorizedContext":{"questions":[]},
                 "catalogSnapshot":{"space":{"id":"101","brand":{"name":"星河"}},
                  "product":{"id":"102","spaceId":"101","name":"星河T7","category":"保温杯","aliases":[]}}}
                """);
        var questions = ((ObjectNode) snapshot.path("authorizedContext")).putArray("questions");
        for (int i = 0; i < questionCount; i++) questions.addObject().put("id", "" + (301 + i))
                .put("text", "消费者问题" + (301 + i)).put("category", "BRAND_DISCOVERY");
        return snapshot;
    }
    private GeoTrendMapper.Evidence evidence(long run, long question, String answer, int round) {
        return new GeoTrendMapper.Evidence(run, question + ":DOUBAO:" + round, question, "消费者问题" + question,
                "DOUBAO", round, "SUCCEEDED", true, answer, "DOUBAO", "model-v1", "native-search-v1", false);
    }
    private GeoTrendMapper.Evidence rewrittenEvidence(long run, long question, long originalTextId, String answer) {
        return new GeoTrendMapper.Evidence(run, question + ":DOUBAO:1", question, "消费者问题" + originalTextId,
                "DOUBAO", 1, "SUCCEEDED", true, answer, "DOUBAO", "model-v1", "native-search-v1", false);
    }
    private GeoTrendMapper.Evidence failed(long run, long question) {
        return new GeoTrendMapper.Evidence(run, question + ":DOUBAO:1", question, "消费者问题" + question,
                "DOUBAO", 1, "FAILED", false, null, "DOUBAO", "model-v1", "native-search-v1", false);
    }
}
