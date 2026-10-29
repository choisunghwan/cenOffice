package com.cenoffice.hub.form;

import static com.cenoffice.hub.form.HwpxTemplate.children;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * 워드(docx)와 파워포인트(pptx) 양식. 둘 다 문단(p) 안의 글 조각(r/t)과 표(tbl/tr/tc)로 이루어져 있어 같은 방식으로 읽는다.
 * 채울 자리는 한글 양식과 같다: 본문의 {{항목}} 자리표시자, 표에서 ‘항목 이름 칸 + 빈 칸’의 빈 칸.
 */
final class OoxmlText implements FormEngine {
    static final OoxmlText DOCX = new OoxmlText("docx", "워드", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            Pattern.compile("word/document\\.xml"), "word/document.xml", "워드에서 .docx 로 저장해 올려 주세요.");
    static final OoxmlText PPTX = new OoxmlText("pptx", "파워포인트", "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            Pattern.compile("ppt/slides/slide\\d+\\.xml"), "ppt/presentation.xml", "파워포인트에서 .pptx 로 저장해 올려 주세요.");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([^{}]{1,60}?)\\s*}}");
    private static final String XML_NS = "http://www.w3.org/XML/1998/namespace";

    private final String format, label, mime, how, must;
    private final Pattern part;

    private OoxmlText(String format, String label, String mime, Pattern part, String must, String how) {
        this.format = format; this.label = label; this.mime = mime; this.part = part; this.must = must; this.how = how;
    }

    public String format() { return format; }
    public String label() { return label; }
    public String mime() { return mime; }

    /** 읽어 들인 양식: zip 항목 전체와 파싱한 본문(워드는 document.xml 하나, 파워포인트는 슬라이드별) */
    private record Loaded(OoxmlZip.Pkg pkg, LinkedHashMap<String, Document> docs) {}

    /** 채울 자리 하나. cell 이면 target 이 표의 빈 칸(tc), placeholder 이면 그 문단(p) */
    private record Slot(HwpxTemplate.Field field, Element target) {}

    private Loaded load(byte[] data) {
        var pkg = OoxmlZip.load(data, "." + format, how);
        if (!pkg.entries().containsKey("[Content_Types].xml") || !pkg.entries().containsKey(must)) throw OoxmlZip.bad(format + " 파일이 아닙니다. " + how);
        var names = new ArrayList<String>();
        for (String n : pkg.entries().keySet()) if (part.matcher(n).matches()) names.add(n);
        names.sort(OoxmlZip::naturalCompare);
        if (names.isEmpty()) throw OoxmlZip.bad("문서 본문을 찾지 못했습니다. " + how);
        var docs = new LinkedHashMap<String, Document>();
        for (String n : names) docs.put(n, OoxmlZip.parse(pkg.entries().get(n), how));
        return new Loaded(pkg, docs);
    }

    public List<HwpxTemplate.Field> analyze(byte[] data) {
        var fields = new LinkedHashMap<String, HwpxTemplate.Field>();
        for (Slot s : slots(load(data))) fields.putIfAbsent(s.field().key(), s.field());
        if (fields.isEmpty()) throw OoxmlZip.bad("채울 항목을 찾지 못했습니다. 본문에 {{항목}} 자리표시자를 넣거나, 표에 ‘항목 이름 칸 + 빈 칸’을 만들어 주세요.");
        if (fields.size() > HwpxTemplate.MAX_FIELDS) throw OoxmlZip.bad("채울 항목이 너무 많습니다(최대 " + HwpxTemplate.MAX_FIELDS + "개).");
        return List.copyOf(fields.values());
    }

    public byte[] fill(byte[] data, Map<String, String> values) {
        Loaded doc = load(data);
        apply(slots(doc), values);
        return OoxmlZip.pack(doc.pkg(), doc.docs());
    }

    // ───────────────────────── 찾기 ─────────────────────────

    private List<Slot> slots(Loaded doc) {
        var out = new ArrayList<Slot>();
        var labelCount = new HashMap<String, Integer>();
        var last = new String[1]; // 직전 문단의 글(목록 표의 이름으로 쓴다)
        for (Document d : doc.docs().values()) walk(d.getDocumentElement(), out, labelCount, last);
        return out;
    }

    private void walk(Element el, List<Slot> out, Map<String, Integer> labelCount, String[] last) {
        switch (el.getLocalName()) {
            case "p" -> {
                Matcher m = PLACEHOLDER.matcher(paraText(el));
                boolean any = false;
                while (m.find()) { any = true; out.add(new Slot(new HwpxTemplate.Field(m.group(1).strip(), m.group(1).strip(), "placeholder"), el)); }
                if (!any) {
                    var opts = new ArrayList<String>();
                    String label = HwpxTemplate.choiceParaLabel(paraText(el), opts);
                    if (label != null) {
                        int n = labelCount.merge(label, 1, Integer::sum);
                        out.add(new Slot(new HwpxTemplate.Field(n == 1 ? label : label + " (" + n + ")", label, "choice", opts), el));
                    }
                }
                String t = paraText(el);
                if (!t.isBlank()) last[0] = t.strip();
            }
            case "tbl" -> { labelCells(el, out, labelCount); listTable(el, last[0], out, labelCount); }
            default -> { }
        }
        for (Element c : children(el)) walk(c, out, labelCount, last);
        if (el.getLocalName().equals("tbl")) last[0] = null; // 표 안의 글이 다음 표의 이름이 되지 않게
    }

