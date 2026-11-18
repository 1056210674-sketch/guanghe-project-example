package com.guanghe.backend.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanghe.backend.agentplatform.AgentPlatformMapper;
import com.guanghe.backend.agentplatform.AgentPlatformRecords;
import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import com.guanghe.backend.common.id.SnowflakeProperties;
import com.guanghe.backend.conversation.ConversationMessageRecord;
import com.guanghe.backend.conversation.context.InternalRunContextDtos;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Exercises the actual memory service against a strict owner/version-aware persistence double. */
class BrandIntakeMemoryTests {
    private static final long USER=41, PROJECT=51;
    private static final String CATEGORIES="brand.categories";
    private static final OffsetDateTime OLD=OffsetDateTime.parse("2026-01-01T00:00:00Z");
    private final Map<Long,MemoryDtos.Stored> rows=new LinkedHashMap<>();
    private AgentMemoryService service;
    private int writes;

    @BeforeEach void setup() {
        var mapper=mock(KnowledgeMapper.class,call -> {
            Object[] args=call.getArguments();
            return switch(call.getMethod().getName()) {
                case "lockAccountForPreferenceWrite" -> (long)args[0]==USER?Optional.of(USER):Optional.empty();
                case "lockProjectForWrite" -> (long)args[0]==USER&&(long)args[1]==PROJECT?Optional.of("ACTIVE"):Optional.empty();
                case "memories" -> rows.values().stream().filter(r->r.userId()==(long)args[0]&&Objects.equals(r.projectId(),args[1])).toList();
                case "memoryByKey" -> rows.values().stream().filter(r->r.userId()==(long)args[0]&&Objects.equals(r.projectId(),args[1])&&r.key().equals(args[2])).findFirst();
                case "lockMemory" -> Optional.ofNullable(rows.get((long)args[1])).filter(r->r.userId()==(long)args[0]);
                case "insertMemory" -> { var row=(MemoryDtos.Stored)args[0];rows.put(row.id(),row);writes++;yield 1; }
                case "updateMemory" -> {
                    var row=(MemoryDtos.Stored)args[0];var old=rows.get(row.id());
                    if(old==null||old.userId()!=row.userId()||old.version()!=(int)args[1]) yield 0;
                    rows.put(row.id(),row);writes++;yield 1;
                }
                default -> throw new AssertionError("Unexpected persistence side effect: "+call.getMethod().getName());
            };
        });
        var projects=mock(AgentPlatformMapper.class,call -> {
            if(!call.getMethod().getName().equals("project")) throw new AssertionError("Unexpected project mutation");
            return (long)call.getArgument(0)==USER&&(long)call.getArgument(1)==PROJECT
                    ? Optional.of(new AgentPlatformRecords.Project(PROJECT,USER,"小行星","小行星","ACTIVE",1,OLD,OLD))
                    : Optional.empty();
        });
        service=new AgentMemoryService(mapper,projects,new SnowflakeIdGenerator(new SnowflakeProperties(1,1)));
    }

    @Test void gradualCategoriesRetainExistingFactsAndReturnAuthoritativeProjection() {
        seed(CATEGORIES,"咖啡",false);
        var result=append(CATEGORIES,"还卖蛋糕",1,"还卖蛋糕");
        assertThat(result.entry().value()).isEqualTo("咖啡\n还卖蛋糕");
        assertThat(result.entry().version()).isEqualTo(2);
        assertThat(result.entry().sourceType()).isEqualTo("USER_MESSAGE");
        assertThat(result.memory().entries()).containsExactly(result.entry());
        assertThat(writes).isEqualTo(1);
    }

    @Test void addingAProductKeepsTheQuestionNeededByTheNextShortAnswer() {
        seed(CATEGORIES,"手机壳",false);
        var time=OffsetDateTime.now().minusMinutes(2);
        var result=service.upsert(USER,PROJECT,source(time),"imd双层手机壳",
                request(CATEGORIES,"imd双层手机壳",1,"imd双层手机壳","APPEND"));
        assertThat(result.memory().historyCutoffAt()).isNull();
        assertThat(projectedHistory(result.memory(),time,"是主要推广方向，还是其中一项？","其中一项"))
                .extracting(InternalRunContextDtos.MemoryMessage::messageId).containsExactly("70","71","72","73");
    }

