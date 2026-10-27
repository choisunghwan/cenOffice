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
}
