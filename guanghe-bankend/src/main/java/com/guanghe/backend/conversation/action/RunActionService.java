package com.guanghe.backend.conversation.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guanghe.backend.agentplatform.*;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.common.api.*;
import com.guanghe.backend.conversation.*;
import com.guanghe.backend.conversation.context.RunContextService;
import com.guanghe.backend.geo.*;
import com.guanghe.backend.knowledge.*;
import com.guanghe.backend.task.*;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Small native-tool surface. Every action is scoped to an authenticated, live USER run. */
@Service
@ConditionalOnExpression("${zhixiaohe.paid-task.enabled:false} and ${zhixiaohe.agent-platform.enabled:true}")
public class RunActionService {
    private final RunContextService contexts;
    private final ConversationMapper conversations;
    private final RunToolAuthorizationService authorization;
    private final RunActionMapper actions;
    private final AgentPlatformService platform;
    private final AgentPlatformProperties properties;
    private final MonitorExecutionMapper budgets;
    private final ProjectGeoTargetService targets;
    private final KnowledgeService knowledge;
    private final GeoService geo;
    private final TaskControlService tasks;
    private final PaidTaskProperties pricing;
    private final ObjectMapper json;
    private final Validator validator;
    private Clock accountingClock = Clock.systemUTC();
    private AgentMemoryService memory;
    private CreativeContinuationEvidence creativeContinuations;
    @org.springframework.beans.factory.annotation.Autowired
    void setCreativeContinuations(CreativeContinuationEvidence value) { creativeContinuations=value; }
    private com.guanghe.backend.article.ArticleFullService articleFull;
    private AgentRuntimeCatalog runtimeCatalog = new AgentRuntimeCatalog();
    @org.springframework.beans.factory.annotation.Autowired
    void setRuntimeCatalog(AgentRuntimeCatalog value) { runtimeCatalog=Objects.requireNonNull(value); }
    @org.springframework.beans.factory.annotation.Autowired
    void setArticleFull(com.guanghe.backend.article.ArticleFullService value) { articleFull=value; }
    @org.springframework.beans.factory.annotation.Autowired
    void setMemory(AgentMemoryService value) { memory=value; }
    @org.springframework.beans.factory.annotation.Autowired
    void setAccountingClock(@org.springframework.beans.factory.annotation.Qualifier("billingClock") Clock value) {
        accountingClock=Objects.requireNonNull(value);
    }

    public RunActionService(RunContextService contexts,ConversationMapper conversations,RunToolAuthorizationService authorization,
            RunActionMapper actions,AgentPlatformService platform,AgentPlatformProperties properties,
            MonitorExecutionMapper budgets,ProjectGeoTargetService targets,KnowledgeService knowledge,
            GeoService geo,TaskControlService tasks,PaidTaskProperties pricing,ObjectMapper json,Validator validator) {
        this.contexts=contexts;this.conversations=conversations;this.authorization=authorization;this.actions=actions;
        this.platform=platform;this.properties=properties;this.budgets=budgets;this.targets=targets;
        this.knowledge=knowledge;this.geo=geo;this.tasks=tasks;this.pricing=pricing;this.json=json;this.validator=validator;
    }