    @Test void aCorrectionKeepsItsUserAndAssistantTurnForTheNextAcknowledgement() {
        seed(CATEGORIES,"手机壳\nimd双层手机壳",false);
        var time=OffsetDateTime.now().minusMinutes(2);
        var result=service.upsert(USER,PROJECT,source(time),"IMD双层手机壳只是其中一个产品的特色",
                request(CATEGORIES,"手机壳",1,"IMD双层手机壳只是其中一个产品的特色","REPLACE"));
        assertThat(result.memory().historyCutoffAt()).isEqualTo(time);
        assertThat(projectedHistory(result.memory(),time,"要单独记录这个产品特色吗？","ok"))
                .extracting(InternalRunContextDtos.MemoryMessage::messageId).containsExactly("71","72","73");
        assertThat(result.entry().value()).isEqualTo("手机壳");
    }

    @Test void appendingAfterABrowserCorrectionKeepsTheExistingHistoryBoundary() {
        seed(CATEGORIES,"手机壳",false);
        service.update(user(),61,new MemoryDtos.Update(1,"品类","手机壳"));
        var fence=service.context(USER,PROJECT).historyCutoffAt();
        var time=fence.plusSeconds(1);
        var result=service.upsert(USER,PROJECT,source(time),"imd双层手机壳",
                request(CATEGORIES,"imd双层手机壳",2,"imd双层手机壳","APPEND"));
        assertThat(result.memory().historyCutoffAt()).isEqualTo(fence);
        assertThat(projectedHistory(result.memory(),time,"主要推广还是其中一项？","其中一项"))
                .extracting(InternalRunContextDtos.MemoryMessage::messageId).containsExactly("71","72","73");
    }

    @Test void forgettingStillRemovesTheForgottenTurnFromFutureModelContext() {
        seed(CATEGORIES,"手机壳",false);
        var time=OffsetDateTime.now().minusMinutes(2);
        var result=service.forget(USER,PROJECT,source(time),"忘记主营业务",
                new MemoryDtos.Forget("PROJECT",CATEGORIES,1,"忘记主营业务"));
        assertThat(result.memory().historyCutoffAt()).isAfter(time);
        assertThat(result.entry().deleted()).isTrue();
        assertThat(projectedHistory(result.memory(),time,"已删除这项资料","ok"))
                .extracting(InternalRunContextDtos.MemoryMessage::messageId).containsExactly("73");
        assertReason(()->service.upsert(USER,PROJECT,source(time),"手机壳",
                request(CATEGORIES,"手机壳",2,"手机壳","REPLACE")),"MEMORY_SOURCE_STALE");
    }

    @Test void browserCorrectionStillExcludesEarlierConversationText() {
        seed(CATEGORIES,"手机壳",false);
        var time=OffsetDateTime.now().minusMinutes(2);
        service.update(user(),61,new MemoryDtos.Update(1,"品类","配件"));
        var memory=service.context(USER,PROJECT);
        assertThat(memory.historyCutoffAt()).isAfter(time);
        assertThat(projectedHistory(memory,time,"旧的主营是手机壳","继续"))
                .extracting(InternalRunContextDtos.MemoryMessage::messageId).containsExactly("73");
    }

    @Test void appendOnlyDeduplicatesExactWholeFragmentsAndNeverRewritesPriorText() {
        seed(CATEGORIES,"咖啡\n蛋糕",false);
        assertThat(append(CATEGORIES,"咖啡\n曲奇\n曲奇",1,"咖啡\n曲奇\n曲奇").entry().value())
                .isEqualTo("咖啡\n蛋糕\n曲奇");
        var duplicate=append(CATEGORIES,"曲奇",2,"曲奇");
        assertThat(duplicate.entry().version()).isEqualTo(2);
        assertThat(writes).isEqualTo(1);
    }

    @Test void appendStillRejectsInventedSourceAndStaleVersionsWithoutWrites() {
        seed(CATEGORIES,"咖啡",false);
        assertReason(()->append(CATEGORIES,"蛋糕",1,"曲奇"),"MEMORY_SOURCE_REQUIRED");
        assertCode(()->append(CATEGORIES,"蛋糕",2,"蛋糕"),ErrorCode.RESOURCE_VERSION_CONFLICT);
        var oldSource=source(OLD.minusDays(1));
        assertReason(()->service.upsert(USER,PROJECT,oldSource,"蛋糕",request(CATEGORIES,"蛋糕",1,"蛋糕","APPEND")),"MEMORY_SOURCE_STALE");
        assertThat(rows.get(61L).value()).isEqualTo("咖啡");
        assertThat(writes).isZero();
    }