    private List<List<String>> gridOf(Element tbl) {
        var grid = new ArrayList<List<String>>();
        for (Element tr : children(tbl, "tr")) {
            var row = new ArrayList<String>();
            for (Element tc : children(tr, "tc")) row.add(cellText(tc).strip());
            grid.add(row);
        }
        return grid;
    }

    /** 목록 표에서 채울 칸들(줄마다 채울 열의 칸) */
    private List<List<Element>> listCells(Element tbl) {
        var shape = HwpxTemplate.listShape(gridOf(tbl));
        var out = new ArrayList<List<Element>>();
        if (shape == null) return out;
        var trs = children(tbl, "tr");
        for (int r = 1; r < trs.size(); r++) {
            var cells = children(trs.get(r), "tc");
            var row = new ArrayList<Element>();
            for (int c : shape.cols()) row.add(cells.get(c));
            out.add(row);
        }
        return out;
    }

    private void listTable(Element tbl, String heading, List<Slot> out, Map<String, Integer> labelCount) {
        var shape = HwpxTemplate.listShape(gridOf(tbl));
        if (shape == null) return;
        String label = HwpxTemplate.listLabel(heading);
        int n = labelCount.merge(label, 1, Integer::sum);
        out.add(new Slot(new HwpxTemplate.Field(n == 1 ? label : label + " (" + n + ")", label, "rows", shape.names(), shape.rows()), tbl));
    }

    /** 목록 표 채우기: 값의 한 줄이 표의 한 줄, 칸은 | 로 나눈다. 표에 있는 줄 수까지만 넣는다. */
    private void fillRows(Element tbl, String value) {
        String[] lines = value.split("\\R");
        var cells = listCells(tbl);
        for (int r = 0; r < cells.size() && r < lines.length; r++) {
            String[] parts = lines[r].split("\\|", -1);
            for (int i = 0; i < cells.get(r).size() && i < parts.length; i++) {
                String text = clean(parts[i]);
                if (text.isBlank()) continue;
                var paras = new ArrayList<Element>();
                descendants(cells.get(r).get(i), "p", paras);
                if (!paras.isEmpty()) setParaText(paras.get(0), text);
            }
        }
    }

    private void labelCells(Element tbl, List<Slot> out, Map<String, Integer> labelCount) {
        for (Element tr : children(tbl, "tr")) {
            List<Element> cells = children(tr, "tc");
            for (int i = 0; i + 1 < cells.size(); i++) {
                String label = cellText(cells.get(i)).strip();
                if (label.isEmpty() || label.contains("{{") || !HwpxTemplate.isName(label)) continue;
                var options = new ArrayList<String>();
                String kind = HwpxTemplate.valueKind(label, cellText(cells.get(i + 1)), options);
                if (kind == null) continue;
                int n = labelCount.merge(label, 1, Integer::sum);
                String key = n == 1 ? label : label + " (" + n + ")";
                out.add(new Slot(new HwpxTemplate.Field(key, label, kind, options), cells.get(i + 1)));
            }
        }
    }

    // ───────────────────────── 채우기 ─────────────────────────

    /** 줄바꿈은 공백으로, 제어 문자는 뺀다(문단 구조를 건드리지 않으려는 선택) */
    private static String clean(String v) { return v.replaceAll("[\\r\\n]+", " ").replaceAll("\\p{Cntrl}", "").strip(); }

    private void apply(List<Slot> slots, Map<String, String> values) {
        Set<Element> done = new HashSet<>();
        for (Slot s : slots) {
            if (s.field().kind().equals("placeholder")) {
                if (!done.add(s.target())) continue;
                Matcher m = PLACEHOLDER.matcher(paraText(s.target()));
                StringBuilder b = new StringBuilder();
                boolean changed = false;
                while (m.find()) {
                    String v = values.get(m.group(1).strip());
                    if (v != null) changed = true;
                    m.appendReplacement(b, Matcher.quoteReplacement(v == null ? m.group(0) : clean(v)));
                }
                m.appendTail(b);
                if (changed) setParaText(s.target(), b.toString());
            } else if (s.field().kind().equals("rows")) {
                String v = values.get(s.field().key());
                if (v != null && !v.isBlank()) fillRows(s.target(), v);
            } else {
                String v = values.get(s.field().key());
                if (v == null) continue;
                List<Element> paras = new ArrayList<>();
                if (s.target().getLocalName().equals("p")) paras.add(s.target()); else descendants(s.target(), "p", paras);
                if (paras.isEmpty()) continue;
                if (s.field().kind().equals("choice")) setParaText(paras.get(0), HwpxTemplate.markChoice(paraText(paras.get(0)), s.field().options(), clean(v)));
                else setParaText(paras.get(0), clean(v));
            }
        }
    }

