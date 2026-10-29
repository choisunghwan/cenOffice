package com.cenoffice.hub.form;

import static com.cenoffice.hub.form.HwpxTemplate.children;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * 엑셀(xlsx) 양식. 채울 자리는 다른 형식과 같다: 셀 안의 {{항목}} 자리표시자, ‘항목 이름 셀 + 오른쪽 빈 셀’의 빈 셀.
 * 채운 값은 문자열 셀로 쓰며, 셀 서식(글꼴·테두리·열 너비 등)은 그대로 둔다. 수식·차트·그림 등 나머지도 원본 그대로다.
 */
final class XlsxTemplate implements FormEngine {
    static final XlsxTemplate INSTANCE = new XlsxTemplate();

    private static final String HOW = "엑셀에서 .xlsx 로 저장해 올려 주세요.";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([^{}]{1,60}?)\\s*}}");
    private static final Pattern SHEET = Pattern.compile("xl/worksheets/sheet\\d+\\.xml");
    private static final String XML_NS = "http://www.w3.org/XML/1998/namespace";
    private static final int PREVIEW_ROWS = 200, PREVIEW_COLS = 26;

    public String format() { return "xlsx"; }
    public String label() { return "엑셀"; }
    public String mime() { return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"; }

    /** 읽어 들인 양식: zip 항목 전체, 시트 본문들, 공유 문자열 */
    private record Loaded(OoxmlZip.Pkg pkg, LinkedHashMap<String, Document> sheets, List<String> shared) {}

    /** 채울 자리 하나. sheet 는 시트 순번, 셀은 (row, col)(1부터). placeholder 이면 그 셀의 글 안에 {{항목}} 이 있다. */
    private record Slot(HwpxTemplate.Field field, String sheet, int row, int col, boolean placeholder) {}

    private Loaded load(byte[] data) {
        var pkg = OoxmlZip.load(data, ".xlsx", HOW);
        if (!pkg.entries().containsKey("[Content_Types].xml") || !pkg.entries().containsKey("xl/workbook.xml")) throw OoxmlZip.bad("xlsx 파일이 아닙니다. " + HOW);
        var names = new ArrayList<String>();
        for (String n : pkg.entries().keySet()) if (SHEET.matcher(n).matches()) names.add(n);
        names.sort(OoxmlZip::naturalCompare);
        if (names.isEmpty()) throw OoxmlZip.bad("시트를 찾지 못했습니다. " + HOW);
        var sheets = new LinkedHashMap<String, Document>();
        for (String n : names) sheets.put(n, OoxmlZip.parse(pkg.entries().get(n), HOW));
        var shared = new ArrayList<String>();
        byte[] ss = pkg.entries().get("xl/sharedStrings.xml");
        if (ss != null) {
            Document d = OoxmlZip.parse(ss, HOW);
            for (Element si : children(d.getDocumentElement(), "si")) shared.add(textOf(si));
        }
        return new Loaded(pkg, sheets, shared);
    }

    /** si 나 is 안의 글(여러 조각이면 이어 붙인다). 발음 표기(rPh)는 제외한다. */
    private static String textOf(Element el) {
        var sb = new StringBuilder();
        for (Element c : children(el)) {
            switch (c.getLocalName()) {
                case "t" -> sb.append(c.getTextContent());
                case "r" -> { for (Element t : children(c, "t")) sb.append(t.getTextContent()); }
                default -> { }
            }
        }
        return sb.toString();
    }

    public List<HwpxTemplate.Field> analyze(byte[] data) {
        var fields = new LinkedHashMap<String, HwpxTemplate.Field>();
        for (Slot s : slots(load(data))) fields.putIfAbsent(s.field().key(), s.field());
        if (fields.isEmpty()) throw OoxmlZip.bad("채울 항목을 찾지 못했습니다. 셀에 {{항목}} 자리표시자를 넣거나, ‘항목 이름 셀 + 오른쪽 빈 셀’을 만들어 주세요.");
        if (fields.size() > HwpxTemplate.MAX_FIELDS) throw OoxmlZip.bad("채울 항목이 너무 많습니다(최대 " + HwpxTemplate.MAX_FIELDS + "개).");
        return List.copyOf(fields.values());
    }

    public byte[] fill(byte[] data, Map<String, String> values) {
        Loaded doc = load(data);
        apply(doc, slots(doc), values);
        return OoxmlZip.pack(doc.pkg(), doc.sheets());
    }

    // ───────────────────────── 셀 읽기 ─────────────────────────

    /** 시트의 셀들: 행 번호 → (열 번호 → 셀 요소) */
    private static TreeMap<Integer, TreeMap<Integer, Element>> grid(Document sheet) {
        var rows = new TreeMap<Integer, TreeMap<Integer, Element>>();
        for (Element sd : children(sheet.getDocumentElement(), "sheetData")) {
            for (Element row : children(sd, "row")) {
                for (Element c : children(row, "c")) {
                    int[] rc = parseRef(c.getAttribute("r"));
                    if (rc != null) rows.computeIfAbsent(rc[0], k -> new TreeMap<>()).put(rc[1], c);
                }
            }
        }
        return rows;
    }

    /** "B3" → {3, 2}. 잘못된 이름이면 null */
    private static int[] parseRef(String ref) {
        Matcher m = Pattern.compile("^([A-Za-z]{1,3})(\\d{1,7})$").matcher(ref == null ? "" : ref);
        if (!m.matches()) return null;
        int col = 0;
        for (char ch : m.group(1).toUpperCase().toCharArray()) col = col * 26 + (ch - 'A' + 1);
        return new int[]{Integer.parseInt(m.group(2)), col};
    }

    private static String colName(int col) {
        var sb = new StringBuilder();
        for (int c = col; c > 0; c = (c - 1) / 26) sb.insert(0, (char) ('A' + (c - 1) % 26));
        return sb.toString();
    }

    /** 셀의 글. 숫자 셀은 값 그대로, 문자열 셀은 문자열을 돌려준다(빈 셀은 ""). */
    private static String text(Element c, List<String> shared) {
        if (c == null) return "";
        String type = c.getAttribute("t");
        if (type.equals("inlineStr")) { for (Element is : children(c, "is")) return textOf(is); return ""; }
        String v = "";
        for (Element ve : children(c, "v")) v = ve.getTextContent();
        if (type.equals("s")) {
            try { int i = Integer.parseInt(v.strip()); return i >= 0 && i < shared.size() ? shared.get(i) : ""; } catch (NumberFormatException e) { return ""; }
        }
        return v;
    }

    private static boolean isText(Element c) {
        String t = c.getAttribute("t");
        return t.equals("s") || t.equals("str") || t.equals("inlineStr");
    }

    // ───────────────────────── 찾기 ─────────────────────────

    private List<Slot> slots(Loaded doc) {
        var out = new ArrayList<Slot>();
        var labelCount = new HashMap<String, Integer>();
        for (var sh : doc.sheets().entrySet()) {
            var rows = grid(sh.getValue());
            for (var row : rows.entrySet()) {
                for (var cell : row.getValue().entrySet()) {
                    Element c = cell.getValue();
                    if (!isText(c)) continue;
                    String t = text(c, doc.shared()).strip();
                    if (t.isEmpty()) continue;
                    Matcher m = PLACEHOLDER.matcher(t);
                    boolean ph = false;
                    while (m.find()) {
                        ph = true;
                        String key = m.group(1).strip();
                        out.add(new Slot(new HwpxTemplate.Field(key, key, "placeholder"), sh.getKey(), row.getKey(), cell.getKey(), true));
                    }
                    if (ph || t.contains("{{") || t.length() > 40 || !t.matches(".*\\p{L}.*")) continue;
                    Element right = row.getValue().get(cell.getKey() + 1);
                    var options = new ArrayList<String>();
                    String kind = HwpxTemplate.valueKind(t, right == null ? "" : text(right, doc.shared()), options);
                    if (kind == null) continue; // 오른쪽이 이미 채워져 있으면(선택지·날짜 틀이 아니면) 항목 이름이 아니다
                    int n = labelCount.merge(t, 1, Integer::sum);
                    String key = n == 1 ? t : t + " (" + n + ")";
                    out.add(new Slot(new HwpxTemplate.Field(key, t, kind, options), sh.getKey(), row.getKey(), cell.getKey() + 1, false));
                }
            }
        }
        return out;
    }

    // ───────────────────────── 채우기 ─────────────────────────

    private static String clean(String v) { return v.replaceAll("[\\r\\n]+", " ").replaceAll("\\p{Cntrl}", "").strip(); }

    private void apply(Loaded doc, List<Slot> slots, Map<String, String> values) {
        var done = new java.util.HashSet<String>();
        for (Slot s : slots) {
            Document sheet = doc.sheets().get(s.sheet());
            if (s.placeholder()) {
                if (!done.add(s.sheet() + ":" + s.row() + ":" + s.col())) continue;
                Element c = cellAt(sheet, s.row(), s.col(), false);
                Matcher m = PLACEHOLDER.matcher(text(c, doc.shared()));
                StringBuilder b = new StringBuilder();
                boolean changed = false;
                while (m.find()) {
                    String v = values.get(m.group(1).strip());
                    if (v != null) changed = true;
                    m.appendReplacement(b, Matcher.quoteReplacement(v == null ? m.group(0) : clean(v)));
                }
                m.appendTail(b);
                if (changed) setText(c, b.toString());
            } else {
                String v = values.get(s.field().key());
                if (v == null) continue;
                Element target = cellAt(sheet, s.row(), s.col(), true);
                if (s.field().kind().equals("choice")) setText(target, HwpxTemplate.markChoice(text(target, doc.shared()), s.field().options(), clean(v)));
                else setText(target, clean(v));
            }
        }
    }

    /** 셀을 문자열 셀로 바꿔 글을 넣는다. 서식(s)은 그대로 둔다. */
    private static void setText(Element c, String text) {
        for (Element child : children(c)) c.removeChild(child);
        c.setAttribute("t", "inlineStr");
        Element is = create(c, "is");
        Element t = create(c, "t");
        t.setAttributeNS(XML_NS, "xml:space", "preserve");
        t.setTextContent(text);
        is.appendChild(t);
        c.appendChild(is);
    }

    private static Element create(Element like, String local) {
        String prefix = like.getPrefix();
        return like.getOwnerDocument().createElementNS(like.getNamespaceURI(), prefix == null ? local : prefix + ":" + local);
    }

    /** (row, col) 셀. 없으면 create 일 때 행·열 순서에 맞춰 새로 만든다. */
    private static Element cellAt(Document sheet, int row, int col, boolean create) {
        Element sd = children(sheet.getDocumentElement(), "sheetData").get(0);
        Element rowEl = null, before = null;
        for (Element r : children(sd, "row")) {
            int n = parseIntSafe(r.getAttribute("r"));
            if (n == row) { rowEl = r; break; }
            if (n > row) { before = r; break; }
        }
        if (rowEl == null) {
            if (!create) return null;
            rowEl = create(sd, "row");
            rowEl.setAttribute("r", String.valueOf(row));
            sd.insertBefore(rowEl, before);
        }
        Element cBefore = null;
        for (Element c : children(rowEl, "c")) {
            int[] rc = parseRef(c.getAttribute("r"));
            int n = rc == null ? 0 : rc[1];
            if (n == col) return c;
            if (n > col) { cBefore = c; break; }
        }
        if (!create) return null;
        Element c = create(rowEl, "c");
        c.setAttribute("r", colName(col) + row);
        rowEl.insertBefore(c, cBefore);
        return c;
    }

    private static int parseIntSafe(String s) { try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; } }

    // ───────────────────────── 미리보기 ─────────────────────────

    public List<HwpxTemplate.PreviewBlock> preview(byte[] data, Map<String, String> values) {
        Loaded doc = load(data);
        List<Slot> slots = slots(doc);
        Map<String, String> keyAt = new HashMap<>();
        for (Slot s : slots) keyAt.putIfAbsent(s.sheet() + ":" + s.row() + ":" + s.col(), s.field().key());
        apply(doc, slots, values);
        var out = new ArrayList<HwpxTemplate.PreviewBlock>();
        int n = 0;
        for (var sh : doc.sheets().entrySet()) {
            out.add(new HwpxTemplate.PreviewBlock("p", "— 시트 " + (++n) + " —", null, null));
            var rows = grid(sh.getValue());
            int maxCol = 1;
            for (var row : rows.values()) for (var e : row.entrySet()) if (!text(e.getValue(), doc.shared()).isBlank()) maxCol = Math.max(maxCol, e.getKey());
            maxCol = Math.min(maxCol, PREVIEW_COLS);
            var table = new ArrayList<List<HwpxTemplate.PreviewCell>>();
            for (var row : rows.entrySet()) {
                if (row.getKey() > PREVIEW_ROWS) break;
                boolean any = false;
                var cells = new ArrayList<HwpxTemplate.PreviewCell>();
                for (int col = 1; col <= maxCol; col++) {
                    String t = text(row.getValue().get(col), doc.shared());
                    String key = keyAt.get(sh.getKey() + ":" + row.getKey() + ":" + col);
                    if (!t.isBlank() || key != null) any = true;
                    cells.add(new HwpxTemplate.PreviewCell(t, key));
                }
                if (any) table.add(cells);
            }
            if (!table.isEmpty()) out.add(new HwpxTemplate.PreviewBlock("table", null, table, null));
        }
        return out;
    }
}
