package com.cenoffice.hub;

import static org.junit.jupiter.api.Assertions.*;

import com.cenoffice.api.ApiException;
import com.cenoffice.hub.DocumentExporter.Block;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class HwpxTemplateTest {
    static byte[] form(List<Block> blocks) throws Exception { return HwpxWriter.write("양식", blocks); }

    static Block p(String text) { return new Block("p", 0, text, null); }

    static Block table(List<List<String>> rows) { return new Block("table", 0, null, rows); }

    static byte[] zip(String[] names, String[] bodies) throws Exception {
        var bos = new ByteArrayOutputStream();
        try (var z = new ZipOutputStream(bos)) {
            for (int i = 0; i < names.length; i++) {
                z.putNextEntry(new ZipEntry(names[i]));
                z.write(bodies[i].getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    @Test void analyzeFindsPlaceholdersAndLabelCells() throws Exception {
        var fields = HwpxTemplate.analyze(form(List.of(p("{{사업명}}"), table(List.of(List.of("항목", "내용"), List.of("성명", ""), List.of("소속", ""))))));
        assertEquals(List.of("사업명", "성명", "소속"), fields.stream().map(HwpxTemplate.Field::key).toList());
        assertEquals(List.of("placeholder", "cell", "cell"), fields.stream().map(HwpxTemplate.Field::kind).toList());
    }

    @Test void duplicateLabelsGetSuffix() throws Exception {
        var fields = HwpxTemplate.analyze(form(List.of(table(List.of(List.of("항목", "내용"), List.of("성명", ""), List.of("성명", ""))))));
        assertEquals(List.of("성명", "성명 (2)"), fields.stream().map(HwpxTemplate.Field::key).toList());
    }

    @Test void rejectsPlainBytes() {
        var e = assertThrows(ApiException.class, () -> HwpxTemplate.analyze("hello".getBytes(StandardCharsets.UTF_8)));
        assertEquals(400, e.status().value());
        assertTrue(e.getMessage().contains("hwpx"));
    }

    @Test void rejectsZipWithoutMimetype() throws Exception {
        assertEquals(400, assertThrows(ApiException.class, () -> HwpxTemplate.analyze(zip(new String[]{"Contents/section0.xml"}, new String[]{"<x/>"}))).status().value());
    }

    @Test void rejectsPathTraversalEntry() throws Exception {
        var bytes = zip(new String[]{"mimetype", "../evil.xml"}, new String[]{"application/hwp+zip", "<x/>"});
        assertEquals(400, assertThrows(ApiException.class, () -> HwpxTemplate.analyze(bytes)).status().value());
    }

    @Test void rejectsFormWithoutFields() throws Exception {
        var e = assertThrows(ApiException.class, () -> HwpxTemplate.analyze(form(List.of(p("그냥 문장입니다.")))));
        assertEquals(400, e.status().value());
        assertTrue(e.getMessage().contains("{{"));
    }

    @Test void rejectsZipThatExpandsTooLarge() throws Exception {
        var bos = new ByteArrayOutputStream();
        try (var z = new ZipOutputStream(bos)) {
            z.putNextEntry(new ZipEntry("mimetype")); z.write("application/hwp+zip".getBytes(StandardCharsets.UTF_8)); z.closeEntry();
            z.putNextEntry(new ZipEntry("Contents/section0.xml"));
            byte[] chunk = new byte[1024 * 1024];
            java.util.Arrays.fill(chunk, (byte) 'a');
            for (int i = 0; i < 31; i++) z.write(chunk);
            z.closeEntry();
        }
        assertEquals(400, assertThrows(ApiException.class, () -> HwpxTemplate.analyze(bos.toByteArray())).status().value());
    }

    // ───────────── Task 2: 채우기·미리보기 ─────────────

    static List<List<HwpxTemplate.PreviewCell>> firstTable(List<HwpxTemplate.PreviewBlock> blocks) {
        return blocks.stream().filter(b -> b.type().equals("table")).findFirst().orElseThrow().rows();
    }

    static byte[] sample() throws Exception {
        return form(List.of(p("{{사업명}}"), table(List.of(List.of("항목", "내용"), List.of("성명", ""), List.of("소속", "")))));
    }

    @Test void fillReplacesPlaceholderAndCells() throws Exception {
        var filled = HwpxTemplate.fill(sample(), java.util.Map.of("사업명", "AI 사업", "성명", "홍길동"));
        var blocks = HwpxTemplate.preview(filled, java.util.Map.of());
        assertTrue(blocks.stream().anyMatch(b -> b.type().equals("p") && b.text().equals("AI 사업")), blocks.toString());
        var rows = firstTable(blocks);
        assertEquals("홍길동", rows.get(1).get(1).text());
        assertEquals("", rows.get(2).get(1).text()); // 값이 없는 칸은 그대로 비어 있다
    }

    @Test void fillIgnoresUnknownKeys() throws Exception {
        var filled = HwpxTemplate.fill(sample(), java.util.Map.of("없는항목", "x"));
        assertEquals("", firstTable(HwpxTemplate.preview(filled, java.util.Map.of())).get(1).get(1).text());
    }

    @Test void fillHandlesPlaceholderSplitAcrossRuns() throws Exception {
        String hp = "http://www.hancom.co.kr/hwpml/2011/paragraph";
        String sec = "<hs:sec xmlns:hs=\"http://www.hancom.co.kr/hwpml/2011/section\" xmlns:hp=\"" + hp + "\"><hp:p><hp:run><hp:t>{{사업</hp:t></hp:run><hp:run><hp:t>명}}</hp:t></hp:run></hp:p></hs:sec>";
        var bytes = zip(new String[]{"mimetype", "Contents/section0.xml"}, new String[]{"application/hwp+zip", sec});
        assertEquals(List.of("사업명"), HwpxTemplate.analyze(bytes).stream().map(HwpxTemplate.Field::key).toList());
        var blocks = HwpxTemplate.preview(HwpxTemplate.fill(bytes, java.util.Map.of("사업명", "스마트 시티")), java.util.Map.of());
        assertEquals("스마트 시티", blocks.get(0).text());
    }

    @Test void fillEscapesSpecialCharsAndNewlines() throws Exception {
        var filled = HwpxTemplate.fill(sample(), java.util.Map.of("성명", "A & B <x>\n둘째줄"));
        assertEquals("A & B <x> 둘째줄", firstTable(HwpxTemplate.preview(filled, java.util.Map.of())).get(1).get(1).text());
    }

    @Test void fillKeepsMimetypeFirstAndStored() throws Exception {
        var filled = HwpxTemplate.fill(sample(), java.util.Map.of("성명", "홍"));
        try (var z = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(filled))) {
            var first = z.getNextEntry();
            assertEquals("mimetype", first.getName());
            assertEquals(java.util.zip.ZipEntry.STORED, first.getMethod());
        }
    }

    @Test void previewMarksFieldsAndShowsValues() throws Exception {
        var blocks = HwpxTemplate.preview(sample(), java.util.Map.of("성명", "홍"));
        var para = blocks.stream().filter(b -> b.type().equals("p") && "사업명".equals(b.fieldKey())).findFirst().orElseThrow();
        assertEquals("{{사업명}}", para.text()); // 채우지 않은 자리표시자는 그대로 보인다
        var rows = firstTable(blocks);
        assertEquals("성명", rows.get(1).get(1).fieldKey());
        assertEquals("홍", rows.get(1).get(1).text());
        assertNull(rows.get(1).get(0).fieldKey()); // 라벨 칸은 필드가 아니다
        assertEquals("소속", rows.get(2).get(1).fieldKey());
    }
}
