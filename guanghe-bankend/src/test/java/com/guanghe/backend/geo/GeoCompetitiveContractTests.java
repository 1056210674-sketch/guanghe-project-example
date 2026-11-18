package com.guanghe.backend.geo;

import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.oas.models.media.Schema;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import static org.assertj.core.api.Assertions.*;

class GeoCompetitiveContractTests {
    @Test void browserContractAndControllerExposeExactlyFiveOwnedOperations() {
        var api=new OpenAPIV3Parser().read(getClass().getResource("/contracts/openapi/browser-openapi.yaml").toExternalForm());
        Set<String> paths=new HashSet<>();
        for(var method:GeoCompetitiveController.class.getDeclaredMethods()) {
            var get=method.getAnnotation(GetMapping.class);var post=method.getAnnotation(PostMapping.class);
            String path=get!=null?get.value()[0]:post.value()[0];paths.add(path);
            var operation=get!=null?api.getPaths().get(path).getGet():api.getPaths().get(path).getPost();
            assertThat(operation).isNotNull();assertThat(operation.getSecurity()).isNotEmpty();
            assertThat(operation.getResponses()).containsKeys("200","401","403","404","422");
            if(get!=null)assertThat(operation.getRequestBody()).isNull();
        }
        assertThat(paths).hasSize(5);
    }
    @Test void browserSchemasMatchRecordFieldsAndFailureNullableRates() {
        var api=new OpenAPIV3Parser().read(getClass().getResource("/contracts/openapi/browser-openapi.yaml").toExternalForm());
        Map<String,Class<?>> records=Map.of("GeoCompetitiveInsights",GeoCompetitiveDtos.Insights.class,"GeoCompetitiveEntity",GeoCompetitiveDtos.Entity.class,
                "GeoCompetitivePlatform",GeoCompetitiveDtos.Platform.class,"GeoCompetitorCandidate",GeoCompetitiveDtos.Candidate.class,
                "GeoCompetitiveEvidence",GeoCompetitiveDtos.Evidence.class,"GeoCompetitorConfirmation",GeoCompetitiveDtos.Confirmation.class);
        records.forEach((name,type)->assertThat(api.getComponents().getSchemas().get(name).getProperties().keySet())
                .containsExactlyInAnyOrderElementsOf(Arrays.stream(type.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).toList()));
        var entity=api.getComponents().getSchemas().get("GeoCompetitiveEntity");
        assertThat(((Schema<?>)entity.getProperties().get("mentionRate")).getTypes()).contains("number","null");
        assertThat(((Schema<?>)entity.getProperties().get("mentionRate")).getMaximum()).isEqualByComparingTo("100");
        assertThat(entity.getProperties()).doesNotContainKeys("rank","confidence","delta","marketShare");
        assertThat(((Schema<?>)api.getComponents().getSchemas().get("GeoCompetitiveInsights").getProperties().get("methodVersion")).getConst()).isEqualTo(GeoCompetitiveService.METHOD);
    }
    @Test void internalDiscoveryIsOptionalBoundedAndEvidenceTyped() {
        var api=new OpenAPIV3Parser().read(getClass().getResource("/contracts/openapi/java-internal-openapi.yaml").toExternalForm());
        var synthesis=api.getComponents().getSchemas().get("GeoSynthesis");
        assertThat(synthesis.getRequired()).doesNotContain("competitorCandidates","competitorDiscoveryVersion");
        var candidates=(Schema<?>)synthesis.getProperties().get("competitorCandidates");assertThat(candidates.getMaxItems()).isEqualTo(6);
        var candidate=candidates.getItems();assertThat(candidate.getRequired()).containsExactlyInAnyOrder("name","entityKind","evidence");
        var evidence=(Schema<?>)candidate.getProperties().get("evidence");assertThat(evidence.getMaxItems()).isEqualTo(2);assertThat(evidence.getMinItems()).isEqualTo(1);
        assertThat(((Schema<?>)evidence.getItems().getProperties().get("quote")).getMaxLength()).isEqualTo(240);
        assertThat(candidate.getProperties()).doesNotContainKeys("confidence","aliases","takeaway","mentionRate");
    }
}
