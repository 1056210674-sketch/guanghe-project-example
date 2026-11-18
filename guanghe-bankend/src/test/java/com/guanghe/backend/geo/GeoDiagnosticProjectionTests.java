package com.guanghe.backend.geo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GeoDiagnosticProjectionTests {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();final GeoTaskMapper mapper=mock(GeoTaskMapper.class);
    @Test void discoveryAndReputationHaveSeparateDenominatorsAndFailureIsNotZero() {
        var evidence=evidence(List.of(observation(10,"DOUBAO","星河咖啡供应冷萃。",false),observation(11,"DOUBAO","其他门店适合上班族。",false),
            observation(12,"DOUBAO","星河咖啡是否适合需要先看门店信息。",false),observation(10,"DEEPSEEK",null,true)));
        var m=evidence.path("metrics");assertThat(m.path("expectedSamples").asInt()).isEqualTo(6);assertThat(m.path("validSamples").asInt()).isEqualTo(3);
        assertThat(m.path("failedSamples").asInt()).isEqualTo(1);assertThat(m.path("missingSamples").asInt()).isEqualTo(2);
        assertThat(m.path("discovery").get(0).path("validSamples").asInt()).isEqualTo(2);
        assertThat(m.path("discovery").get(0).path("mentions").get(0).path("mentionRate").asDouble()).isEqualTo(.5);
        assertThat(m.path("discovery").get(1).path("mentions").get(0).path("mentionRate").isNull()).isTrue();
        assertThat(m.path("reputation").get(0).path("recognizedSamples").asInt()).isEqualTo(1);
    }
    @Test void laterBrandContextIsAContinuousOriginalWindowAndFullAnswerStillComputesMention() {
        String original="开头说明。".repeat(400)+"星河咖啡供应冷萃。后续评价仍须看门店信息。";
        var evidence=evidence(List.of(observation(10,"DOUBAO",original,false)));
        assertThat(evidence.path("metrics").path("discovery").get(0).path("mentions").get(0).path("mentionedSamples").asInt()).isEqualTo(1);
        String excerpt=evidence.path("samples").get(0).path("answerExcerpt").asText();
        assertThat(excerpt).contains("星河咖啡供应冷萃。后续评价");assertThat(original).contains(excerpt);assertThat(excerpt.length()).isLessThanOrEqualTo(1600);
        assertThat(evidence.path("samples").get(0).path("answerTruncated").asBoolean()).isTrue();
        assertThat(evidence.path("samples").get(0).path("matchedBrandIds").toString()).isEqualTo("[\"OWN\"]");
    }
    @Test void allFailedAndMissingKeepsWholeMatrixWithUnknownRates() {
        var evidence=evidence(List.of(observation(10,"DOUBAO",null,true)));
        assertThat(evidence.path("samples")).hasSize(6);
        assertThat(evidence.path("metrics").path("validSamples").asInt()).isZero();
        assertThat(evidence.path("metrics").path("missingSamples").asInt()).isEqualTo(5);
        for(var p:evidence.path("metrics").path("discovery"))assertThat(p.path("mentions").get(0).path("mentionRate").isNull()).isTrue();
        assertThat(evidence.path("samples").get(0).path("answerExcerpt").isNull()).isTrue();
        for(var sample:evidence.path("samples"))assertThat(sample.path("matchedBrandIds")).isEmpty();
    }
    @Test void citationsKeepRuntimeIdsAndActualLinksRatherThanModelUrls() {
        var observation=observation(10,"DOUBAO","星河咖啡供应冷萃。",false);
        when(mapper.findCitations(observation.id())).thenReturn(List.of(new GeoTaskRecords.Citation(61,observation.id(),1,3,1,"实际来源",
            "https://example.com/actual","example.com","真实引用片段",OffsetDateTime.now(),OffsetDateTime.now())));
        var evidence=evidence(List.of(observation));assertThat(evidence.path("samples").get(0).path("sourceIds").get(0).asText()).isEqualTo("OBS:61");
        assertThat(evidence.path("sources").get(1).path("url").asText()).isEqualTo("https://example.com/actual");
    }
    @Test void modelSourcesBoundFallbackTitlesAndExcludeNonpublicOrNonstandardPortsWithoutChangingOriginals() {
        var observed=observation(10,"DOUBAO","星河咖啡供应冷萃。",false);var now=OffsetDateTime.now();String longUrl="https://example.com/"+"x".repeat(1200);
        var citations=List.of(new GeoTaskRecords.Citation(61,observed.id(),1,3,1,null,longUrl,"example.com","真实引用",now,now),
            new GeoTaskRecords.Citation(62,observed.id(),1,3,2,"私网来源","http://127.0.0.1/a","127.0.0.1","原始私网引用",now,now),
            new GeoTaskRecords.Citation(63,observed.id(),1,3,3,"非标准端口","https://example.com:8443/a","example.com","原始引用",now,now));
        when(mapper.findCitations(observed.id())).thenReturn(citations);var result=evidence(List.of(observed));
        assertThat(result.path("sources")).hasSize(2);assertThat(result.path("sources").get(1).path("title").asText()).hasSize(500);
        assertThat(result.path("samples").get(0).path("sourceIds")).hasSize(1);assertThat(result.path("limitations").toString()).contains("标准端口","完整原始引用");
        assertThat(citations.get(0).url()).isEqualTo(longUrl);assertThat(citations.get(1).snippet()).isEqualTo("原始私网引用");
    }
    @Test void modelDictionaryAvoidsRepeatedQuestionsAndFiltersBlankLegacyWarnings() {
        var snapshot=snapshot();((ObjectNode)snapshot.path("authorizedContext").path("diagnostic")).withArray("warnings").add("").add("   ").add("保留有效提示");
        var result=GeoDiagnosticProjection.evidence(run(snapshot),List.of(observation(10,"DOUBAO","星河咖啡供应冷萃。",false)),mapper,json);
        assertThat(result.path("questions")).hasSize(3);for(var sample:result.path("samples"))assertThat(sample.has("questionText")).isFalse();
        for(var limit:result.path("limitations"))assertThat(limit.asText()).isNotBlank();assertThat(result.path("limitations").toString()).contains("保留有效提示");
        assertThat(result.path("limitations").toString()).contains("准备阶段提示（不代表最终诊断报告的全部证据）");
    }
    @Test void fullAnswerBrandFlagsCoverBothGroupsAndAgreeWithAuthoritativeDiscoveryMetrics() {
        var result=evidence(List.of(observation(10,"DOUBAO","开头".repeat(500)+"星河咖啡和辰光咖啡。",false),observation(11,"DOUBAO","其他门店。",false),
            observation(12,"DOUBAO","开头".repeat(500)+"星河咖啡和辰光咖啡。",false)));
        assertThat(result.path("samples").get(0).path("answerExcerpt").asText()).contains("星河咖啡");
        assertThat(result.path("samples").get(0).path("matchedBrandIds").toString()).isEqualTo("[\"OWN\",\"comp-1\"]");
        assertThat(result.path("samples").get(2).path("matchedBrandIds")).isEmpty();
        assertThat(result.path("samples").get(4).path("matchedBrandIds").toString()).isEqualTo("[\"OWN\",\"comp-1\"]");
        assertThat(result.path("metrics").path("discovery").get(0).path("mentions").get(0).path("mentionedSamples").asInt()).isEqualTo(1);
        assertThat(result.path("metrics").path("reputation").get(0).path("recognizedSamples").asInt()).isEqualTo(1);
    }
    @Test void fullTenBySevenMatrixKeepsMetricsAndDictionaryWhileModelEvidenceIsByteBounded() throws Exception {
        var snapshot=snapshot();var codes=((ObjectNode)snapshot.path("parameters")).putArray("platformCodes");
        List<String> platforms=List.of("DOUBAO","DEEPSEEK","QWEN","WENXIN","KIMI","YUANBAO","ZHIPU");platforms.forEach(codes::add);
        var questions=((ObjectNode)snapshot.path("authorizedContext")).putArray("questions");List<GeoTaskRecords.Observation> observed=new ArrayList<>();var now=OffsetDateTime.now();
        for(int q=10;q<20;q++){questions.addObject().put("id",Integer.toString(q)).put("category",q<17?"BRAND_DISCOVERY":"BRAND_REPUTATION").put("text","长问题".repeat(320));
            for(int p=0;p<7;p++){long observationId=q*10L+p;String answer="原文".repeat(650)+"星河咖啡";
                observed.add(new GeoTaskRecords.Observation(observationId,1,2,3,UUID.randomUUID(),"a".repeat(64),q+":"+platforms.get(p)+":1",q,q,"长问题",platforms.get(p),1,"SUCCEEDED",answer,null,platforms.get(p),"fixture",now,now,1,"{}",1,now,"fixture-v1"));
                when(mapper.findCitations(observationId)).thenReturn(List.of(new GeoTaskRecords.Citation(observationId*2,observationId,1,3,1,"来源","https://example.com/source/"+observationId,"example.com","引用".repeat(500),now,now),
                    new GeoTaskRecords.Citation(observationId*2+1,observationId,1,3,2,"来源二","https://example.com/source/second/"+observationId,"example.com","引用".repeat(500),now,now)));}}
        var r=new GeoTaskRecords.Run(1,2,3,4,null,5,"RUNNING","STANDARD",70,70,0,0,snapshot.toString(),"geo-diagnostic-observation-v1","geo-diagnostic-sampling-v1",now,null,now,now,70,0,0);
        var result=GeoDiagnosticProjection.evidence(r,observed,mapper,json);assertThat(result.path("samples")).hasSize(70);assertThat(result.path("questions")).hasSize(10);
        assertThat(result.path("metrics").path("validSamples").asInt()).isEqualTo(70);assertThat(json.writeValueAsBytes(result).length).isLessThan(250*1024);
        for(var platform:result.path("metrics").path("discovery"))assertThat(platform.path("mentions").get(0).path("mentionRate").asDouble()).isEqualTo(1d);
        for(var sample:result.path("samples")){assertThat(sample.has("questionText")).isFalse();assertThat(sample.path("answerTruncated").asBoolean()).isTrue();}
        assertThat(result.path("limitations").toString()).contains("完整来源","解读使用的回答和来源摘录");assertThat(observed.getFirst().answerText()).endsWith("星河咖啡");
    }
    @Test void capacityIsRejectedBeforePaidAdmissionForOversizedMinimumFacts() {
        var data=(ObjectNode)snapshot().path("authorizedContext").path("diagnostic").deepCopy();var profile=(ObjectNode)data.path("profile");profile.put("introduction","资料".repeat(3000));
        var aliases=profile.putArray("brandTerms");for(int i=0;i<50;i++)aliases.add("自家品牌"+"词".repeat(190)+i);
        var competitors=data.putArray("competitors");for(int c=0;c<5;c++){var comp=competitors.addObject().put("id","comp-"+c).put("name","同类品牌"+c);var terms=comp.putArray("brandTerms");for(int t=0;t<50;t++)terms.add("竞品词"+"字".repeat(190)+t);}
        var questions=data.putArray("discoveryQuestions");for(int q=0;q<9;q++)questions.addObject().put("text","问题".repeat(490));data.putArray("reputationQuestions").addObject().put("text","自家品牌体验如何？");
        var platforms=data.putArray("platformCodes");GeoTaskService.PLATFORM_ORDER.forEach(platforms::add);
        assertThatThrownBy(()->GeoDiagnosticProjection.preflight(data,json)).isInstanceOf(com.guanghe.backend.common.api.BusinessException.class).hasMessageContaining("输入容量");
    }
    @Test void legacyDraftReservedCompetitorIdentityIsRejectedBeforeQuoteOrPaidAdmission() {
        var data=(ObjectNode)snapshot().path("authorizedContext").path("diagnostic").deepCopy();
        ((ObjectNode)data.path("competitors").get(0)).put("id","OWN");String frozen=data.toString();
        assertThatThrownBy(()->GeoDiagnosticProjection.preflight(data,json)).isInstanceOfSatisfying(com.guanghe.backend.common.api.BusinessException.class,
            e->assertThat(e.details()).containsEntry("reason","DIAGNOSTIC_RESERVED_BRAND_ID"));
        assertThat(data.toString()).isEqualTo(frozen);
    }
    @Test void exactVerifiedQuotesAndCoveredBrandTermsProduceTypedBlueprint() {
        var evidence=goodEvidence();var report=goodAnalysis();
        var validated=GeoDiagnosticProjection.validateAnalysis(report,evidence,json);
        assertThat(validated.contentTopics()).hasSize(1);assertThat(validated.findings()).hasSize(3);
        assertThat(validated.contentTopics().getFirst().coveredBrandTerms()).containsExactly("星河咖啡");
    }
    @Test void consultingAnalysisKeepsStatisticalSummaryAndAddsEvidenceBasedActionsWithoutChangingFrozenData() {
        var evidence=goodEvidence();var report=consultingAnalysis();String frozen=evidence.toString();
        var result=GeoDiagnosticProjection.validateAnalysis(report,evidence,json,true);
        assertThat(result.summary()).contains("本品牌「星河咖啡」 1/2");
        assertThat(result.strategy().coreJudgment().headline()).isEqualTo("先补齐可核查的品牌事实");
        assertThat(result.strategy().keywordLayers().getFirst().questionIds()).containsExactly("10");
        assertThat(result.recommendations().getFirst().acceptance()).contains("同题复测");
        assertThat(result.contentTopics().getFirst().intentLayer()).isEqualTo("SCENARIO");assertThat(evidence.toString()).isEqualTo(frozen);
        assertThat(GeoDiagnosticProjection.validateAnalysis(goodAnalysis(),evidence,json).strategy()).isNull();
        assertThatThrownBy(()->GeoDiagnosticProjection.validateAnalysis(goodAnalysis(),evidence,json,true)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void consultingTextBoundsCountUnicodeCodePointsLikePythonAndJsonSchema() {
        var evidence=goodEvidence();var report=consultingAnalysis();
        String atLimit="𠮷野家".repeat(40);assertThat(atLimit.codePointCount(0,atLimit.length())).isEqualTo(120);
        assertThat(atLimit.length()).isGreaterThan(120);
        var core=(ObjectNode)report.path("strategy").path("coreJudgment");core.put("headline",atLimit);
        assertThat(GeoDiagnosticProjection.validateAnalysis(report,evidence,json,true).strategy().coreJudgment().headline()).isEqualTo(atLimit);
        core.put("headline",atLimit+"店");
        assertThatThrownBy(()->GeoDiagnosticProjection.validateAnalysis(report,evidence,json,true)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void consultingLayersAndRoadmapRejectUnmeasuredQuestionsWrongGroupsAndNonexistentActions() {
        var evidence=goodEvidence();
        for(String id:List.of("999","12","11")){
            var report=consultingAnalysis();((ObjectNode)report.path("strategy").path("keywordLayers").get(0)).putArray("questionIds").add(id);
            assertThatThrownBy(()->GeoDiagnosticProjection.validateAnalysis(report,evidence,json,true)).as("invalid or unrelated question %s",id).isInstanceOf(IllegalArgumentException.class);
        }
        for(int index:List.of(-1,1)){
            var report=consultingAnalysis();((ObjectNode)report.path("strategy").path("roadmap").get(0)).putArray("recommendationIndexes").add(index);
            assertThatThrownBy(()->GeoDiagnosticProjection.validateAnalysis(report,evidence,json,true)).isInstanceOf(IllegalArgumentException.class);
        }
        var duplicate=consultingAnalysis();((ObjectNode)duplicate.path("strategy")).withArray("keywordLayers").add(duplicate.path("strategy").path("keywordLayers").get(0).deepCopy());
        assertThatThrownBy(()->GeoDiagnosticProjection.validateAnalysis(duplicate,evidence,json,true)).isInstanceOf(IllegalArgumentException.class);
        var invented=consultingAnalysis();((ObjectNode)invented.path("strategy").path("coreJudgment").path("evidence").get(0)).put("quote","不存在的权威评价");
        assertThatThrownBy(()->GeoDiagnosticProjection.validateAnalysis(invented,evidence,json,true)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void representativeExcerptPrefersOwnBrandThenCompetitorWithoutStitchingOrInventingText() {
        var data=snapshot().path("authorizedContext").path("diagnostic");String answer="前文。".repeat(700)+"辰光咖啡是候选。"+"中间。".repeat(700)+"星河咖啡适合冷萃需求，仍需确认营业时间。";
        String own=GeoDiagnosticProjection.answerWindow(answer,data.path("profile"),data.path("competitors"),1600);
        assertThat(answer).contains(own);assertThat(own).contains("星河咖啡适合冷萃需求").doesNotContain("辰光咖啡");
        String onlyCompetitor="说明。".repeat(700)+"辰光咖啡提供手冲。";
        assertThat(GeoDiagnosticProjection.answerWindow(onlyCompetitor,data.path("profile"),data.path("competitors"),1600)).contains("辰光咖啡");
        String unrelated="没有任何品牌。".repeat(400);
        assertThat(GeoDiagnosticProjection.answerWindow(unrelated,data.path("profile"),data.path("competitors"),1600)).isEqualTo(unrelated.substring(0,1600));
    }
    @Test void sectionOmissionsAreRejectedWhenThatGroupHasValidSamples() {
        var evidence=goodEvidence();var report=goodAnalysis();report.withArray("findings").remove(1);
        assertThatThrownBy(()->GeoDiagnosticProjection.validateAnalysis(report,evidence,json)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void missingCompetitionSectionRejectedOnlyWithSelectedCompetitorsAndValidDiscovery() {
        var evidence=goodEvidence();var report=goodAnalysis();report.withArray("findings").remove(2);
        assertThatThrownBy(()->GeoDiagnosticProjection.validateAnalysis(report,evidence,json)).isInstanceOf(IllegalArgumentException.class);
        evidence.putArray("competitors");assertThat(GeoDiagnosticProjection.validateAnalysis(report,evidence,json).findings()).hasSize(2);
    }
    @Test void inventedQuotesUrlsAndSourceIdsAreRejected() {
        var evidence=goodEvidence();var report=goodAnalysis();
        ((ObjectNode)report.path("findings").get(0).path("evidence").get(0)).put("quote","不存在的句子");
        var badQuote=report;assertThatThrownBy(()->GeoDiagnosticProjection.validateAnalysis(badQuote,evidence,json)).isInstanceOf(IllegalArgumentException.class);
        report=goodAnalysis();report.put("summary","https://invented.example.com");var urlReport=report;
        assertThatThrownBy(()->GeoDiagnosticProjection.validateAnalysis(urlReport,evidence,json)).isInstanceOf(IllegalArgumentException.class);
        report=goodAnalysis();var f=report.withArray("findings").addObject().put("section","PUBLIC_RESEARCH").put("heading","公开信息").put("description","仅核对来源");
        f.putArray("evidence").addObject().putNull("unitKey").put("sourceId","missing-source").put("quote","真实公开信息");var missing=report;
        assertThatThrownBy(()->GeoDiagnosticProjection.validateAnalysis(missing,evidence,json)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void publicResearchCannotBeLaunderedThroughSamplingOrEmptyCitationSnippets() {
        var evidence=goodEvidence();var report=goodAnalysis();((ObjectNode)report.path("findings").get(0)).put("section","PUBLIC_RESEARCH");
        assertThatThrownBy(()->GeoDiagnosticProjection.validateAnalysis(report,evidence,json)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void blueprintCannotInventAliasesOrPromoteUnselectedCompanies() {
        var evidence=goodEvidence();var report=goodAnalysis();((ObjectNode)report.path("contentTopics").get(0)).putArray("coveredBrandTerms").add("未知品牌");
        assertThatThrownBy(()->GeoDiagnosticProjection.validateAnalysis(report,evidence,json)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void summaryUsesFrozenThreeOfFourInsteadOfModelFourOfFourWithoutChangingQualitativeWorkOrInputs() {
        var evidence=evidence(List.of(observation(10,"DOUBAO","星河咖啡供应冷萃。辰光咖啡也被提及。",false),
            observation(11,"DOUBAO","辰光咖啡供应手冲。",false),observation(10,"DEEPSEEK","辰光咖啡供应手冲。",false),
            observation(11,"DEEPSEEK","其他咖啡店。",false),observation(12,"DOUBAO","星河咖啡是否适合需要先看门店信息。",false)));
        var report=goodAnalysis();report.put("summary","辰光咖啡在4/4自然回答中被提及，稳定领先。");
        String frozenEvidence=evidence.toString(),frozenReport=report.toString();var result=GeoDiagnosticProjection.validateAnalysis(report,evidence,json);
        assertThat(result.summary()).contains("同类品牌「辰光咖啡」 3/4","本品牌「星河咖啡」 1/4","点名口碑：有效回答 1 / 预计 2")
            .doesNotContain("4/4","稳定领先");
        assertThat(json.valueToTree(result).path("findings")).isEqualTo(report.path("findings"));
        var typedOriginal=json.convertValue(report,GeoDiagnosticReportDtos.Analysis.class);
        assertThat(result.recommendations()).isEqualTo(typedOriginal.recommendations());
        assertThat(result.contentTopics()).isEqualTo(typedOriginal.contentTopics());
        assertThat(json.valueToTree(result).path("limitations")).isEqualTo(report.path("limitations"));
        assertThat(evidence.toString()).isEqualTo(frozenEvidence);assertThat(report.toString()).isEqualTo(frozenReport);
    }
    @Test void summaryExcludesFailedAndMissingSamplesFromMentionDenominator() {
        var evidence=evidence(List.of(observation(10,"DOUBAO","星河咖啡供应冷萃。",false),observation(11,"DOUBAO","辰光咖啡供应手冲。",false),
            observation(12,"DOUBAO","星河咖啡是否适合需要先看门店信息。",false),observation(10,"DEEPSEEK",null,true)));
        var summary=GeoDiagnosticProjection.validateAnalysis(goodAnalysis(),evidence,json).summary();
        assertThat(summary).contains("预计 6 个样本：有效 3，失败 1，缺失 2","自然发现：有效回答 2 / 预计 4",
            "本品牌「星河咖啡」 1/2","同类品牌「辰光咖啡」 1/2","豆包 1/2","DeepSeek 暂无有效自然回答").doesNotContain("0.0%","本品牌「星河咖啡」 1/4");
    }
    @Test void summaryJoinsMetricsByBrandIdAndUsesConfirmedNamesRatherThanArrayOrderOrMetricLabels() {
        var evidence=goodEvidence();((ObjectNode)evidence.path("competitors").get(0)).put("name","辰光确认品牌");
        for(var platform:evidence.path("metrics").path("discovery")){
            var mentions=(com.fasterxml.jackson.databind.node.ArrayNode)platform.path("mentions");var own=mentions.remove(0);
            ((ObjectNode)own).put("name","错误展示名");mentions.add(own);
        }
        String summary=GeoDiagnosticProjection.validateAnalysis(goodAnalysis(),evidence,json).summary();
        assertThat(summary).contains("本品牌「星河咖啡」 1/2","同类品牌「辰光确认品牌」 1/2").doesNotContain("错误展示名");
        ((ObjectNode)evidence.path("metrics").path("discovery").get(0).path("mentions").get(0)).put("brandId","unknown-id");
        assertThatThrownBy(()->GeoDiagnosticProjection.validateAnalysis(goodAnalysis(),evidence,json)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void summaryWithNoValidSamplesSaysUnknownNotZeroVisibilityOrReputation() {
        var evidence=evidence(List.of(observation(10,"DOUBAO",null,true)));var report=goodAnalysis();
        for(var finding:report.path("findings")){
            ((ObjectNode)finding).put("section","PUBLIC_RESEARCH");publicRef((ObjectNode)finding.path("evidence").get(0));
        }
        publicRef((ObjectNode)report.path("recommendations").get(0).path("basisEvidence").get(0));
        publicRef((ObjectNode)report.path("contentTopics").get(0).path("basisEvidence").get(0));
        String summary=GeoDiagnosticProjection.validateAnalysis(report,evidence,json).summary();
        assertThat(summary).contains("有效 0，失败 1，缺失 5","各品牌的自然提及比例不可计算","暂无有效口碑回答")
            .doesNotContain("0%","0.0%","本品牌「星河咖啡」 0/");
    }
    @Test void summaryStaysWithinBoundAtFiveCompetitorsSevenPlatformsAndMaximumBrandNameLength() {
        var snapshot=snapshot();var diagnostic=(ObjectNode)snapshot.path("authorizedContext").path("diagnostic");
        ((ObjectNode)diagnostic.path("profile")).put("brandName","品牌".repeat(100));var competitors=diagnostic.putArray("competitors");
        for(int i=0;i<5;i++)competitors.addObject().put("id","comp-"+i).put("name",("同类"+i).repeat(66)+"品牌").putArray("brandTerms").add("辰光咖啡");
        var codes=((ObjectNode)snapshot.path("parameters")).putArray("platformCodes");GeoTaskService.PLATFORM_ORDER.forEach(codes::add);
        var questions=((ObjectNode)snapshot.path("authorizedContext")).putArray("questions");
        List<GeoTaskRecords.Observation> observed=new ArrayList<>();
        for(int q=10;q<20;q++){
            questions.addObject().put("id",Integer.toString(q)).put("category",q==12?"BRAND_REPUTATION":"BRAND_DISCOVERY").put("text","问题"+q);
            for(String platform:GeoTaskService.PLATFORM_ORDER)observed.add(observation(q,platform,"星河咖啡供应冷萃。辰光咖啡供应手冲。星河咖啡是否适合需要先看门店信息。",false));
        }
        var now=OffsetDateTime.now();var run=new GeoTaskRecords.Run(1,2,3,4,null,5,"SUCCEEDED","STANDARD",70,70,0,0,snapshot.toString(),
            "geo-diagnostic-observation-v1","geo-diagnostic-sampling-v1",now,now,now,now,70,70,0);
        var evidence=GeoDiagnosticProjection.evidence(run,observed,mapper,json);String summary=GeoDiagnosticProjection.validateAnalysis(goodAnalysis(),evidence,json).summary();
        assertThat(summary.length()).isLessThan(4000);assertThat(summary).contains("覆盖 7 个平台","预计 70 个样本：有效 70","自然发现：有效回答 63 / 预计 63","点名口碑：有效回答 7 / 预计 7");
        for(var competitor:competitors)assertThat(summary).contains("同类品牌「"+competitor.path("name").asText()+"」 63/63");
    }
    private void publicRef(ObjectNode reference){reference.putNull("unitKey").put("sourceId","web-1").put("quote","真实公开信息");}
    ObjectNode goodEvidence(){return evidence(List.of(observation(10,"DOUBAO","星河咖啡供应冷萃。",false),observation(11,"DOUBAO","辰光咖啡供应手冲。",false),observation(12,"DOUBAO","星河咖啡是否适合需要先看门店信息。",false)));}
    ObjectNode evidence(List<GeoTaskRecords.Observation> observations){return GeoDiagnosticProjection.evidence(run(snapshot()),observations,mapper,json);}
    GeoTaskRecords.Run run(ObjectNode snapshot){var now=OffsetDateTime.now();return new GeoTaskRecords.Run(1,2,3,4,null,5,"RUNNING","STANDARD",6,0,0,6,snapshot.toString(),
        "geo-diagnostic-observation-v1","geo-diagnostic-sampling-v1",now,null,now,now,6,0,0);}
    ObjectNode snapshot(){
        var snapshot=json.createObjectNode();snapshot.putObject("parameters").put("mode","DIAGNOSTIC_RUN").putArray("platformCodes").add("DOUBAO").add("DEEPSEEK");
        var auth=snapshot.putObject("authorizedContext");var questions=auth.putArray("questions");
        for(int q=10;q<=12;q++)questions.addObject().put("id",Integer.toString(q)).put("category",q==12?"BRAND_REPUTATION":"BRAND_DISCOVERY").put("text",q==12?"星河咖啡体验如何？":"广州适合上班族的咖啡店有哪些？"+q);
        var diagnostic=auth.putObject("diagnostic");diagnostic.set("profile",json.valueToTree(new GeoDiagnosticDtos.Profile("星河咖啡","https://example.com","现磨咖啡门店",List.of("星河咖啡","Star Coffee"),"广州")));
        diagnostic.putArray("competitors").addObject().put("id","comp-1").put("name","辰光咖啡").putArray("brandTerms").add("辰光咖啡");
        diagnostic.putArray("sources").addObject().put("id","web-1").put("title","实际公开来源").put("url","https://example.com/about").put("snippet","真实公开信息");diagnostic.putArray("warnings");return snapshot;
    }
    GeoTaskRecords.Observation observation(long question,String platform,String answer,boolean failed){var now=OffsetDateTime.now();return new GeoTaskRecords.Observation(question+(platform.equals("DEEPSEEK")?100:0),1,2,3,UUID.randomUUID(),"a".repeat(64),
        question+":"+platform+":1",(int)question,question,"问题",platform,1,failed?"FAILED":"SUCCEEDED",answer,failed?"PROVIDER_TIMEOUT":null,platform,"fixture",now,now,1,"{}",1,now,"fixture-v1");}
    ObjectNode goodAnalysis(){
        var report=json.createObjectNode().put("title","星河咖啡 GEO 诊断").put("summary","自然发现与点名口碑分开解释。");var findings=report.putArray("findings");
        finding(findings.addObject(),"DISCOVERY","10:DOUBAO:1","星河咖啡供应冷萃。");finding(findings.addObject(),"REPUTATION","12:DOUBAO:1","星河咖啡是否适合需要先看门店信息。");
        finding(findings.addObject(),"COMPETITION","11:DOUBAO:1","辰光咖啡供应手冲。");
        var suggestion=report.putArray("recommendations").addObject().put("priority","P1").put("title","完善品牌事实").put("action","补充可核查的门店公开资料。");ref(suggestion.putArray("basisEvidence").addObject(),"10:DOUBAO:1","星河咖啡供应冷萃。");
        var topic=report.putArray("contentTopics").addObject().put("title","广州上班族怎么选冷萃").put("audienceQuestion","广州有哪些冷萃门店？").put("angle","从实际产品与门店场景回答。");
        topic.putArray("coveredBrandTerms").add("星河咖啡");ref(topic.putArray("basisEvidence").addObject(),"10:DOUBAO:1","星河咖啡供应冷萃。");report.putArray("limitations").add("单次采样不代表长期趋势。");return report;
    }
    ObjectNode consultingAnalysis(){
        var report=goodAnalysis();var suggestion=(ObjectNode)report.path("recommendations").get(0);
        suggestion.put("currentState","本批只观察到冷萃事实").put("businessImpact","顾客缺少判断依据").put("deliverable","一份核对后的门店事实页")
            .put("acceptance","核对来源并以同题复测检查表述").put("ownerRole","建议品牌负责人").put("timeframe","建议第一阶段");
        ((ObjectNode)report.path("contentTopics").get(0)).put("intentLayer","SCENARIO");
        var strategy=report.putObject("strategy").put("version","consulting-v1");
        var core=strategy.putObject("coreJudgment").put("headline","先补齐可核查的品牌事实").put("description","本批回答描述冷萃，但点名问题仍缺少判断细节。");
        ref(core.putArray("evidence").addObject(),"10:DOUBAO:1","星河咖啡供应冷萃。");
        var cognition=strategy.putArray("cognitionFindings").addObject().put("title","适合性信息尚待补齐").put("observation","回答提示先看门店信息").put("businessImpact","顾客仍需进一步核实");
        ref(cognition.putArray("evidence").addObject(),"12:DOUBAO:1","星河咖啡是否适合需要先看门店信息。");
        var layer=strategy.putArray("keywordLayers").addObject().put("layer","SCENARIO").put("assessment","本题出现了冷萃事实").put("nextAction","补充上班族购买场景");
        layer.putArray("questionIds").add("10");ref(layer.putArray("evidence").addObject(),"10:DOUBAO:1","星河咖啡供应冷萃。");
        strategy.putArray("roadmap").addObject().put("stage","建议第一阶段").put("objective","确认并公开关键门店信息").putArray("recommendationIndexes").add(0);return report;
    }
    private void finding(ObjectNode node,String section,String key,String quote){node.put("section",section).put("heading","发现 "+section).put("description","基于实际采样说明，不推断排名。");ref(node.putArray("evidence").addObject(),key,quote);}
    private void ref(ObjectNode node,String key,String quote){node.put("unitKey",key).putNull("sourceId").put("quote",quote);}
}
