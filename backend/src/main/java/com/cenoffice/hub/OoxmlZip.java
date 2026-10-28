package com.cenoffice.hub;

import com.cenoffice.api.ApiException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.http.HttpStatus;
import org.w3c.dom.Document;

/** docx·xlsx·pptx(Office Open XML)의 zip 읽기·쓰기. 한글 양식과 같은 크기 제한을 두고, 외부 개체(XXE)는 읽지 않는다. */
final class OoxmlZip {
    private OoxmlZip() {}

    /** 읽어 들인 파일: zip 항목 전체(순서 유지) */
    record Pkg(LinkedHashMap<String, byte[]> entries) {}

    static Pkg load(byte[] data, String formatName, String how) {
        if (data == null || data.length == 0) throw bad("빈 파일입니다. " + how);
        if (data.length > HwpxTemplate.MAX_BYTES) throw bad("파일이 너무 큽니다(최대 5MB).");
        var entries = new LinkedHashMap<String, byte[]>();
        long total = 0;
        try (var zin = new ZipInputStream(new ByteArrayInputStream(data), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String name = e.getName();
                if (name.startsWith("/") || name.contains("\\") || Arrays.asList(name.split("/")).contains("..")) throw bad("양식 파일 구조가 올바르지 않습니다. " + how);
                if (entries.size() >= HwpxTemplate.MAX_ENTRIES) throw bad("양식 파일 구조가 올바르지 않습니다. " + how);
                var out = new ByteArrayOutputStream();
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = zin.read(buf)) > 0) {
                    total += n;
                    if (total > HwpxTemplate.MAX_UNPACKED) throw bad("파일이 너무 큽니다. " + how);
                    out.write(buf, 0, n);
                }
                if (!e.isDirectory()) entries.put(name, out.toByteArray());
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw bad(formatName + " 파일이 아닙니다. " + how);
        }
        return new Pkg(entries);
    }

    static Document parse(byte[] xml, String how) {
        try {
            var f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        } catch (Exception e) {
            throw bad("문서 본문을 읽지 못했습니다. " + how);
        }
    }

    /** 바뀐 본문(docs)만 다시 쓰고 나머지 항목은 원본 그대로 묶는다 */
    static byte[] pack(Pkg pkg, Map<String, Document> changed) {
        try {
            var bos = new ByteArrayOutputStream();
            try (var zip = new java.util.zip.ZipOutputStream(bos)) {
                for (var en : pkg.entries().entrySet()) {
                    byte[] data = changed.containsKey(en.getKey()) ? serialize(changed.get(en.getKey())) : en.getValue();
                    zip.putNextEntry(new ZipEntry(en.getKey()));
                    zip.write(data);
                    zip.closeEntry();
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

    /** "slide2.xml" < "slide10.xml" 처럼 이름 속 숫자를 숫자로 비교한다 */
    static int naturalCompare(String a, String b) {
        var m1 = java.util.regex.Pattern.compile("(\\d+)").matcher(a);
        var m2 = java.util.regex.Pattern.compile("(\\d+)").matcher(b);
        String lastA = null, lastB = null;
        while (m1.find()) lastA = m1.group(1);
        while (m2.find()) lastB = m2.group(1);
        if (lastA != null && lastB != null && lastA.length() < 9 && lastB.length() < 9) {
            int c = Integer.compare(Integer.parseInt(lastA), Integer.parseInt(lastB));
            if (c != 0) return c;
        }
        return a.compareTo(b);
    }

    static ApiException bad(String message) { return new ApiException(HttpStatus.BAD_REQUEST, message); }
}
