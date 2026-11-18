package com.guanghe.backend.knowledge;

import com.guanghe.backend.agentplatform.AgentPlatformMapper;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.CatalogAccess;
import com.guanghe.backend.common.api.*;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import com.guanghe.backend.conversation.ConversationMessageRecord;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix="zhixiaohe.agent-platform",name="enabled",havingValue="true",matchIfMissing=true)
public class AgentMemoryService {
    private final KnowledgeMapper mapper;
    private final AgentPlatformMapper projects;
    private final SnowflakeIdGenerator ids;
    public AgentMemoryService(KnowledgeMapper mapper,AgentPlatformMapper projects,SnowflakeIdGenerator ids) {
        this.mapper=mapper;this.projects=projects;this.ids=ids;
    }
    @Transactional(readOnly=true)
    public List<MemoryDtos.Entry> list(AuthenticatedAccount account,String scope,Long projectId,boolean includeDeleted) {
        long user=CatalogAccess.requireUser(account);validateScope(user,scope,projectId,false);
        return mapper.memories(user,projectId).stream().filter(e->includeDeleted||e.deletedAt()==null).map(this::view).toList();
    }
    @Transactional(readOnly=true, noRollbackFor=BusinessException.class)
    public MemoryDtos.Context context(long user,Long projectId) {
        if(projectId!=null) project(user,projectId,false);
        List<MemoryDtos.Stored> rows=new ArrayList<>(mapper.memories(user,null));
        if(projectId!=null) rows.addAll(mapper.memories(user,projectId));
        var cutoff=rows.stream().map(MemoryDtos.Stored::historyCutoffAt).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
        String state=rows.stream().map(r->r.id()+":"+r.version()).reduce("memory-v1:"+projectId,(a,b)->a+";"+b);
        return new MemoryDtos.Context(1,str(projectId),rows.stream().map(this::view).toList(),hash(state),cutoff);
    }
    @Transactional
    public MemoryDtos.Entry create(AuthenticatedAccount account,MemoryDtos.Create request) {
        long user=CatalogAccess.requireUser(account);lock(user);
        Long projectId=request.projectId()==null?null:Long.valueOf(request.projectId());
        validateScope(user,request.scope(),projectId,true);validateKind(request.scope(),request.kind());
        if(mapper.memoryByKey(user,projectId,request.key()).isPresent()) throw conflict();
        return view(write(user,projectId,request.scope(),request.kind(),request.key(),request.label(),request.value(),null,null,false));
    }
    @Transactional
    public MemoryDtos.Entry update(AuthenticatedAccount account,long id,MemoryDtos.Update request) {
        long user=CatalogAccess.requireUser(account);lock(user);
        var current=mapper.lockMemory(user,id).orElseThrow(this::missing);
        validateScope(user,current.scope(),current.projectId(),true);
        // Restoration is an explicit new user input, never an implicit replay of a forgotten value.
        if(current.version()!=request.version() || (current.deletedAt()!=null)!=Boolean.TRUE.equals(request.restore())) throw conflict();
        return view(write(user,current.projectId(),current.scope(),current.kind(),current.key(),request.label(),request.value(),current,null,false));
    }
    @Transactional
    public void delete(AuthenticatedAccount account,long id,int version) {
        long user=CatalogAccess.requireUser(account);lock(user);
        var current=mapper.lockMemory(user,id).orElseThrow(this::missing);
        validateScope(user,current.scope(),current.projectId(),false);
        if(current.version()!=version||current.deletedAt()!=null) throw conflict();
        write(user,current.projectId(),current.scope(),current.kind(),current.key(),current.label(),null,current,null,true);
    }
    @Transactional
    public MemoryDtos.Mutation upsert(long user,Long boundProject,ConversationMessageRecord source,String original,MemoryDtos.Upsert request) {
        lock(user);String scope=request.scope()==null?"PROJECT":request.scope();
        Long projectId="PROJECT".equals(scope)?boundProject:null;
        validateScope(user,scope,projectId,true);validateKind(scope,request.kind());
        source(source,original,request.sourceQuote());
        if(!request.sourceQuote().contains(request.value())) throw rejected("MEMORY_SOURCE_REQUIRED","记忆内容必须来自本轮用户原话。");
        if("ACCOUNT".equals(scope)) {
            if(!original.matches("(?is).*(所有|全部|每个|各个)(项目|客户).*(通用|适用|都|统一|一样).*|.*(通用|统一|适用).*(所有|全部)(项目|客户).*")
                    || original.matches("(?is).*(不要|不是|不适用|不能|别).{0,8}(所有|全部|通用|统一).*"))
                throw rejected("MEMORY_ACCOUNT_SCOPE_REQUIRED","只有明确适用于所有项目的表达偏好才能保存为账号通用记忆。");
            if(!request.value().matches("(?is).*(写作|语气|风格|回复|回答|称呼|用词|格式|措辞|文风|标点|语言|段落|篇幅|表情|emoji|tone|writing|response|style).*"))
                throw rejected("MEMORY_ACCOUNT_PREFERENCE_REQUIRED","账号通用记忆仅保存表达偏好，不保存客户经营事实。");
        }
        var current=mapper.memoryByKey(user,projectId,request.key()).orElse(null);
        expected(current,request.expectedVersion(),source);
        if(current!=null&&!current.kind().equals(request.kind())) throw conflict();
        String mode=request.mode()==null?"REPLACE":request.mode();
        String value=request.value();
        if("APPEND".equals(mode)) {
            // Only categories are additive. Exposure targets must never silently expand service coverage.
            if(!"PROJECT".equals(scope)||!"FACT".equals(request.kind())||!"brand.categories".equals(request.key()))
                throw rejected("MEMORY_APPEND_NOT_ALLOWED","追加仅适用于当前项目的经营品类，不得自动扩展服务或推广地域。");
            if(current==null||current.deletedAt()!=null)
                throw rejected("MEMORY_APPEND_SOURCE_REQUIRED","追加需要已有的有效品类资料；首次提供或重新提供已遗忘资料请使用替换。");
            var fragments=new LinkedHashSet<>(current.value().lines().toList());
            var additions=new ArrayList<String>();
            for(String fragment:value.strip().lines().toList()) {
                if(!fragment.isBlank()&&fragments.add(fragment)) additions.add(fragment);
            }
            if(additions.isEmpty()) return new MemoryDtos.Mutation(view(current),context(user,boundProject));
            value=current.value()+"\n"+String.join("\n",additions);
            if(value.length()>2000) throw rejected("MEMORY_CAPACITY_REACHED","当前品类资料过长，请先整理已有内容。");
        } else if(!"REPLACE".equals(mode)) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        var saved=write(user,projectId,scope,request.kind(),request.key(),request.label(),value,current,source,false,"APPEND".equals(mode));
        return new MemoryDtos.Mutation(view(saved),context(user,boundProject));
    }
    @Transactional
    public MemoryDtos.Mutation forget(long user,Long boundProject,ConversationMessageRecord source,String original,MemoryDtos.Forget request) {
        lock(user);String scope=request.scope()==null?"PROJECT":request.scope();
        Long projectId="PROJECT".equals(scope)?boundProject:null;validateScope(user,scope,projectId,true);
        source(source,original,request.sourceQuote());
        if(!request.sourceQuote().matches("(?is).*(忘|删|移除|不再保留|不要记|不再记|别记|forget|remove|delete).*$"))
            throw rejected("MEMORY_FORGET_SOURCE_REQUIRED","请根据用户本轮明确的删除或遗忘要求操作。");
        var current=mapper.memoryByKey(user,projectId,request.key()).orElseThrow(this::missing);
        expected(current,request.expectedVersion(),source);
        var saved=write(user,projectId,scope,current.kind(),current.key(),current.label(),null,current,source,true);
        return new MemoryDtos.Mutation(view(saved),context(user,boundProject));
    }
    private MemoryDtos.Stored write(long user,Long project,String scope,String kind,String key,String label,String value,
            MemoryDtos.Stored current,ConversationMessageRecord source,boolean deleted) {
        return write(user,project,scope,kind,key,label,value,current,source,deleted,false);
    }
    private MemoryDtos.Stored write(long user,Long project,String scope,String kind,String key,String label,String value,
            MemoryDtos.Stored current,ConversationMessageRecord source,boolean deleted,boolean append) {
        var existing=mapper.memories(user,project);
        if(current==null&&existing.size()>=100)
            throw rejected("MEMORY_CAPACITY_REACHED","当前范围的记忆已达到上限，请先整理已有记忆。");
        int characters=existing.stream().filter(e->current==null||e.id()!=current.id())
                .mapToInt(e->e.key().length()+e.label().length()+(e.value()==null?0:e.value().length())).sum();
        if(!deleted&&characters+key.length()+label.length()+value.length()>6000)
            throw rejected("MEMORY_CAPACITY_REACHED","当前范围的记忆内容已达到上限，请精简已有记忆后重试。");
        OffsetDateTime now=OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        var cutoff=current==null?null:current.historyCutoffAt();
        if(current!=null&&!append) {
            // Appends invalidate no facts. A conversational correction starts a new
            // history window at its USER turn, not at the later tool write: assistant
            // placeholders share that turn's creation time and may ask a follow-up.
            // Forget/browser edits still fence at write time; never rewind a prior fence.
            var boundary=deleted||source==null?now:source.createdAt();
            if(cutoff==null||boundary.isAfter(cutoff)) cutoff=boundary;
        }
        var row=new MemoryDtos.Stored(current==null?ids.nextId():current.id(),user,project,scope,kind,key,
                deleted?"已忘记的记忆":label.strip(),deleted?null:value.strip(),current==null?1:current.version()+1,
                source==null?"USER_INPUT":"USER_MESSAGE",source==null?null:source.id(),source==null?null:source.conversationId(),
                current==null?now:current.createdAt(),now,deleted?now:null,cutoff);
        if(current==null) mapper.insertMemory(row);
        else if(mapper.updateMemory(row,current.version())!=1) throw conflict();
        return row;
    }
    private void expected(MemoryDtos.Stored current,int version,ConversationMessageRecord source) {
        if(current==null?version!=0:version!=current.version()) throw conflict();
        if(current!=null&&source.createdAt().isBefore(current.updatedAt()))
            throw rejected("MEMORY_SOURCE_STALE","这条资料已被修改，请使用本轮用户新提供的信息，不要恢复旧消息中的内容。");
    }
    private void source(ConversationMessageRecord source,String original,String quote) {
        if(!"USER".equals(source.role())||quote==null||quote.isBlank()||!original.contains(quote))
            throw rejected("MEMORY_SOURCE_REQUIRED","记忆来源必须是本轮用户原话。");
    }
    private void validateScope(long user,String scope,Long projectId,boolean write) {
        if(!Set.of("PROJECT","ACCOUNT").contains(scope)||("ACCOUNT".equals(scope)&&projectId!=null)) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        if("PROJECT".equals(scope)&&projectId==null) throw rejected("MEMORY_PROJECT_REQUIRED","请先选择当前客户的项目，再保存或修改客户记忆。");
        if(projectId!=null) project(user,projectId,write);
    }
    private void validateKind(String scope,String kind) {
        if(!Set.of("FACT","PREFERENCE").contains(kind)||("ACCOUNT".equals(scope)&&!"PREFERENCE".equals(kind)))
            throw rejected("MEMORY_ACCOUNT_PREFERENCE_REQUIRED","品牌和客户事实只能保存在项目中。");
    }
    private void project(long user,long id,boolean write) {
        var p=projects.project(user,id).orElseThrow(this::missing);
        if(!"ACTIVE".equals(p.status())) throw new BusinessException(ErrorCode.RESOURCE_IN_USE);
        if(write&&mapper.lockProjectForWrite(user,id).filter("ACTIVE"::equals).isEmpty()) throw missing();
    }
    private void lock(long user) { mapper.lockAccountForPreferenceWrite(user).orElseThrow(this::missing); }
    private MemoryDtos.Entry view(MemoryDtos.Stored r) {
        return new MemoryDtos.Entry(Long.toString(r.id()),r.scope(),str(r.projectId()),r.kind(),r.key(),r.label(),
                r.deletedAt()==null?r.value():null,r.version(),r.deletedAt()!=null,r.sourceType(),str(r.sourceMessageId()),str(r.sourceConversationId()),r.updatedAt());
    }
    private String str(Long id) { return id==null?null:id.toString(); }
    private String hash(String state) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(state.getBytes(StandardCharsets.UTF_8))); } catch(Exception e) { throw new IllegalStateException(e); } }
    private BusinessException conflict() { return new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT); }
    private BusinessException missing() { return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND); }
    private BusinessException rejected(String reason,String message) { return new BusinessException(ErrorCode.VALIDATION_FAILED,message,Map.of("reasonCode",reason)); }
}