    @Test void appendCannotExpandGeographyOrUseAccountScope() {
        seed("brand.service_area","广州",false);
        assertReason(()->append("brand.service_area","福建",1,"福建"),"MEMORY_APPEND_NOT_ALLOWED");
        seed("geo.target_regions","广东",false);
        assertReason(()->append("geo.target_regions","福建",1,"福建"),"MEMORY_APPEND_NOT_ALLOWED");
        String text="所有项目统一使用简短回复";
        assertReason(()->service.upsert(USER,PROJECT,source(),text,new MemoryDtos.Upsert("ACCOUNT","PREFERENCE",CATEGORIES,"品类",text,0,text,"APPEND")),"MEMORY_APPEND_NOT_ALLOWED");
        assertThat(writes).isZero();
    }

    @Test void appendRequiresLiveExistingEntryAndRespectsResultLength() {
        assertReason(()->append(CATEGORIES,"蛋糕",0,"蛋糕"),"MEMORY_APPEND_SOURCE_REQUIRED");
        seed(CATEGORIES,null,true);
        assertReason(()->append(CATEGORIES,"蛋糕",1,"蛋糕"),"MEMORY_APPEND_SOURCE_REQUIRED");
        seed(CATEGORIES,"咖".repeat(1999),false);
        assertReason(()->append(CATEGORIES,"蛋糕",1,"蛋糕"),"MEMORY_CAPACITY_REACHED");
        assertThat(writes).isZero();
    }

    @Test void replaceDefaultPreservesLegacyBehaviorAndMayExplicitlyRestoreUserFacts() {
        seed(CATEGORIES,"咖啡",false);
        var changed=service.upsert(USER,PROJECT,source(),"只卖蛋糕",request(CATEGORIES,"只卖蛋糕",1,"只卖蛋糕",null));
        assertThat(changed.entry().value()).isEqualTo("只卖蛋糕");
        seed(CATEGORIES,null,true);
        var restored=service.upsert(USER,PROJECT,source(),"咖啡",request(CATEGORIES,"咖啡",1,"咖啡","REPLACE"));
        assertThat(restored.entry().deleted()).isFalse();
        assertThat(restored.entry().value()).isEqualTo("咖啡");
        assertThat(restored.memory().historyCutoffAt()).isAfter(OLD);
    }

    @Test void browserTombstoneRestorationRequiresExplicitFlagAndExactVersion() {
        seed("brand.service_area",null,true);
        assertCode(()->service.update(user(),61,new MemoryDtos.Update(1,"服务范围","广州")),ErrorCode.RESOURCE_VERSION_CONFLICT);
        assertCode(()->service.update(user(),61,new MemoryDtos.Update(2,"服务范围","广州",true)),ErrorCode.RESOURCE_VERSION_CONFLICT);
        assertThat(writes).isZero();
        var restored=service.update(user(),61,new MemoryDtos.Update(1,"服务范围","广州",true));
        assertThat(restored.deleted()).isFalse();
        assertThat(restored.value()).isEqualTo("广州");
        assertThat(restored.version()).isEqualTo(2);
        assertThat(restored.sourceType()).isEqualTo("USER_INPUT");
        assertThat(restored.sourceMessageId()).isNull();
        assertThat(rows.get(61L).historyCutoffAt()).isAfter(OLD);
        assertCode(()->service.update(user(),61,new MemoryDtos.Update(2,"服务范围","福建",true)),ErrorCode.RESOURCE_VERSION_CONFLICT);
        assertThat(writes).isEqualTo(1);
    }

