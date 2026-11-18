package com.guanghe.backend.agentplatform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.competitor.CompetitorMapper;
import com.guanghe.backend.catalog.product.ProductMapper;
import com.guanghe.backend.catalog.space.BusinessSpaceMapper;
import com.guanghe.backend.catalog.space.BusinessSpaceRecord;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import com.guanghe.backend.conversation.*;
import com.guanghe.backend.conversation.context.AuthorizedCatalogContextFactory;
import com.guanghe.backend.geo.*;
import com.guanghe.backend.task.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static com.guanghe.backend.agentplatform.MonitorExecutionDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MonitorExecutionServiceTests {
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private final OffsetDateTime now=OffsetDateTime.parse("2026-10-08T00:00:00Z");
    private final AuthenticatedAccount user=new AuthenticatedAccount(41,"test",AccountRole.USER,1,AccountStatus.ACTIVE,null,null,null);
    private MonitorExecutionMapper mapper; private ConversationMapper conversations;
    private GeoMapper geo;private BusinessSpaceMapper spaces;private GeoTaskService tasks;
    private AgentPlatformService platform;private AgentPlatformProperties properties;private MonitorExecutionService service;
    private final AgentPlatformDtos.AgentIdentity identity=new AgentPlatformDtos.AgentIdentity("51","geo","1.0.0","GEO",null,List.of("chat","geo.monitor"));
    private final MonitorRangeInput input=new MonitorRangeInput("61",List.of("71","72","73"),List.of("DOUBAO"),"STANDARD");

    @BeforeEach void setup() {
        mapper=mock(MonitorExecutionMapper.class);conversations=mock(ConversationMapper.class);geo=mock(GeoMapper.class);
        spaces=mock(BusinessSpaceMapper.class);platform=mock(AgentPlatformService.class);properties=new AgentPlatformProperties();
        properties.setAutomaticMonitorEnabled(true);properties.setMonitorBudgetEarliestTrustedDate(LocalDate.of(2026,10,8));
        var pricing=new PaidTaskProperties();pricing.setGeoExecutionEnabled(true);pricing.setGeoTestPricingVersionId(5001);pricing.setGeoTestUnitPoints(2);
        var ids=mock(SnowflakeIdGenerator.class);when(ids.nextId()).thenReturn(101L);
        tasks=spy(new GeoTaskService(mock(GeoTaskMapper.class),geo,mock(TaskControlMapper.class),conversations,
                mock(AuthorizedCatalogContextFactory.class),ids,json,pricing));
        service=new MonitorExecutionService(mapper,platform,conversations,geo,spaces,mock(ProductMapper.class),mock(CompetitorMapper.class),
                tasks,pricing,properties,ids,json,Clock.fixed(now.toInstant(),ZoneOffset.UTC));
        when(conversations.findConversation(91,41)).thenReturn(Optional.of(new ConversationRecord(91,41,"test",null,null,1,null,now,now)));
        when(platform.assertMonitorExecutionAllowed(41,91)).thenReturn(identity);
        when(mapper.definitionHash("geo","1.0.0")).thenReturn("a".repeat(64));
        when(geo.shareTopic(61,41)).thenReturn(Optional.of(topic("广州")));
        when(spaces.shareProjectByIdAndUserId(81,41)).thenReturn(Optional.of(space("小行星",3)));
        doAnswer(call->{var params=call.<com.fasterxml.jackson.databind.node.ObjectNode>getArgument(3);
            var snapshot=json.createObjectNode();snapshot.set("parameters",params.deepCopy());
            var questions=snapshot.putObject("authorizedContext").putArray("questions");
            for(String id:input.questionIds())questions.addObject().put("id",id).put("category","BRAND_DISCOVERY").put("text","咖啡店推荐"+id);
            return new GeoTaskService.PreparedDraft(params,snapshot,3);
        }).when(tasks).prepareDraft(eq(41L),eq(81L),isNull(),any());
    }
    @Test void missingPolicyHasNoInventedLimits() {
        var view=service.budget(user);assertThat(view.configured()).isFalse();assertThat(view.enabled()).isFalse();
        assertThat(view.version()).isZero();assertThat(view.maxPointsPerAction()).isNull();assertThat(view.maxPointsPerDay()).isNull();
    }
    @Test void policyRequiresExplicitTrustworthyDayAndNeverGuessesFromOldLedger() {
        properties.setMonitorBudgetEarliestTrustedDate(null);
        assertThatThrownBy(()->service.putBudget(user,new BudgetPolicyPut(0,true,6,50))).isInstanceOf(BusinessException.class);
        verify(mapper,never()).insertPolicy(any());verify(mapper,never()).consumedPoints(anyLong(),any(),any());
    }
    @Test void noDefaultNationAndNoProjectNameAsBrand() {
        when(geo.shareTopic(61,41)).thenReturn(Optional.of(topic(null)));
        assertThat(service.preview(user,91,input).reasonCode()).isEqualTo("MONITOR_REGION_MISSING");
        when(geo.shareTopic(61,41)).thenReturn(Optional.of(topic("广州")));
        when(spaces.shareProjectByIdAndUserId(81,41)).thenReturn(Optional.of(space(null,3)));
        assertThat(service.preview(user,91,input).reasonCode()).isEqualTo("MONITOR_TARGET_MISSING");
        verify(tasks,never()).prepareDraft(anyLong(),anyLong(),any(),any());
    }
    @Test void previewReturnsExactFrozenVersionsAndQuoteButNoGrantOrCharge() {
        var preview=service.preview(user,91,input);assertThat(preview.status()).isEqualTo("READY");
        assertThat(preview.range().topicVersion()).isEqualTo(2);assertThat(preview.range().inputVersions().spaceVersion()).isEqualTo(3);
        assertThat(preview.quotedPoints()).isEqualTo(6);assertThat(preview.range().inputHash()).matches("[0-9a-f]{64}");
        verify(mapper,never()).insertGrant(any());verify(mapper,never()).insertAdmission(any());
    }
    @Test void duplicateQuestionIdsAreRejectedInsteadOfSilentlyExpandingOrDeduplicating() {
        assertThatThrownBy(()->service.preview(user,91,new MonitorRangeInput("61",List.of("71","71","72"),List.of("DOUBAO"),"STANDARD"))).isInstanceOf(BusinessException.class);
        verify(geo,never()).shareTopic(anyLong(),anyLong());
    }
    @Test void changedInputVersionStopsBeforeActionContextIsSent() {
        var preview=service.preview(user,91,input);var intent=intent(preview.range());
        when(mapper.shareGrant(101,41,91)).thenReturn(Optional.of(grant(preview.range())));
        when(spaces.shareProjectByIdAndUserId(81,41)).thenReturn(Optional.of(space("小行星",4)));
        var selected=service.prepareMessage(41,91,intent);assertThat(selected.reason()).isEqualTo(MonitorWaitReason.INPUT_CHANGED);
        assertThat(selected.context()).isNull();assertThat(selected.spaceId()).isNull();
    }
    @Test void disabledAgentIsAnExpectedWaitForPreviewInsteadOfAClaimedReadyAction() {
        when(platform.assertMonitorExecutionAllowed(41,91)).thenThrow(new BusinessException(
                com.guanghe.backend.common.api.ErrorCode.FORBIDDEN));
        var preview=service.preview(user,91,input);
        assertThat(preview.status()).isEqualTo("WAIT_INPUT");
        assertThat(preview.reasonCode()).isEqualTo("AGENT_UNAVAILABLE");
        assertThat(preview.range()).isNull();
        verify(mapper,never()).insertAdmission(any());
    }
    @Test void failedAndStoppedRunsCannotBecomePaidAdmissions() {
        for(String status:List.of("FAILED","STOPPED")) {
            assertThatThrownBy(()->service.validateAdmission(null,null,run(status)))
                    .isInstanceOf(MonitorExecutionService.Refusal.class)
                    .extracting(error->((MonitorExecutionService.Refusal)error).reason())
                    .isEqualTo("FAILED".equals(status)?MonitorWaitReason.RUN_FAILED:MonitorWaitReason.RUN_STOPPED);
        }
        verify(mapper,never()).sharePolicy(anyLong());
        verify(mapper,never()).consumedPoints(anyLong(),any(),any());
    }
    @Test void originalUserProposalCreatesDurableAdmissionWithoutAuthorizingOrLockingWallet() throws Exception {
        var preview=service.preview(user,91,input);var intent=intent(preview.range());var run=run("RUNNING");
        var content=json.createObjectNode();content.set("executionIntent",json.valueToTree(intent));
        when(conversations.findMessage(111,41,91)).thenReturn(Optional.of(message(run,content.toString())));
        when(mapper.grant(101,41,91)).thenReturn(Optional.of(grant(preview.range())));
        service.registerProposal(run,draft(preview.range()),now);
        var captured=ArgumentCaptor.forClass(MonitorExecutionRecords.Admission.class);verify(mapper).insertAdmission(captured.capture());
        assertThat(captured.getValue().status()).isEqualTo("PENDING");assertThat(captured.getValue().requestMessageId()).isEqualTo(111);
        verify(mapper,never()).sharePolicy(anyLong());verify(mapper,never()).shareRunnableAccount(anyLong());verify(mapper,never()).consumedPoints(anyLong(),any(),any());
    }
    @Test void assistantIntentCannotBecomeOriginalUserAuthorization() {
        var run=run("RUNNING");var original=message(run,"{}");
        when(conversations.findMessage(111,41,91)).thenReturn(Optional.of(new ConversationMessageRecord(original.id(),91,41,"ASSISTANT","TEXT",
                "{}",null,run.id(),null,null,null,1,"COMPLETED",now,now)));
        assertThatThrownBy(()->service.registerProposal(run,draft(service.preview(user,91,input).range()),now)).isInstanceOf(IllegalStateException.class);
        verify(mapper,never()).insertAdmission(any());
    }
    @Test void dailyCapIncludesAllFrozenAmountsAndCountsSettlementRowsNotDistinctTasks() {
        var range=service.preview(user,91,input).range();var policy=policy(6,20);
        when(mapper.consumedPoints(eq(41L),any(),any())).thenReturn(7L);
        assertThatThrownBy(()->service.validateBudget(new MonitorExecutionService.Permit(grant(range),policy),draft(range),
                new TaskRecords.PointAccount(41,100,10,1,now,now),now)).isInstanceOf(MonitorExecutionService.Refusal.class)
                .extracting(e->((MonitorExecutionService.Refusal)e).reason()).isEqualTo(MonitorWaitReason.DAY_LIMIT);
        verify(mapper).consumedPoints(41,OffsetDateTime.parse("2026-10-08T00:00:00+08:00"),OffsetDateTime.parse("2026-10-09T00:00:00+08:00"));
    }
    @Test void freshAfterWaitTimeRechecksExpiryBeforeSeparateDailySum() {
        var range=service.preview(user,91,input).range();
        assertThatThrownBy(()->service.validateBudget(new MonitorExecutionService.Permit(grant(range),policy(6,50)),draft(range),
                new TaskRecords.PointAccount(41,100,0,1,now,now),now.plusDays(2))).isInstanceOf(MonitorExecutionService.Refusal.class);
        verify(mapper,never()).consumedPoints(anyLong(),any(),any());
    }
    @Test void strictShapesRejectHiddenPermissionsAndNullPlatformElements() throws Exception {
        assertThatThrownBy(()->json.readValue("{\"topicId\":\"61\",\"questionIds\":[\"71\",\"72\",\"73\"],\"platformCodes\":[\"DOUBAO\"],\"sampleMode\":\"STANDARD\",\"authorized\":true}",MonitorRangeInput.class)).isInstanceOf(Exception.class);
        try(var factory=jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(new MonitorRangeInput("61",input.questionIds(),Arrays.asList((String)null),"STANDARD"))).isNotEmpty();
        }
    }
    private GeoRecords.Topic topic(String region){return new GeoRecords.Topic(61,41,81,null,"监测", "监测",region,2,now,now);}
    private BusinessSpaceRecord space(String brand,int version){return new BusinessSpaceRecord(81,41,"不当品牌使用的项目","项目",brand,"{}",null,version,now,now,0,0);}
    private MonitorExecutionIntent intent(FrozenMonitorRange range){return new MonitorExecutionIntent("MONITOR_RUN","101",1,range,"5001",6);}
    private MonitorExecutionRecords.Grant grant(FrozenMonitorRange range){return new MonitorExecutionRecords.Grant(101,41,91,51,"geo","1.0.0","a".repeat(64),null,61,json.valueToTree(range).toString(),1,"ACTIVE",now.plusDays(1),null,now,now);}
    private MonitorExecutionRecords.Policy policy(int single,int day){return new MonitorExecutionRecords.Policy(41,1,true,single,day,LocalDate.of(2026,10,8),now,now);}
    private AgentRunRecord run(String status){return new AgentRunRecord(UUID.randomUUID(),91,41,111,null,81L,null,"[]",status,1,
            "{\"agentIdentity\":"+json.valueToTree(identity)+"}","h",now.plusMinutes(5),null,0,"{}",null,null,null,now,null,now,now);}
    private ConversationMessageRecord message(AgentRunRecord run,String content){return new ConversationMessageRecord(111,91,41,"USER","TEXT",content,null,run.id(),UUID.randomUUID(),UUID.randomUUID(),"f",1,"COMPLETED",now,now);}
    private TaskRecords.Draft draft(FrozenMonitorRange range){var params=json.createObjectNode().put("mode","MONITOR_RUN").put("topicId",range.topicId()).put("sampleMode",range.sampleMode()).put("region",range.region());params.set("questionIds",json.valueToTree(range.questionIds()));params.set("platformCodes",json.valueToTree(range.platformCodes()));
        return new TaskRecords.Draft(121,41,91L,UUID.randomUUID(),null,"CHAT",null,null,null,"GEO",1,81,null,params.toString(),"{}",range.inputHash(),5001,6,"[]","WAITING_CONFIRMATION",now.plusMinutes(10),null,now,now);}
}
