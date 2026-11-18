package com.guanghe.backend.geo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.conversation.context.InternalRunContextDtos;
import com.guanghe.backend.task.PaidTaskProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeoCreativePlanProofTests {
    private final ObjectMapper json = new ObjectMapper();
    private final PaidTaskProperties properties = new PaidTaskProperties();
    private final GeoCreativePlanProof proof = new GeoCreativePlanProof(properties, json);

    @Test
    void proofBindsPlanOwnerAndCompleteCurrentProductFacts() {
        properties.setTokenSecret("geo-visual-proof-test-secret-with-32-bytes");
        InternalRunContextDtos.Product product = product(List.of("不得承诺治疗"));
        ObjectNode plan = json.createObjectNode();
        plan.put("reportId", "301");
        plan.put("message", "展示真实使用");
        plan.putArray("evidenceKeys").add("unit-1");
        proof.sign(plan, 41, 101, 102, product);
        assertThat(plan.path("proof").asText()).matches("^v1\\.[A-Za-z0-9_-]{43}$");
        proof.verify(plan, 41, 101, 102, product);

        ObjectNode edited = plan.deepCopy();
        edited.put("message", "伪造效果承诺");
        invalid(() -> proof.verify(edited, 41, 101, 102, product));
        invalid(() -> proof.verify(plan, 42, 101, 102, product));
        invalid(() -> proof.verify(plan, 41, 999, 102, product));
        invalid(() -> proof.verify(plan, 41, 101, 999, product));
        invalid(() -> proof.verify(plan, 41, 101, 102, product(List.of("新增禁用宣称"))));
        InternalRunContextDtos.Product newFact = new InternalRunContextDtos.Product(
                product.id(), product.spaceId(), product.name(), product.aliases(), product.category(),
                product.modelSpec(), List.of("商品事实已更新"), product.parameters(), product.audience(),
                product.usageScenarios(), product.prohibitedClaims(), product.primaryAssetId(),
                product.identityAssetIds());
        invalid(() -> proof.verify(plan, 41, 101, 102, newFact));
    }

    private InternalRunContextDtos.Product product(List<String> prohibited) {
        return new InternalRunContextDtos.Product("102", "101", "咖啡机", List.of(), "家电", "A1",
                List.of("方便使用"), Map.of("功率", "100W"), List.of("居家"), List.of("早晨"),
                prohibited, "11", List.of("11"));
    }

    private void invalid(Runnable verification) {
        assertThatThrownBy(verification::run).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }
}
