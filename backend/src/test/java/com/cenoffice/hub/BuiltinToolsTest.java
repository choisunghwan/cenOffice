package com.cenoffice.hub;

import static org.junit.jupiter.api.Assertions.*;
import com.cenoffice.hub.runtime.BuiltinRuntimeAdapter;
import com.cenoffice.hub.runtime.AgentRuntimeAdapter.RuntimeRequest;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class BuiltinToolsTest {
    byte[] zip(String... pairs) throws Exception {
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            for (int i = 0; i < pairs.length; i += 2) { zip.putNextEntry(new ZipEntry(pairs[i])); zip.write(pairs[i + 1].getBytes(StandardCharsets.UTF_8)); zip.closeEntry(); }
        }
        return out.toByteArray();
    }
    @Test void readsActualXlsxSharedAndInlineStringsAndDocx() throws Exception {
        var reader = new BusinessDocumentReader();
        var xlsx = zip("xl/workbook.xml", "<workbook/>", "xl/sharedStrings.xml", "<sst xmlns='urn:x'><si><t>고객 등록</t></si></sst>",
            "xl/worksheets/sheet1.xml", "<worksheet xmlns='urn:x'><sheetData><row><c t='s'><v>0</v></c><c t='inlineStr'><is><t>필수</t></is></c></row></sheetData></worksheet>");
        assertEquals("고객 등록 · 필수", reader.read("requirements.xlsx", xlsx));
        var docx = zip("word/document.xml", "<w:document xmlns:w='urn:word'><w:body><w:p><w:r><w:t>통합 고객관리</w:t></w:r></w:p></w:body></w:document>");
        assertEquals("통합 고객관리", reader.read("brief.docx", docx));
    }
    @Test void blocksTraversalXxeZipBombAndOversizedText() throws Exception {
        var reader = new BusinessDocumentReader();
        assertThrows(Exception.class, () -> reader.read("bad.docx", zip("../word/document.xml", "<x/>")));
        assertThrows(Exception.class, () -> reader.read("bad.docx", zip("word/document.xml", "<!DOCTYPE x [<!ENTITY a SYSTEM 'file:///etc/passwd'>]><x>&a;</x>")));
        assertThrows(Exception.class, () -> reader.read("bomb.docx", zip("word/document.xml", "a".repeat(3 * 1024 * 1024 + 1))));
        assertThrows(Exception.class, () -> reader.read("long.txt", "a".repeat(12001).getBytes(StandardCharsets.UTF_8)));
    }
    @Test void fpAnalyzesActualInputAndKeepsUncertainty() throws Exception {
        var runtime = new BuiltinRuntimeAdapter();
        var id = runtime.submit(new RuntimeRequest("1", "SKILL", "cen-fp-estimator", List.of(), "고객 등록\n고객 조회\n알 수 없는 기능", "u"));
        var result = runtime.poll(id);
        assertEquals("SUCCEEDED", result.state());
        assertTrue(result.output().contains("고객 등록 | EI"));
        assertTrue(result.output().contains("고객 조회 | EQ"));
        assertTrue(result.output().contains("확인 필요"));
        assertTrue(result.output().contains("FP 합계는 산출하지 않았습니다"));
    }
    @Test void proposalQuotesRealSourceAndDoesNotInventCustomer() throws Exception {
        var runtime = new BuiltinRuntimeAdapter();
        var id = runtime.submit(new RuntimeRequest("2", "SKILL", "cen-proposal-writer", List.of(), "고객정보 통합\n이력 조회", "u"));
        var out = runtime.poll(id).output();
        assertTrue(out.contains("고객정보 통합"));
        assertTrue(out.contains("자료 근거"));
        assertTrue(out.contains("규칙 기반"));
    }
    @Test void rejectsUnsupportedDocumentsAndInvalidUtf8() {
        var reader = new BusinessDocumentReader();
        assertThrows(Exception.class, () -> reader.read("attack.pdf", new byte[]{1,2,3}));
        assertThrows(Exception.class, () -> reader.read("test.txt", new byte[]{(byte)0xff}));
        assertThrows(Exception.class, () -> reader.read("bad.xlsx", "not a zip".getBytes()));
    }
}