    /** 문단 글자를 통째로 바꾼다. 첫 글 조각에 넣고 나머지는 비운다(서식은 첫 조각을 따른다). */
    private void setParaText(Element p, String text) {
        List<Element> runs = runs(p);
        List<Element> ts = new ArrayList<>();
        for (Element r : runs) ts.addAll(children(r, "t"));
        if (ts.isEmpty()) {
            Element run = runs.isEmpty() ? null : runs.get(0);
            if (run == null) {
                run = create(p, "r");
                Element end = firstChild(p, "endParaRPr"); // 파워포인트: 끝 서식 앞에 넣어야 한다
                p.insertBefore(run, end);
            }
            Element t = create(p, "t");
            run.appendChild(t);
            ts.add(t);
        }
        ts.get(0).setTextContent(text);
        if (format.equals("docx")) ts.get(0).setAttributeNS(XML_NS, "xml:space", "preserve"); // 앞뒤 공백을 그대로 둔다
        for (int i = 1; i < ts.size(); i++) ts.get(i).setTextContent("");
    }

    private static Element create(Element like, String local) {
        String prefix = like.getPrefix();
        return like.getOwnerDocument().createElementNS(like.getNamespaceURI(), prefix == null ? local : prefix + ":" + local);
    }

    private static Element firstChild(Element el, String local) {
        for (Element c : children(el)) if (c.getLocalName().equals(local)) return c;
        return null;
    }

    // ───────────────────────── 글 읽기 ─────────────────────────

    /** 문단의 글 조각들. 링크 같은 감싸는 요소 안의 조각도 포함하고, 문단 속성은 건너뛴다. */
    private static List<Element> runs(Element p) {
        var out = new ArrayList<Element>();
        for (Element c : children(p)) {
            switch (c.getLocalName()) {
                case "r" -> out.add(c);
                case "hyperlink", "smartTag", "ins", "fld" -> out.addAll(runs(c));
                default -> { }
            }
        }
        return out;
    }

    static String paraText(Element p) {
        var sb = new StringBuilder();
        for (Element r : runs(p)) for (Element t : children(r, "t")) sb.append(t.getTextContent());
        return sb.toString();
    }

    private static void descendants(Element el, String local, List<Element> out) {
        for (Element c : children(el)) {
            if (c.getLocalName().equals(local)) out.add(c);
            else descendants(c, local, out);
        }
    }

    static String cellText(Element tc) {
        var paras = new ArrayList<Element>();
        descendants(tc, "p", paras);
        var sb = new StringBuilder();
        for (Element p : paras) { if (sb.length() > 0) sb.append('\n'); sb.append(paraText(p)); }
        return sb.toString();
    }

    // ───────────────────────── 미리보기 ─────────────────────────

    public List<HwpxTemplate.PreviewBlock> preview(byte[] data, Map<String, String> values) {
        Loaded doc = load(data);
        List<Slot> slots = slots(doc);
        Map<Element, String> keyOf = new IdentityHashMap<>();
        for (Slot s : slots) {
            if (s.field().kind().equals("rows")) { for (var row : listCells(s.target())) for (Element c : row) keyOf.putIfAbsent(c, s.field().key()); }
            else keyOf.putIfAbsent(s.target(), s.field().key());
        }
        apply(slots, values);
        var out = new ArrayList<HwpxTemplate.PreviewBlock>();
        int n = 0;
        for (Document d : doc.docs().values()) {
            if (format.equals("pptx")) out.add(new HwpxTemplate.PreviewBlock("p", "— 슬라이드 " + (++n) + " —", null, null));
            blocks(d.getDocumentElement(), keyOf, out);
        }
        return out;
    }

    private void blocks(Element el, Map<Element, String> keyOf, List<HwpxTemplate.PreviewBlock> out) {
        switch (el.getLocalName()) {
            case "tbl" -> {
                var rows = new ArrayList<List<HwpxTemplate.PreviewCell>>();
                for (Element tr : children(el, "tr")) {
                    var cells = new ArrayList<HwpxTemplate.PreviewCell>();
                    for (Element tc : children(tr, "tc")) cells.add(new HwpxTemplate.PreviewCell(cellText(tc).replace("\n", " / "), keyOf.get(tc)));
                    rows.add(cells);
                }
                out.add(new HwpxTemplate.PreviewBlock("table", null, rows, null));
            }
            case "p" -> {
                String text = paraText(el);
                if (!text.isBlank()) out.add(new HwpxTemplate.PreviewBlock("p", text, null, keyOf.get(el)));
            }
            default -> { for (Element c : children(el)) blocks(c, keyOf, out); }
        }
    }
}
