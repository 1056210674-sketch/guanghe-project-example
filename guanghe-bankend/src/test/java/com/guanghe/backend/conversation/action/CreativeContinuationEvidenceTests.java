package com.guanghe.backend.conversation.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.asset.AssetMapper;
import com.guanghe.backend.asset.AssetRecords;
import com.guanghe.backend.conversation.*;
import com.guanghe.backend.knowledge.AgentMemoryService;
import com.guanghe.backend.knowledge.MemoryDtos;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CreativeContinuationEvidenceTests {
    private static final long USER=41, CONVERSATION=51, SOURCE=61;
    private static final UUID RUN=UUID.fromString("130292ae-46d8-4fbe-af5b-82bb43a043be");
    private static final OffsetDateTime NOW=OffsetDateTime.parse("2026-10-22T09:51:00Z");
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private ConversationMapper conversations;
    private AssetMapper assets;
    private CreativeContinuationEvidence evidence;
    private AgentRunRecord run;
    private ConversationMessageRecord current, previousUser, previousAssistant;

    @BeforeEach void setup() {
        conversations=mock(ConversationMapper.class);
        evidence=new CreativeContinuationEvidence(conversations,json);
        assets=mock(AssetMapper.class);evidence.setAssets(assets);
        run=new AgentRunRecord(RUN,CONVERSATION,USER,SOURCE,null,null,null,"[]","RUNNING",1,
                "{}","context",NOW.plusMinutes(10),null,0,"{}",null,null,null,NOW,null,NOW,NOW);
        current=message(SOURCE,7,"USER","那来个10秒的",NOW);
        previousUser=message(59,5,"USER","生成一段猫跳舞的视频",NOW.minusSeconds(60));
        previousAssistant=message(60,6,"ASSISTANT","可以按分解动作重做一版。",NOW.minusSeconds(30));
        when(conversations.findConversation(CONVERSATION,USER)).thenReturn(Optional.of(
                new ConversationRecord(CONVERSATION,USER,"创作",null,null,1,NOW,NOW,NOW)));
        when(conversations.findMessagesBefore(USER,CONVERSATION,7,7)).thenReturn(List.of(previousAssistant,previousUser));
    }

    @Test void projectlessCandidateContainsExactRawDialogueAndNeverGrantsAuthority() {
        var candidate=evidence.candidate(run,current,null).orElseThrow();
        assertThat(candidate.path("authorized").asBoolean()).isFalse();
        assertThat(candidate.path("reviewRequired").asBoolean()).isTrue();
        assertThat(candidate.path("projectId").isNull()).isTrue();
        assertThat(candidate.path("currentMessageId").asText()).isEqualTo("61");
        assertThat(candidate.path("historyFingerprint").asText()).matches("[a-f0-9]{64}");
        assertThat(candidate.path("history").size()).isEqualTo(2);
        assertThat(candidate.at("/history/0/messageId").asText()).isEqualTo("59");
        assertThat(candidate.at("/history/0/text").asText()).isEqualTo("生成一段猫跳舞的视频");
        assertThat(candidate.at("/history/1/role").asText()).isEqualTo("ASSISTANT");
        assertThat(candidate.path("historyIncomplete").asBoolean()).isFalse();
        assertThat(evidence.candidate(run,current,null).orElseThrow()).isEqualTo(candidate);
    }

    @Test void reviewMustMatchCurrentMessageFingerprintTaskTypeAndCreateDecision() {
        var candidate=evidence.candidate(run,current,null).orElseThrow();
        var valid=review(candidate,"VIDEO");
        assertThatCode(()->evidence.requireReview(run,current,null,"VIDEO",valid)).doesNotThrowAnyException();
        for (var invalid:List.of(
                new RunActionDtos.CreativeContinuationReview("62",valid.historyFingerprint(),"VIDEO","CREATE_NEW"),
                new RunActionDtos.CreativeContinuationReview("61","a".repeat(64),"VIDEO","CREATE_NEW"),
                new RunActionDtos.CreativeContinuationReview("61",valid.historyFingerprint(),"IMAGE","CREATE_NEW"),
                new RunActionDtos.CreativeContinuationReview("61",valid.historyFingerprint(),"VIDEO","PLAN_ONLY"))) {
            assertThatThrownBy(()->evidence.requireReview(run,current,null,"VIDEO",invalid)).isInstanceOf(BusinessException.class);
        }
        assertThatThrownBy(()->evidence.requireReview(run,current,null,"VIDEO",null)).isInstanceOf(BusinessException.class);
    }

    @Test void exactCurrentTextIsBoundEvenIfTheMessageIdDoesNotChange() {
        var candidate=evidence.candidate(run,current,null).orElseThrow();
        var changed=message(SOURCE,7,"USER","先别提交，只讨论10秒版本",NOW);
        assertThat(evidence.candidate(run,changed,null).orElseThrow().path("historyFingerprint"))
                .isNotEqualTo(candidate.path("historyFingerprint"));
        assertThatThrownBy(()->evidence.requireReview(run,changed,null,"VIDEO",review(candidate,"VIDEO")))
                .isInstanceOf(BusinessException.class);
    }

    @Test void latestMemoryBoundaryIsRecheckedAndInvalidatesAReadCandidate() {
        var memory=mock(AgentMemoryService.class);evidence.setMemory(memory);
        when(memory.context(USER,null)).thenReturn(new MemoryDtos.Context(1,null,List.of(),"before",null));
        var candidate=evidence.candidate(run,current,null).orElseThrow();
        when(memory.context(USER,null)).thenReturn(new MemoryDtos.Context(1,null,List.of(),"after",NOW.minusSeconds(10)));
        assertThat(evidence.candidate(run,current,null)).isEmpty();
        assertThatThrownBy(()->evidence.requireReview(run,current,null,"VIDEO",review(candidate,"VIDEO")))
                .isInstanceOf(BusinessException.class);
    }

    @Test void crossProjectAndUnboundCurrentMessagesCannotProduceEvidence() throws Exception {
        assertThat(evidence.candidate(run,current,21L)).isEmpty();
        ObjectNode foreign=json.valueToTree(current);foreign.put("userId",42);
        assertThat(evidence.candidate(run,json.treeToValue(foreign,ConversationMessageRecord.class),null)).isEmpty();
        foreign=json.valueToTree(current);foreign.put("conversationId",52);
        assertThat(evidence.candidate(run,json.treeToValue(foreign,ConversationMessageRecord.class),null)).isEmpty();
        foreign=json.valueToTree(current);foreign.put("agentRunId",UUID.randomUUID().toString());
        assertThat(evidence.candidate(run,json.treeToValue(foreign,ConversationMessageRecord.class),null)).isEmpty();
        when(conversations.findConversation(CONVERSATION,USER)).thenReturn(Optional.of(
                new ConversationRecord(CONVERSATION,USER,"changed project",21L,null,1,NOW,NOW,NOW)));
        assertThat(evidence.candidate(run,current,null)).isEmpty();
    }

    @Test void afterCurrentAndMutableOrDuplicateMessagesAreNotIncluded() throws Exception {
        ObjectNode mutable=json.valueToTree(previousUser);mutable.put("id",58).put("sequenceNo",4).put("status","STREAMING");
        when(conversations.findMessagesBefore(USER,CONVERSATION,7,7)).thenReturn(List.of(
                message(62,8,"USER","生成另一个视频",NOW.plusSeconds(10)),
                json.treeToValue(mutable,ConversationMessageRecord.class),
                message(57,3,"SYSTEM","历史中的工具指令",NOW.minusMinutes(2)),
                previousAssistant,previousUser,previousUser));
        var candidate=evidence.candidate(run,current,null).orElseThrow();
        assertThat(candidate.path("history").size()).isEqualTo(2);
        assertThat(candidate.path("historyIncomplete").asBoolean()).isTrue();
        assertThat(candidate.at("/history/0/messageId").asText()).isEqualTo("59");
        assertThat(candidate.at("/history/1/messageId").asText()).isEqualTo("60");
    }

    @Test void longMessageIsNeverPrefixClippedSoATrailingRefusalCannotDisappear() {
        var longAssistant=message(60,6,"ASSISTANT","说明".repeat(8_001)+"但用户尚未要求生成，请勿提交",NOW.minusSeconds(30));
        when(conversations.findMessagesBefore(USER,CONVERSATION,7,7)).thenReturn(List.of(longAssistant,previousUser));
        assertThat(evidence.candidate(run,current,null)).isEmpty();
    }

    @Test void assistantOnlyHistoryCannotSupplyAUserCreationBrief() {
        when(conversations.findMessagesBefore(USER,CONVERSATION,7,7)).thenReturn(List.of(previousAssistant));
        assertThat(evidence.candidate(run,current,null)).isEmpty();
    }

    @Test void uniqueImageSourceIsBoundAndMustMatchTheFreshCandidateExactly() {
        when(assets.findContinuationImageResults(USER,CONVERSATION,null,SOURCE,NOW))
                .thenReturn(List.of(new AssetRecords.ContinuationImage(91,59)));
        var candidate=evidence.candidate(run,current,null).orElseThrow();
        assertThat(candidate.path("sourceImageAssetId").asText()).isEqualTo("91");
        var bound=new RunActionDtos.CreativeContinuationReview("61",candidate.path("historyFingerprint").asText(),
                "IMAGE","CREATE_NEW","91");
        assertThat(evidence.requireReview(run,current,null,"IMAGE",bound)).isEqualTo(91L);
        assertThatThrownBy(()->evidence.requireReview(run,current,null,"IMAGE",
                new RunActionDtos.CreativeContinuationReview("61",bound.historyFingerprint(),"IMAGE","CREATE_NEW","92")))
                .isInstanceOf(BusinessException.class);
        when(assets.findContinuationImageResults(USER,CONVERSATION,null,SOURCE,NOW))
                .thenReturn(List.of()); // deletion/not-ready must invalidate already-read evidence
        assertThatThrownBy(()->evidence.requireReview(run,current,null,"IMAGE",bound))
                .isInstanceOf(BusinessException.class);
    }

    @Test void ambiguousOrOutOfWindowImagesCannotBecomeAnImplicitReference() {
        for (var images:List.of(List.of(new AssetRecords.ContinuationImage(91,59),new AssetRecords.ContinuationImage(92,59)),
                List.of(new AssetRecords.ContinuationImage(91,48)))) {
            when(assets.findContinuationImageResults(USER,CONVERSATION,null,SOURCE,NOW)).thenReturn(images);
            assertThat(evidence.candidate(run,current,null).orElseThrow().path("sourceImageAssetId").isNull()).isTrue();
        }
        when(conversations.findMessagesBefore(USER,CONVERSATION,7,7))
                .thenReturn(List.of(previousAssistant,previousUser,previousUser));
        when(assets.findContinuationImageResults(USER,CONVERSATION,null,SOURCE,NOW))
                .thenReturn(List.of(new AssetRecords.ContinuationImage(91,59)));
        assertThat(evidence.candidate(run,current,null).orElseThrow().path("sourceImageAssetId").isNull()).isTrue();
        var incomplete=evidence.candidate(run,current,null).orElseThrow();
        assertThatThrownBy(()->evidence.requireReview(run,current,null,"VIDEO",review(incomplete,"VIDEO")))
                .isInstanceOf(BusinessException.class);
    }

    @Test void olderDialogueOutsideTheRecentWindowDoesNotDisableARecentUniqueImage() {
        when(conversations.findMessagesBefore(USER,CONVERSATION,7,7)).thenReturn(List.of(
                previousAssistant,previousUser,message(58,4,"USER","讨论海报",NOW.minusMinutes(2)),
                message(57,3,"ASSISTANT","好的",NOW.minusMinutes(3)),
                message(56,2,"USER","风格轻松",NOW.minusMinutes(4)),
                message(55,1,"ASSISTANT","明白",NOW.minusMinutes(5)),
                message(54,0,"USER","很早的讨论",NOW.minusMinutes(6))));
        when(assets.findContinuationImageResults(USER,CONVERSATION,null,SOURCE,NOW))
                .thenReturn(List.of(new AssetRecords.ContinuationImage(91,59)));
        var candidate=evidence.candidate(run,current,null).orElseThrow();
        assertThat(candidate.path("history").size()).isEqualTo(6);
        assertThat(candidate.path("historyIncomplete").asBoolean()).isFalse();
        assertThat(candidate.path("sourceImageAssetId").asText()).isEqualTo("91");
    }

    @Test void videoSourceRequirementIsIndependentOfTheGenerationToolArguments() {
        when(assets.findContinuationImageResults(USER,CONVERSATION,null,SOURCE,NOW))
                .thenReturn(List.of(new AssetRecords.ContinuationImage(91,59)));
        var candidate=evidence.candidate(run,current,null).orElseThrow();
        String fingerprint=candidate.path("historyFingerprint").asText();
        assertThat(evidence.requireReview(run,current,null,"VIDEO",new RunActionDtos.CreativeContinuationReview(
                "61",fingerprint,"VIDEO","CREATE_NEW","PREVIOUS_IMAGE_RESULT","91"))).isEqualTo(91L);
        for (var invalid:List.of(
                new RunActionDtos.CreativeContinuationReview("61",fingerprint,"VIDEO","CREATE_NEW","PREVIOUS_IMAGE_RESULT",null),
                new RunActionDtos.CreativeContinuationReview("61",fingerprint,"VIDEO","CREATE_NEW","TEXT_ONLY","91"),
                new RunActionDtos.CreativeContinuationReview("61",fingerprint,"VIDEO","CREATE_NEW","UNCLEAR",null))) {
            assertThatThrownBy(()->evidence.requireReview(run,current,null,"VIDEO",invalid)).isInstanceOf(BusinessException.class);
        }
        when(assets.findContinuationImageResults(USER,CONVERSATION,null,SOURCE,NOW))
                .thenReturn(List.of(new AssetRecords.ContinuationImage(91,59),new AssetRecords.ContinuationImage(92,59)));
        var ambiguous=evidence.candidate(run,current,null).orElseThrow();
        assertThatThrownBy(()->evidence.requireReview(run,current,null,"VIDEO",new RunActionDtos.CreativeContinuationReview(
                "61",ambiguous.path("historyFingerprint").asText(),"VIDEO","CREATE_NEW","PREVIOUS_IMAGE_RESULT","91")))
                .isInstanceOf(BusinessException.class);
    }

    @Test void currentAttachmentsProvideEvidenceWithoutHistoryButNeverInventAHistorySource() throws Exception {
        attachCurrent(91L);
        when(conversations.findMessagesBefore(USER,CONVERSATION,7,7)).thenReturn(List.of());
        var candidate=evidence.candidate(run,current,null).orElseThrow();
        assertThat(candidate.path("history")).isEmpty();
        assertThat(candidate.path("currentAttachmentAssetIds").toString()).isEqualTo("[\"91\"]");
        assertThat(candidate.path("sourceImageAssetId").isNull()).isTrue();
        assertThat(evidence.requireReview(run,current,null,"IMAGE",currentReview(candidate,"CREATE_NEW"))).isNull();
        assertThatThrownBy(()->evidence.requireReview(run,current,null,"VIDEO",review(candidate,"VIDEO")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->evidence.requireReview(run,current,null,"IMAGE",new RunActionDtos.CreativeContinuationReview(
                "61",candidate.path("historyFingerprint").asText(),"IMAGE","CREATE_NEW","PREVIOUS_IMAGE_RESULT","91")))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(assets);
    }

    @Test void noAttachmentAndNoHistoryCannotProduceACandidate() {
        when(conversations.findMessagesBefore(USER,CONVERSATION,7,7)).thenReturn(List.of());
        assertThat(evidence.candidate(run,current,null)).isEmpty();
    }

    @Test void currentAttachmentReviewDoesNotDependOnTruncatedOrOutdatedHistory() throws Exception {
        attachCurrent(91L);
        for (var rows:List.of(
                List.of(message(60,6,"ASSISTANT","说明".repeat(8_001),NOW.minusSeconds(30)),previousUser),
                List.of(message(60,6,"ASSISTANT","历史未完成",NOW.plusSeconds(1)),previousUser))) {
            when(conversations.findMessagesBefore(USER,CONVERSATION,7,7)).thenReturn(rows);
            var candidate=evidence.candidate(run,current,null).orElseThrow();
            assertThat(candidate.path("historyIncomplete").asBoolean()).isTrue();
            assertThat(evidence.requireReview(run,current,null,"IMAGE",currentReview(candidate,"CREATE_NEW"))).isNull();
            assertThatThrownBy(()->evidence.requireReview(run,current,null,"VIDEO",review(candidate,"VIDEO")))
                    .isInstanceOf(BusinessException.class);
        }
        var memory=mock(AgentMemoryService.class);evidence.setMemory(memory);
        when(memory.context(USER,null)).thenReturn(new MemoryDtos.Context(1,null,List.of(),"after",NOW.minusSeconds(1)));
        when(conversations.findMessagesBefore(USER,CONVERSATION,7,7)).thenReturn(List.of(previousAssistant,previousUser));
        var candidate=evidence.candidate(run,current,null).orElseThrow();
        assertThat(candidate.path("history")).isEmpty();
        assertThat(evidence.requireReview(run,current,null,"IMAGE",currentReview(candidate,"CREATE_NEW"))).isNull();
    }

    @Test void currentAttachmentsMustMatchPersistedIdsAndInvalidateAnAlreadyReadReviewWhenChanged() throws Exception {
        attachCurrent(91L);
        var candidate=evidence.candidate(run,current,null).orElseThrow();
        var bound=currentReview(candidate,"CREATE_NEW");
        when(conversations.findMessageAssetIds(SOURCE,USER,CONVERSATION)).thenReturn(List.of(92L));
        assertThat(evidence.candidate(run,current,null)).isEmpty();
        assertThatThrownBy(()->evidence.requireReview(run,current,null,"IMAGE",bound)).isInstanceOf(BusinessException.class);
        attachCurrent(92L);
        assertThat(evidence.candidate(run,current,null).orElseThrow().path("historyFingerprint"))
                .isNotEqualTo(candidate.path("historyFingerprint"));
        assertThatThrownBy(()->evidence.requireReview(run,current,null,"IMAGE",bound)).isInstanceOf(BusinessException.class);
        attachCurrent(91L,92L);
        when(conversations.findMessageAssetIds(SOURCE,USER,CONVERSATION)).thenReturn(List.of(92L,91L));
        assertThat(evidence.candidate(run,current,null)).isEmpty();
        attachCurrent();
        when(conversations.findMessageAssetIds(SOURCE,USER,CONVERSATION)).thenReturn(List.of(91L));
        assertThat(evidence.candidate(run,current,null)).isEmpty();
    }

    @Test void malformedFrozenAttachmentIdsCannotBecomeCurrentEvidence() throws Exception {
        for (String ids:List.of("null","{}","[91]","[\"0\"]","[\"091\"]","[\"9223372036854775808\"]",
                "[\"91\",\"91\"]","[\"1\",\"2\",\"3\",\"4\",\"5\",\"6\",\"7\",\"8\",\"9\"]")) {
            ObjectNode changed=json.valueToTree(run);changed.put("attachmentAssetIdsJson",ids);
            assertThat(evidence.candidate(json.treeToValue(changed,AgentRunRecord.class),current,null)).as(ids).isEmpty();
        }
    }

    @Test void currentReviewCannotUseAnAbsentSourceForgedAssetIdOrNonCreationDecision() throws Exception {
        var historical=evidence.candidate(run,current,null).orElseThrow();
        assertThat(historical.path("currentAttachmentAssetIds")).isEmpty();
        assertThatThrownBy(()->evidence.requireReview(run,current,null,"IMAGE",currentReview(historical,"CREATE_NEW")))
                .isInstanceOf(BusinessException.class);
        attachCurrent(91L);
        var candidate=evidence.candidate(run,current,null).orElseThrow();
        for (var invalid:List.of(currentReview(candidate,"DISCUSS"),currentReview(candidate,"DO_NOT_CREATE"),
                new RunActionDtos.CreativeContinuationReview("61",candidate.path("historyFingerprint").asText(),
                        "IMAGE","CREATE_NEW","CURRENT_ATTACHMENTS","91"),
                new RunActionDtos.CreativeContinuationReview("62",candidate.path("historyFingerprint").asText(),
                        "IMAGE","CREATE_NEW","CURRENT_ATTACHMENTS",null))) {
            assertThatThrownBy(()->evidence.requireReview(run,current,null,"IMAGE",invalid)).isInstanceOf(BusinessException.class);
        }
    }

    @Test void currentAttachmentReviewIsBoundToTheRunCurrentTextUserAndProject() throws Exception {
        attachCurrent(91L);
        var candidate=evidence.candidate(run,current,null).orElseThrow();
        var bound=currentReview(candidate,"CREATE_NEW");
        assertThatThrownBy(()->evidence.requireReview(run,message(SOURCE,7,"USER","先别生成，只讨论",NOW),null,"IMAGE",bound))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->evidence.requireReview(run,current,21L,"IMAGE",bound)).isInstanceOf(BusinessException.class);
        for (String field:List.of("userId","conversationId","agentRunId")) {
            ObjectNode changed=json.valueToTree(current);
            if ("agentRunId".equals(field)) changed.put(field,UUID.randomUUID().toString());
            else changed.put(field,42L);
            var foreign=json.treeToValue(changed,ConversationMessageRecord.class);
            assertThatThrownBy(()->evidence.requireReview(run,foreign,null,"IMAGE",bound)).isInstanceOf(BusinessException.class);
        }
        UUID otherRun=UUID.randomUUID();
        ObjectNode changedRun=json.valueToTree(run);changedRun.put("id",otherRun.toString());
        ObjectNode changedCurrent=json.valueToTree(current);changedCurrent.put("agentRunId",otherRun.toString());
        var nextRun=json.treeToValue(changedRun,AgentRunRecord.class);
        var nextCurrent=json.treeToValue(changedCurrent,ConversationMessageRecord.class);
        assertThatThrownBy(()->evidence.requireReview(nextRun,nextCurrent,null,"IMAGE",bound)).isInstanceOf(BusinessException.class);
    }

    private void attachCurrent(Long... ids) throws Exception {
        ObjectNode changed=json.valueToTree(run);changed.put("attachmentAssetIdsJson",json.writeValueAsString(
                java.util.Arrays.stream(ids).map(String::valueOf).toList()));
        run=json.treeToValue(changed,AgentRunRecord.class);
        when(conversations.findMessageAssetIds(SOURCE,USER,CONVERSATION)).thenReturn(List.of(ids));
    }

    private RunActionDtos.CreativeContinuationReview currentReview(ObjectNode candidate,String decision) {
        return new RunActionDtos.CreativeContinuationReview("61",candidate.path("historyFingerprint").asText(),
                "IMAGE",decision,"CURRENT_ATTACHMENTS",null);
    }

    private RunActionDtos.CreativeContinuationReview review(ObjectNode candidate,String taskType) {
        return new RunActionDtos.CreativeContinuationReview("61",candidate.path("historyFingerprint").asText(),taskType,"CREATE_NEW");
    }
    private ConversationMessageRecord message(long id,long sequence,String role,String text,OffsetDateTime created) {
        return new ConversationMessageRecord(id,CONVERSATION,USER,role,"TEXT",json.createObjectNode().put("text",text).toString(),
                null,RUN,null,null,null,sequence,"COMPLETED",created,created);
    }
}