    @Transactional
    public RunActionDtos.Response execute(UUID runId,String contextRef,RunActionDtos.Request request) {
        if(request==null || !validator.validate(request).isEmpty() || !request.arguments().isObject()) throw invalid();
        var frozenContext=contexts.get(runId,contextRef);
        var authorized=authorization.authorize(runId,frozenContext,request.action(),Clock.systemUTC());
        var run=authorized.run();
        var agent=authorized.agent();
        var account=authorized.account();
        var conversation=conversations.findConversation(run.conversationId(),run.userId()).orElseThrow(this::unavailable);
        if(run.selectedSpaceId()!=null && !Objects.equals(run.selectedSpaceId(),conversation.selectedSpaceId()))
            throw new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT);
        if(agent.projectId()!=null && conversation.selectedSpaceId()!=null
                && !agent.projectId().equals(conversation.selectedSpaceId().toString())) throw new BusinessException(ErrorCode.FORBIDDEN);
        var source=conversations.findMessage(run.requestMessageId(),run.userId(),run.conversationId()).orElseThrow(this::unavailable);
        if(!"USER".equals(source.role())) throw unavailable();
        String sourceText=tree(source.contentJson()).path("text").asText("");
        Object input=switch(request.action()) {
            case "brand.save" -> decode(request.arguments(),RunActionDtos.BrandSave.class);
            case "geo.monitor" -> decode(request.arguments(),RunActionDtos.GeoMonitor.class);
            case "article.generate" -> decode(request.arguments(),RunActionDtos.ArticleGenerate.class);
            case "image.generate" -> decode(request.arguments(),RunActionDtos.ImageGenerate.class);
            case "video.generate" -> decode(request.arguments(),RunActionDtos.VideoGenerate.class);
            case "memory.upsert" -> decode(request.arguments(),MemoryDtos.Upsert.class);
            case "memory.forget" -> decode(request.arguments(),MemoryDtos.Forget.class);
            case "context.read", "memory.read" -> { if(!request.arguments().isEmpty()) throw invalid(); yield Map.of(); }
            default -> throw invalid();
        };
        ObjectNode fingerprint=json.createObjectNode().put("action",request.action());
        JsonNode receiptArguments=json.valueToTree(input);
        // Preserve receipt hashes for older native calls that predate this optional field.
        if((input instanceof RunActionDtos.ImageGenerate image && image.continuationReview()==null)
                || (input instanceof RunActionDtos.VideoGenerate video && video.continuationReview()==null))
            ((ObjectNode)receiptArguments).remove("continuationReview");
        fingerprint.set("arguments",receiptArguments);
        String hash=hash(fingerprint);
        var receipt=actions.receipt(runId,request.toolCallId()).orElse(null);
        if(receipt!=null) {
            if(!receipt.inputHash().equals(hash) || !receipt.action().equals(request.action())) throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            // Receipts prove idempotency, not authority to redisplay an obsolete/deleted value.
            if(request.action().startsWith("memory.")) {
                Long replayProject=agent.projectId()!=null?Long.valueOf(agent.projectId()):conversation.selectedSpaceId();
                var fresh=memory.context(run.userId(),replayProject);
                if("memory.read".equals(request.action())) return completed(request.action(),json.valueToTree(fresh));
                String savedId=tree(receipt.responseJson()).path("data").path("entry").path("id").asText();
                var current=fresh.entries().stream().filter(e->e.id().equals(savedId)).findFirst().orElseThrow(this::unavailable);
                return completed(request.action(),json.valueToTree(new MemoryDtos.Mutation(current,fresh)));
            }
            if("article.generate".equals(request.action()) && "COMPLETED".equals(tree(receipt.responseJson()).path("status").asText())) {
                long preparationId=Long.parseLong(tree(receipt.responseJson()).path("data").path("id").asText());
                return completed(request.action(),json.valueToTree(articleFull.get(account,preparationId)));
            }
            return decode(tree(receipt.responseJson()),RunActionDtos.Response.class);
        }
        if(actions.count(runId)>=12) throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT,"本轮操作已达到上限，请继续发送消息。",Map.of("reasonCode","TOOL_LIMIT"));
        Long project=agent.projectId()!=null?Long.valueOf(agent.projectId()):conversation.selectedSpaceId();
        // Unbound conversations never guess the client's project, even when only one exists.
        RunActionDtos.Response response=switch(request.action()) {
            case "context.read" -> completed(request.action(),context(account,project,source,agent,run));
            case "brand.save" -> saveBrand(account,run,project,(RunActionDtos.BrandSave)input,sourceText);
            case "geo.monitor" -> monitor(account,run,project,(RunActionDtos.GeoMonitor)input,sourceText);
            case "article.generate" -> generateArticle(account,run,project,(RunActionDtos.ArticleGenerate)input);
            case "image.generate", "video.generate" -> generateCreative(account,run,project,request.action(),input,sourceText);
            case "memory.read" -> completed(request.action(),json.valueToTree(memory.context(run.userId(),project)));
            case "memory.upsert" -> completed(request.action(),json.valueToTree(memory.upsert(run.userId(),project,source,sourceText,(MemoryDtos.Upsert)input)));
            case "memory.forget" -> completed(request.action(),json.valueToTree(memory.forget(run.userId(),project,source,sourceText,(MemoryDtos.Forget)input)));
            default -> throw invalid();
        };
        if(actions.insert(runId,request.toolCallId(),run.userId(),run.conversationId(),run.requestMessageId(),request.action(),hash,
                write(response),now())!=1) throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        return response;
    }

    private ObjectNode context(AuthenticatedAccount account,Long project,ConversationMessageRecord source,
            AgentPlatformDtos.AgentIdentity agent,AgentRunRecord run) {
        var registration = runtimeCatalog.require(agent);
        boolean brandContext = "geo-brand".equals(registration.contextPolicy());
        boolean creative = agent.capabilities().contains("image.generate") || agent.capabilities().contains("video.generate");
        ObjectNode out=json.createObjectNode();
        out.put("sourceMessageId",Long.toString(source.id()));
        out.set("projects",json.valueToTree(platform.projects(account).stream().filter(p->"ACTIVE".equals(p.status()))
                .filter(p->project==null || p.id().equals(project.toString())).limit(5).toList()));
        out.set("budget",budget(account.id()));
        out.set("configuredPlatforms",json.valueToTree(pricing.getGeoConfiguredPlatforms().stream().sorted().toList()));
        out.put("maxDiscoveryQuestions",pricing.getGeoMaxDiscoveryQuestions());
        if(creative) out.set("recentTasks",json.valueToTree(actions.recentCreativeTasks(account.id(),source.conversationId())));
        if(creative && creativeContinuations!=null)
            creativeContinuations.candidate(run,source,project).ifPresent(value->out.set("creativeContinuation",value));
        if(project==null) {
            out.putNull("project");out.putNull("knowledge");
            if(!creative) out.putArray("recentTasks");
            return out;
        }
        var owned=ownedProject(account,project);
        var target=targets.get(account,project);
        ObjectNode brand=out.putObject("project");brand.put("id",owned.id());brand.put("name",owned.name());
        brand.put("brandName",target.name());brand.set("aliases",json.valueToTree(target.aliases()));
        if(brandContext && agent.capabilities().contains("geo.monitor")) actions.unsubmittedPreviousTurn(account.id(),source.conversationId(),project,
                source.sequenceNo(),source.createdAt()).ifPresent(previous -> {
            ObjectNode continuation=out.putObject("monitorContinuation");
            continuation.put("currentMessageId",Long.toString(source.id()));
            continuation.put("conversationId",Long.toString(source.conversationId()));
            continuation.put("projectId",Long.toString(project));
            continuation.put("sourceMessageId",previous.sourceMessageId());
            continuation.put("requestText",previous.requestText());
            continuation.put("submissionState","NOT_ATTEMPTED");
        });
        if("geo-article".equals(registration.continuationPolicy()) && articleFull!=null && source.agentRunId()!=null)
            articleFull.articleContinuation(source.agentRunId(),project).ifPresent(value->out.set("articleContinuation",value));
        if(brandContext && articleFull!=null)
            articleFull.latestArticle(account.id(),source.conversationId(),project,source.sequenceNo(),source.id(),source.createdAt())
                    .ifPresent(value->out.set("latestArticle",value));
        var knowledgeContext=knowledge.context(account.id(),project,tree(source.contentJson()).path("text").asText(""));
        if(knowledgeContext!=null && !agent.capabilities().contains("memory.read")) knowledgeContext=knowledgeContext.withoutMemory();
        out.set("knowledge",json.valueToTree(knowledgeContext));
        if(brandContext && agent.capabilities().contains("geo.read")) out.set("recentTasks",json.valueToTree(actions.recentTasks(account.id(),project)));
        else if(!creative) out.putArray("recentTasks");
        return out;
    }

    private RunActionDtos.Response saveBrand(AuthenticatedAccount account,AgentRunRecord run,Long project,
            RunActionDtos.BrandSave input,String sourceText) {
        String name=input.name().strip();
        ProjectGeoTargetDtos.View existing=project==null?null:targets.get(account,project);
        // Exact public identity is sourced from user text or an already saved identity, never invented from a project title.
        if(!sourceText.contains(name) && (existing==null || !name.equals(existing.name())))
            return waiting("brand.save","BRAND_NAME_REQUIRED","请使用用户提供的准确品牌名称，不要补写名称。");
        List<String> oldAliases=existing==null?List.of():existing.aliases();
        List<String> aliases=input.aliases()==null?oldAliases:input.aliases();
        for(String alias:aliases) if(!sourceText.contains(alias) && !oldAliases.contains(alias))
            return waiting("brand.save","BRAND_ALIAS_REQUIRED","别名需要来自用户已提供的品牌资料。");
        if(project==null) {
            var matches=platform.projects(account).stream().filter(p->"ACTIVE".equals(p.status()) && p.name().equals(name)).toList();
            project=matches.size()==1?Long.valueOf(matches.getFirst().id()):Long.valueOf(platform.createProject(account,new AgentPlatformDtos.CreateProjectRequest(name)).id());
            existing=targets.get(account,project);
        }
        ownedProject(account,project);
        var target=targets.update(account,project,new ProjectGeoTargetDtos.UpdateRequest(existing.version(),name,aliases));
        // Brand identity remains structured; individual facts belong in versioned scoped memory.
        // Do not append raw USER messages to documents that could resurrect corrected/deleted facts.
        if(conversations.updateConversationSelection(run.conversationId(),run.userId(),project,null,now())!=1) throw unavailable();
        ObjectNode result=json.createObjectNode();result.put("projectId",Long.toString(project));result.put("name",target.name());
        result.set("aliases",json.valueToTree(target.aliases()));result.put("sourceMessageId",Long.toString(run.requestMessageId()));
        return completed("brand.save",result);
    }

    private RunActionDtos.Response monitor(AuthenticatedAccount account,AgentRunRecord run,Long project,
            RunActionDtos.GeoMonitor input,String sourceText) {
        if(project==null) return waiting("geo.monitor","BRAND_REQUIRED","请先提供品牌名称和必要的经营信息。");
        ownedProject(account,project);
        var target=targets.get(account,project);
        if(!target.configured()) return waiting("geo.monitor","BRAND_REQUIRED","请先提供准确的品牌名称。");
        if(new HashSet<>(input.questions()).size()!=input.questions().size()) throw invalid();
        List<String> platforms=input.platformCodes()==null?pricing.getGeoConfiguredPlatforms().stream().sorted().toList():input.platformCodes().stream().sorted().toList();
        if(platforms.isEmpty()) return waiting("geo.monitor","PLATFORM_UNAVAILABLE","监测平台暂不可用。");
        if(new HashSet<>(platforms).size()!=platforms.size()) throw invalid();
        String topic=input.topic()==null?target.name()+"可见度监测":input.topic().strip();
        if(topic.length()>100) topic=topic.substring(0,100);
        ObjectNode parameters=json.createObjectNode();
        final String requestedTopic=topic;
        var candidates=geo.list(account,project,null,topic,1,20).items().stream().filter(t->t.name().equals(requestedTopic)).toList();
        GeoDtos.TopicResponse reusable=null;
        if(candidates.size()==1) {
            var value=geo.get(account,Long.parseLong(candidates.getFirst().id()));
            if(Objects.equals(value.region(),input.region()) && value.questions().stream().filter(GeoDtos.QuestionResponse::enabled).map(GeoDtos.QuestionResponse::text).toList().equals(input.questions())) reusable=value;
        }
        parameters.put("region",input.region());parameters.put("sampleMode","STANDARD");
        parameters.set("platformCodes",json.valueToTree(platforms));
        if(reusable!=null) {
            parameters.put("mode","MONITOR_RUN");parameters.put("topicId",reusable.id());
            parameters.set("questionIds",json.valueToTree(reusable.questions().stream().filter(GeoDtos.QuestionResponse::enabled).map(GeoDtos.QuestionResponse::id).toList()));
        } else {
            if(!candidates.isEmpty()) topic=topic.substring(0,Math.min(topic.length(),70))+"-"+run.requestMessageId();
            parameters.put("mode","QUESTION_DISCOVERY");parameters.put("topic",topic);parameters.put("requestText",sourceText);
            parameters.put("agentSummary","监测品牌在各平台回答中的提及情况");parameters.set("questions",json.valueToTree(input.questions()));
        }
        var result=tasks.admitAgentGeoAction(run.id(),project,parameters);
        ObjectNode out=json.createObjectNode();out.put("taskId",result.task().id());out.put("status",result.task().status());
        out.put("topicId",result.topicId());out.put("quotedPoints",result.task().quotedPoints());out.putNull("reportId");out.put("reused",result.reused());
        return completed("geo.monitor",out);
    }

    private ObjectNode budget(long user) {
        var policy=budgets.policy(user).orElse(null);ObjectNode node=json.createObjectNode();
        boolean enabled=policy==null || policy.enabled();
        int perAction=policy==null?properties.getNativeActionMaxPointsPerAction():policy.maxPointsPerAction();
        int perDay=policy==null?properties.getNativeActionMaxPointsPerDay():policy.maxPointsPerDay();
        // configured describes a custom policy only; a missing row does not disable native actions.
        node.put("configured",policy!=null);node.put("enabled",enabled);
        node.put("policySource",policy==null?"DEFAULT":"ACCOUNT");
        node.put("maxPointsPerAction",perAction);node.put("maxPointsPerDay",perDay);
        node.put("timezone","Asia/Shanghai");
        if(policy==null) node.putNull("effectiveShanghaiDate");
        else node.put("effectiveShanghaiDate",policy.effectiveShanghaiDate().toString());
        var day=accountingClock.instant().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
        String status=!enabled?"DISABLED":perAction<1 || perDay<perAction?"INVALID":
                policy!=null && day.isBefore(policy.effectiveShanghaiDate())?"NOT_EFFECTIVE":"ENABLED";
        node.put("status",status);
        String message=switch(status) {
            case "DISABLED" -> "账号自动执行额度已关闭；默认额度不会覆盖这个设置。";
            case "INVALID" -> "自动执行额度配置无效，需要管理员检查；不是余额不足。";
            case "NOT_EFFECTIVE" -> "账号自动执行额度将于北京时间 "+policy.effectiveShanghaiDate()+" 生效。";
            default -> policy==null
                    ?"未单独设置账号额度，当前使用有效的系统默认额度，无需先配置。"
                    :"当前使用已启用且已生效的账号额度。";
        };
        node.put("message",message+"额度上限不是账户余额或今日剩余额度；实际执行仍须通过服务端报价、余额及今日额度校验。");
        node.put("finalAdmissionRequired",true);
        return node;
    }
    private RunActionDtos.Response generateArticle(AuthenticatedAccount account,AgentRunRecord run,Long project,RunActionDtos.ArticleGenerate input) {
        if(project==null) return waiting("article.generate","BRAND_REQUIRED","请先选择文章所属项目并补充品牌资料。");
        ownedProject(account,project);
        if(!targets.get(account,project).configured()) return waiting("article.generate","BRAND_REQUIRED","请先提供准确的品牌名称。");
        return completed("article.generate",json.valueToTree(articleFull.startFromAgent(
                run.id(),project,input.tone(),input.targetLength(),input.research(),input.continuationReview())));
    }
    private RunActionDtos.Response generateCreative(AuthenticatedAccount account,AgentRunRecord run,Long project,
            String action,Object input,String sourceText) {
        ObjectNode parameters=json.valueToTree(input);
        RunActionDtos.CreativeContinuationReview continuationReview = input instanceof RunActionDtos.ImageGenerate image
                ? image.continuationReview() : ((RunActionDtos.VideoGenerate)input).continuationReview();
        // Harness evidence is admission metadata, never a model/provider task parameter.
        parameters.remove("continuationReview");
        List<String> nulls=new ArrayList<>();parameters.fields().forEachRemaining(entry -> {if(entry.getValue().isNull()) nulls.add(entry.getKey());});
        parameters.remove(nulls);
        parameters.put("requestText",sourceText);parameters.put("mode","AGENT_ASSISTED");
        parameters.put("agentSummary","image.generate".equals(action)?"按本次要求创作图片":"按本次要求创作视频");
        if("image.generate".equals(action)) {
            if(!parameters.has("count")) parameters.put("count",1);
            if(!parameters.has("aspectRatio")) parameters.put("aspectRatio","1:1");
        }
        var accepted=tasks.admitAgentCreativeAction(account,run.id(),project,
                "image.generate".equals(action)?"IMAGE":"VIDEO",parameters,continuationReview);
        ObjectNode result=json.createObjectNode();result.put("taskId",accepted.task().id());
        result.put("taskType",accepted.task().taskType());result.put("status",accepted.task().status());
        result.put("quotedPoints",accepted.task().quotedPoints());result.put("reused",accepted.reused());
        return completed(action,result);
    }
    private AgentPlatformDtos.ProjectView ownedProject(AuthenticatedAccount account,long project) {
        return platform.projects(account).stream().filter(p->p.id().equals(Long.toString(project)) && "ACTIVE".equals(p.status())).findFirst().orElseThrow(this::unavailable);
    }
    private RunActionDtos.Response completed(String action,JsonNode data) { return new RunActionDtos.Response(action,"COMPLETED",data); }
    private RunActionDtos.Response waiting(String action,String reason,String message) { return new RunActionDtos.Response(action,"WAIT_INPUT",json.valueToTree(Map.of("reasonCode",reason,"message",message))); }
    private <T>T decode(JsonNode node,Class<T> type) {
        try { T value=json.treeToValue(node,type);if(!validator.validate(value).isEmpty()) throw invalid();return value; }
        catch(BusinessException error) { throw error; } catch(Exception error) { throw invalid(); }
    }
    private JsonNode tree(String value) { try { return json.readTree(value); } catch(Exception error) { throw new IllegalStateException("Invalid stored action data",error); } }
    private String write(Object value) { try { return json.writeValueAsString(value); } catch(Exception error) { throw new IllegalStateException("Unable to store action receipt",error); } }
    private String hash(JsonNode value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(write(value).getBytes(StandardCharsets.UTF_8))); } catch(Exception error) { throw new IllegalStateException(error); } }
    private OffsetDateTime now() { return OffsetDateTime.now(ZoneOffset.UTC); }
    private BusinessException invalid() { return new BusinessException(ErrorCode.VALIDATION_FAILED); }
    private BusinessException unavailable() { return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND); }
}