    @Test void restorationCannotCrossAccountOrProjectAndCreateDoesNotBypassTombstone() {
        seed(CATEGORIES,null,true);
        assertCode(()->service.create(user(),new MemoryDtos.Create("PROJECT","51","FACT",CATEGORIES,"品类","蛋糕")),ErrorCode.RESOURCE_VERSION_CONFLICT);
        var existing=rows.get(61L);
        rows.put(61L,new MemoryDtos.Stored(existing.id(),USER,52L,"PROJECT","FACT",CATEGORIES,"品类",null,1,"USER_INPUT",null,null,OLD,OLD,OLD,OLD));
        assertCode(()->service.update(user(),61,new MemoryDtos.Update(1,"品类","蛋糕",true)),ErrorCode.RESOURCE_NOT_FOUND);
        assertCode(()->service.upsert(USER,52L,source(),"蛋糕",request(CATEGORIES,"蛋糕",0,"蛋糕",null)),ErrorCode.RESOURCE_NOT_FOUND);
        assertCode(()->service.update(new AuthenticatedAccount(42,"other",AccountRole.USER,1,AccountStatus.ACTIVE,null,"other",null),61,new MemoryDtos.Update(1,"品类","蛋糕",true)),ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(writes).isZero();
    }

    @Test void intakeCrudDoesNotInvokeOtherBusinessWritesAndOrdinaryUpdateStillWorks() {
        var created=service.create(user(),new MemoryDtos.Create("PROJECT","51","FACT","geo.target_regions","推广地域","广东"));
        var updated=service.update(user(),Long.parseLong(created.id()),new MemoryDtos.Update(1,"推广地域","广东、福建"));
        assertThat(updated.value()).isEqualTo("广东、福建");
        service.delete(user(),Long.parseLong(created.id()),2);
        assertThat(service.list(user(),"PROJECT",PROJECT,true)).hasSize(1).allMatch(MemoryDtos.Entry::deleted);
        assertThat(service.list(user(),"PROJECT",PROJECT,false)).isEmpty();
        // Strict mapper doubles fail on any method beyond scoped memory/account/project access above.
        assertThat(writes).isEqualTo(3);
    }

    @Test void optionalContractFieldsDeserializeWithoutChangingOldClients() throws Exception {
        var json=new ObjectMapper();
        assertThat(json.readValue("{\"version\":1,\"label\":\"地域\",\"value\":\"广州\"}",MemoryDtos.Update.class).restore()).isNull();
        assertThat(json.readValue("{\"version\":1,\"label\":\"地域\",\"value\":\"广州\",\"restore\":true}",MemoryDtos.Update.class).restore()).isTrue();
        var upsert=json.readValue("{\"kind\":\"FACT\",\"key\":\"brand.categories\",\"label\":\"品类\",\"value\":\"蛋糕\",\"expectedVersion\":1,\"sourceQuote\":\"蛋糕\",\"mode\":\"APPEND\"}",MemoryDtos.Upsert.class);
        assertThat(upsert.mode()).isEqualTo("APPEND");
    }

    private void seed(String key,String value,boolean deleted) {
        rows.put(61L,new MemoryDtos.Stored(61,USER,PROJECT,"PROJECT","FACT",key,"品牌资料",value,1,"USER_INPUT",null,null,OLD,OLD,deleted?OLD:null,null));
    }
    private List<InternalRunContextDtos.MemoryMessage> projectedHistory(MemoryDtos.Context memory,
            OffsetDateTime time,String question,String reply) {
        var nodes=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        var messages=List.of(
                new InternalRunContextDtos.MemoryMessage("70",1,"USER",nodes.objectNode().put("text","较早的资料"),time.minusMinutes(1),null),
                new InternalRunContextDtos.MemoryMessage("71",2,"USER",nodes.objectNode().put("text","本轮资料更正"),time,null),
                // Assistant placeholders share the USER creation time; persistence completes later.
                new InternalRunContextDtos.MemoryMessage("72",3,"ASSISTANT",nodes.objectNode().put("text",question),time,null),
                new InternalRunContextDtos.MemoryMessage("73",4,"USER",nodes.objectNode().put("text",reply),time.plusMinutes(3),null));
        var context=new InternalRunContextDtos.RunContext("run","81",1,null,null,List.of(),List.of(),
                new InternalRunContextDtos.ConversationMemory(messages,null,4,0,"POSTGRESQL"),null,List.of(),null);
        return context.withKnowledge(new KnowledgeDtos.KnowledgeContext(List.of(),List.of(),null,false,0,0,memory))
                .enforceMemoryBoundary("73").conversationMemory().messages();
    }
    private MemoryDtos.Mutation append(String key,String value,int version,String quote) {
        return service.upsert(USER,PROJECT,source(),quote,request(key,value,version,quote,"APPEND"));
    }
    private MemoryDtos.Upsert request(String key,String value,int version,String quote,String mode) {
        return new MemoryDtos.Upsert("PROJECT","FACT",key,"品牌资料",value,version,quote,mode);
    }
    private ConversationMessageRecord source() { return source(OffsetDateTime.now().plusSeconds(1)); }
    private ConversationMessageRecord source(OffsetDateTime time) {
        return new ConversationMessageRecord(71,81,USER,"USER","TEXT","{}",null,null,null,null,null,1,"COMPLETED",time,time);
    }
    private AuthenticatedAccount user() { return new AuthenticatedAccount(USER,"agent",AccountRole.USER,1,AccountStatus.ACTIVE,null,"代理",null); }
    private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,ErrorCode code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BusinessException.class,error->assertThat(error.errorCode()).isEqualTo(code));
    }
    private void assertReason(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,String reason) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BusinessException.class,error->assertThat(error.details()).containsEntry("reasonCode",reason));
    }
}
