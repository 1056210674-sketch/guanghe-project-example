package com.guanghe.backend.geo;

import java.awt.Color;
import java.io.IOException;
import java.util.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import static com.guanghe.backend.geo.GeoDiagnosticPdfRenderer.Layout.*;

/** Sixteen evidence-led consulting sections; overflow is preserved in complete extensions.
 * Top-based coordinates. Original full-answer appendices remain in the portrait renderer. */
final class GeoDiagnosticConsultingPdf implements AutoCloseable {
    private static final float X=48,W=864,BOTTOM=488;
    private static final Color NAVY=Color.decode("#16222d"),TEAL=Color.decode("#097a75"),
        CORAL=Color.decode("#ed6f50"),GRAY=Color.decode("#657582"),BG=Color.decode("#f7fafc"),
        PALE=Color.decode("#e8f3f1"),LINE=Color.decode("#d5e1e8");
    private final PDDocument document;private final PDType0Font font;
    private final GeoDiagnosticReportDtos.Detail report;private final GeoDiagnosticPdfRenderer.Layout typography;
    private final GeoDiagnosticReportDtos.Analysis a;private final GeoDiagnosticReportDtos.Strategy s;private final GeoDiagnosticReportDtos.Metrics m;
    private final Map<String,Integer> numbers=new LinkedHashMap<>();private final Map<String,String> extensions=new LinkedHashMap<>();
    private PDPageContentStream out;private String currentSection;private int corePages,currentPage;
    GeoDiagnosticConsultingPdf(PDDocument document,PDType0Font font,GeoDiagnosticReportDtos.Detail report,GeoDiagnosticPdfRenderer.Layout typography){
        this.document=document;this.font=font;this.report=report;this.typography=typography;a=report.analysis();s=a.strategy();m=report.metrics();
        for(var sample:report.samples())numbers.computeIfAbsent(sample.questionId(),ignored->numbers.size()+1);
    }
    void render() throws IOException {
        corePages=16-(int)List.of("P0","P1","P2").stream().filter(p->priority(p).isEmpty()).count()
            +Math.max(1,(report.samples().size()+5)/6)-1
            +Math.max(1,(m.discovery().size()+(int)report.samples().stream().filter(v->"BRAND_DISCOVERY".equals(v.category())).count()+5)/6)-1;
        preserveNarrative();executive();methodology();profile();judgment();questionEvidence();cognition();intent();competition();
        diagnosticMap();p0();p1();p2();baseline();topics();roadmap();delivery();extensions();
    }
    private void executive() throws IOException {
        page(1,"EXECUTIVE SUMMARY","GEO 现状诊断报告");
        text("品牌",report.profile().brandName(),X,100,W,46,27,NAVY,true);
        text("快照","数据快照 "+time(report.snapshotAt())+"（北京时间）  |  "+report.profile().region()+"  |  v"+report.reportVersion(),X,150,W,24,10,GRAY,false);
        panel(X,186,W,276,Color.WHITE);text("核心判断",s.coreJudgment().headline(),68,205,824,49,21,TEAL,true);
        text("核心判断说明",s.coreJudgment().description(),68,263,824,68,11,NAVY,false);
        List<String> values=List.of(ratio(ownHits(),discoveryValid()),ownHits()+" / "+discoveryValid(),m.validSamples()+" / "+m.expectedSamples(),String.valueOf(report.platformCodes().size()));
        List<String> labels=List.of("自然提及率","自然发现命中 / 有效样本","全组有效 / 计划样本","本批监测平台");
        for(int i=0;i<4;i++){float x=68+i*210;rect(x,342,194,76,i==0?PALE:BG);
            text("摘要指标",values.get(i),x+12,350,170,32,23,i==0?TEAL:NAVY,true);text("摘要指标说明",labels.get(i),x+12,389,170,19,9,GRAY,false);}
        text("统计摘要",a.summary(),68,430,824,24,9,GRAY,false);
    }
    private void methodology() throws IOException {
        page(2,"RESEARCH METHOD","用三种视角，区分品牌的真实处境");
        String[][] data={
            {"01","自然发现","模拟顾客不点名品牌时的真实需求，观察品牌是否自然出现在回答中。",discoveryValid()+" 个有效自然回答，"+ownHits()+" 次本品牌命中。","输出：逐问观察、平台差异、同批竞品对照。"},
            {"02","点名口碑","明确问及品牌，观察 AI 识别了哪些事实、如何描述适合性与顾虑。",reputationValid()+" 个有效口碑回答，"+recognized()+" 次再次点名。","输出：品牌画像、认知偏差与决策信息缺口。"},
            {"03","来源核查","交叉查看本批回答及实际取得的来源，保留来源编号和连续原文摘录。",sourceCount()+" 个去重报告信源，含品牌调研与回答引用。","输出：可回查依据、行动优先级和内容蓝图。"}};
        for(int i=0;i<3;i++){float x=X+i*294;panel(x,116,276,310,Color.WHITE);
            text("方法编号",data[i][0],x+18,133,240,40,29,TEAL,true);text("方法标题",data[i][1],x+18,188,240,33,18,NAVY,true);
            text("方法说明",data[i][2],x+18,235,240,78,11,NAVY,false);text("方法样本",data[i][3],x+18,325,240,48,10,TEAL,false);
            text("方法输出",data[i][4],x+18,382,240,32,9,GRAY,false);}
        band("口径","失败和缺失样本不记为零提及；点名口碑不计入自然可见度。当前结论仅对应本次冻结问题、平台与时间快照。",444,40);
    }
    private void profile() throws IOException {
        page(3,"BRAND PROFILE","先确认：我们诊断的是谁");panel(X,115,520,369,Color.WHITE);
        String[][] rows={{"品牌名称",report.profile().brandName()},{"对外名称 / 品牌词",String.join("、",report.profile().brandTerms())},
            {"官网",empty(report.profile().website(),"未提供")},{"目标地域",report.profile().region()},{"已确认的品牌介绍",report.profile().introduction()}};
        float y=129;for(int i=0;i<rows.length;i++){float h=i==4?151:47;
            text("品牌资料字段",rows[i][0],64,y,130,30,10,TEAL,true);text(rows[i][0],rows[i][1],206,y,344,h-7,10.5f,NAVY,false);
            if(i<4)rect(64,y+h-4,488,1,LINE);y+=h;}
        String[][] info={{"平台范围",String.join(" / ",report.platformCodes().stream().map(GeoDiagnosticPdfRenderer.Layout::platform).toList())},
            {"问题范围",numbers.size()+" 个独立问题；自然发现和点名口碑分组分析。"},
            {"有效覆盖",m.validSamples()+" 个有效，"+m.failedSamples()+" 个失败，"+m.missingSamples()+" 个缺失。"},
            {"同类品牌",brands().size()>1?String.join("、",brands().entrySet().stream().filter(e->!e.getKey().equals("OWN")).map(Map.Entry::getValue).toList()):"本次没有确认可靠竞品，不推演对照结论。"}};
        for(int i=0;i<4;i++){float top=115+i*95;panel(586,top,326,84,i==0?PALE:Color.WHITE);text("范围标题",info[i][0],602,top+10,294,18,11,TEAL,true);text(info[i][0],info[i][1],602,top+35,294,40,10,NAVY,false);}
    }
    private void judgment() throws IOException {
        page(4,"CORE DIAGNOSIS","核心判断：问题不止是有没有被提及");
        text("核心大判断",s.coreJudgment().headline(),X,111,W,62,24,CORAL,true);
        text("核心判断解释",s.coreJudgment().description(),X,187,W,58,11,NAVY,false);
        var findings=a.findings();for(int i=0;i<Math.min(3,findings.size());i++){var f=findings.get(i);float x=X+i*294;panel(x,261,276,173,Color.WHITE);
            text("关键观察标题",f.heading(),x+16,276,244,40,15,TEAL,true);text("关键观察",f.description(),x+16,323,244,62,10.5f,NAVY,false);
            text("关键观察证据",refs(f.evidence()),x+16,394,244,28,8.5f,GRAY,false);}
        if(findings.isEmpty())text("关键观察缺口","本批证据不足以补充独立观察，先保留已有核心判断与原始采样。",X,277,W,66,11,GRAY,false);
        band("核心依据",refs(s.coreJudgment().evidence()),450,34);
    }
    private void questionEvidence() throws IOException {
        var samples=report.samples();int chunks=Math.max(1,(samples.size()+5)/6);
        for(int chunk=0;chunk<chunks;chunk++){page(5,"QUESTION EVIDENCE","逐问观察：AI 实际说了什么"+(chunk==0?"":"（续）"));
            tableHeader(new String[]{"真实问题 / 平台","回答原文摘录","品牌提及 / 来源"},new float[]{250,402,212},112);
            for(int i=chunk*6;i<Math.min(samples.size(),chunk*6+6);i++){var sample=samples.get(i);float y=145+(i-chunk*6)*53;rect(X,y,W,53,i%2==0?Color.WHITE:PALE);
                text("问题 "+sample.unitKey(),q(sample.questionId())+" · "+platform(sample.platformCode())+" · "+("BRAND_DISCOVERY".equals(sample.category())?"自然":"口碑")+"\n"+sample.questionText(),X+10,y+5,230,43,9.5f,NAVY,false);
                text("回答 "+sample.unitKey(),empty(sample.answerExcerpt(),"没有可用回答；状态："+sample.status()),X+260,y+5,382,43,9.5f,NAVY,false);
                text("提及与来源 "+sample.unitKey(),hit(sample,"OWN")+"\n"+(sample.sourceIds().isEmpty()?"未记录引用":String.join(" / ",sample.sourceIds())),X+662,y+5,192,43,9,TEAL,false);}
            if(samples.isEmpty())text("采样缺口","本批没有可展示的采样单元。",X,170,W,40,12,GRAY,false);
            band("逐问口径","提及 = 冻结品牌词命中；未知 = 历史数据未保留命中明细；失败 / 缺失不是未提及。完整回答及可点击来源见原始附录。",467,21);}
    }
    private void cognition() throws IOException {
        page(6,"BRAND COGNITION","AI 对品牌的认知，在哪里失真或不完整");var values=s.cognitionFindings();
        int count=Math.min(5,values.size());float height=count==1?180:count==2?132:count==0?0:(320-12*(count-1))/count;
        for(int i=0;i<count;i++){var f=values.get(i);float y=113+i*(height+12);panel(X,y,W,height,Color.WHITE);rect(X,y,4,height,i==0?CORAL:TEAL);
            text("认知问题",f.title(),64,y+8,187,height-22,count<3?16:11,TEAL,true);
            text("认知观察",f.observation(),268,y+8,400,height-22,11,NAVY,false);text("认知影响",f.businessImpact(),684,y+8,212,height-22,10.5f,GRAY,false);
            text("认知依据",refs(f.evidence()),64,y+height-12,832,9,7.5f,GRAY,false);}
        if(values.isEmpty())text("认知缺口","本批证据不足以确认独立的认知偏差；不将缺少样本写成品牌的负面评价。",X,130,W,65,12,NAVY,false);
        if(count<3){float y=count==0?230:113+count*(height+12)+10;
            text("口碑覆盖标题","实际点名口碑覆盖",X,y,W,23,13,TEAL,true);y+=31;
            for(var p:m.reputation()){if(y+27>435)break;rect(X,y,W,26,Color.WHITE);
                text("口碑平台覆盖",platform(p.platformCode())+"  |  有效 "+p.validSamples()+" / 计划 "+p.expectedSamples()+"  |  回答再次点名 "+p.recognizedSamples()+"  |  失败 "+p.failedSamples()+" / 缺失 "+p.missingSamples(),X+12,y+6,W-24,18,10,NAVY,false);y+=29;}}
        band("口碑分组",reputationValid()+" 个有效点名口碑回答中，"+recognized()+" 个再次点名品牌。结合上方实际观察，核对顾客仍缺少哪些决策信息。",450,34);
    }
    private void intent() throws IOException {
        page(7,"INTENT COVERAGE","从品牌词，走向顾客真正会问的问题");String[] codes={"BRAND","CATEGORY","SCENARIO","QUESTION","DECISION"};
        for(int i=0;i<codes.length;i++){String code=codes[i];var layer=s.keywordLayers().stream().filter(l->l.layer().equals(code)).findFirst().orElse(null);float y=114+i*66;
            panel(X,y,595,57,Color.WHITE);text("意图层",layerName(code),62,y+8,104,30,11,TEAL,true);
            String category="BRAND".equals(code)?"BRAND_REPUTATION":"BRAND_DISCOVERY";
            var valid=layer==null?List.<GeoDiagnosticReportDtos.Sample>of():report.samples().stream().filter(v->layer.questionIds().contains(v.questionId())&&category.equals(v.category())&&"SUCCEEDED".equals(v.status())).toList();
            boolean known=!valid.isEmpty()&&valid.stream().allMatch(v->v.matchedBrandIds()!=null);long hits=valid.stream().filter(v->v.matchedBrandIds()!=null&&v.matchedBrandIds().contains("OWN")).count();
            if(known){rect(181,y+10,188,7,LINE);if(hits>0)rect(181,y+10,188f*hits/valid.size(),7,TEAL);}
            text("意图统计",layer==null?"未覆盖":valid.isEmpty()?"无有效样本":known?hits+" / "+valid.size()+("BRAND".equals(code)?" 点名再次提及":" 自然提及"):"命中明细未知",380,y+5,245,18,9,TEAL,false);
            text("意图覆盖",layer==null?"本批未覆盖，不推断表现。":String.join(" / ",layer.questionIds().stream().map(this::q).toList())+" · "+layer.assessment(),181,y+27,446,24,9.5f,NAVY,false);}
        panel(662,114,250,321,NAVY);text("意图结论标题","内容组织的下一步",680,133,214,49,19,Color.WHITE,true);
        String actions=String.join("\n\n",s.keywordLayers().stream().map(l->layerName(l.layer())+"："+l.nextAction()).toList());
        text("意图行动",empty(actions,"先补齐与业务有关的顾客问题，再以同口径采样确认缺口。"),680,205,214,207,11,Color.WHITE,false);
        band("意图下一步","本批解读覆盖 "+s.keywordLayers().size()+" 个意图层。"+(s.keywordLayers().isEmpty()?"下一步补充与业务有关的顾客问题。":"建议下一步："+s.keywordLayers().get(0).nextAction()),451,33);
    }
    private void competition() throws IOException {
        var brands=brands();var ids=new ArrayList<>(brands.keySet());float first=258,cw=(W-first)/ids.size();List<List<String>> rows=new ArrayList<>();
        for(var p:m.discovery()){List<String> row=new ArrayList<>(List.of(platform(p.platformCode())+" · 自然提及 / 有效"));
            for(String id:ids){var hit=p.mentions().stream().filter(v->v.brandId().equals(id)).findFirst().orElse(null);row.add(hit==null?"未知":hit.mentionedSamples()+" / "+p.validSamples()+"\n"+ratio(hit.mentionedSamples(),p.validSamples()));}rows.add(row);}
        for(var question:report.samples().stream().filter(v->"BRAND_DISCOVERY".equals(v.category())).toList()){List<String> row=new ArrayList<>(List.of(q(question.questionId())+" · "+platform(question.platformCode())+"\n"+question.questionText()));for(String id:ids)row.add(hit(question,id));rows.add(row);}
        int chunks=Math.max(1,(rows.size()+5)/6);
        for(int chunk=0;chunk<chunks;chunk++){page(8,"COMPETITIVE CONTEXT","同批竞品对照：谁出现在同一需求中"+(chunk==0?"":"（续）"));
            rect(X,112,first,40,NAVY);text("竞品行标题","平台 / 真实需求问题",X+10,124,first-20,20,10,Color.WHITE,true);
            for(int i=0;i<ids.size();i++){float x=X+first+i*cw;rect(x,112,cw,40,i==0?TEAL:NAVY);text("竞品名称",brands.get(ids.get(i))+(i==0?" · 本品牌":""),x+8,119,cw-16,28,9.5f,Color.WHITE,true);}
            for(int r=chunk*6;r<Math.min(rows.size(),chunk*6+6);r++){float y=152+(r-chunk*6)*50;rect(X,y,W,50,r%2==0?Color.WHITE:BG);rect(X+first,y,cw,50,PALE);
                text("竞品对照问题",rows.get(r).get(0),X+10,y+7,first-20,37,9.5f,NAVY,false);
                for(int c=0;c<ids.size();c++)text("竞品对照结果",rows.get(r).get(c+1),X+first+c*cw+8,y+9,cw-16,33,10,c==0?TEAL:NAVY,c==0);}
            band("对照摘要","本批自然有效回答 "+discoveryValid()+" 条，本品牌命中 "+ownHits()+" 条；纳入 "+(ids.size()-1)+" 个同类品牌。结合逐问差异，确定要补充的资料与内容。",463,25);}
    }
    private void diagnosticMap() throws IOException {
        page(9,"DIAGNOSTIC MAP","把观察归类，找出最需要补齐的环节");String[] categories={"DISCOVERY","REPUTATION","COMPETITION","PUBLIC_RESEARCH"};
        for(int i=0;i<4;i++){String category=categories[i];float x=X+(i%2)*306,y=114+(i/2)*162;panel(x,y,289,145,i==0?PALE:Color.WHITE);
            text("诊断类别",section(category),x+15,y+13,259,28,15,TEAL,true);
            String findings=String.join("\n",a.findings().stream().filter(f->f.section().equals(category)).map(f->f.heading()+"："+f.description()).toList());
            String fallback=i==3?"本批未单列公开调研解读。报告记录 "+sourceCount()+" 个去重信源，实际来源见附录。":"本批未形成这一类独立结论。";
            text("分类观察",empty(findings,fallback),x+15,y+53,259,80,10.5f,NAVY,false);}
        panel(676,114,236,307,NAVY);text("诊断结论标题","从观察到优先级",694,133,200,50,19,Color.WHITE,true);
        text("诊断结论",s.coreJudgment().description(),694,200,200,124,11,Color.WHITE,false);
        text("分类方法","把自然发现、品牌口碑、竞品与信源中的实际差异，转成可核对的优先行动。",694,353,200,52,9,Color.WHITE,false);
        band("优先级","先修复有证据支持、影响顾客判断的事实缺口，再组织场景内容，最后以同口径问题复测。",449,35);
    }
    private void p0() throws IOException {
        var values=priority("P0");if(values.isEmpty())return;page(10,"P0 / FOUNDATIONAL ACTIONS","P0：先修复影响判断的基础信息");
        int count=Math.min(2,values.size());float height=(320-12*(count-1))/count;
        for(int i=0;i<count;i++){var r=values.get(i);float y=114+i*(height+12);panel(X,y,W,height,Color.WHITE);rect(X,y,63,height,CORAL);
            text("P0 编号","P0-"+(i+1),X+10,y+14,43,24,13,Color.WHITE,true);
            text("P0 阶段",value(r.timeframe()),X+10,y+57,43,height-72,9,Color.WHITE,false);
            text("P0 标题",r.title(),126,y+11,489,34,16,NAVY,true);
            float field=(height-63)/3; text("P0 现状","现状："+value(r.currentState()),126,y+53,490,field-1,10,NAVY,false);
            text("P0 影响","影响："+value(r.businessImpact()),126,y+53+field,490,field-1,10,NAVY,false);
            text("P0 动作","动作："+r.action(),126,y+53+2*field,490,field-1,10,NAVY,false);
            rect(635,y+10,261,height-20,PALE);text("P0 验收标题","验收口径",650,y+20,231,20,11,TEAL,true);
            text("P0 验收",value(r.acceptance()),650,y+49,231,height-85,10,NAVY,false);
            text("P0 负责人","负责人："+value(r.ownerRole()),650,y+height-32,231,23,9.5f,TEAL,false);}
        band("行动排序",prioritySummary("P0"),454,30);
    }
    private void p1() throws IOException {
        var values=priority("P1");if(values.isEmpty())return;page(11,"P1 / CONTENT SYSTEM","P1：把零散信息变成可引用的内容");
        int count=Math.min(3,values.size());float width=(W-18*(count-1))/count;
        for(int i=0;i<count;i++){var r=values.get(i);float x=X+i*(width+18);panel(x,114,width,324,Color.WHITE);rect(x,114,width,3,CORAL);
            text("P1 标记","P1 / "+(i+1),x+16,130,width-32,20,10,CORAL,true);text("P1 标题",r.title(),x+16,164,width-32,52,17,NAVY,true);
            text("P1 现状","现状："+value(r.currentState()),x+16,225,width-32,37,10,NAVY,false);
            text("P1 影响","影响："+value(r.businessImpact()),x+16,270,width-32,37,10,NAVY,false);
            text("P1 动作",r.action(),x+16,316,width-32,62,10.5f,NAVY,false);rect(x+16,389,width-32,1,LINE);
            text("P1 交付","交付："+value(r.deliverable()),x+16,400,width-32,29,9.5f,TEAL,false);}
        band("实施条件",prioritySummary("P1"),454,30);
    }
    private void p2() throws IOException {
        var values=priority("P2");if(values.isEmpty())return;page(12,"P2 / REINFORCEMENT","P2：补齐长期可复用的内容与验证能力");
        int count=Math.min(3,values.size());float height=(325-12*(count-1))/count;
        for(int i=0;i<count;i++){var r=values.get(i);float y=114+i*(height+12);panel(X,y,561,height,Color.WHITE);
            text("P2 标题",r.title(),65,y+11,527,29,15,TEAL,true);text("P2 动作",r.action()+"\n交付："+value(r.deliverable())+"\n验收："+value(r.acceptance()),65,y+48,527,height-58,10.5f,NAVY,false);}
        panel(628,114,284,325,NAVY);text("持续行动标题","持续推进顺序",646,132,248,36,19,Color.WHITE,true);
        for(int i=0;i<Math.min(3,s.roadmap().size());i++){var r=s.roadmap().get(i);float y=185+i*78;rect(646,y,248,66,i==0?TEAL:new Color(43,61,72));text("P2 阶段",r.stage()+"\n"+r.objective(),658,y+10,224,47,10.5f,Color.WHITE,false);}
        band("持续验证",prioritySummary("P2"),455,29);
    }
    private void baseline() throws IOException {
        page(13,"MEASUREMENT BASELINE","用六项真实基线，定义后续如何验收");
        tableHeader(new String[]{"基线指标","本批实际值","检查标准 / 下一次怎么比","计算口径"},new float[]{185,138,312,229},113);
        String[][] rows={{"自然提及率",ratio(ownHits(),discoveryValid()),"固定自然问题和平台，逐期记录变化，不预设增长。","本品牌命中 / 自然有效回答"},
            {"自然发现命中 / 有效样本",ownHits()+" / "+discoveryValid(),"核对每一道问题是否出现品牌、表述是否准确。","自然组有效回答单位"},
            {"点名再次提及",recognized()+" / "+reputationValid()+"\n"+ratio(recognized(),reputationValid()),"核对事实、适合性和顾虑，不能当正面率。","再次点名 / 口碑有效回答"},
            {"全组有效覆盖",m.validSamples()+" / "+m.expectedSamples()+"\n"+ratio(m.validSamples(),m.expectedSamples()),"同口径复测时保持并披露覆盖范围。","有效回答 / 计划样本"},
            {"同类品牌命中合计",String.valueOf(otherHits()),"在同题同平台比较品牌出现情况，不合成排名。","非 OWN 命中；品牌 × 回答单位"},
            {"去重报告信源",String.valueOf(sourceCount()),"核查引用是否可访问、事实能否对应原始页面。","report.sources URL 去重；含准备资料"}};
        float[] widths={185,138,312,229};for(int r=0;r<rows.length;r++){float y=146+r*50;rect(X,y,W,50,r%2==0?Color.WHITE:PALE);float x=X;
            for(int c=0;c<4;c++){text("基线 "+rows[r][0],rows[r][c],x+10,y+8,widths[c]-20,36,c==1?13:9.5f,c==1?TEAL:NAVY,c==1);x+=widths[c];}}
        band("复测方式","沿用同一品牌词、问题与平台；先核对覆盖和事实准确性，再逐期记录提及与来源变化。",461,27);
    }
    private void topics() throws IOException {
        page(14,"CONTENT BLUEPRINT","把顾客问题，转成有证据支持的内容选题");var values=a.contentTopics();
        int count=Math.min(3,values.size());float width=count==0?W:(W-18*(count-1))/count;
        for(int i=0;i<count;i++){var t=values.get(i);float x=X+i*(width+18);panel(x,114,width,319,Color.WHITE);
            text("选题编号",String.format(Locale.ROOT,"%02d",i+1),x+16,126,width-32,38,27,TEAL,true);text("选题标题",t.title(),x+16,174,width-32,52,16,NAVY,true);
            text("顾客问题","顾客问题："+t.audienceQuestion(),x+16,236,width-32,59,10.5f,NAVY,false);text("选题角度","内容角度："+t.angle(),x+16,303,width-32,68,10,NAVY,false);
            text("选题依据","依据："+refs(t.basisEvidence()),x+16,382,width-32,40,9,GRAY,false);}
        if(values.isEmpty())text("内容蓝图缺口","当前证据尚不足以提出独立选题。先补齐诊断资料与顾客真实问题。",X,133,W,65,12,NAVY,false);
        rect(X,451,W,33,NAVY);text("选题下一步",values.isEmpty()?"先补齐顾客问题与证据，再确定内容选题。":"建议先完成："+values.get(0).title()+"；回应顾客问题："+values.get(0).audienceQuestion(),X+14,458,W-28,21,9.5f,Color.WHITE,false);
    }
    private void roadmap() throws IOException {
        page(15,"EXECUTION ROADMAP","从诊断到交付，把动作连成一条路线");var stages=s.roadmap();int count=Math.min(4,stages.size());float width=count==0?W:(W-18*(count-1))/count;
        if(count==0)text("路线图缺口","本次未形成可确认的阶段安排。",X,132,W,50,12,GRAY,false);
        for(int i=0;i<count;i++){var stage=stages.get(i);float x=X+i*(width+18);rect(x,128,width,4,i==0?CORAL:TEAL);
            text("阶段编号",String.format(Locale.ROOT,"%02d",i+1),x,145,width,35,25,TEAL,true);text("阶段标题",stage.stage(),x,191,width,43,15,NAVY,true);
            text("阶段目标",stage.objective(),x,242,width,71,10.5f,NAVY,false);panel(x,326,width,109,Color.WHITE);text("阶段交付",stageDeliverables(stage),x+12,339,width-24,83,9.5f,NAVY,false);}
        rect(X,452,W,32,NAVY);text("路线图摘要","推进顺序："+String.join(" → ",stages.stream().map(GeoDiagnosticReportDtos.RoadmapStage::stage).toList())+"。交付后沿用六项基线的冻结口径复测。",X+14,459,W-28,20,10,Color.WHITE,false);
    }
    private void delivery() throws IOException {
        page(16,"DELIVERY & NEXT STEP","这份报告如何转化成下一步工作");panel(X,114,535,273,Color.WHITE);text("交付清单标题","本次交付清单",65,132,501,30,17,TEAL,true);
        text("交付清单","01  品牌画像与冻结调研范围\n02  自然发现、口碑与逐问证据\n03  同批竞品对照及认知问题\n04  分级行动、内容蓝图与验收基线\n05  完整解读、原始回答和来源附录",65,183,501,144,12,NAVY,false);
        text("交付信息","报告版本 "+report.reportVersion()+" · "+time(report.snapshotAt())+"（北京时间）",65,348,501,22,10,GRAY,false);
        panel(602,114,310,273,NAVY);text("范围标题","范围与后续使用",620,132,274,32,18,Color.WHITE,true);
        text("范围说明","本批覆盖 "+numbers.size()+" 个问题、"+report.platformCodes().size()+" 个平台。\n\n自然发现与点名口碑单独解释；未取得的事实不补造。\n\n完整解读可在后续延展页核对；全部原始回答、引用链接保留在纵向附录。",620,185,274,174,11,Color.WHITE,false);
        panel(X,407,W,77,PALE);text("交付结论",s.coreJudgment().headline(),65,418,830,26,16,TEAL,true);text("交付下一步",firstAction(),65,453,830,22,10.5f,NAVY,false);
    }
    /** Complete fields are retained irrespective of the concise main-page display. */
    private void preserveNarrative(){
        archive("品牌画像 / 已确认介绍",report.profile().introduction());
        archive("核心判断",s.coreJudgment().headline()+"\n"+s.coreJudgment().description()+"\n"+refs(s.coreJudgment().evidence()));
        for(var f:a.findings())archive("本批观察 / "+section(f.section())+" / "+f.heading(),f.description()+"\n"+refs(f.evidence()));
        for(var f:s.cognitionFindings())archive("认知诊断 / "+f.title(),"本批观察："+f.observation()+"\n业务影响："+f.businessImpact()+"\n"+refs(f.evidence()));
        for(var l:s.keywordLayers())archive("意图层 / "+layerName(l.layer()),"问题："+String.join(" / ",l.questionIds().stream().map(this::q).toList())+"\n判断："+l.assessment()+"\n下一步："+l.nextAction()+"\n"+refs(l.evidence()));
        int i=0;for(var r:a.recommendations())archive("行动 "+(++i)+" / "+r.priority()+" / "+r.title(),"现状："+value(r.currentState())+"\n业务影响："+value(r.businessImpact())+"\n动作："+r.action()+"\n交付物："+value(r.deliverable())+"\n验收口径："+value(r.acceptance())+"\n建议分工："+value(r.ownerRole())+"\n建议阶段："+value(r.timeframe())+"\n"+refs(r.basisEvidence()));
        for(var t:a.contentTopics())archive("内容蓝图 / "+t.title(),"顾客问题："+t.audienceQuestion()+"\n内容角度："+t.angle()+"\n覆盖品牌词："+String.join("、",t.coveredBrandTerms())+"\n意图层："+empty(t.intentLayer(),"未单列")+"\n"+refs(t.basisEvidence()));
        for(var stage:s.roadmap())archive("路线图 / "+stage.stage(),stage.objective()+"\n"+stageDeliverables(stage));
        if(report.competitors()!=null)for(var c:report.competitors())archive("竞品选择 / "+c.name(),"品牌词："+String.join("、",c.brandTerms())+"\n同类理由："+c.reason()+"\n来源："+String.join(" / ",c.sourceIds()));
        var limits=new LinkedHashSet<>(report.limitations());limits.addAll(a.limitations());archive("调研范围与信息缺口",String.join("\n",limits));
        for(var source:report.sources())archive("报告信源 / "+source.id(),source.title()+"\n"+source.url()+"\n"+value(source.snippet()));
    }
    private void extensions() throws IOException {
        var entries=new ArrayList<>(extensions.entrySet());float y=115;extensionPage();
        for(var entry:entries){var heading=wrap(entry.getKey(),13,W);var lines=wrap(entry.getValue(),10.5f,W);
            if(y+heading.size()*19+35>BOTTOM){extensionPage();y=115;}
            for(String line:heading){write(line,X,y+13,13,TEAL,true);y+=19;}y+=6;
            for(String line:lines){if(y+16>BOTTOM){extensionPage();y=115;}write(line,X,y+10.5f,10.5f,NAVY,false);y+=16;}y+=17;}
    }
    private void extensionPage() throws IOException {page(0,"EXTENDED INTERPRETATION","延展解读：完整结论、行动与证据");}
    private void archive(String label,String content){if(content==null||content.isBlank()||extensions.containsValue(content))return;String key=label;int n=2;while(extensions.containsKey(key))key=label+" / "+n++;extensions.put(key,content);}
    private String stageDeliverables(GeoDiagnosticReportDtos.RoadmapStage stage){List<String> lines=new ArrayList<>();for(int index:stage.recommendationIndexes())if(index>=0&&index<a.recommendations().size()){
        var r=a.recommendations().get(index);lines.add("行动 "+(index+1)+" · "+r.title()+"\n交付："+value(r.deliverable())+"\n验收："+value(r.acceptance()));}return String.join("\n",lines);}
    private String prioritySummary(String p){var values=priority(p);return values.isEmpty()?"按已确认的其他优先级行动推进，后续依据实际复测补充。":String.join("；",values.stream().map(r->value(r.ownerRole())+" / "+value(r.timeframe())).distinct().toList())+"。完整现状、影响、动作、交付物、验收和引证见延展解读。";}
    private List<GeoDiagnosticReportDtos.Recommendation> priority(String p){return a.recommendations().stream().filter(r->r.priority().equals(p)).toList();}
    private String firstAction(){return a.recommendations().isEmpty()?"先保留当前基线与信息缺口，再确认下一步调研范围。":"建议先推进："+a.recommendations().get(0).title()+"；交付："+value(a.recommendations().get(0).deliverable());}
    private int discoveryValid(){return m.discovery().stream().mapToInt(GeoDiagnosticReportDtos.Discovery::validSamples).sum();}
    private int ownHits(){return m.discovery().stream().flatMap(v->v.mentions().stream()).filter(v->v.brandId().equals("OWN")).mapToInt(GeoDiagnosticReportDtos.Mention::mentionedSamples).sum();}
    private int otherHits(){return m.discovery().stream().flatMap(v->v.mentions().stream()).filter(v->!v.brandId().equals("OWN")).mapToInt(GeoDiagnosticReportDtos.Mention::mentionedSamples).sum();}
    private int reputationValid(){return m.reputation().stream().mapToInt(GeoDiagnosticReportDtos.Reputation::validSamples).sum();}
    private int recognized(){return m.reputation().stream().mapToInt(GeoDiagnosticReportDtos.Reputation::recognizedSamples).sum();}
    private long sourceCount(){return report.sources().stream().map(GeoDiagnosticDtos.Source::url).filter(Objects::nonNull).map(String::trim).filter(v->!v.isBlank()).distinct().count();}
    private Map<String,String> brands(){var result=new LinkedHashMap<String,String>();result.put("OWN",report.profile().brandName());if(report.competitors()!=null)report.competitors().forEach(c->result.put(c.id(),c.name()));m.discovery().forEach(v->v.mentions().forEach(b->result.putIfAbsent(b.brandId(),b.name())));return result;}
    private String q(String id){return "Q"+numbers.getOrDefault(id,0);}
    private static String empty(String v,String fallback){return v==null||v.isBlank()?fallback:v;}
    private static String value(String v){return empty(v,"未单列");}
    private static String ratio(int n,int d){return d==0?"不可计算":String.format(Locale.ROOT,"%.1f%%",100d*n/d);}
    private static String hit(GeoDiagnosticReportDtos.Sample s,String brand){if(s==null||"MISSING".equals(s.status()))return "缺失";if(!"SUCCEEDED".equals(s.status()))return "失败";if(s.matchedBrandIds()==null)return "未知";return s.matchedBrandIds().contains(brand)?"提及":"未提及";}
    private static String layerName(String layer){return switch(layer){case "BRAND"->"品牌认知";case "CATEGORY"->"品类需求";case "SCENARIO"->"场景需求";case "QUESTION"->"问题解决";case "DECISION"->"决策比较";default->layer;};}
    private static String refs(List<GeoDiagnosticReportDtos.Evidence> refs){return String.join("\n",refs.stream().map(r->(r.unitKey()!=null?"样本 "+r.unitKey():"来源 "+r.sourceId())+"：“"+r.quote()+"”").toList());}
    private void band(String label,String content,float y,float h) throws IOException {rect(X,y,W,h,PALE);text(label,content,X+12,y+6,W-24,h-10,h<28?8.5f:9.5f,TEAL,false);}
    private void tableHeader(String[] titles,float[] widths,float y) throws IOException {rect(X,y,W,33,NAVY);float x=X;for(int i=0;i<titles.length;i++){text("表头",titles[i],x+10,y+9,widths[i]-20,20,10,Color.WHITE,true);x+=widths[i];}}
    /** Fixed cards only abbreviate display; visible marker links conceptually to complete extensions. */
    private void text(String label,String content,float x,float top,float width,float height,float size,Color color,boolean bold) throws IOException {
        if(content==null||content.isBlank())return;var lines=wrap(content,size,width);float lead=size*1.45f;int capacity=Math.max(1,(int)((height-size)/lead)+1);
        if(lines.size()>capacity){archive(currentSection+" / "+label,content);int shown=Math.max(0,capacity-1);for(int i=0;i<shown;i++)write(lines.get(i),x,top+size+i*lead,size,color,bold);
            String marker=" · 延展解读";if(font.getStringWidth(marker)*size/1000>width)marker=" ↗";
            float available=width-font.getStringWidth(marker)*size/1000;String prefix=available>size?wrap(lines.get(shown),size,available).get(0):"";
            write(prefix+marker,x,top+size+shown*lead,size,color,false);}
        else for(int i=0;i<lines.size();i++)write(lines.get(i),x,top+size+i*lead,size,color,bold);
    }
    private List<String> wrap(String value,float size,float width) throws IOException {return typography.wrap(typography.printable(value),size,width);}
    private void page(int number,String eyebrow,String title) throws IOException {
        if(out!=null)out.close();if(document.getNumberOfPages()>=240)throw new GeoDiagnosticPdfRenderer.TooLarge();currentSection=number==0?"延展解读":title;
        var page=new PDPage(new PDRectangle(960,540));document.addPage(page);out=new PDPageContentStream(document,page);rect(0,0,960,540,BG);
        if(number>0)currentPage++;write((number>0?String.format(Locale.ROOT,"%02d",currentPage):currentSection)+" / "+eyebrow,X,36,9,TEAL,true);write(title,X,77,26,NAVY,true);rect(X,505,W,1,LINE);
        write("光禾AI智能体框架 · GEO 现状诊断",X,522,8,GRAY,false);write("基于本批真实采样与冻结资料",401,522,8,GRAY,false);
        write(number==0?"延展 · "+document.getNumberOfPages():String.format(Locale.ROOT,"%02d / %02d",currentPage,corePages),858,522,8,TEAL,false);
    }
    private void panel(float x,float y,float w,float h,Color fill) throws IOException {rect(x,y,w,h,fill);out.setStrokingColor(LINE);out.setLineWidth(.65f);out.addRect(x,540-y-h,w,h);out.stroke();}
    private void rect(float x,float y,float w,float h,Color fill) throws IOException {out.setNonStrokingColor(fill);out.addRect(x,540-y-h,w,h);out.fill();}
    private void write(String value,float x,float baseline,float size,Color color,boolean bold) throws IOException {
        if(value==null||value.isEmpty())return;out.beginText();out.setFont(font,size);out.setNonStrokingColor(color);out.setStrokingColor(color);out.setLineWidth(size>=15?.4f:.22f);
        out.setRenderingMode(bold?RenderingMode.FILL_STROKE:RenderingMode.FILL);out.newLineAtOffset(x,540-baseline);out.showText(typography.printable(value));out.endText();out.setRenderingMode(RenderingMode.FILL);
    }
    @Override public void close() throws IOException {if(out!=null){out.close();out=null;}}
}
