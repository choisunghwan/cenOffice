package com.cenoffice.hub;

import static org.junit.jupiter.api.Assertions.*;

import com.cenoffice.api.ApiException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

/** 워드·엑셀·파워포인트 양식: 항목 찾기, 채우기, 미리보기 */
class OfficeFormsTest {
    static byte[] zip(String... namesAndBodies) throws Exception {
        var bos = new ByteArrayOutputStream();
        try (var z = new ZipOutputStream(bos)) {
            for (int i = 0; i < namesAndBodies.length; i += 2) {
                z.putNextEntry(new ZipEntry(namesAndBodies[i]));
                z.write(namesAndBodies[i + 1].getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    static String entry(byte[] zip, String name) throws Exception {
        try (var z = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) if (e.getName().equals(name)) return new String(z.readAllBytes(), StandardCharsets.UTF_8);
        }
        return null;
    }

    static List<String> keys(List<HwpxTemplate.Field> fields) { return fields.stream().map(HwpxTemplate.Field::key).toList(); }

    static String flat(List<HwpxTemplate.PreviewBlock> blocks) {
        var sb = new StringBuilder();
        for (var b : blocks) {
            if (b.text() != null) sb.append(b.text()).append('|');
            if (b.rows() != null) for (var row : b.rows()) for (var c : row) sb.append(c.text()).append('|');
        }
        return sb.toString();
    }

    // ───────────── 워드 ─────────────
    static final String W = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"";

    static byte[] docx() throws Exception {
        String body = "<w:document " + W + "><w:body>"
                + "<w:p><w:r><w:t>사업명: {{사업명}}</w:t></w:r></w:p>"
                + "<w:p><w:r><w:t>{{</w:t></w:r><w:r><w:t>담당자</w:t></w:r><w:r><w:t>}}</w:t></w:r></w:p>" // 워드는 자리표시자를 여러 조각으로 나누곤 한다
                + "<w:tbl><w:tr><w:tc><w:p><w:r><w:t>성명</w:t></w:r></w:p></w:tc><w:tc><w:p/></w:tc></w:tr>"
                + "<w:tr><w:tc><w:p><w:r><w:t>소속</w:t></w:r></w:p></w:tc><w:tc><w:p/></w:tc></w:tr>"
                + "<w:tr><w:tc><w:p><w:r><w:t>항목</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>내용</w:t></w:r></w:p></w:tc></w:tr></w:tbl>"
                + "</w:body></w:document>";
        return zip("[Content_Types].xml", "<Types/>", "word/document.xml", body);
    }

    @Test void docxFindsPlaceholdersAndLabelCells() throws Exception {
        assertEquals(List.of("사업명", "담당자", "성명", "소속"), keys(FormEngine.Registry.get("docx").analyze(docx())));
    }

    @Test void docxFillsSplitPlaceholderAndCells() throws Exception {
        var engine = FormEngine.Registry.get("docx");
        byte[] filled = engine.fill(docx(), Map.of("사업명", "AI 사업", "담당자", "홍길동", "성명", "김대리"));
        String text = flat(engine.preview(filled, Map.of()));
        assertTrue(text.contains("사업명: AI 사업|"), text);
        assertTrue(text.contains("홍길동|"), text); // 쪼개진 {{ 담당자 }} 도 한 번에 바뀐다
        assertTrue(text.contains("김대리|"), text);
        assertFalse(text.contains("{{"), text);
        assertNotNull(entry(filled, "[Content_Types].xml")); // 나머지 항목은 그대로
    }

    @Test void docxPreviewMarksFieldsAndKeepsEmptyOnes() throws Exception {
        var blocks = FormEngine.Registry.get("docx").preview(docx(), Map.of("성명", "김대리"));
        var table = blocks.stream().filter(b -> b.type().equals("table")).findFirst().orElseThrow();
        assertEquals("김대리", table.rows().get(0).get(1).text());
        assertEquals("성명", table.rows().get(0).get(1).fieldKey());
        assertEquals("", table.rows().get(1).get(1).text()); // 값이 없는 칸은 비어 있다
    }

    @Test void docxRejectsNonDocx() {
        assertEquals(400, assertThrows(ApiException.class, () -> FormEngine.Registry.get("docx").analyze("글자".getBytes(StandardCharsets.UTF_8))).status().value());
    }

    // ───────────── 파워포인트 ─────────────
    static final String P = "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\" xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"";

    static byte[] pptx() throws Exception {
        String slide = "<p:sld " + P + "><p:cSld><p:spTree>"
                + "<p:sp><p:txBody><a:p><a:r><a:t>제목: {{제목}}</a:t></a:r></a:p></p:txBody></p:sp>"
                + "<p:graphicFrame><a:graphic><a:graphicData><a:tbl>"
                + "<a:tr><a:tc><a:txBody><a:p><a:r><a:t>담당</a:t></a:r></a:p></a:txBody></a:tc><a:tc><a:txBody><a:p><a:endParaRPr lang=\"ko-KR\"/></a:p></a:txBody></a:tc></a:tr>"
                + "</a:tbl></a:graphicData></a:graphic></p:graphicFrame></p:spTree></p:cSld></p:sld>";
        String second = "<p:sld " + P + "><p:cSld><p:spTree><p:sp><p:txBody><a:p><a:r><a:t>기한 {{기한}}</a:t></a:r></a:p></p:txBody></p:sp></p:spTree></p:cSld></p:sld>";
        return zip("[Content_Types].xml", "<Types/>", "ppt/presentation.xml", "<p:presentation " + P + "/>", "ppt/slides/slide10.xml", second, "ppt/slides/slide2.xml", slide);
    }

    @Test void pptxFindsFieldsInSlideOrder() throws Exception {
        assertEquals(List.of("제목", "담당", "기한"), keys(FormEngine.Registry.get("pptx").analyze(pptx()))); // slide2 가 slide10 보다 먼저
    }

    @Test void pptxFillKeepsRunBeforeEndParaProps() throws Exception {
        var engine = FormEngine.Registry.get("pptx");
        byte[] filled = engine.fill(pptx(), Map.of("제목", "수주 제안", "담당", "홍길동", "기한", "금요일"));
        String text = flat(engine.preview(filled, Map.of()));
        assertTrue(text.contains("제목: 수주 제안|") && text.contains("홍길동|") && text.contains("기한 금요일|"), text);
        String xml = entry(filled, "ppt/slides/slide2.xml");
        int run = xml.indexOf("홍길동");
        assertTrue(run > 0 && run < xml.indexOf("endParaRPr"), xml); // 끝 서식보다 앞에 글이 들어가야 파워포인트가 연다
    }

    // ───────────── 엑셀 ─────────────
    static final String S = "xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"";

    static byte[] xlsx() throws Exception {
        String sheet = "<worksheet " + S + "><sheetData>"
                + "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c></row>" // 성명: 오른쪽 셀 자체가 없다
                + "<row r=\"2\"><c r=\"A2\" t=\"s\"><v>1</v></c><c r=\"B2\" s=\"5\"/></row>" // 소속: 서식만 있는 빈 셀
                + "<row r=\"3\"><c r=\"A3\" t=\"s\"><v>2</v></c><c r=\"B3\" t=\"inlineStr\"><is><t>010-0000</t></is></c></row>" // 이미 채워져 있으면 항목이 아니다
                + "<row r=\"4\"><c r=\"A4\" t=\"inlineStr\"><is><t>비고: {{비고}}</t></is></c></row>"
                + "<row r=\"5\"><c r=\"A5\"><v>123</v></c></row>" // 숫자 셀은 항목 이름이 아니다
                + "</sheetData></worksheet>";
        String shared = "<sst " + S + "><si><t>성명</t></si><si><t>소속</t></si><si><t>연락처</t></si></sst>";
        return zip("[Content_Types].xml", "<Types/>", "xl/workbook.xml", "<workbook " + S + "/>", "xl/sharedStrings.xml", shared, "xl/worksheets/sheet1.xml", sheet);
    }

    @Test void xlsxFindsLabelCellsAndPlaceholders() throws Exception {
        assertEquals(List.of("성명", "소속", "비고"), keys(FormEngine.Registry.get("xlsx").analyze(xlsx())));
    }

    @Test void xlsxFillsAndKeepsStyleAndOrder() throws Exception {
        var engine = FormEngine.Registry.get("xlsx");
        byte[] filled = engine.fill(xlsx(), Map.of("성명", "홍길동", "소속", "개발팀", "비고", "없음"));
        String text = flat(engine.preview(filled, Map.of()));
        assertTrue(text.contains("성명|홍길동|") && text.contains("소속|개발팀|") && text.contains("비고: 없음|"), text);
        String xml = entry(filled, "xl/worksheets/sheet1.xml");
        assertTrue(xml.contains("s=\"5\""), xml); // 빈 셀에 있던 서식은 그대로
        assertTrue(xml.indexOf("r=\"A1\"") < xml.indexOf("r=\"B1\""), xml); // 새로 만든 셀이 제자리에 들어간다
        assertNotNull(entry(filled, "xl/sharedStrings.xml"));
    }

    @Test void xlsxPreviewMarksTargetCells() throws Exception {
        var blocks = FormEngine.Registry.get("xlsx").preview(xlsx(), Map.of("성명", "홍길동"));
        var table = blocks.stream().filter(b -> b.type().equals("table")).findFirst().orElseThrow();
        assertEquals("홍길동", table.rows().get(0).get(1).text());
        assertEquals("성명", table.rows().get(0).get(1).fieldKey());
    }

    /** 번호 칸(1, 2, 3)은 항목 이름이 아니다. 번호가 매겨진 빈 표 때문에 항목이 수십 개로 늘어나면 안 된다. */
    @Test void numberedRowsAreNotFields() throws Exception {
        var hwpx = HwpxWriter.write("양식", List.of(new DocumentExporter.Block("table", 0, null,
                List.of(List.of("번호", "업무 내용"), List.of("1", ""), List.of("2", ""), List.of("3", ""), List.of("성명", "")))));
        assertEquals(List.of("성명"), keys(FormEngine.Registry.get("hwpx").analyze(hwpx)));
        String body = "<w:document " + W + "><w:body><w:tbl>"
                + "<w:tr><w:tc><w:p><w:r><w:t>번호</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>내용</w:t></w:r></w:p></w:tc></w:tr>"
                + "<w:tr><w:tc><w:p><w:r><w:t>1</w:t></w:r></w:p></w:tc><w:tc><w:p/></w:tc></w:tr>"
                + "<w:tr><w:tc><w:p><w:r><w:t>2</w:t></w:r></w:p></w:tc><w:tc><w:p/></w:tc></w:tr>"
                + "<w:tr><w:tc><w:p><w:r><w:t>담당자</w:t></w:r></w:p></w:tc><w:tc><w:p/></w:tc></w:tr></w:tbl></w:body></w:document>";
        assertEquals(List.of("담당자"), keys(FormEngine.Registry.get("docx").analyze(zip("[Content_Types].xml", "<Types/>", "word/document.xml", body))));
    }

    @Test void formsWithoutFieldsGetClearMessage() throws Exception {
        byte[] none = zip("[Content_Types].xml", "<Types/>", "xl/workbook.xml", "<workbook " + S + "/>", "xl/worksheets/sheet1.xml", "<worksheet " + S + "><sheetData/></worksheet>");
        var e = assertThrows(ApiException.class, () -> FormEngine.Registry.get("xlsx").analyze(none));
        assertTrue(e.getMessage().contains("채울 항목"), e.getMessage());
    }

    @Test void hostileXmlIsRejected() throws Exception {
        String evil = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><w:document " + W + "><w:body><w:p><w:r><w:t>&e;</w:t></w:r></w:p></w:body></w:document>";
        byte[] bad = zip("[Content_Types].xml", "<Types/>", "word/document.xml", evil);
        assertEquals(400, assertThrows(ApiException.class, () -> FormEngine.Registry.get("docx").analyze(bad)).status().value());
    }
}
