package com.guanghe.backend.conversation.action;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RunCreativeContinuationDtosTests {
    private final ObjectMapper json=new ObjectMapper();
    private final ValidatorFactory validators=Validation.buildDefaultValidatorFactory();
    @AfterEach void cleanup() { validators.close(); }

    @Test void creativeReviewIsOptionalAndOldActionConstructorsRemainCompatible() throws Exception {
        var video=new RunActionDtos.VideoGenerate("TEXT_ONLY","猫跳舞",10,"9:16","720P",false,null,null);
        var image=new RunActionDtos.ImageGenerate("TEXT_ONLY","猫",null,1,null,null,null);
        assertThat(video.continuationReview()).isNull();assertThat(image.continuationReview()).isNull();
        assertThat(validators.getValidator().validate(video)).isEmpty();
        var decoded=json.treeToValue(json.createObjectNode().put("sourceMode","TEXT_ONLY").put("scene","猫跳舞")
                .put("durationSeconds",10),RunActionDtos.VideoGenerate.class);
        assertThat(decoded.durationSeconds()).isEqualTo(10);assertThat(decoded.continuationReview()).isNull();
    }

    @Test void creativeReviewRequiresAllFiveBoundFieldsAndValidSemantics() throws Exception {
        var valid=validReview();
        assertThat(validators.getValidator().validate(json.treeToValue(valid,RunActionDtos.CreativeContinuationReview.class))).isEmpty();
        for(String field:List.of("currentMessageId","historyFingerprint","taskType","decision","sourceMode")) {
            var missing=valid.deepCopy();missing.remove(field);
            assertThat(validators.getValidator().validate(json.treeToValue(missing,RunActionDtos.CreativeContinuationReview.class)))
                    .as(field).isNotEmpty();
        }
        for(var invalid:List.of(valid.deepCopy().put("currentMessageId","-1"),valid.deepCopy().put("historyFingerprint","a".repeat(63)),
                valid.deepCopy().put("taskType","ARTICLE"),valid.deepCopy().put("decision","DISCUSS"),
                valid.deepCopy().put("sourceMode","UNCLEAR"),
                valid.deepCopy().put("sourceImageAssetId","-1"))) {
            assertThat(validators.getValidator().validate(json.treeToValue(invalid,RunActionDtos.CreativeContinuationReview.class))).isNotEmpty();
        }
    }

    @Test void nestedReviewValidationCannotBeSkippedAndUnknownAuthorityFieldsAreRejected() throws Exception {
        var input=json.createObjectNode().put("sourceMode","TEXT_ONLY").put("scene","猫跳舞").put("durationSeconds",10);
        input.set("continuationReview",validReview().put("taskType","ARTICLE"));
        assertThat(validators.getValidator().validate(json.treeToValue(input,RunActionDtos.VideoGenerate.class))).isNotEmpty();
        for(String field:List.of("authorized","projectId","userId","history")) {
            assertThatThrownBy(()->json.treeToValue(validReview().put(field,"forged"),RunActionDtos.CreativeContinuationReview.class))
                    .isInstanceOf(JsonProcessingException.class);
        }
    }

    @Test void currentAttachmentReviewUsesTheSameBoundReviewWithoutAModelSelectedAssetId() throws Exception {
        var review=validReview().put("taskType","IMAGE").put("sourceMode","CURRENT_ATTACHMENTS");
        var decoded=json.treeToValue(review,RunActionDtos.CreativeContinuationReview.class);
        assertThat(validators.getValidator().validate(decoded)).isEmpty();
        assertThat(decoded.sourceImageAssetId()).isNull();
        var input=json.createObjectNode().put("sourceMode","CURRENT_ATTACHMENTS").put("scene","背景再淡一点");
        input.set("continuationReview",review);
        assertThat(validators.getValidator().validate(json.treeToValue(input,RunActionDtos.ImageGenerate.class))).isEmpty();
    }

    private ObjectNode validReview() {
        return json.createObjectNode().put("currentMessageId","61").put("historyFingerprint","b".repeat(64))
                .put("taskType","VIDEO").put("decision","CREATE_NEW").put("sourceMode","TEXT_ONLY");
    }
}
