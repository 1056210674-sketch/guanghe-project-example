package com.guanghe.backend.geo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** Report facts are computed from full saved answers, never from model excerpts or conclusions. */
final class GeoDiagnosticProjection {
    private GeoDiagnosticProjection() { }
    static ObjectNode evidence(GeoTaskRecords.Run run,List<GeoTaskRecords.Observation> observations,GeoTaskMapper mapper,ObjectMapper json) {
        ObjectNode snapshot;
        try{snapshot=(ObjectNode)json.readTree(run.contextSnapshotJson());}catch(Exception bad){throw new IllegalStateException("Invalid diagnostic snapshot");}
        var authorized=snapshot.path("authorizedContext");var diagnostic=authorized.path("diagnostic");
        ObjectNode result=json.createObjectNode();result.set("profile",diagnostic.path("profile").deepCopy());
        result.set("competitors",diagnostic.path("competitors").deepCopy());result.set("questions",authorized.path("questions").deepCopy());
        result.set("platformCodes",snapshot.path("parameters").path("platformCodes").deepCopy());
        Map<String,GeoDiagnosticDtos.Source> sources=new LinkedHashMap<>();Map<String,String> urls=new HashMap<>();
        for(var s:diagnostic.path("sources")){var source=new GeoDiagnosticDtos.Source(s.path("id").asText(),excerpt(s.path("title").asText(),500),s.path("url").asText(),excerpt(s.path("snippet").asText(),500));
            sources.put(source.id(),source);urls.putIfAbsent(source.url(),source.id());}
        Map<String,GeoTaskRecords.Observation> byUnit=new HashMap<>();observations.forEach(o->byUnit.put(o.unitKey(),o));
        List<GeoDiagnosticReportDtos.Sample> samples=new ArrayList<>();List<String> limits=new ArrayList<>(List.of(
            "本报告为一次采集快照，不证明优化因果效果、市场份额或推荐排名。",
            "自然发现问题不点名品牌；点名口碑问题单独展示，不计入自然可见度的分母。",
            "可见度为有效自然回答中名称提及比例，使用完整已保存回答匹配；失败和缺失不按未提及计入。",
            "供解读使用的回答摘录最多1600字，优先保留品牌相关的连续上下文，裁剪会明确标记；完整已保存回答仍保留在报告附录。来源链接来自实际检索或采样回执，不代表平台永久引用。",
            "口碑是对点名提问样本的定性分析，不是用户满意度调查或口碑评分；平台和模型路径可能不同。"
        ));boolean sourceTruncated=false,sourceFiltered=false;
        for(var question:authorized.path("questions"))for(var p:result.path("platformCodes")) {
            String key=question.path("id").asText()+":"+p.asText()+":1";var observed=byUnit.get(key);List<String> refs=new ArrayList<>();
            if(observed!=null)for(var c:mapper.findCitations(observed.id())) {
                // Only the model projection is constrained to public standard-port sources. The
                // immutable original citation remains available in the authenticated appendix.
                try{GeoDiagnosticService.sourceUrl(c.url());}catch(RuntimeException invalid){sourceFiltered=true;continue;}
                String sourceId=urls.get(c.url());
                if(sourceId==null&&sources.size()<100){sourceId="OBS:"+c.id();var source=new GeoDiagnosticDtos.Source(sourceId,
                    excerpt(c.title()==null||c.title().isBlank()?c.url():c.title(),500),c.url(),excerpt(c.snippet()==null?"":c.snippet(),500));
                    sources.put(sourceId,source);urls.put(c.url(),sourceId);}
                if(sourceId!=null&&!refs.contains(sourceId))refs.add(sourceId);else if(sourceId==null)sourceTruncated=true;
            }
            String answer=observed==null||observed.answerText()==null?null:observed.answerText().strip();
            samples.add(new GeoDiagnosticReportDtos.Sample(key,question.path("id").asText(),question.path("category").asText(),question.path("text").asText(),
                p.asText(),observed==null?"MISSING":observed.status(),answer==null?null:answerWindow(answer,diagnostic.path("profile"),diagnostic.path("competitors"),1600),answer!=null&&answer.length()>1600,
                observed==null?"SAMPLE_MISSING":observed.stableErrorCode(),List.copyOf(refs)));
        }
        List<GeoDiagnosticReportDtos.Discovery> discovery=new ArrayList<>();List<GeoDiagnosticReportDtos.Reputation> reputation=new ArrayList<>();
        var own=diagnostic.path("profile");List<String> ownTerms=strings(own.path("brandTerms"));if(!ownTerms.contains(own.path("brandName").asText()))ownTerms.add(own.path("brandName").asText());
        for(var platform:result.path("platformCodes")) {
            var d=samples.stream().filter(s->s.platformCode().equals(platform.asText())&&s.category().equals("BRAND_DISCOVERY")).toList();
            var r=samples.stream().filter(s->s.platformCode().equals(platform.asText())&&s.category().equals("BRAND_REPUTATION")).toList();
            int valid=(int)d.stream().filter(s->s.status().equals("SUCCEEDED")).count();List<GeoDiagnosticReportDtos.Mention> mentions=new ArrayList<>();
            mentions.add(mention("OWN",own.path("brandName").asText(),ownTerms,d,byUnit,valid));
            for(var competitor:diagnostic.path("competitors")){var terms=strings(competitor.path("brandTerms"));terms.add(competitor.path("name").asText());
                mentions.add(mention(competitor.path("id").asText(),competitor.path("name").asText(),terms,d,byUnit,valid));}
            discovery.add(new GeoDiagnosticReportDtos.Discovery(platform.asText(),d.size(),valid,count(d,"FAILED"),count(d,"MISSING"),List.copyOf(mentions)));
            int recognized=0;for(var sample:r){var o=byUnit.get(sample.unitKey());if(o!=null&&o.status().equals("SUCCEEDED")&&GeoDiagnosticService.matchesNames(o.answerText(),ownTerms))recognized++;}
            reputation.add(new GeoDiagnosticReportDtos.Reputation(platform.asText(),r.size(),count(r,"SUCCEEDED"),count(r,"FAILED"),count(r,"MISSING"),recognized));
        }
        if(sourceTruncated)limits.add("解读使用的来源最多100条；完整来源仍保存在报告附录和在线原文中，所选来源数量不代表全部引用数量。");
        if(sourceFiltered)limits.add("部分来源不符合公开 HTTP/HTTPS 标准端口规则，未用于解读；完整原始引用仍保留在报告附录和在线原文中。");
        for(var warning:diagnostic.path("warnings"))if(limits.size()<20&&!warning.asText().isBlank()){
            String scoped=excerpt("准备阶段提示（不代表最终诊断报告的全部证据）："+warning.asText(),1000);if(!limits.contains(scoped))limits.add(scoped);}
        result.set("metrics",json.valueToTree(new GeoDiagnosticReportDtos.Metrics(run.expectedUnits(),count(samples,"SUCCEEDED"),count(samples,"FAILED"),count(samples,"MISSING"),List.copyOf(discovery),List.copyOf(reputation))));
        result.set("samples",json.valueToTree(samples));result.set("sources",json.valueToTree(sources.values()));result.set("limitations",json.valueToTree(limits));
        // Full questions appear once in the dictionary. Browser/PDF restore them by questionId;
        // a 10x7 matrix must not duplicate ten 1000-character questions seventy times in model IO.
        for(var sample:result.path("samples")){var modelSample=(ObjectNode)sample;modelSample.remove("questionText");
            var matched=modelSample.putArray("matchedBrandIds");var full=byUnit.get(sample.path("unitKey").asText());
            if(full!=null&&full.status().equals("SUCCEEDED"))matchedBrandIds(full.answerText(),own,diagnostic.path("competitors")).forEach(matched::add);}
        boundModelEvidence(result,json);return result;
    }
    private static GeoDiagnosticReportDtos.Mention mention(String id,String name,List<String> terms,List<GeoDiagnosticReportDtos.Sample> samples,
        Map<String,GeoTaskRecords.Observation> observations,int valid) {
        int mentioned=0;for(var sample:samples){var o=observations.get(sample.unitKey());if(o!=null&&o.status().equals("SUCCEEDED")&&GeoDiagnosticService.matchesNames(o.answerText(),terms))mentioned++;}
        return new GeoDiagnosticReportDtos.Mention(id,name,mentioned,valid==0?null:(double)mentioned/valid);
    }
    private static int count(List<GeoDiagnosticReportDtos.Sample> samples,String status){return (int)samples.stream().filter(s->s.status().equals(status)).count();}
    private static List<String> strings(JsonNode values){List<String> result=new ArrayList<>();values.forEach(v->result.add(v.asText()));return result;}
    static String excerpt(String value,int limit){if(value.length()<=limit)return value;int end=limit;if(Character.isHighSurrogate(value.charAt(end-1)))end--;return value.substring(0,end);}
    /** One contiguous original-text window; never combine distant passages into an apparent quotation. */
    static String answerWindow(String answer,JsonNode profile,JsonNode competitors,int limit) {
        if(answer.length()<=limit)return answer;
        List<String> terms=strings(profile.path("brandTerms"));terms.add(profile.path("brandName").asText());
        int anchor=firstNameIndex(answer,terms);
        if(anchor<0){terms.clear();for(var competitor:competitors){terms.add(competitor.path("name").asText());terms.addAll(strings(competitor.path("brandTerms")));}anchor=firstNameIndex(answer,terms);}
        if(anchor<0||anchor<limit/2)return excerpt(answer,limit);
        int start=Math.max(0,anchor-Math.min(500,limit/3));
        int paragraph=answer.lastIndexOf('\n',start);if(paragraph>=Math.max(0,start-200))start=paragraph+1;
        if(start>0&&Character.isLowSurrogate(answer.charAt(start)))start--;
        return excerpt(answer.substring(start),limit);
    }
    private static int firstNameIndex(String answer,List<String> terms) {
        int earliest=-1;for(String term:terms){if(term==null||term.isBlank())continue;
            var match=java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(term),java.util.regex.Pattern.CASE_INSENSITIVE|java.util.regex.Pattern.UNICODE_CASE).matcher(answer);
            if(match.find()&&(earliest<0||match.start()<earliest))earliest=match.start();}
        return earliest;
    }
    static List<String> matchedBrandIds(String fullAnswer,JsonNode profile,JsonNode competitors) {
        if(fullAnswer==null)return List.of();List<String> matched=new ArrayList<>();var ownTerms=strings(profile.path("brandTerms"));ownTerms.add(profile.path("brandName").asText());
        if(GeoDiagnosticService.matchesNames(fullAnswer,ownTerms))matched.add("OWN");
        for(var competitor:competitors){var terms=strings(competitor.path("brandTerms"));terms.add(competitor.path("name").asText());
            if(GeoDiagnosticService.matchesNames(fullAnswer,terms))matched.add(competitor.path("id").asText());}
        return List.copyOf(matched);
    }

    static GeoDiagnosticReportDtos.Analysis validateAnalysis(JsonNode report,JsonNode evidence,ObjectMapper json) {
        return validateAnalysis(report,evidence,json,false);
    }
    static GeoDiagnosticReportDtos.Analysis validateAnalysis(JsonNode report,JsonNode evidence,ObjectMapper json,boolean consultingRequired) {
        fields(report,Set.of("title","summary","findings","recommendations","contentTopics","limitations"),Set.of("strategy"));
        prose(report.path("title"),200);prose(report.path("summary"),4000);array(report.path("findings"),1,20);array(report.path("recommendations"),1,15);array(report.path("limitations"),0,20);
        for(var limitation:report.path("limitations"))prose(limitation,1000);
        for(var finding:report.path("findings")) {
            GeoDiagnosticDeliveryService.exact(finding,Set.of("section","heading","description","evidence"));
            if(!Set.of("DISCOVERY","REPUTATION","COMPETITION","PUBLIC_RESEARCH").contains(finding.path("section").asText()))invalid();
            prose(finding.path("heading"),200);prose(finding.path("description"),2000);array(finding.path("evidence"),1,5);
            for(var ref:finding.path("evidence"))reference(ref,evidence,finding.path("section").asText());
        }
        for(var recommendation:report.path("recommendations")) {
            fields(recommendation,Set.of("priority","title","action","basisEvidence"),Set.of("currentState","businessImpact","deliverable","acceptance","ownerRole","timeframe"));
            if(!Set.of("P0","P1","P2").contains(recommendation.path("priority").asText()))invalid();
            prose(recommendation.path("title"),200);prose(recommendation.path("action"),2000);array(recommendation.path("basisEvidence"),1,5);
            for(var ref:recommendation.path("basisEvidence"))reference(ref,evidence,null);
            optionalProse(recommendation,"currentState",1000,consultingRequired);optionalProse(recommendation,"businessImpact",800,consultingRequired);
            optionalProse(recommendation,"deliverable",600,consultingRequired);optionalProse(recommendation,"acceptance",600,consultingRequired);
            optionalProse(recommendation,"ownerRole",120,consultingRequired);optionalProse(recommendation,"timeframe",120,consultingRequired);
        }
        Set<String> sections=new HashSet<>();report.path("findings").forEach(f->sections.add(f.path("section").asText()));
        boolean discovery=false,reputation=false;
        for(var sample:evidence.path("samples"))if(sample.path("status").asText().equals("SUCCEEDED")){
            discovery|=sample.path("category").asText().equals("BRAND_DISCOVERY");reputation|=sample.path("category").asText().equals("BRAND_REPUTATION");}
        if(discovery&&!sections.contains("DISCOVERY")||reputation&&!sections.contains("REPUTATION")
            ||discovery&&!evidence.path("competitors").isEmpty()&&!sections.contains("COMPETITION"))invalid();
        array(report.path("contentTopics"),1,10);var brandTerms=strings(evidence.path("profile").path("brandTerms"));brandTerms.add(evidence.path("profile").path("brandName").asText());
        for(var topic:report.path("contentTopics")){
            fields(topic,Set.of("title","audienceQuestion","angle","coveredBrandTerms","basisEvidence"),Set.of("intentLayer"));
            prose(topic.path("title"),200);prose(topic.path("audienceQuestion"),1000);prose(topic.path("angle"),2000);
            array(topic.path("coveredBrandTerms"),0,50);for(var term:topic.path("coveredBrandTerms"))if(!brandTerms.contains(text(term,200)))invalid();
            array(topic.path("basisEvidence"),1,5);for(var ref:topic.path("basisEvidence"))reference(ref,evidence,null);
            if(topic.hasNonNull("intentLayer")&&!LAYERS.contains(text(topic.path("intentLayer"),20)))invalid();
        }
        if(report.hasNonNull("strategy"))strategy(report.path("strategy"),report.path("recommendations").size(),evidence);
        else if(consultingRequired)invalid();
        var validated=json.convertValue(report,GeoDiagnosticReportDtos.Analysis.class);
        return new GeoDiagnosticReportDtos.Analysis(validated.title(),statisticalSummary(evidence),validated.findings(),
            validated.recommendations(),validated.contentTopics(),validated.limitations(),validated.strategy());
    }
    private static final Set<String> LAYERS=Set.of("BRAND","CATEGORY","SCENARIO","QUESTION","DECISION");
    private static void strategy(JsonNode strategy,int recommendationCount,JsonNode evidence) {
        GeoDiagnosticDeliveryService.exact(strategy,Set.of("version","coreJudgment","cognitionFindings","keywordLayers","roadmap"));
        if(!"consulting-v1".equals(strategy.path("version").asText()))invalid();
        var core=strategy.path("coreJudgment");GeoDiagnosticDeliveryService.exact(core,Set.of("headline","description","evidence"));
        prose(core.path("headline"),120);prose(core.path("description"),1600);references(core.path("evidence"),evidence,null);
        array(strategy.path("cognitionFindings"),0,5);
        for(var finding:strategy.path("cognitionFindings")){
            GeoDiagnosticDeliveryService.exact(finding,Set.of("title","observation","businessImpact","evidence"));
            prose(finding.path("title"),120);prose(finding.path("observation"),1200);prose(finding.path("businessImpact"),800);references(finding.path("evidence"),evidence,null);
        }
        array(strategy.path("keywordLayers"),0,5);Set<String> seenLayers=new HashSet<>();
        Map<String,String> questions=new HashMap<>();evidence.path("questions").forEach(q->questions.put(q.path("id").asText(),q.path("category").asText()));
        for(var layer:strategy.path("keywordLayers")){
            GeoDiagnosticDeliveryService.exact(layer,Set.of("layer","questionIds","assessment","nextAction","evidence"));
            String name=text(layer.path("layer"),20);if(!LAYERS.contains(name)||!seenLayers.add(name))invalid();
            array(layer.path("questionIds"),1,10);Set<String> ids=new HashSet<>();String category=name.equals("BRAND")?"BRAND_REPUTATION":"BRAND_DISCOVERY";
            for(var id:layer.path("questionIds"))if(!category.equals(questions.get(text(id,128)))||!ids.add(id.asText()))invalid();
            prose(layer.path("assessment"),800);prose(layer.path("nextAction"),800);
            references(layer.path("evidence"),evidence,name.equals("BRAND")?"REPUTATION":"DISCOVERY");
            for(var ref:layer.path("evidence")){boolean associated=false;for(var sample:evidence.path("samples"))
                if(sample.path("unitKey").equals(ref.path("unitKey"))&&ids.contains(sample.path("questionId").asText()))associated=true;if(!associated)invalid();}
        }
        array(strategy.path("roadmap"),1,4);
        for(var stage:strategy.path("roadmap")){
            GeoDiagnosticDeliveryService.exact(stage,Set.of("stage","objective","recommendationIndexes"));prose(stage.path("stage"),80);prose(stage.path("objective"),800);
            array(stage.path("recommendationIndexes"),1,15);Set<Integer> indexes=new HashSet<>();
            for(var index:stage.path("recommendationIndexes"))if(!index.isIntegralNumber()||!index.canConvertToInt()||index.asInt()<0||index.asInt()>=recommendationCount||!indexes.add(index.asInt()))invalid();
        }
    }
    private static void references(JsonNode refs,JsonNode evidence,String section){array(refs,1,5);for(var ref:refs)reference(ref,evidence,section);}
    private static void optionalProse(JsonNode value,String field,int maximum,boolean required){if(value.hasNonNull(field))prose(value.path(field),maximum);else if(required)invalid();}
    private static void fields(JsonNode value,Set<String> required,Set<String> optional) {
        if(!value.isObject())invalid();Set<String> actual=new HashSet<>();value.fieldNames().forEachRemaining(actual::add);
        if(!actual.containsAll(required))invalid();actual.removeAll(required);if(!optional.containsAll(actual))invalid();
    }
    /** Numeric summary belongs to the frozen metric projection; the model owns qualitative interpretation only. */
    private static String statisticalSummary(JsonNode evidence) {
        var metrics=evidence.path("metrics");var brands=new LinkedHashMap<String,String>();
        brands.put("OWN",text(evidence.path("profile").path("brandName"),200));
        array(evidence.path("competitors"),0,5);array(evidence.path("platformCodes"),1,7);
        for(var competitor:evidence.path("competitors"))if(brands.putIfAbsent(text(competitor.path("id"),128),text(competitor.path("name"),200))!=null)invalid();
        Map<String,Integer> mentioned=new LinkedHashMap<>();brands.keySet().forEach(id->mentioned.put(id,0));
        int discoveryExpected=0,discoveryValid=0,reputationExpected=0,reputationValid=0,recognized=0;
        List<String> platforms=new ArrayList<>(),ownByPlatform=new ArrayList<>();
        for(var code:evidence.path("platformCodes")){
            String name=platformName(code.asText());platforms.add(name);JsonNode discovery=null,reputation=null;
            for(var item:metrics.path("discovery"))if(item.path("platformCode").equals(code)){if(discovery!=null)invalid();discovery=item;}
            for(var item:metrics.path("reputation"))if(item.path("platformCode").equals(code)){if(reputation!=null)invalid();reputation=item;}
            if(discovery==null||reputation==null)invalid();
            int expected=metricCount(discovery,"expectedSamples"),valid=metricCount(discovery,"validSamples");if(valid>expected)invalid();
            discoveryExpected+=expected;discoveryValid+=valid;Map<String,Integer> local=new HashMap<>();
            for(var mention:discovery.path("mentions")){
                String id=mention.path("brandId").asText();if(!brands.containsKey(id))continue;
                int count=metricCount(mention,"mentionedSamples");if(count>valid||local.putIfAbsent(id,count)!=null)invalid();
            }
            if(!local.keySet().equals(brands.keySet()))invalid();
            local.forEach((id,count)->mentioned.merge(id,count,Integer::sum));
            ownByPlatform.add(name+" "+(valid==0?"暂无有效自然回答":local.get("OWN")+"/"+valid));
            int rExpected=metricCount(reputation,"expectedSamples"),rValid=metricCount(reputation,"validSamples"),rMentioned=metricCount(reputation,"recognizedSamples");
            if(rValid>rExpected||rMentioned>rValid)invalid();reputationExpected+=rExpected;reputationValid+=rValid;recognized+=rMentioned;
        }
        int expected=metricCount(metrics,"expectedSamples"),valid=metricCount(metrics,"validSamples"),failed=metricCount(metrics,"failedSamples"),missing=metricCount(metrics,"missingSamples");
        if(expected!=valid+failed+missing||expected!=discoveryExpected+reputationExpected||valid!=discoveryValid+reputationValid)invalid();
        StringBuilder summary=new StringBuilder("本次诊断覆盖 ").append(platforms.size()).append(" 个平台（").append(String.join("、",platforms))
            .append("），预计 ").append(expected).append(" 个样本：有效 ").append(valid).append("，失败 ").append(failed).append("，缺失 ").append(missing).append("。\n");
        summary.append("自然发现：有效回答 ").append(discoveryValid).append(" / 预计 ").append(discoveryExpected).append("。");
        if(discoveryValid==0)summary.append("暂无有效自然回答，各品牌的自然提及比例不可计算。\n");
        else{
            List<String> totals=new ArrayList<>();brands.forEach((id,name)->totals.add((id.equals("OWN")?"本品牌":"同类品牌")+"「"+name+"」 "+mentioned.get(id)));
            for(int i=0;i<totals.size();i++)totals.set(i,totals.get(i)+"/"+discoveryValid);
            summary.append("合计提及数/有效自然回答数：").append(String.join("；",totals)).append("。\n");
        }
        summary.append("本品牌分平台自然提及（提及数/有效自然回答数）：").append(String.join("；",ownByPlatform)).append("。\n")
            .append("点名口碑：有效回答 ").append(reputationValid).append(" / 预计 ").append(reputationExpected).append("；");
        if(reputationValid==0)summary.append("暂无有效口碑回答，不能判断平台如何描述品牌。");
        else summary.append("其中再次提及本品牌 ").append(recognized).append("/").append(reputationValid).append("。");
        summary.append("口碑组不计入自然可见度，也不代表满意度。以上仅为本次采样快照，不证明优化因果、排名或市场份额。");
        if(summary.length()>4000)invalid();return summary.toString();
    }
    private static int metricCount(JsonNode value,String field){var count=value.path(field);if(!count.isIntegralNumber()||!count.canConvertToInt()||count.asInt()<0||count.asInt()>70)invalid();return count.asInt();}
    private static String platformName(String code){return switch(code){case "DOUBAO"->"豆包";case "DEEPSEEK"->"DeepSeek";case "QWEN"->"通义千问";
        case "WENXIN"->"文心一言";case "KIMI"->"Kimi";case "YUANBAO"->"腾讯元宝";case "ZHIPU"->"智谱";default->throw new IllegalArgumentException("Unsupported diagnostic platform");};}
    /** Byte-aware model projection. Matrix/metrics stay intact; final PDF reads full immutable answers/citations separately. */
    private static void boundModelEvidence(ObjectNode result,ObjectMapper json) {
        boolean bounded=false;
        for(int bound:new int[]{350,175,80,24}){
            if(bytes(result,json)<=240*1024){if(bounded)projectionLimit(result);return;}
            bounded=true;
            for(var sample:result.path("samples"))if(sample.path("answerExcerpt").isTextual()){
                String old=sample.path("answerExcerpt").asText();if(old.length()>bound)((ObjectNode)sample).put("answerExcerpt",answerWindow(old,result.path("profile"),result.path("competitors"),bound)).put("answerTruncated",true);}
            for(var source:result.path("sources")){var s=(ObjectNode)source;s.put("snippet",excerpt(s.path("snippet").asText(),bound));s.put("title",excerpt(s.path("title").asText(),100));}
        }
        Set<String> required=new HashSet<>();result.path("competitors").forEach(c->c.path("sourceIds").forEach(s->required.add(s.asText())));
        var sources=(com.fasterxml.jackson.databind.node.ArrayNode)result.path("sources");
        while(bytes(result,json)>240*1024&&sources.size()>required.size()){
            int index=-1;for(int i=sources.size()-1;i>=0;i--)if(!required.contains(sources.get(i).path("id").asText())){index=i;break;}
            if(index<0)break;String removed=sources.remove(index).path("id").asText();
            for(var sample:result.path("samples")){var refs=(com.fasterxml.jackson.databind.node.ArrayNode)sample.path("sourceIds");
                for(int i=refs.size()-1;i>=0;i--)if(refs.get(i).asText().equals(removed))refs.remove(i);}
        }
        if(bytes(result,json)>250*1024)invalid();
        projectionLimit(result);
    }
    private static void projectionLimit(ObjectNode result){var limits=(com.fasterxml.jackson.databind.node.ArrayNode)result.path("limitations");
        String notice="解读使用的回答和来源摘录因篇幅限制进一步缩短；完整回答及完整引用仍保存在报告附录和在线原文中。";
        if(limits.size()<20)limits.add(notice);else limits.set(19,new com.fasterxml.jackson.databind.node.TextNode(notice));}
    private static int bytes(JsonNode value,ObjectMapper json){try{return json.writeValueAsBytes(value).length;}catch(Exception invalid){throw new IllegalStateException(invalid);}}
    static void preflight(ObjectNode data,ObjectMapper json) {
        // Legacy, unsubmitted drafts/quotes may predate the reserved identity check.
        // Reject before quoting or freezing funds instead of creating an ambiguous paid report.
        for(var competitor:data.path("competitors"))if("OWN".equals(competitor.path("id").asText().strip()))
            throw new com.guanghe.backend.common.api.BusinessException(com.guanghe.backend.common.api.ErrorCode.VALIDATION_FAILED,
                "同类品牌标识与自家品牌保留标识冲突，请重新添加该同类品牌。",Map.of("field","competitors","reason","DIAGNOSTIC_RESERVED_BRAND_ID"));
        // No provider/DB reads. Test the minimum required dictionary/matrix/selected-source shape
        // before freezing funds; actual answers and extra citations are bounded only for model IO.
        ObjectNode minimum=data.deepCopy();minimum.remove(List.of("discoveryQuestions","reputationQuestions","warnings"));
        var questions=minimum.putArray("questions");int sequence=0;
        for(String field:List.of("discoveryQuestions","reputationQuestions"))for(var q:data.path(field))questions.addObject()
            .put("id",Long.toString(9_000_000_000_000_000_000L+sequence++)).put("category",field.equals("discoveryQuestions")?"BRAND_DISCOVERY":"BRAND_REPUTATION").put("text",q.path("text").asText());
        var knownBrandIds=new ArrayList<String>(List.of("OWN"));data.path("competitors").forEach(c->knownBrandIds.add(c.path("id").asText()));
        var samples=minimum.putArray("samples");for(var q:questions)for(var p:data.path("platformCodes")){
            var sample=samples.addObject()
            .put("unitKey",q.path("id").asText()+":"+p.asText()+":1").put("questionId",q.path("id").asText()).put("category",q.path("category").asText()).put("platformCode",p.asText())
            .put("status","MISSING").putNull("answerExcerpt").put("answerTruncated",false).put("stableErrorCode","SAMPLE_MISSING");sample.putArray("sourceIds");
            // Capacity-only worst-case flags: an eventual successful answer may contain every confirmed brand.
            var matches=sample.putArray("matchedBrandIds");knownBrandIds.forEach(matches::add);}
        for(var source:minimum.path("sources")){var s=(ObjectNode)source;s.put("title",excerpt(s.path("title").asText(),100)).put("snippet","");}
        // Reserve headroom for immutable metrics, limitations and the signed request envelope.
        if(bytes(minimum,json)>215*1024)throw new com.guanghe.backend.common.api.BusinessException(com.guanghe.backend.common.api.ErrorCode.VALIDATION_FAILED,
            "本次诊断资料超过报告输入容量，请精简品牌介绍、品牌词或问题后重新报价。",Map.of("reason","DIAGNOSTIC_EVIDENCE_CAPACITY"));
    }
    private static void reference(JsonNode ref,JsonNode evidence,String section) {
        GeoDiagnosticDeliveryService.exact(ref,Set.of("unitKey","sourceId","quote"));
        String unit=nullable(ref.path("unitKey"),256),source=nullable(ref.path("sourceId"),128),quote=text(ref.path("quote"),1000);
        if(unit==null&&source==null)invalid();JsonNode sample=null;
        if(unit!=null){for(var s:evidence.path("samples"))if(s.path("unitKey").asText().equals(unit)){sample=s;break;}
            if(sample==null||!sample.path("status").asText().equals("SUCCEEDED")||!sample.path("answerExcerpt").asText().contains(quote))invalid();
            if("PUBLIC_RESEARCH".equals(section)||("REPUTATION".equals(section)&&!sample.path("category").asText().equals("BRAND_REPUTATION"))
                ||(Set.of("DISCOVERY","COMPETITION").contains(section==null?"":section)&&!sample.path("category").asText().equals("BRAND_DISCOVERY")))invalid();}
        else if(section!=null&&!section.equals("PUBLIC_RESEARCH"))invalid();
        if(source!=null){JsonNode item=null;for(var s:evidence.path("sources"))if(s.path("id").asText().equals(source)){item=s;break;}
            if(item==null||!item.path("snippet").asText().contains(quote))invalid();
            if(sample!=null&&!strings(sample.path("sourceIds")).contains(source))invalid();}
    }
    private static void prose(JsonNode value,int max){String text=text(value,max);if(text.toLowerCase(Locale.ROOT).matches("(?s).*https?://.*"))invalid();}
    private static String text(JsonNode value,int max){if(!value.isTextual())invalid();String text=value.asText();if(text.isBlank()||text.codePointCount(0,text.length())>max)invalid();return text;}
    private static String nullable(JsonNode value,int max){return value.isNull()?null:text(value,max);}
    private static void array(JsonNode value,int min,int max){if(!value.isArray()||value.size()<min||value.size()>max)invalid();}
    private static void invalid(){throw new IllegalArgumentException("Unsupported diagnostic evidence");}
}
