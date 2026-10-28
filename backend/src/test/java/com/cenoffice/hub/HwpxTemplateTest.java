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

    /** 실제 회의록 양식 구조: 표 첫 줄의 항목, 날짜 틀, □ 선택지 칸, 선택지 문단, 번호 칸 */
    static byte[] minutesLikeForm() throws Exception {
        return form(List.of(
                p("보안등급:  □ 일반   □ 대외비   □ 기밀"),
                table(List.of(
                        List.of("회의명", ""),
                        List.of("일시", "20  .   .   .  (   )   :   ~   :", "장소", ""),
                        List.of("회의 구분", "□ 정기   □ 임시   □ 기타", "기록 방식", "□ 직접 작성   □ 녹취 요약"),
                        List.of("작성일", "2026-10-27 14:34"))),
                table(List.of(List.of("번호", "안건명"), List.of("1", ""), List.of("2", "")))));
    }

    @Test void firstRowDatetimeSkeletonAndChoiceCellsAreFields() throws Exception {
        var fields = HwpxTemplate.analyze(minutesLikeForm());
        var byKey = new java.util.LinkedHashMap<String, HwpxTemplate.Field>();
        for (var f : fields) byKey.put(f.key(), f);
        assertEquals(java.util.List.of("보안등급", "회의명", "일시", "장소", "회의 구분", "기록 방식"), new java.util.ArrayList<>(byKey.keySet()), byKey.toString());
        assertEquals("cell", byKey.get("회의명").kind()); // 표의 첫 줄이어도 채울 칸이다
        assertEquals("datetime", byKey.get("일시").kind());
        assertEquals("choice", byKey.get("회의 구분").kind());
        assertEquals(java.util.List.of("정기", "임시", "기타"), byKey.get("회의 구분").options());
        assertEquals(java.util.List.of("직접 작성", "녹취 요약"), byKey.get("기록 방식").options());
        assertEquals("choice", byKey.get("보안등급").kind()); // "라벨: □ … □ …" 문단
        assertFalse(byKey.containsKey("작성일")); // 이미 날짜가 적힌 칸은 건드리지 않는다
        assertFalse(byKey.containsKey("1")); // 번호 칸은 항목 이름이 아니다
    }

    @Test void fillMarksChosenOptionsAndWritesDatetime() throws Exception {
        var filled = HwpxTemplate.fill(minutesLikeForm(), java.util.Map.of("회의명", "팀 협업 회의", "일시", "2026-10-27 11:21", "회의 구분", "정기", "기록 방식", "녹취 요약", "보안등급", "일반"));
        var sb = new StringBuilder();
        for (var b : HwpxTemplate.preview(filled, java.util.Map.of())) {
            if (b.text() != null) sb.append(b.text()).append('|');
            if (b.rows() != null) for (var row : b.rows()) for (var c : row) sb.append(c.text()).append('|');
        }
        String all = sb.toString();
        assertTrue(all.contains("팀 협업 회의|"), all);
        assertTrue(all.contains("2026-10-27 11:21|"), all);
        assertTrue(all.contains("■ 정기") && all.contains("□ 임시") && all.contains("□ 기타"), all);
        assertTrue(all.contains("□ 직접 작성") && all.contains("■ 녹취 요약"), all);
        assertTrue(all.contains("보안등급: ■ 일반") || all.contains("■ 일반"), all);
        assertTrue(all.contains("□ 대외비") && all.contains("□ 기밀"), all);
    }

    @Test void unknownChoiceValueLeavesTheTextAlone() throws Exception {
        var filled = HwpxTemplate.fill(minutesLikeForm(), java.util.Map.of("회의 구분", "없는 선택지"));
        var sb = new StringBuilder();
        for (var b : HwpxTemplate.preview(filled, java.util.Map.of())) if (b.rows() != null) for (var row : b.rows()) for (var c : row) sb.append(c.text()).append('|');
        assertTrue(sb.toString().contains("□ 정기   □ 임시   □ 기타") || sb.toString().contains("□ 정기 □ 임시 □ 기타"), sb.toString());
    }

    @Test void pickOptionOnlyWhenExactlyOneOptionIsNamed() {
        assertEquals("정기", TemplateService.pickOption("정기 회의", java.util.List.of("정기", "임시", "기타")));
        assertNull(TemplateService.pickOption("정기 또는 임시", java.util.List.of("정기", "임시", "기타"))); // 둘이 걸리면 사람이 고른다
        assertNull(TemplateService.pickOption("관련 없는 말", java.util.List.of("정기", "임시", "기타")));
        assertNull(TemplateService.pickOption(null, java.util.List.of("정기", "임시")));
    }
}
