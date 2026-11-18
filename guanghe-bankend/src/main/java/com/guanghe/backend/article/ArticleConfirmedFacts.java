package com.guanghe.backend.article;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Narrow source-excerpt confirmation, never an authority upgrade of a whole assistant answer. */
final class ArticleConfirmedFacts {
    private static final Pattern AFFIRMATION=Pattern.compile(
            "(?:信息|资料|消息|事实|内容|这些|以上|上面).{0,20}(?:都(?:是)?准确|都(?:是)?正确|都属实|全部准确|全部属实|均属实|准确无误)");
    private static final Pattern EXCLUSION=Pattern.compile(
            "但|不过|除了|除外|部分|有些|不准确|不正确|不对|不属实|不全|不是|不能|无法|不确定|仅|只有|如果|假如|待核实|待确认|需要核实|需要确认|[？?]|```|^\\s*>");
    private static final Pattern FACT_QUESTION=Pattern.compile(
            "(?:哪些|是否|是不是|请.{0,8}确认|核对|确认一下)[^。\\n]{0,160}(?:准确|属实|正确|真实)[^。\\n]{0,80}(?:对外|公开)|"
            + "(?:准确|属实|正确|真实)[^。\\n]{0,80}(?:可以|可否|能否|能不能)(?:对外|公开)");
    private static final Pattern EXCERPT=Pattern.compile(
            "(?:摘录(?:里|中)?提到|资料(?:里|中)提到|已查到的品牌事实)[：:]\\s*([^\\r\\n]+)");
    private static final Pattern SOURCE_URL=Pattern.compile("https://[^\\s\\]）)>]+",Pattern.CASE_INSENSITIVE);
    private static final Pattern LIST_HEADER=Pattern.compile("(?:待确认|待核对)(?:的)?(?:品牌|公司|企业)?(?:资料|事实|信息)[：:]?");
    private static final Pattern FACT_ITEM=Pattern.compile(
            "^\\s*(?:[-*]|[0-9]+[.、])\\s*(?:品牌(?:名称)?|公司(?:名称|全称)?|企业名称|成立(?:时间|年份)|官网(?:域名)?|"
            + "所在地|地址|主营(?:业务)?|服务(?:对象|范围)|目标(?:客群|客户)|客群|行业|产品形态)[：:]\\s*.+$");
    private static final Pattern UNSAFE_FACT=Pattern.compile(
            "```|忽略.{0,8}(?:指令|规则)|系统提示|system\\s*prompt|调用工具|执行(?:命令|代码)|(?:保证|承诺).{0,20}(?:排名|第一|效果)|[？?]",
            Pattern.CASE_INSENSITIVE);

    private ArticleConfirmedFacts() { }

    static ArrayNode project(ObjectMapper json,long project,long conversation,JsonNode brand,
            List<ArticleFullMapper.ConfirmedFactSource> candidates) {
        ArrayNode out=json.createArrayNode();
        List<String> names=new ArrayList<>();
        if(brand.path("name").isTextual()) names.add(brand.path("name").asText());
        brand.path("aliases").forEach(alias->{ if(alias.isTextual()) names.add(alias.asText()); });
        for(var candidate:candidates) {
            if(out.size()>=3) break;
            String confirmation=candidate.confirmationText();
            String source=candidate.sourceText();
            if(confirmation==null || source==null || confirmation.length()>500 || source.length()>16000
                    || EXCLUSION.matcher(confirmation).find() || !AFFIRMATION.matcher(confirmation).find()
                    || !FACT_QUESTION.matcher(source).find() || source.contains("```")) continue;
            String excerpt=extract(source);
            if(excerpt==null || excerpt.length()>2000 || UNSAFE_FACT.matcher(excerpt).find()
                    || names.stream().filter(name->!name.isBlank()).noneMatch(excerpt::contains)) continue;
            // Attribute an excerpt only to a URL in its own paragraph, never an unrelated link.
            int excerptStart=source.indexOf(excerpt);
            String sourcePrefix=excerptStart<0?"":source.substring(source.lastIndexOf('\n',excerptStart)+1,excerptStart);
            var url=SOURCE_URL.matcher(sourcePrefix);
            String sourceUrl=null;
            while(url.find()) sourceUrl=url.group();
            if(sourceUrl!=null && sourceUrl.length()>2048) continue;
            List<String> facts=new ArrayList<>();
            for(String fact:excerpt.split("[；\\r\\n]+")) {
                String value=fact.strip();
                if(!value.isEmpty()) facts.add(value);
            }
            if(facts.isEmpty() || facts.size()>10 || facts.stream().anyMatch(fact->fact.length()>1000)) continue;
            var item=out.addObject().put("source","USER_CONFIRMED_SOURCE_EXCERPT")
                    .put("projectId",Long.toString(project)).put("conversationId",Long.toString(conversation))
                    .put("sourceAssistantMessageId",Long.toString(candidate.assistantMessageId()))
                    .put("confirmationUserMessageId",Long.toString(candidate.confirmationMessageId()))
                    .put("sourceCreatedAt",candidate.sourceCreatedAt().toString()).put("confirmedAt",candidate.confirmedAt().toString())
                    .put("sourceExcerpt",excerpt).put("confirmationQuote",confirmation);
            if(sourceUrl==null) item.putNull("sourceUrl"); else item.put("sourceUrl",sourceUrl);
            var values=item.putArray("facts");facts.forEach(values::add);
        }
        return out;
    }

    private static String extract(String source) {
        var excerpt=EXCERPT.matcher(source);
        if(excerpt.find()) {
            String text=excerpt.group(1).strip();
            // Do not combine multiple unrelated sourced excerpts into one blanket confirmation.
            return excerpt.find() || !text.matches("(?s).*[。.!！]$")?null:text;
        }
        String[] lines=source.split("\\R");
        List<String> items=new ArrayList<>();
        boolean collecting=false;
        for(String line:lines) {
            String plain=line.replace("**","").strip();
            if(!collecting) {
                if(LIST_HEADER.matcher(plain).matches()) collecting=true;
                continue;
            }
            if(plain.isBlank() && items.isEmpty()) continue;
            if(!FACT_ITEM.matcher(plain).matches()) break;
            // Preserve the entire source line, not a model-generated paraphrase.
            items.add(line.strip());
        }
        return items.isEmpty()?null:String.join("\n",items);
    }
}
