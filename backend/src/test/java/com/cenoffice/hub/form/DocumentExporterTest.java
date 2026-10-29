package com.cenoffice.hub.form;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;

class DocumentExporterTest {
    static final String MD = "# 기능 분류 검토표\n\n규칙 기반 초안입니다.\n\n| 원문 기능 | 유형 |\n|---|---|\n| 고객 등록 | EI |\n| =SUM(A1) | EQ |\n\n## 추가 확인\n- 항목 하나\n- 항목 둘\n";
    DocumentExporter ex = new DocumentExporter("");

    @Test void xlsxKeepsTableAndFormulaTextAsString() throws Exception {
        var out = ex.export("xlsx", "검토표", MD);
        try (var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new ByteArrayInputStream(out.data()))) {
            var s = wb.getSheetAt(0);
            boolean found = false;
            for (var row : s) for (var c : row) if (c.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING && c.getStringCellValue().equals("=SUM(A1)")) found = true;
            assertTrue(found, "수식처럼 보이는 값도 문자열 셀이어야 한다");
        }
    }

    @Test void docxContainsHeadingTableAndBullets() throws Exception {
        var out = ex.export("docx", "검토표", MD);
        try (var d = new org.apache.poi.xwpf.usermodel.XWPFDocument(new ByteArrayInputStream(out.data()))) {
            assertEquals(1, d.getTables().size());
            assertTrue(d.getParagraphs().stream().anyMatch((p) -> p.getText().contains("추가 확인")));
        }
    }

    @Test void pptxHasCoverAndContentSlides() throws Exception {
        var out = ex.export("pptx", "검토표", MD);
        try (var p = new org.apache.poi.xslf.usermodel.XMLSlideShow(new ByteArrayInputStream(out.data()))) {
            assertTrue(p.getSlides().size() >= 3);
        }
    }

    @Test void pdfIsValidWhenKoreanFontExists() {
        var out = ex.export("pdf", "검토표", MD);
        assertEquals("%PDF", new String(out.data(), 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
    }

    @Test void hwpxIsZipWithStoredMimetypeFirstAndWellFormedXml() throws Exception {
        var out = ex.export("hwpx", "검토표", MD + "\n특수문자 <&>\n");
        try (var in = new java.util.zip.ZipInputStream(new ByteArrayInputStream(out.data()))) {
            var first = in.getNextEntry();
            assertEquals("mimetype", first.getName());
            assertEquals("application/hwp+zip", new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            var dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            java.util.zip.ZipEntry e;
            int xmlParts = 0;
            while ((e = in.getNextEntry()) != null) {
                byte[] data = in.readAllBytes();
                if (e.getName().endsWith(".xml") || e.getName().endsWith(".hpf")) { dbf.newDocumentBuilder().parse(new ByteArrayInputStream(data)); xmlParts++; }
                if (e.getName().equals("Contents/section0.xml")) assertTrue(new String(data, java.nio.charset.StandardCharsets.UTF_8).contains("&lt;&amp;&gt;"));
            }
            assertTrue(xmlParts >= 6);
        }
    }

    @Test void rejectsUnknownFormatAndEmptyInput() {
        assertThrows(com.cenoffice.api.ApiException.class, () -> ex.export("exe", "x", MD));
        assertThrows(com.cenoffice.api.ApiException.class, () -> ex.export("xlsx", "x", " "));
    }
}
