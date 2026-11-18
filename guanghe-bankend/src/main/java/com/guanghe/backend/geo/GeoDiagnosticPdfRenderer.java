package com.guanghe.backend.geo;

import java.awt.Color;
import java.io.*;
import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;
import org.jsoup.Jsoup;

/** Offline Chinese/vector PDF, with full immutable answers and citations, not the model excerpts. */
final class GeoDiagnosticPdfRenderer {
    private GeoDiagnosticPdfRenderer() { }
    static final class TooLarge extends RuntimeException { }
    static byte[] pdf(GeoDiagnosticReportDtos.Detail report,List<GeoTaskDtos.ObservationResponse> observations,List<GeoDiagnosticDtos.Source> sources) throws IOException {
        if(report.analysis()==null)throw new IllegalArgumentException("Diagnostic analysis is not ready");
        try(PDDocument document=new PDDocument();InputStream font=GeoDiagnosticPdfRenderer.class.getResourceAsStream("/fonts/NotoSansSC-Regular.ttf")) {
            if(font==null)throw new IOException("Bundled diagnostic font unavailable");
            document.getDocumentInformation().setTitle(report.analysis().title());document.getDocumentInformation().setAuthor("光禾AI智能体框架");
            document.getDocumentInformation().setSubject("GEO 自然发现、点名口碑与完整采样证据诊断");
            var chineseFont=PDType0Font.load(document,font);
            try(Layout layout=new Layout(document,chineseFont,report,observations,sources)){
                if(report.analysis().strategy()==null)layout.render();
                else {
                    // Consulting pages are landscape; full-answer appendices retain the proven
                    // portrait flow so long answers, links and unsupported glyphs remain complete.
                    try(var consulting=new GeoDiagnosticConsultingPdf(document,chineseFont,report,layout)){consulting.render();}
                    layout.nextPage();layout.appendices();
                }
            }
            ByteArrayOutputStream output=new ByteArrayOutputStream();document.save(output);if(output.size()>10*1024*1024)throw new TooLarge();return output.toByteArray();
        }
    }
    static final class Layout implements AutoCloseable {
        private static final Parser MARKDOWN=Parser.builder().extensions(List.of(TablesExtension.create())).build();
        private static final String NO_LINE_START="，。、；：！？）》】〕〉」』”’…,.!?;:%)]}";
        private static final String NO_LINE_END="（《【〔〈「『“‘([{“";
        static final float LEFT=44,WIDTH=507,BOTTOM=51;
        static final Color INK=new Color(27,42,55),MUTED=new Color(103,119,132),TEAL=new Color(26,117,119),PALE=new Color(238,245,245);
        static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm").withZone(ZoneId.of("Asia/Shanghai"));
        private final PDDocument document;private final PDType0Font font;private final GeoDiagnosticReportDtos.Detail report;
        private final Map<String,GeoTaskDtos.ObservationResponse> observations=new LinkedHashMap<>();
        private final List<GeoDiagnosticDtos.Source> preparationSources;
        private final Map<Integer,String> symbolNotes=new LinkedHashMap<>();
        private PDPage page;private PDPageContentStream stream;private float y;private long glyphs;
        Layout(PDDocument document,PDType0Font font,GeoDiagnosticReportDtos.Detail report,List<GeoTaskDtos.ObservationResponse> observations,List<GeoDiagnosticDtos.Source> sources) throws IOException {
            this.document=document;this.font=font;this.report=report;this.preparationSources=sources;observations.forEach(o->this.observations.put(o.unitKey(),o));
        }
        void render() throws IOException {
            nextPage();
            text("客户交付 / GEO DIAGNOSTIC",10,TEAL,10);text(report.analysis().title(),25,INK,12);
            text("诊断 "+report.id()+" · 报告版本 "+report.reportVersion()+" · 数据快照 "+time(report.snapshotAt())+"（北京时间）",9,MUTED,13);
            text("地域："+report.profile().region()+"   采集结果："+status(report.samplingStatus()),11,TEAL,12);
            if(report.hasNoData())text("未获得有效采样结果。本 PDF 为失败说明和实际记录，不是优化成果或口碑评价。",12,new Color(173,77,49),12);
            text(report.analysis().summary(),11,INK,12);
            heading("01  品牌与诊断范围");text(report.profile().brandName(),15,INK,7);text(report.profile().introduction(),10.5f,INK,10);
            text("品牌词："+String.join("、",report.profile().brandTerms()),9,MUTED,7);if(report.profile().website()!=null)link("官网（用户确认资料）",report.profile().website());
            text("平台："+String.join(" / ",report.platformCodes().stream().map(Layout::platform).toList()),10,TEAL,12);
            var m=report.metrics();coverage("完整样本覆盖",List.of("有效 "+m.validSamples(),"失败 "+m.failedSamples(),"缺失 "+m.missingSamples()),List.of(m.validSamples(),m.failedSamples(),m.missingSamples()));
            text("预计 "+m.expectedSamples()+" 个样本；已结算 "+report.settledPoints()+" 积分，已释放 "+report.releasedPoints()+" 积分。报告恢复不再次采样或收费。",9,MUTED,10);
            heading("02  自然发现与同批竞品对照");
            text("以下提及率只使用不点名品牌的自然发现问题，有效回答为分母；不是排名，也不与口碑问题合并。",9,MUTED,10);
            for(var platform:m.discovery()){
                float block=55;for(var mention:platform.mentions())block+=height(mention.name()+"（"+mention.mentionedSamples()+" / "+platform.validSamples()+"）",9,3)+24;
                ensureBlock(block,85);text(platform(platform.platformCode())+" · 有效 "+platform.validSamples()+" / 预计 "+platform.expectedSamples(),12,TEAL,8);
                for(var mention:platform.mentions()){
                    text(mention.name()+"（"+mention.mentionedSamples()+" / "+platform.validSamples()+"）",9,INK,3);
                    ratio(mention.mentionRate());
                }
                text("失败 "+platform.failedSamples()+" / 缺失 "+platform.missingSamples(),8.5f,MUTED,12);
            }
            heading("03  点名品牌口碑");
            text("口碑问题显式提及客户品牌，观察平台怎样描述品牌；回答里再次写出品牌名不代表自然发现或真实满意度。",9,MUTED,10);
            for(var platform:m.reputation())text(platform(platform.platformCode())+" · 有效回答 "+platform.validSamples()+" / 预计 "+platform.expectedSamples()+"，回答点名品牌 "+platform.recognizedSamples()+
                "，失败 "+platform.failedSamples()+" / 缺失 "+platform.missingSamples(),10,INK,8);
            heading("04  证据驱动的诊断解读");
            for(var finding:report.analysis().findings()){
                keepWithFollowing(finding.heading()+" / "+section(finding.section()),13,7,10.5f);text(finding.heading()+" / "+section(finding.section()),13,TEAL,7);text(finding.description(),10.5f,INK,8);refs(finding.evidence());
            }
            heading("05  优化建议与内容选题蓝图");
            for(var suggestion:report.analysis().recommendations()){keepWithFollowing(suggestion.priority()+" · "+suggestion.title(),12,6,10.5f);text(suggestion.priority()+" · "+suggestion.title(),12,TEAL,6);text(suggestion.action(),10.5f,INK,7);refs(suggestion.basisEvidence());}
            for(var topic:report.analysis().contentTopics()){
                ensureBlock(height("选题："+topic.title(),12,6)+height("顾客会问："+topic.audienceQuestion(),10,5)+height("写作角度："+topic.angle(),10.5f,7)
                    +height("覆盖品牌词："+String.join("、",topic.coveredBrandTerms()),9,6)+refsHeight(topic.basisEvidence()),100);
                text("选题："+topic.title(),12,TEAL,6);text("顾客会问："+topic.audienceQuestion(),10,INK,5);
                text("写作角度："+topic.angle(),10.5f,INK,7);text("覆盖品牌词："+String.join("、",topic.coveredBrandTerms()),9,MUTED,6);refs(topic.basisEvidence());}
            heading("06  方法与局限");
            Set<String> displayedLimitations=new LinkedHashSet<>(report.limitations());
            displayedLimitations.addAll(report.analysis().limitations());
            for(String limitation:displayedLimitations)text("- "+limitation,9.5f,MUTED,7);
            text("- 附录按原回答排版标题、列表和表格；不支持的特殊符号用文字说明，码点见文末对照。在线原始记录保持不变。",9.5f,MUTED,7);
            appendices();
        }
        void appendices() throws IOException {
            heading("附录 A  全部问题、完整回答与完整引用");
            text("下方按问题和平台列出全部采样单元；成功回答与引用使用保存原文，不以分析摘录代替。缺失单元不补回答。",9,MUTED,10);
            Map<String,Integer> questionNumbers=new LinkedHashMap<>();
            for(var sample:report.samples())questionNumbers.computeIfAbsent(sample.questionId(),ignored->questionNumbers.size()+1);
            int index=0;for(var sample:report.samples()){
                ensureBlock(height((index+1)+". "+sample.questionText(),11.5f,7)+65,100);text(++index+". "+sample.questionText(),11.5f,INK,7);
                if(report.analysis().strategy()!=null)text("Q"+questionNumbers.get(sample.questionId())+" · 问题原始编号 "+sample.questionId(),8.5f,TEAL,5);
                text(platform(sample.platformCode())+"（"+sample.platformCode()+"） / "+section(sample.category().equals("BRAND_DISCOVERY")?"DISCOVERY":"REPUTATION")+
                    " / "+status(sample.status())+"（"+sample.status()+"） / "+sample.unitKey(),8.5f,MUTED,6);
                var observation=observations.get(sample.unitKey());
                if(observation==null){text("未收到采样回执；不代表该平台未提及品牌。",10,MUTED,12);continue;}
                text("采集平台 "+observation.provider()+" · 模型 "+observation.model()+" · 采集方式 "+safe(observation.collectionMethod())+" · "+time(observation.finishedAt()),8,MUTED,5);
                if("SUCCEEDED".equals(observation.status())){
                    markdown(observation.answerText(),9.5f);
                    if(observation.citations().isEmpty())text("本单元回执未提供引用链接；不能据此判断平台没有参考公开资料。",9,MUTED,7);
                    for(var citation:observation.citations()){
                        source("引用 "+citation.sequence()+" · "+safe(citation.title()),citation.url(),citation.snippet(),9);
                        text("来源采集时间 "+time(citation.observedAt())+"（北京时间）",8,MUTED,9);}
                }else text("失败代码："+safe(observation.stableErrorCode()),10,new Color(159,79,56),10);
            }
            heading("附录 B  公开资料调研来源");
            // Preparation sources are immutable; sampling citations are complete in Appendix A, not the bounded analysis source list.
            Map<String,GeoDiagnosticDtos.Source> sourceIndex=new LinkedHashMap<>();
            if(report.analysis().strategy()!=null)report.sources().forEach(s->sourceIndex.put(s.id(),s));
            preparationSources.forEach(s->sourceIndex.put(s.id(),s));
            for(var source:sourceIndex.values())source(source.id()+" · "+source.title(),source.url(),source.snippet(),10);
            text("附录 B 保留提交前确认的全部公开调研来源及完整摘录；全部采样引用（含未进入分析预算的来源）以附录 A 完整回执为准。",9,MUTED,8);
            if(!symbolNotes.isEmpty()){
                heading("附录 C  特殊符号对照");
                text("以下说明仅解决字体显示限制，可按码点核对在线原文；不影响报告统计或已保存证据。",9,MUTED,8);
                List<String> notes=new ArrayList<>();
                for(var entry:new ArrayList<>(symbolNotes.entrySet()))notes.add("["+entry.getValue()+"] = [U+"+Integer.toHexString(entry.getKey()).toUpperCase(Locale.ROOT)+"]");
                List<String> rows=new ArrayList<>();String row="";
                for(String note:notes){String combined=row.isEmpty()?note:row+"； "+note;
                    if(!row.isEmpty()&&font.getStringWidth(combined)*9/1000>WIDTH){rows.add(row);row=note;}else row=combined;
                }
                if(!row.isEmpty())rows.add(row);text(String.join("\n",rows),9,MUTED,5);
            }
        }
        static String section(String value){return switch(value){case "DISCOVERY"->"自然发现";case "REPUTATION"->"点名口碑";case "COMPETITION"->"同批竞品";default->"公开调研";};}
        private static String safe(String value){return value==null?"未提供":value;}
        static String time(OffsetDateTime value){return value==null?"未提供":TIME.format(value);}
        static String platform(String value){return switch(value){case "DOUBAO"->"豆包";case "DEEPSEEK"->"DeepSeek";case "QWEN"->"通义千问";
            case "WENXIN"->"文心一言";case "KIMI"->"Kimi";case "YUANBAO"->"腾讯元宝";case "ZHIPU"->"智谱";default->value;};}
        private static String status(String value){return switch(value){case "SUCCEEDED"->"有效回答";case "PARTIAL"->"部分完成";case "FAILED"->"采集失败";
            case "MISSING"->"尚无回执";case "QUEUED"->"等待采集";case "RUNNING"->"采集中";case "SYSTEM_TERMINATED"->"采集已终止";default->value;};}
        private void nextPage() throws IOException {
            if(stream!=null)stream.close();if(document.getNumberOfPages()>=240)throw new TooLarge();
            page=new PDPage(PDRectangle.A4);document.addPage(page);stream=new PDPageContentStream(document,page);y=778;
            write("光禾AI智能体框架 / GEO 诊断",LEFT,810,8.5f,TEAL);line(LEFT,798,LEFT+WIDTH,798,new Color(217,229,231));
            write("私有交付 · 报告 "+report.id()+" / v"+report.reportVersion(),LEFT,28,8,MUTED);write("第 "+document.getNumberOfPages()+" 页",492,28,8,MUTED);
        }
        private void ensure(float height) throws IOException {if(y-height<BOTTOM)nextPage();}
        private void ensureBlock(float full,float minimum) throws IOException {ensure(full<=725?full:minimum);}
        private float height(String value,float size,float after) throws IOException {return wrap(printable(value==null?"":value),size,WIDTH).size()*size*1.6f+after;}
        private float refsHeight(List<GeoDiagnosticReportDtos.Evidence> values) throws IOException {float height=0;for(var ref:values)height+=height("证据："+(ref.unitKey()==null?ref.sourceId():ref.unitKey())+" · “"+ref.quote()+"”",9,7);return height;}
        private void keepWithFollowing(String value,float size,float after,float nextSize) throws IOException {
            ensureBlock(height(value,size,after)+nextSize*3.2f,Math.min(150,height(value,size,after)+nextSize*1.6f));
        }
        private void heading(String value) throws IOException {ensureBlock(height(value,16,23)+36,90);y-=10;text(value,16,TEAL,13);}
        private void text(String value,float size,Color color,float after) throws IOException {
            String printable=printable(value==null?"":value);glyphs+=printable.length();if(glyphs>4_000_000)throw new TooLarge();
            List<String> rows=wrap(printable,size,WIDTH);float leading=size*1.6f;
            for(int i=0;i<rows.size();i++){
                // A paragraph should not start or end with a single stranded line.
                int remaining=rows.size()-i;ensure(leading*(remaining==2||i==0&&remaining>1?2:1));
                write(rows.get(i),LEFT,y,size,color);y-=leading;
            }y-=after;
        }
        private void source(String title,String url,String snippet,float size) throws IOException {
            // Keep only the source title and first URL line together. The complete snippet may
            // span pages; keeping an entire citation together used to leave half a page empty.
            keepWithFollowing(title,size,4,8.5f);text(title,size,TEAL,4);link("来源",url);
            if(snippet!=null&&!snippet.isBlank())markdown(snippet,9);
        }
        private record EvidenceBlock(String text,boolean heading) { }
        private void markdown(String value,float size) throws IOException {
            List<EvidenceBlock> blocks=new ArrayList<>();evidenceBlocks(MARKDOWN.parse(value==null?"":value),"",blocks);
            for(int i=0;i<blocks.size();i++){
                var block=blocks.get(i);float blockSize=block.heading()?size+1.5f:size;
                if(block.heading()){
                    float groupHeight=0;for(int following=i;following<blocks.size();following++){
                        var next=blocks.get(following);
                        if(!next.heading()){groupHeight+=Math.min(height(next.text(),size,0),size*3.2f);break;}
                        groupHeight+=height(next.text(),size+1.5f,6);
                    }
                    ensureBlock(groupHeight,Math.min(150,height(block.text(),blockSize,6)+size*3.2f));
                }
                text(block.text(),blockSize,block.heading()?TEAL:INK,block.heading()?6:7);
            }
        }
        private void evidenceBlocks(Node node,String prefix,List<EvidenceBlock> blocks) {
            if(node instanceof Heading){blocks.add(new EvidenceBlock(prefix+inline(node),true));return;}
            if(node instanceof Paragraph){boolean standaloneHeading=node.getFirstChild() instanceof StrongEmphasis&&node.getFirstChild().getNext()==null;
                blocks.add(new EvidenceBlock(prefix+inline(node),standaloneHeading));return;}
            if(node instanceof FencedCodeBlock code){blocks.add(new EvidenceBlock(prefix+code.getLiteral(),false));return;}
            if(node instanceof IndentedCodeBlock code){blocks.add(new EvidenceBlock(prefix+code.getLiteral(),false));return;}
            if(node instanceof HtmlBlock html){blocks.add(new EvidenceBlock(prefix+htmlText(html.getLiteral()),false));return;}
            if(node instanceof ThematicBreak){return;}
            if(node instanceof BulletList){for(Node item=node.getFirstChild();item!=null;item=item.getNext())evidenceBlocks(item,prefix+"- ",blocks);return;}
            if(node instanceof OrderedList ordered){int number=ordered.getMarkerStartNumber();for(Node item=node.getFirstChild();item!=null;item=item.getNext()){
                evidenceBlocks(item,prefix+number+". ",blocks);number++;
            }return;}
            if(node instanceof org.commonmark.ext.gfm.tables.TableBlock){
                List<List<String>> rows=new ArrayList<>();tableRows(node,rows);
                if(rows.isEmpty())return;var labels=rows.getFirst();
                blocks.add(new EvidenceBlock(prefix+String.join(" / ",labels),true));
                for(int r=1;r<rows.size();r++){
                    List<String> fields=new ArrayList<>();for(int c=0;c<rows.get(r).size();c++)fields.add((c<labels.size()?labels.get(c)+"：":"")+rows.get(r).get(c));
                    blocks.add(new EvidenceBlock(prefix+String.join("；",fields),false));
                }return;
            }
            if(node instanceof BlockQuote)prefix+="引用：";
            boolean first=true;for(Node child=node.getFirstChild();child!=null;child=child.getNext()){
                evidenceBlocks(child,first?prefix:"",blocks);first=false;
            }
        }
        private void tableRows(Node node,List<List<String>> rows){
            if(node instanceof TableRow){List<String> row=new ArrayList<>();for(Node cell=node.getFirstChild();cell!=null;cell=cell.getNext())row.add(inline(cell));rows.add(row);return;}
            for(Node child=node.getFirstChild();child!=null;child=child.getNext())tableRows(child,rows);
        }
        private String inline(Node node){
            if(node instanceof Text text)return text.getLiteral();
            if(node instanceof Code code)return code.getLiteral();
            if(node instanceof SoftLineBreak)return " ";
            if(node instanceof HardLineBreak)return "\n";
            if(node instanceof HtmlInline html){String literal=html.getLiteral();return literal.matches("(?i)<br\\s*/?>")?"\n":htmlText(literal);}
            StringBuilder value=new StringBuilder();for(Node child=node.getFirstChild();child!=null;child=child.getNext())value.append(inline(child));
            if(node instanceof Link link&&!value.toString().equals(link.getDestination()))value.append(" (").append(link.getDestination()).append(')');
            if(node instanceof Image image)value.insert(0,"图片：").append(" (").append(image.getDestination()).append(')');
            return value.toString();
        }
        private String htmlText(String html){
            var fragment=Jsoup.parseBodyFragment(html);String text=fragment.body().wholeText();StringBuilder result=new StringBuilder(text);
            for(var anchor:fragment.select("a[href]"))if(!text.contains(anchor.attr("href")))result.append(" (").append(anchor.attr("href")).append(')');
            for(var image:fragment.select("img[src]"))result.append(" 图片：").append(image.attr("alt")).append(" (").append(image.attr("src")).append(')');
            return result.toString().strip();
        }
        private void refs(List<GeoDiagnosticReportDtos.Evidence> values) throws IOException {for(var ref:values){String value="证据："+(ref.unitKey()==null?ref.sourceId():ref.unitKey())+" · “"+ref.quote()+"”";
            ensureBlock(height(value,9,7),45);text(value,9,MUTED,7);}}
        private void ratio(Double value) throws IOException {
            ensure(28);if(value==null){text("暂无有效样本，提及率不可计算",9,MUTED,7);return;}
            rect(LEFT,y-12,WIDTH-65,11,PALE);if(value>0)rect(LEFT,y-12,(WIDTH-65)*value.floatValue(),11,TEAL);
            write(String.format(Locale.ROOT,"%.1f%%",value*100),LEFT+WIDTH-55,y-11,9,INK);y-=24;
        }
        private void coverage(String title,List<String> labels,List<Integer> values) throws IOException {
            ensure(95);text(title,12,TEAL,8);int total=values.stream().mapToInt(Integer::intValue).sum();float x=LEFT;
            Color[] colors={TEAL,new Color(203,102,73),new Color(153,164,173)};
            for(int i=0;i<values.size();i++){float width=total==0?0:WIDTH*values.get(i)/total; if(width>0)rect(x,y-14,width,14,colors[i]);x+=width;}
            y-=32;text(String.join("     ",labels),9,INK,10);
        }
        private void link(String label,String url) throws IOException {
            URI uri=URI.create(url);if(!Set.of("https","http").contains(uri.getScheme().toLowerCase(Locale.ROOT)))throw new IOException("Unsafe diagnostic citation");
            for(String row:wrap(printable(label+": "+url),8.5f,WIDTH)){ensure(16);write(row,LEFT,y,8.5f,TEAL);
                PDAnnotationLink annotation=new PDAnnotationLink();annotation.setRectangle(new PDRectangle(LEFT,y-3,Math.min(WIDTH,font.getStringWidth(row)*8.5f/1000),13));
                var border=new PDBorderStyleDictionary();border.setWidth(0);annotation.setBorderStyle(border);var action=new PDActionURI();action.setURI(url);annotation.setAction(action);page.getAnnotations().add(annotation);y-=15;}y-=6;
        }
        List<String> wrap(String value,float size,float width) throws IOException {
            List<String> rows=new ArrayList<>();for(String paragraph:value.split("\\R",-1)){
                StringBuilder line=new StringBuilder();for(int at=0;at<paragraph.length();){int cp=paragraph.codePointAt(at);String glyph=new String(Character.toChars(cp));at+=Character.charCount(cp);
                    if(!line.isEmpty()&&font.getStringWidth(line+glyph)*size/1000>width){
                        String carry="";int last=line.codePointBefore(line.length());
                        if(line.codePointCount(0,line.length())>1&&(NO_LINE_START.indexOf(cp)>=0||NO_LINE_END.indexOf(last)>=0)){
                            int boundary=line.offsetByCodePoints(line.length(),-1);carry=line.substring(boundary);line.setLength(boundary);
                        }
                        rows.add(line.toString());line.setLength(0);line.append(carry);
                    }line.append(glyph);}
                rows.add(line.toString());}return rows;
        }
        String printable(String value) throws IOException {
            StringBuilder result=new StringBuilder();for(int at=0;at<value.length();){int cp=value.codePointAt(at);at+=Character.charCount(cp);
                if(cp=='\n'||cp=='\r'){result.appendCodePoint(cp);continue;}if(cp=='\t'){result.append("    ");continue;}
                String glyph=new String(Character.toChars(cp));try{font.encode(glyph);result.append(glyph);}catch(IllegalArgumentException unsupported){
                    String label=symbolLabel(cp);symbolNotes.putIfAbsent(cp,label);result.append('[').append(label).append(']');}}
            return result.toString();
        }
        private static String symbolLabel(int cp){return switch(cp){
            case 0x1F600,0x1F603,0x1F604,0x1F60A->"笑脸";case 0x1F449->"提示";case 0x1F4CD->"位置";case 0x1F4CC->"重点";
            case 0x1F50D,0x1F50E->"查找";case 0x1F4A1->"建议";case 0x1F4CA->"图表";case 0x1F4DD->"笔记";case 0x2705,0x2714,0x2611->"勾选";
            case 0x26A0->"注意";case 0x2615->"咖啡";case 0x1F31F,0x2B50->"星标";case 0x1F539,0x1F538->"要点";case 0xFE0F->"表情样式";
            default->"符号 "+Integer.toHexString(cp).toUpperCase(Locale.ROOT);
        };}
        private void write(String value,float x,float baseline,float size,Color color) throws IOException {if(value.isEmpty())return;stream.beginText();stream.setFont(font,size);stream.setNonStrokingColor(color);stream.newLineAtOffset(x,baseline);stream.showText(value);stream.endText();}
        private void rect(float x,float y,float width,float height,Color color) throws IOException {stream.setNonStrokingColor(color);stream.addRect(x,y,width,height);stream.fill();}
        private void line(float x,float y,float toX,float toY,Color color) throws IOException {stream.setStrokingColor(color);stream.setLineWidth(.6f);stream.moveTo(x,y);stream.lineTo(toX,toY);stream.stroke();}
        @Override public void close() throws IOException {if(stream!=null)stream.close();}
    }
}
