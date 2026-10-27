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
