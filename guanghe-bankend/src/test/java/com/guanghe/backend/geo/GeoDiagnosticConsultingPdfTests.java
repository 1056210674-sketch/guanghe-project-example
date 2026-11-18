package com.guanghe.backend.geo;

import java.nio.file.*;
import java.time.OffsetDateTime;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class GeoDiagnosticConsultingPdfTests {
    @Test void consultingReportUsesMeasuredMatrixLandscapeStrategyAndCompletePortraitAppendix() throws Exception {
        var fixture=new GeoDiagnosticProjectionTests();var evidence=fixture.goodEvidence();
        var analysis=GeoDiagnosticProjection.validateAnalysis(fixture.consultingAnalysis(),evidence,fixture.json,true);
        var report=report(fixture,analysis,"已确认的品牌介绍。");
        var original=fixture.observation(10,"DOUBAO","真实回答完整末尾标记😀",false);
        var observation=new GeoTaskDtos.ObservationResponse(original.unitKey(),"10","完整自然问题","DOUBAO",1,"SUCCEEDED",original.answerText(),null,
            "DOUBAO","fixture",original.startedAt(),original.finishedAt(),1,new GeoTaskDtos.UsageResponse(1,1,1,null,null),1,
            List.of(new GeoTaskDtos.CitationResponse(1,"原始证据","https://example.com/evidence","example.com","完整来源",original.finishedAt())),"fixture-v1");
        byte[] pdf=GeoDiagnosticPdfRenderer.pdf(report,List.of(observation),List.of());
        Files.createDirectories(Path.of("target/diagnostic-preview/compact-v2"));Files.write(Path.of("target/diagnostic-preview/compact-v2/consulting-fixture.pdf"),pdf);
        try(var document=Loader.loadPDF(pdf)){
            assertThat(document.getPage(0).getMediaBox().getWidth()).isEqualTo(960);
            assertThat(document.getPage(0).getMediaBox().getHeight()).isEqualTo(540);
            String text=new PDFTextStripper().getText(document).replaceAll("\\s+","");
            assertThat(text).contains("先补齐可核查的品牌事实","逐问观察","未知","未提及","失败","缺失","自然发现命中/有效样本",
                "本批观察","验收口径","一份核对后的门店事实页","Q1","真实回答完整末尾标记[笑脸]","[U+1F600]","OBS:preview");
            assertThat(text).contains("1个去重报告信源","去重报告信源1");
            assertThat(document.getPages()).anyMatch(p->p.getMediaBox().getHeight()>p.getMediaBox().getWidth());
            assertThat(document.getPages()).anyMatch(p->{try{return !p.getAnnotations().isEmpty();}catch(Exception e){throw new RuntimeException(e);}});
            assertWithinPage(document);
            String[] sections={"EXECUTIVE SUMMARY","RESEARCH METHOD","BRAND PROFILE","CORE DIAGNOSIS",
                "QUESTION EVIDENCE","BRAND COGNITION","INTENT COVERAGE","COMPETITIVE CONTEXT",
                "DIAGNOSTIC MAP","P1 / CONTENT SYSTEM","MEASUREMENT BASELINE","CONTENT BLUEPRINT",
                "EXECUTION ROADMAP","DELIVERY & NEXT STEP"};
            String fullText=new PDFTextStripper().getText(document);int previous=-1;
            for(String section:sections){int position=fullText.indexOf(section);assertThat(position).as(section).isGreaterThan(previous);previous=position;}
            assertThat(fullText).doesNotContain("P0 / FOUNDATIONAL ACTIONS","P2 / REINFORCEMENT");
            renderCore(document,"compact-v2");
        }
    }
    @Test void fullSixteenSectionTemplatePreservesEveryLongActionAndUsesDenseLayouts() throws Exception {
        var fixture=new GeoDiagnosticProjectionTests();var source=fixture.consultingAnalysis();
        var rec=source.path("recommendations").get(0).deepCopy();var recommendations=source.putArray("recommendations");
        String[] priorities={"P0","P0","P1","P1","P1","P2","P2","P2"};
        for(int i=0;i<priorities.length;i++){
            var r=(com.fasterxml.jackson.databind.node.ObjectNode)rec.deepCopy();r.put("priority",priorities[i]).put("title","确认可引用事实与服务场景 "+i)
                .put("currentState","回答提供了零散事实，但用户仍缺少选择服务时需要的范围与交付依据。".repeat(7)+"现状终点"+i)
                .put("businessImpact","顾客需要明确服务对象和执行边界，才能判断是否继续沟通。".repeat(7)+"影响终点"+i)
                .put("action","整理经过核对的资料，逐条标注来源，围绕顾客问题补充可公开的事实说明。".repeat(9)+"动作终点"+i)
                .put("deliverable","一份结构化资料页面，包含服务对象、使用场景、执行范围与来源索引。".repeat(4)+"交付终点"+i)
                .put("acceptance","由负责人核对原文事实，并用冻结问题与平台复测实际呈现。".repeat(7)+"验收终点"+i);
            recommendations.add(r);
        }
        var strategy=(com.fasterxml.jackson.databind.node.ObjectNode)source.path("strategy");
        var cognition=strategy.path("cognitionFindings").get(0).deepCopy();var cognitionRows=strategy.putArray("cognitionFindings");
        for(int i=0;i<5;i++){var c=(com.fasterxml.jackson.databind.node.ObjectNode)cognition.deepCopy();c.put("title","认知问题 "+i).put("observation","实际回答出现品牌相关信息，但这次回答没有完整展开服务边界。".repeat(5)+"观察终点"+i);cognitionRows.add(c);}
        var topic=source.path("contentTopics").get(0).deepCopy();var topicRows=source.putArray("contentTopics");
        for(int i=0;i<3;i++){var t=(com.fasterxml.jackson.databind.node.ObjectNode)topic.deepCopy();t.put("title","如何判断服务是否符合场景需求 "+i).put("angle","围绕事实、场景与选择标准组织内容，引用真实资料，不追加未经确认的能力。".repeat(8)+"角度终点"+i);topicRows.add(t);}
        var stage=strategy.path("roadmap").get(0).deepCopy();var stages=strategy.putArray("roadmap");
        for(int i=0;i<4;i++){var r=(com.fasterxml.jackson.databind.node.ObjectNode)stage.deepCopy();r.put("stage","建议阶段 "+(i+1));r.putArray("recommendationIndexes").add(i*2).add(i*2+1);stages.add(r);}
        var analysis=fixture.json.convertValue(source,GeoDiagnosticReportDtos.Analysis.class);
        byte[] pdf=GeoDiagnosticPdfRenderer.pdf(report(fixture,analysis,"这是用于版面验证的合成品牌资料，不是真实客户诊断。".repeat(15)),List.of(),List.of());
        Files.createDirectories(Path.of("target/diagnostic-preview/rich-v2"));Files.write(Path.of("target/diagnostic-preview/rich-v2/synthetic-layout-fixture.pdf"),pdf);
        try(var document=Loader.loadPDF(pdf)){
            String text=new PDFTextStripper().getText(document).replaceAll("\\s+","");
            assertThat(text).contains("P0/FOUNDATIONALACTIONS","P1/CONTENTSYSTEM","P2/REINFORCEMENT","延展解读");
            for(int i=0;i<priorities.length;i++)assertThat(text).contains("现状终点"+i,"影响终点"+i,"动作终点"+i,"交付终点"+i,"验收终点"+i);
            for(int i=0;i<5;i++)assertThat(text).contains("观察终点"+i);
            for(int i=0;i<3;i++)assertThat(text).contains("角度终点"+i);
            assertWithinPage(document);renderCore(document,"rich-v2");
        }
    }
    private void renderCore(org.apache.pdfbox.pdmodel.PDDocument document,String directory) throws Exception {
        var renderer=new PDFRenderer(document);
        for(int page=0;page<document.getNumberOfPages();page++){
            var stripper=new PDFTextStripper();stripper.setStartPage(page+1);stripper.setEndPage(page+1);
            if(document.getPage(page).getMediaBox().getWidth()==960 && !stripper.getText(document).contains("EXTENDED INTERPRETATION"))
                javax.imageio.ImageIO.write(renderer.renderImageWithDPI(page,96),"png",Path.of("target/diagnostic-preview/"+directory+"/core-"+(page+1)+".png").toFile());
        }
    }
    @Test void longestAllowedActionAndBrandFieldsFlowWithoutLosingTailOrOverlappingFooter() throws Exception {
        var fixture=new GeoDiagnosticProjectionTests();var source=fixture.consultingAnalysis();
        var rec=(com.fasterxml.jackson.databind.node.ObjectNode)source.path("recommendations").get(0);
        rec.put("currentState","可核对的实际观察。".repeat(100)+"现状尾标记");
        rec.put("businessImpact","业务影响说明。".repeat(100)+"影响尾标记");
        rec.put("acceptance","按相同问题观察变化。".repeat(50)+"验收尾标记");
        var analysis=fixture.json.convertValue(source,GeoDiagnosticReportDtos.Analysis.class);
        byte[] pdf=GeoDiagnosticPdfRenderer.pdf(report(fixture,analysis,"品牌确认信息。".repeat(850)+"资料末尾标记"),List.of(),List.of());
        try(var document=Loader.loadPDF(pdf)){
            String text=new PDFTextStripper().getText(document).replaceAll("\\s+","");
            assertThat(text).contains("资料末尾标记","现状尾标记","影响尾标记","验收尾标记");
            assertWithinPage(document);assertThat(document.getNumberOfPages()).isLessThan(80);
        }
    }
    private GeoDiagnosticReportDtos.Detail report(GeoDiagnosticProjectionTests fixture,GeoDiagnosticReportDtos.Analysis analysis,String intro){
        var e=fixture.goodEvidence();var p=fixture.json.convertValue(e.path("profile"),GeoDiagnosticDtos.Profile.class);
        List<GeoDiagnosticReportDtos.Sample> samples=List.of(
            sample("10","DOUBAO","SUCCEEDED",List.of("OWN")),sample("10","DEEPSEEK","SUCCEEDED",null),
            sample("11","DOUBAO","SUCCEEDED",List.of()),sample("11","DEEPSEEK","FAILED",null),
            sample("12","DOUBAO","MISSING",null));
        return new GeoDiagnosticReportDtos.Detail("preview",4,1,"2","1","PDF_RUNNING","PARTIAL",false,1,1,false,null,6,3,3,
            new GeoDiagnosticDtos.Profile(p.brandName(),p.website(),intro,p.brandTerms(),p.region()),List.of("DOUBAO","DEEPSEEK"),
            fixture.json.convertValue(e.path("metrics"),GeoDiagnosticReportDtos.Metrics.class),analysis,samples,
            List.of(new GeoDiagnosticDtos.Source("OBS:preview","解读引用索引","https://example.com/index","证据摘录"),
                new GeoDiagnosticDtos.Source("OBS:duplicate","重复引用","https://example.com/index","同一来源")),
            List.of("单次采样快照。"),OffsetDateTime.parse("2026-10-14T01:00:00Z"),null,List.of());
    }
    private GeoDiagnosticReportDtos.Sample sample(String q,String platform,String status,List<String> hits){
        return new GeoDiagnosticReportDtos.Sample(q+":"+platform+":1",q,"BRAND_DISCOVERY","广州哪些咖啡店适合上班族？",platform,status,"完整摘录",false,null,List.of(),hits);
    }
    private void assertWithinPage(org.apache.pdfbox.pdmodel.PDDocument document) throws Exception {
        for(int page=1;page<=document.getNumberOfPages();page++){
            var box=document.getPage(page-1).getMediaBox();if(box.getWidth()!=960)continue;
            final int number=page;
            var stripper=new PDFTextStripper(){
                @Override protected void processTextPosition(TextPosition text){
                    assertThat(text.getXDirAdj()).as("page %s x",number).isGreaterThanOrEqualTo(47);
                    assertThat(text.getXDirAdj()+text.getWidthDirAdj()).as("page %s right",number).isLessThanOrEqualTo(913);
                    float baseline=text.getYDirAdj();
                    assertThat(baseline).as("page %s baseline",number).isBetween(18f,522f);
                    // Footer baseline 522; no body content may enter the footer band.
                    if(baseline>100&&baseline<515)assertThat(baseline).as("page %s body bottom",number).isLessThanOrEqualTo(490);
                    super.processTextPosition(text);
                }
            };stripper.setStartPage(page);stripper.setEndPage(page);stripper.getText(document);
        }
    }
}
