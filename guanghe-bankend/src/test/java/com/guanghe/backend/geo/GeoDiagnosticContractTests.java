package com.guanghe.backend.geo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guanghe.backend.catalog.CatalogNameNormalizer;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import static org.assertj.core.api.Assertions.*;

class GeoDiagnosticContractTests {
    private final ObjectMapper json=new ObjectMapper();
    private final GeoDiagnosticService service=new GeoDiagnosticService(null,null,null,null,json,null,new CatalogNameNormalizer(),null);
    @Test void versionedPreparationSupportsPriorResultsButNeverUnknownVersions() {
        assertThat(GeoDiagnosticService.supportedPromptVersion("GEO_DIAGNOSTIC_PREPARATION_V1")).isTrue();
        assertThat(GeoDiagnosticService.supportedPromptVersion("GEO_DIAGNOSTIC_PREPARATION_V2")).isTrue();
        assertThat(GeoDiagnosticService.supportedPromptVersion("GEO_DIAGNOSTIC_PREPARATION_V3")).isTrue();
        assertThat(GeoDiagnosticService.supportedPromptVersion("GEO_DIAGNOSTIC_PREPARATION_V4")).isTrue();
        assertThat(GeoDiagnosticService.supportedPromptVersion("GEO_DIAGNOSTIC_PREPARATION_V5")).isTrue();
        assertThat(GeoDiagnosticService.supportedPromptVersion("GEO_DIAGNOSTIC_PREPARATION_V6")).isFalse();
        assertThat(GeoDiagnosticService.supportedPromptVersion(null)).isFalse();
    }
    @Test void boundedEvidenceKeepsSelectedCitationsThenFreshSourcesWithoutChangingHistory() {
        ObjectNode data=json.createObjectNode();var prior=new ArrayList<GeoDiagnosticDtos.Source>();
        for(int i=1;i<=32;i++)prior.add(source("old-"+i));
        data.set("sources",json.valueToTree(prior));var history=data.deepCopy();
        var fresh=new ArrayList<GeoDiagnosticDtos.Source>();for(int i=1;i<=5;i++)fresh.add(source("fresh-"+i));
        data.putArray("competitors").addObject().put("origin","USER").put("edited",true).putArray("sourceIds").add("old-32");
        data.withArray("competitors").addObject().put("origin","AI").put("edited",false).putArray("sourceIds").add("fresh-3");
        var incoming=json.valueToTree(fresh);var available=service.sourceCandidates(data,incoming);
        assertThat(available).hasSize(37);
        service.retainSources(data,available,incoming);
        var selected=json.convertValue(data.path("sources"),GeoDiagnosticDtos.Source[].class);
        assertThat(selected).hasSize(32).extracting(GeoDiagnosticDtos.Source::id)
            .contains("old-32","fresh-1","fresh-2","fresh-3","fresh-4","fresh-5").doesNotContain("old-31");
        assertThat(selected[0]).isEqualTo(prior.getLast());assertThat(selected[1]).isEqualTo(fresh.get(2));
        assertThat(history.path("sources")).isEqualTo(json.valueToTree(prior));
        assertThat(selected).allSatisfy(s->assertThat(available.get(s.id())).isEqualTo(json.valueToTree(s)));
    }
    @Test void evidenceDoesNotOverwriteIdentityEvenWhenAnUnreferencedSourceWouldBeTrimmed() {
        ObjectNode data=json.createObjectNode();data.set("sources",json.valueToTree(List.of(source("old-1"))));
        var changed=new GeoDiagnosticDtos.Source("old-1","替换标题","https://example.com/other","替换原文");
        assertThatThrownBy(()->service.sourceCandidates(data,json.valueToTree(List.of(changed)))).isInstanceOf(RuntimeException.class);
        assertThat(data.path("sources")).isEqualTo(json.valueToTree(List.of(source("old-1"))));
    }
    @Test void evidenceRejectsMissingOrOverCapacityRequiredCitationsRatherThanDroppingThem() {
        ObjectNode data=json.createObjectNode();var sources=new ArrayList<GeoDiagnosticDtos.Source>();
        for(int i=1;i<=32;i++)sources.add(source("old-"+i));data.set("sources",json.valueToTree(sources));
        var incoming=json.valueToTree(List.of(source("fresh-1")));var available=service.sourceCandidates(data,incoming);
        var competitors=data.putArray("competitors");
        for(int i=1;i<=5;i++){var references=competitors.addObject().putArray("sourceIds");
            for(int j=(i-1)*8+1;j<=Math.min(i*8,33);j++)references.add(j==33?"fresh-1":"old-"+j);}
        assertThatThrownBy(()->service.retainSources(data,available,incoming)).isInstanceOf(RuntimeException.class);
        data.putArray("competitors").addObject().putArray("sourceIds").add("missing");
        assertThatThrownBy(()->service.retainSources(data,available,incoming)).isInstanceOf(RuntimeException.class);
        assertThat(data.path("sources")).isEqualTo(json.valueToTree(sources));
    }
    private static GeoDiagnosticDtos.Source source(String id) {
        return new GeoDiagnosticDtos.Source(id,"来源 "+id,"https://example.com/"+id,"实际节选 "+id);
    }
    @Test void diagnosticTermsIncludeShortLongAndCategoryNamesWithoutSubstringFalsePositives() {
        assertThat(GeoDiagnosticService.matchesNames("品牌禾是否值得考虑？",java.util.List.of("禾"))).isTrue();
        String longName="禾".repeat(180);
        assertThat(GeoDiagnosticService.matchesNames(longName+"体验如何？",java.util.List.of(longName))).isTrue();
        assertThat(GeoDiagnosticService.matchesNames("咖啡好不好？",java.util.List.of("咖啡"))).isTrue();
        assertThat(GeoDiagnosticService.matchesNames("premium咖啡",java.util.List.of("mi"))).isFalse();
        assertThat(GeoDiagnosticService.matchesNames("MI 好不好？",java.util.List.of("Ｍｉ"))).isTrue();
        assertThat(GeoDiagnosticService.matchesNames("Mix好不好？",java.util.List.of("mi"))).isFalse();
        assertThat(GeoDiagnosticService.matchesNames("2X体验",java.util.List.of("X"))).isFalse();
    }
    @Test void websiteAndSourceUrlsHaveDifferentSupportedProtocolsAndRejectLocalAddresses() {
        assertThat(GeoDiagnosticService.website("example.com/brand")).isEqualTo("https://example.com/brand");
        assertThat(GeoDiagnosticService.sourceUrl("http://example.com/article")).isEqualTo("http://example.com/article");
        for(String url:new String[]{"http://example.com","https://localhost/a","https://127.0.0.1/a","https://a.local/a","https://user:secret@example.com/a"})
            assertThatThrownBy(()->GeoDiagnosticService.website(url)).isInstanceOf(RuntimeException.class);
        for(String url:new String[]{"file:///tmp/x","http://169.254.169.254/a","http://[::1]/a","http://a.local/a","https://user:secret@example.com/a"})
            assertThatThrownBy(()->GeoDiagnosticService.sourceUrl(url)).isInstanceOf(RuntimeException.class);
    }
    @Test void independentFeatureFlagDefaultsOffAndGatesEveryEntryAndWorker() {
        assertThat(new com.guanghe.backend.task.PaidTaskProperties().isGeoDiagnosticEnabled()).isFalse();
        for(Class<?> type:new Class<?>[]{GeoDiagnosticController.class,GeoDiagnosticService.class,GeoDiagnosticPreparationWorker.class}) {
            var condition=type.getAnnotation(ConditionalOnProperty.class);
            assertThat(condition.name()).containsExactly("enabled","geo-diagnostic-enabled");assertThat(condition.havingValue()).isEqualTo("true");
        }
    }
    @Test void userCompetitorCannotCollideWithTheReservedOwnIdentity() {
        var data=new GeoDiagnosticProjectionTests().snapshot().path("authorizedContext").path("diagnostic");
        for(String reserved:List.of("OWN"," OWN ")){
            var value=new GeoDiagnosticDtos.Competitor(reserved,"辰光咖啡",List.of("辰光咖啡"),"同类门店",List.of(),"USER",true);
            assertThatThrownBy(()->org.springframework.test.util.ReflectionTestUtils.invokeMethod(service,"competitors",List.of(value),data))
                .isInstanceOf(com.guanghe.backend.common.api.BusinessException.class);
        }
        var value=new GeoDiagnosticDtos.Competitor(UUID.randomUUID().toString(),"辰光咖啡",List.of("辰光咖啡"),"同类门店",List.of(),"USER",true);
        List<GeoDiagnosticDtos.Competitor> accepted=org.springframework.test.util.ReflectionTestUtils.invokeMethod(service,"competitors",List.of(value),data);
        assertThat(accepted).hasSize(1).first().extracting(GeoDiagnosticDtos.Competitor::id).isEqualTo(value.id());
    }
}
