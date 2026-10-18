package com.cenoffice.hub;

import com.cenoffice.api.ApiException;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.zip.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Bounded, no-disk OOXML text extraction. No external entities, macro/formula execution or linked resources. */
@Component
public class BusinessDocumentReader {
    public String read(String name, byte[] bytes) {
        if (name == null || bytes.length == 0 || bytes.length > 5 * 1024 * 1024) throw bad("파일은 비어 있지 않은 5MB 이하 자료여야 합니다");
        String lower = name.toLowerCase(Locale.ROOT);
        try {
            String text;
            if (lower.endsWith(".txt") || lower.endsWith(".md")) {
                text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            } else if (lower.endsWith(".xlsx") || lower.endsWith(".docx")) {
                var entries = unzip(bytes);
                if (lower.endsWith(".xlsx")) {
                    if (!entries.containsKey("xl/workbook.xml")) throw bad("올바른 XLSX 파일이 아닙니다");
                    List<String> shared = new ArrayList<>();
                    if (entries.containsKey("xl/sharedStrings.xml")) {
                        var si = xml(entries.get("xl/sharedStrings.xml")).getElementsByTagNameNS("*", "si");
                        for (int i = 0; i < si.getLength(); i++) shared.add(si.item(i).getTextContent());
                    }
                    var out = new StringBuilder();
                    for (var e : entries.entrySet()) if (e.getKey().matches("xl/worksheets/sheet[0-9]+\\.xml")) {
                        var rows = xml(e.getValue()).getElementsByTagNameNS("*", "row");
                        for (int i = 0; i < rows.getLength(); i++) {
                            var cells = ((Element) rows.item(i)).getElementsByTagNameNS("*", "c");
                            List<String> values = new ArrayList<>();
                            for (int c = 0; c < cells.getLength(); c++) {
                                var cell = (Element) cells.item(c);
                                var v = cell.getElementsByTagNameNS("*", "v");
                                String value = "inlineStr".equals(cell.getAttribute("t")) ? cell.getTextContent() : v.getLength() == 0 ? "" : v.item(0).getTextContent();
                                if ("s".equals(cell.getAttribute("t")) && !value.isBlank()) value = shared.get(Integer.parseInt(value));
                                if (!value.isBlank()) values.add(value.replaceAll("\\s+", " ").trim());
                            }
                            if (!values.isEmpty()) out.append(String.join(" · ", values)).append('\n');
                        }
                    }
                    text = out.toString();
                } else {
                    if (!entries.containsKey("word/document.xml")) throw bad("올바른 DOCX 파일이 아닙니다");
                    var paragraphs = xml(entries.get("word/document.xml")).getElementsByTagNameNS("*", "p");
                    StringBuilder out = new StringBuilder();
                    for (int i = 0; i < paragraphs.getLength(); i++) {
                        var texts = ((Element) paragraphs.item(i)).getElementsByTagNameNS("*", "t");
                        for (int t = 0; t < texts.getLength(); t++) out.append(texts.item(t).getTextContent());
                        out.append('\n');
                    }
                    text = out.toString();
                }
            } else throw bad("XLSX, DOCX, UTF-8 TXT/MD 파일을 지원합니다. PDF·이미지·소스 ZIP은 아직 지원하지 않습니다");
            text = text.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "").trim();
            if (text.isBlank()) throw bad("읽을 수 있는 텍스트가 없습니다");
            if (text.length() > 12000 || text.lines().count() > 80) throw bad("파일당 12,000자·80행 이하의 분석 범위로 나누어 주세요");
            return text;
        } catch (ApiException ex) { throw ex; }
        catch (Exception ex) { throw bad("파일을 읽을 수 없습니다. 형식·인코딩·암호화 여부를 확인하세요"); }
    }
    private Map<String, byte[]> unzip(byte[] bytes) throws IOException {
        Map<String, byte[]> map = new TreeMap<>();
        int total = 0, count = 0;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null;) {
                String path = e.getName();
                if (++count > 3000 || path.startsWith("/") || path.contains("..") || path.contains("\\")) throw bad("안전하지 않은 문서 패키지입니다");
                if (e.isDirectory()) continue;
                var out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                for (int n; (n = zip.read(buffer)) != -1;) {
                    total += n;
                    if (total > 15 * 1024 * 1024 || out.size() + n > 3 * 1024 * 1024) throw bad("문서 압축 해제 용량 제한을 초과했습니다");
                    out.write(buffer, 0, n);
                }
                if (map.putIfAbsent(path, out.toByteArray()) != null) throw bad("중복 파일을 포함한 문서입니다");
            }
        }
        return map;
    }
    private Document xml(byte[] bytes) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }
    private ApiException bad(String message) { return new ApiException(HttpStatus.BAD_REQUEST, message); }
}
