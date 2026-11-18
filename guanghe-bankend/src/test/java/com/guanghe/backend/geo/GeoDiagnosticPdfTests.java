package com.guanghe.backend.geo;

import java.time.OffsetDateTime;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class GeoDiagnosticPdfTests {
    @Test void chineseVectorReportKeepsFullAnswersAllCitationsAndExactVersion() throws Exception {
        var fixture=new GeoDiagnosticProjectionTests();var evidence=fixture.goodEvidence();var analysis=GeoDiagnosticProjection.validateAnalysis(fixture.goodAnalysis(),evidence,fixture.json);
        var source=new GeoDiagnosticDtos.Source("web-1","完整公开来源","https://example.com/about","公开资料。".repeat(200)+"\n完整公开资料末尾标记");
        var original=fixture.observation(10,"DOUBAO","开头事实。".repeat(200)+"\n完整回答末尾证据保留标记\n特殊表情😀",false);
        List<GeoTaskDtos.CitationResponse> citations=new ArrayList<>();for(int i=1;i<=12;i++)citations.add(new GeoTaskDtos.CitationResponse(i,"来源 "+i,
            "https://example.com/source-"+i,"example.com","完整引用片段 "+i,OffsetDateTime.parse("2026-10-14T01:00:00Z")));
        var observation=new GeoTaskDtos.ObservationResponse(original.unitKey(),"10","广州适合上班族的咖啡店有哪些？","DOUBAO",1,"SUCCEEDED",original.answerText(),null,
            "DOUBAO","fixture",original.startedAt(),original.finishedAt(),1,new GeoTaskDtos.UsageResponse(1,1,1,null,null),1,citations,"fixture-v1");
        List<GeoDiagnosticReportDtos.Sample> samples=new ArrayList<>();for(var value:evidence.path("samples")){
            var s=(com.fasterxml.jackson.databind.node.ObjectNode)value.deepCopy();s.remove("matchedBrandIds");s.put("questionText",s.path("questionId").asText().equals("12")?"星河咖啡体验如何？":"广州适合上班族的咖啡店有哪些？");
            samples.add(fixture.json.convertValue(s,GeoDiagnosticReportDtos.Sample.class));}
        var profile=fixture.json.convertValue(evidence.path("profile"),GeoDiagnosticDtos.Profile.class);
        var metrics=fixture.json.convertValue(evidence.path("metrics"),GeoDiagnosticReportDtos.Metrics.class);
        var report=new GeoDiagnosticReportDtos.Detail("90",7,1,"2","1","PDF_RUNNING","PARTIAL",false,1,1,false,null,6,3,3,profile,List.of("DOUBAO","DEEPSEEK"),metrics,
            analysis,samples,List.of(new GeoDiagnosticDtos.Source("web-1","分析用缩短来源","https://example.com/about","短摘录")),List.of("单次采样，不证明因果。"),OffsetDateTime.parse("2026-10-14T01:00:00Z"),null);
        byte[] bytes=GeoDiagnosticPdfRenderer.pdf(report,List.of(observation),List.of(source));
        try(var document=Loader.loadPDF(bytes)){
            assertThat(document.getNumberOfPages()).isGreaterThan(2).isLessThan(30);
            String text=new PDFTextStripper().getText(document);
            assertThat(text).contains("星河咖啡","报告版本 1","2026/10/14 09:00","北京时间","完整回答末尾证据保留标记","完整公开资料末尾标记","[U+1F600]",
                "source-12","自然发现","点名口碑","优化建议与内容选题蓝图");
            long links=0;for(var page:document.getPages())links+=page.getAnnotations().stream().filter(a->a instanceof org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink).count();
            assertThat(links).isGreaterThanOrEqualTo(13);assertThat(bytes.length).isLessThan(10*1024*1024);
        }
    }
    @Test void noDataPdfDoesNotPaintUnknownVisibilityAsZeroPercent() throws Exception {
        var fixture=new GeoDiagnosticProjectionTests();var evidence=fixture.evidence(List.of());
        var analysis=new GeoDiagnosticReportDtos.Analysis("星河咖啡 · 采集失败说明","无有效采样，不判断优化成果。",List.of(),List.of(),List.of(),List.of("不存在可计算口碑评分。"));
        List<GeoDiagnosticReportDtos.Sample> samples=new ArrayList<>();for(var value:evidence.path("samples")){var s=(com.fasterxml.jackson.databind.node.ObjectNode)value.deepCopy();s.remove("matchedBrandIds");s.put("questionText","问题原文");samples.add(fixture.json.convertValue(s,GeoDiagnosticReportDtos.Sample.class));}
        var report=new GeoDiagnosticReportDtos.Detail("91",4,1,"2","1","PDF_RUNNING","FAILED",true,0,1,false,null,6,0,6,
            fixture.json.convertValue(evidence.path("profile"),GeoDiagnosticDtos.Profile.class),List.of("DOUBAO","DEEPSEEK"),fixture.json.convertValue(evidence.path("metrics"),GeoDiagnosticReportDtos.Metrics.class),
            analysis,samples,List.of(),List.of("没有有效回答，不补零。"),OffsetDateTime.now(),null);
        try(var document=Loader.loadPDF(GeoDiagnosticPdfRenderer.pdf(report,List.of(),List.of()))){
            String text=new PDFTextStripper().getText(document);assertThat(text).contains("提及率不可计算","失败说明","已释放 6 积分").doesNotContain("0.0%");
        }
    }
    @Test void shortEvidenceQuoteIsKeptTogetherRatherThanLeavingPunctuationOnTheNextPage() throws Exception {
        var fixture=new GeoDiagnosticProjectionTests();var evidence=fixture.goodEvidence();String quote="原样引证".repeat(50);
        var finding=new GeoDiagnosticReportDtos.Finding("DISCOVERY","完整证据引用","说明段落。".repeat(180),List.of(new GeoDiagnosticReportDtos.Evidence("10:DOUBAO:1",null,quote)));
        var analysis=new GeoDiagnosticReportDtos.Analysis("诊断证据分页测试","摘要说明。".repeat(75),List.of(finding),List.of(),List.of(),List.of());
        var report=new GeoDiagnosticReportDtos.Detail("92",4,1,"2","1","PDF_RUNNING","SUCCEEDED",false,1,1,false,null,6,6,0,
            fixture.json.convertValue(evidence.path("profile"),GeoDiagnosticDtos.Profile.class),List.of("DOUBAO","DEEPSEEK"),fixture.json.convertValue(evidence.path("metrics"),GeoDiagnosticReportDtos.Metrics.class),
            analysis,List.of(),List.of(),List.of(),OffsetDateTime.now(),null);
        try(var document=Loader.loadPDF(GeoDiagnosticPdfRenderer.pdf(report,List.of(),List.of()))){boolean found=false;
            for(int page=1;page<=document.getNumberOfPages();page++){var stripper=new PDFTextStripper();stripper.setStartPage(page);stripper.setEndPage(page);
                String value=stripper.getText(document).replaceAll("\\s+","");if(value.contains("证据：10:DOUBAO:1·“"+quote+"”"))found=true;}
            assertThat(found).isTrue();
        }
    }
    @Test void appendixFormatsMarkdownAndKeepsEveryHeadingWithItsFollowingText() throws Exception {
        StringBuilder answer=new StringBuilder("**完整原文开头**\n\n");
        for(int i=1;i<=16;i++)answer.append("正文铺垫。".repeat(55)).append("\n\n### 分节标题").append(i)
            .append("\n\n**连续小标题").append(i).append("**\n\n跟随正文标记").append(i).append("，保留全部文字和内容。\n\n");
        answer.append("| 品牌 | 特点 |\n| --- | --- |\n| 星河 | **现磨** |\n\n- 清单第一项\n- 清单第二项\n\n")
            .append("[公开原文](https://example.com/original)\n\n<div>HTML保留文字<br>第二行原文</div>\n\n最后标记😀📍");
        var input=answer.toString();
        try(var document=Loader.loadPDF(appendixPdf(input,List.of()))){
            var full=new PDFTextStripper().getText(document);String compact=full.replaceAll("\\s+","");
            assertThat(compact).contains("品牌：星河；特点：现磨","清单第一项","清单第二项","https://example.com/original",
                "HTML保留文字","第二行原文","最后标记[笑脸][位置]","[U+1F600]","[U+1F4CD]").doesNotContain("###","**","|---|","<div>");
            for(int section=1;section<=16;section++){
                boolean together=false;
                String heading="分节标题"+section;
                for(int page=1;page<=document.getNumberOfPages();page++){
                    var stripper=new PDFTextStripper();stripper.setStartPage(page);stripper.setEndPage(page);var text=stripper.getText(document);
                    if(text.lines().anyMatch(line->line.strip().equals(heading)))together=text.contains("跟随正文标记"+section)&&text.contains("连续小标题"+section);
                }
                assertThat(together).as("heading %s must stay with its paragraph",section).isTrue();
            }
        }
        assertThat(answer.toString()).isEqualTo(input);
    }
    @Test void longSourceSnippetsFlowAcrossPagesAndFullUrlRemainsClickable() throws Exception {
        List<GeoDiagnosticDtos.Source> sources=new ArrayList<>();String url="https://example.com/"+"long-source-path-".repeat(50);
        for(int i=0;i<6;i++)sources.add(new GeoDiagnosticDtos.Source("public-"+i,"来源标题"+i,i==0?url:"https://example.com/"+i,
            "来源完整文字".repeat(145)+"来源末尾"+i));
        try(var document=Loader.loadPDF(appendixPdf("完整回答。",sources))){
            String compact=new PDFTextStripper().getText(document).replaceAll("\\s+","");
            assertThat(compact).contains(url,"来源末尾5");
            boolean clickable=false;int sourcePages=0;
            for(int page=1;page<=document.getNumberOfPages();page++){
                var stripper=new BodyPositions();stripper.setStartPage(page);stripper.setEndPage(page);String text=stripper.getText(document);
                if(text.contains("来源完整文字")&&!text.contains("附录 B")&&!text.contains("来源末尾5")){
                    sourcePages++;assertThat(stripper.maximumBodyY).as("source page %s should use the available space",page).isGreaterThan(700);
                }
                for(var annotation:document.getPage(page-1).getAnnotations())if(annotation instanceof org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink link
                    &&link.getAction() instanceof org.apache.pdfbox.pdmodel.interactive.action.PDActionURI action&&url.equals(action.getURI()))clickable=true;
            }
            assertThat(sourcePages).isGreaterThan(0);assertThat(clickable).isTrue();
        }
    }
    @Test void wrappedChineseParagraphDoesNotStartLinesWithClosingPunctuation() throws Exception {
        String answer="正文".repeat(26)+"。\n\n"+("长段落内容，保留句尾标点。".repeat(100));
        try(var document=Loader.loadPDF(appendixPdf(answer,List.of()))){
            String text=new PDFTextStripper().getText(document);
            for(String line:text.split("\\R"))assertThat(line).doesNotMatch("^[，。、；：！？）》】〕〉」』”’]+$");
        }
    }
    @Test void symbolKeyUsesCompactWrappedLinesWithoutLosingCodePoints() throws Exception {
        String symbols="😀📍📌🔍💡📊📝✅⚠☕🌟🔹";
        try(var document=Loader.loadPDF(appendixPdf("原文表情："+symbols,List.of()))){
            String text=new PDFTextStripper().getText(document);
            String key=text.substring(text.indexOf("附录 C"));
            assertThat(key).contains("[U+1F600]","[U+1F4CD]","[U+1F4CC]","[U+1F50D]","[U+1F4A1]","[U+1F4CA]","[U+1F4DD]");
            assertThat(key.lines().filter(line->line.split("\\[U\\+",-1).length>2).count()).isGreaterThan(0);
        }
    }
    @Test void methodologyDeduplicatesExactCopiesOnlyAndPreservesDistinctLimitsInOrder() throws Exception {
        var fixture=new GeoDiagnosticProjectionTests();var evidence=fixture.goodEvidence();
        var fixed=List.of("共同方法说明。","准备阶段提示：当时来源有限。","摘录限700字。");
        var interpreted=List.of("共同方法说明。","摘录限350字。","仅代表本批样本。");
        var analysis=new GeoDiagnosticReportDtos.Analysis("限制说明去重测试","摘要说明。",List.of(),List.of(),List.of(),interpreted);
        var report=new GeoDiagnosticReportDtos.Detail("94",4,1,"2","1","PDF_RUNNING","SUCCEEDED",false,1,1,false,null,6,6,0,
            fixture.json.convertValue(evidence.path("profile"),GeoDiagnosticDtos.Profile.class),List.of("DOUBAO","DEEPSEEK"),fixture.json.convertValue(evidence.path("metrics"),GeoDiagnosticReportDtos.Metrics.class),
            analysis,List.of(),List.of(),fixed,OffsetDateTime.now(),null);
        try(var document=Loader.loadPDF(GeoDiagnosticPdfRenderer.pdf(report,List.of(),List.of()))){
            String text=new PDFTextStripper().getText(document).replaceAll("\\s+","");
            String methods=text.substring(text.indexOf("06方法与局限"),text.indexOf("附录A"));
            assertThat(methods.split("共同方法说明。",-1)).hasSize(2);
            var ordered=List.of("共同方法说明。","准备阶段提示：当时来源有限。","摘录限700字。","摘录限350字。","仅代表本批样本。");
            int previous=-1;for(String limitation:ordered){assertThat(methods).contains(limitation);int at=methods.indexOf(limitation);assertThat(at).isGreaterThan(previous);previous=at;}
        }
        assertThat(report.limitations()).isEqualTo(fixed);assertThat(report.analysis().limitations()).isEqualTo(interpreted);
    }
    private static final class BodyPositions extends PDFTextStripper {
        float maximumBodyY;
        BodyPositions() throws java.io.IOException {super();}
        @Override protected void processTextPosition(TextPosition text){
            if(text.getYDirAdj()>45&&text.getYDirAdj()<795)maximumBodyY=Math.max(maximumBodyY,text.getYDirAdj());super.processTextPosition(text);
        }
    }
    private byte[] appendixPdf(String answer,List<GeoDiagnosticDtos.Source> sources) throws Exception {
        var fixture=new GeoDiagnosticProjectionTests();var evidence=fixture.goodEvidence();var original=fixture.observation(10,"DOUBAO",answer,false);
        var observation=new GeoTaskDtos.ObservationResponse(original.unitKey(),"10","完整问题原文","DOUBAO",1,"SUCCEEDED",answer,null,"DOUBAO","fixture",
            original.startedAt(),original.finishedAt(),1,new GeoTaskDtos.UsageResponse(1,1,1,null,null),1,List.of(),"fixture-v1");
        var sample=new GeoDiagnosticReportDtos.Sample(original.unitKey(),"10","BRAND_DISCOVERY","完整问题原文","DOUBAO","SUCCEEDED","短摘录",true,null,List.of());
        var report=new GeoDiagnosticReportDtos.Detail("93",4,1,"2","1","PDF_RUNNING","SUCCEEDED",false,1,1,false,null,6,6,0,
            fixture.json.convertValue(evidence.path("profile"),GeoDiagnosticDtos.Profile.class),List.of("DOUBAO","DEEPSEEK"),fixture.json.convertValue(evidence.path("metrics"),GeoDiagnosticReportDtos.Metrics.class),
            GeoDiagnosticProjection.validateAnalysis(fixture.goodAnalysis(),evidence,fixture.json),List.of(sample),List.of(),List.of(),OffsetDateTime.now(),null);
        return GeoDiagnosticPdfRenderer.pdf(report,List.of(observation),sources);
    }
}
