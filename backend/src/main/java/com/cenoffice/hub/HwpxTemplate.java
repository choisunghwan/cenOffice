package com.cenoffice.hub;

import com.cenoffice.api.ApiException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.http.HttpStatus;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * 한글 문서(HWPX = OWPML 을 담은 ZIP)를 양식으로 읽는다. 채울 자리는 두 가지다.
 * 1) 본문 어디서든 {{이름}} 자리표시자, 2) 표에서 글자가 있는 칸 바로 오른쪽(같은 행)의 빈 칸(키는 왼쪽 칸 글자, 머리글 행 제외).
 * 서버 파일·DB 를 건드리지 않는 순수 변환이다. 업로드된 파일은 믿지 않고 크기·항목 이름·구조를 먼저 검사한다.
 */
final class HwpxTemplate {
    private HwpxTemplate() {}

    record Field(String key, String label, String kind) {}

    static final int MAX_BYTES = 5 * 1024 * 1024;
    static final long MAX_UNPACKED = 30L * 1024 * 1024;
    static final int MAX_ENTRIES = 500;
    static final int MAX_FIELDS = 100;
    private static final String MIME = "application/hwp+zip";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([^{}]{1,60}?)\\s*}}");
    private static final Pattern SECTION = Pattern.compile("Contents/section\\d+\\.xml");
    private static final String HOW = "한글에서 .hwpx 로 저장해 올려 주세요.";

    /** 읽어 들인 양식: zip 항목 전체(순서 유지)와 파싱한 본문 구역들 */
    record Loaded(LinkedHashMap<String, byte[]> entries, LinkedHashMap<String, Document> sections) {}

    /** 채울 자리 하나. cell 이면 target 이 표의 빈 칸(hp:tc), placeholder 이면 target 이 그 문단(hp:p) */
    record Slot(Field field, Element target) {}

    static List<Field> analyze(byte[] hwpx) {
        var slots = slots(load(hwpx));
        var fields = new LinkedHashMap<String, Field>();
        for (Slot s : slots) fields.putIfAbsent(s.field().key(), s.field());
        if (fields.isEmpty()) throw bad("채울 항목을 찾지 못했습니다. 본문에 {{항목}} 자리표시자를 넣거나, 표에 ‘라벨 칸 + 빈 칸’을 만들어 주세요.");
        if (fields.size() > MAX_FIELDS) throw bad("채울 항목이 너무 많습니다(최대 " + MAX_FIELDS + "개).");
        return List.copyOf(fields.values());
    }

    static Loaded load(byte[] data) {
        if (data == null || data.length == 0) throw bad("빈 파일입니다. " + HOW);
        if (data.length > MAX_BYTES) throw bad("파일이 너무 큽니다(최대 5MB).");
        var entries = new LinkedHashMap<String, byte[]>();
        long total = 0;
        try (var zin = new ZipInputStream(new ByteArrayInputStream(data), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String name = e.getName();
                if (name.startsWith("/") || name.startsWith("\\") || name.contains("\\") || Arrays.asList(name.split("/")).contains("..")) throw bad("양식 파일 구조가 올바르지 않습니다. " + HOW);
                if (entries.size() >= MAX_ENTRIES) throw bad("양식 파일 구조가 올바르지 않습니다. " + HOW);
                var out = new ByteArrayOutputStream();
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = zin.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_UNPACKED) throw bad("파일이 너무 큽니다. " + HOW);
                    out.write(buf, 0, n);
                }
                if (!e.isDirectory()) entries.put(name, out.toByteArray());
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw bad("hwpx 파일이 아닙니다. " + HOW);
        }
        byte[] mime = entries.get("mimetype");
        if (mime == null || !new String(mime, StandardCharsets.UTF_8).strip().equals(MIME)) throw bad("hwpx 파일이 아닙니다(옛 .hwp 형식은 지원하지 않습니다). " + HOW);
        var sections = new LinkedHashMap<String, Document>();
        for (var en : entries.entrySet()) if (SECTION.matcher(en.getKey()).matches()) sections.put(en.getKey(), parse(en.getValue()));
        if (sections.isEmpty()) throw bad("문서 본문을 찾지 못했습니다. " + HOW);
        return new Loaded(entries, sections);
    }

    private static Document parse(byte[] xml) {
        try {
            var f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        } catch (Exception e) {
            throw bad("문서 본문을 읽지 못했습니다. " + HOW);
        }
    }

    /** 문서 순서대로 채울 자리를 모은다. 표 라벨 키는 겹치면 ‘키 (2)’ 로 구분한다. */
    static List<Slot> slots(Loaded doc) {
        var out = new ArrayList<Slot>();
        var labelCount = new HashMap<String, Integer>();
        for (Document d : doc.sections().values()) walk(d.getDocumentElement(), out, labelCount);
        return out;
    }

    private static void walk(Element el, List<Slot> out, Map<String, Integer> labelCount) {
        switch (el.getLocalName()) {
            case "p" -> {
                Matcher m = PLACEHOLDER.matcher(paraText(el));
                while (m.find()) out.add(new Slot(new Field(m.group(1).strip(), m.group(1).strip(), "placeholder"), el));
            }
            case "tbl" -> labelCells(el, out, labelCount);
            default -> { }
        }
        for (Element c : children(el)) walk(c, out, labelCount);
    }

    private static void labelCells(Element tbl, List<Slot> out, Map<String, Integer> labelCount) {
        boolean first = true;
        for (Element tr : children(tbl, "tr")) {
            List<Element> cells = children(tr, "tc");
            if (!first) {
                for (int i = 0; i + 1 < cells.size(); i++) {
                    String label = cellText(cells.get(i)).strip();
                    if (!label.isEmpty() && cellText(cells.get(i + 1)).isBlank() && !label.contains("{{")) {
                        int n = labelCount.merge(label, 1, Integer::sum);
                        String key = n == 1 ? label : label + " (" + n + ")";
                        out.add(new Slot(new Field(key, label, "cell"), cells.get(i + 1)));
                    }
                }
            }
            first = false;
        }
    }

    // ───────────────────────── 채우기 ─────────────────────────

    /** 값이 있는 항목만 채운 새 hwpx. 값이 없는 항목·서식·그림 등 나머지는 원본 그대로. */
    static byte[] fill(byte[] hwpx, Map<String, String> values) {
        Loaded doc = load(hwpx);
        apply(doc, slots(doc), values);
        return pack(doc);
    }

    /** 줄바꿈은 공백으로, 제어 문자는 뺀다(문단 구조를 건드리지 않으려는 선택) */
    private static String clean(String v) { return v.replaceAll("[\\r\\n]+", " ").replaceAll("\\p{Cntrl}", "").strip(); }

    private static void apply(Loaded doc, List<Slot> slots, Map<String, String> values) {
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
            } else {
                String v = values.get(s.field().key());
                if (v == null) continue;
                List<Element> subs = children(s.target(), "subList");
                List<Element> paras = subs.isEmpty() ? List.of() : children(subs.get(0), "p");
                if (!paras.isEmpty()) setParaText(paras.get(0), clean(v));
            }
        }
    }

    /** 문단 글자를 통째로 바꾼다. 첫 hp:t 에 넣고 나머지 hp:t 는 비운다(런 서식은 첫 런을 따른다). */
    private static void setParaText(Element p, String text) {
        List<Element> ts = new ArrayList<>();
        for (Element run : children(p, "run")) ts.addAll(children(run, "t"));
        if (ts.isEmpty()) {
            Element run = children(p, "run").isEmpty() ? null : children(p, "run").get(0);
            if (run == null) {
                run = p.getOwnerDocument().createElementNS(p.getNamespaceURI(), p.getPrefix() + ":run");
                run.setAttribute("charPrIDRef", "0");
                p.appendChild(run);
            }
            Element t = p.getOwnerDocument().createElementNS(p.getNamespaceURI(), p.getPrefix() + ":t");
            run.appendChild(t);
            ts.add(t);
        }
        ts.get(0).setTextContent(text);
        for (int i = 1; i < ts.size(); i++) ts.get(i).setTextContent("");
    }

    private static byte[] pack(Loaded doc) {
        try {
            var bos = new ByteArrayOutputStream();
            try (var zip = new java.util.zip.ZipOutputStream(bos)) {
                for (var en : doc.entries().entrySet()) {
                    byte[] data = doc.sections().containsKey(en.getKey()) ? serialize(doc.sections().get(en.getKey())) : en.getValue();
                    var e = new ZipEntry(en.getKey());
                    if (en.getKey().equals("mimetype")) { // 한글이 형식을 알아보도록 첫 항목에 압축 없이 둔다
                        e.setMethod(ZipEntry.STORED); e.setSize(data.length); e.setCompressedSize(data.length);
                        var crc = new java.util.zip.CRC32(); crc.update(data); e.setCrc(crc.getValue());
                    }
                    zip.putNextEntry(e); zip.write(data); zip.closeEntry();
                }
            }
            return bos.toByteArray();
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "문서를 만들지 못했습니다.");
        }
    }

    private static byte[] serialize(Document d) throws Exception {
        d.setXmlStandalone(true);
        var tf = javax.xml.transform.TransformerFactory.newInstance();
        tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        var t = tf.newTransformer();
        t.setOutputProperty(javax.xml.transform.OutputKeys.ENCODING, "UTF-8");
        var out = new ByteArrayOutputStream();
        t.transform(new javax.xml.transform.dom.DOMSource(d), new javax.xml.transform.stream.StreamResult(out));
        return out.toByteArray();
    }

    // ───────────────────────── 미리보기 ─────────────────────────

    record PreviewCell(String text, String fieldKey) {}

    /** type: "p"(문단; 자리표시자 문단이면 fieldKey) | "table"(rows) */
    record PreviewBlock(String type, String text, List<List<PreviewCell>> rows, String fieldKey) {}

    /** 값을 반영한 문서 내용을 문서 순서대로 돌려준다. 채울 자리는 fieldKey 로 표시되고, 값이 없는 자리표시자는 {{항목}} 그대로 보인다. */
    static List<PreviewBlock> preview(byte[] hwpx, Map<String, String> values) {
        Loaded doc = load(hwpx);
        List<Slot> slots = slots(doc);
        Map<Element, String> keyOf = new IdentityHashMap<>();
        for (Slot s : slots) keyOf.putIfAbsent(s.target(), s.field().key());
        apply(doc, slots, values);
        var out = new ArrayList<PreviewBlock>();
        for (Document d : doc.sections().values()) {
            for (Element p : children(d.getDocumentElement(), "p")) {
                for (Element run : children(p, "run")) for (Element tbl : children(run, "tbl")) out.add(previewTable(tbl, keyOf));
                String text = paraText(p);
                if (!text.isBlank()) out.add(new PreviewBlock("p", text, null, keyOf.get(p)));
            }
        }
        return out;
    }

    private static PreviewBlock previewTable(Element tbl, Map<Element, String> keyOf) {
        var rows = new ArrayList<List<PreviewCell>>();
        for (Element tr : children(tbl, "tr")) {
            var cells = new ArrayList<PreviewCell>();
            for (Element tc : children(tr, "tc")) cells.add(new PreviewCell(cellText(tc), keyOf.get(tc)));
            rows.add(cells);
        }
        return new PreviewBlock("table", null, rows, null);
    }

    // ───────────────────────── DOM 도우미 ─────────────────────────

    /** 문단 글자: 이 문단의 런(hp:run) 바로 아래 hp:t 만 이어 붙인다(안에 든 표의 글자는 제외) */
    static String paraText(Element p) {
        StringBuilder b = new StringBuilder();
        for (Element run : children(p, "run")) for (Element t : children(run, "t")) b.append(t.getTextContent());
        return b.toString();
    }

    static String cellText(Element tc) {
        StringBuilder b = new StringBuilder();
        for (Element sub : children(tc, "subList")) for (Element p : children(sub, "p")) b.append(paraText(p));
        return b.toString();
    }

    static List<Element> children(Element el) {
        var out = new ArrayList<Element>();
        for (Node n = el.getFirstChild(); n != null; n = n.getNextSibling()) if (n instanceof Element e) out.add(e);
        return out;
    }

    static List<Element> children(Element el, String localName) {
        return children(el).stream().filter(e -> localName.equals(e.getLocalName())).toList();
    }

    private static ApiException bad(String message) { return new ApiException(HttpStatus.BAD_REQUEST, message); }
}
