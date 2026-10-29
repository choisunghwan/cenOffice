package com.cenoffice.hub.form;

import static org.junit.jupiter.api.Assertions.*;

import com.cenoffice.hub.form.DocumentExporter.Block;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;

/** 새로 만드는 hwpx 가 한글에서 글자가 검은 상자로 덮이거나 색이 뒤바뀌어 보이지 않도록 header.xml 의 약속을 확인한다. */
class HwpxWriterTest {
    static String header() throws Exception {
        byte[] hwpx = HwpxWriter.write("제목", List.of(new Block("h", 1, "소제목", null), new Block("table", 0, null, List.of(List.of("항목", "내용"), List.of("성명", "홍길동")))));
        try (var z = new ZipInputStream(new ByteArrayInputStream(hwpx))) {
            for (var e = z.getNextEntry(); e != null; e = z.getNextEntry())
                if (e.getName().equals("Contents/header.xml")) return new String(z.readAllBytes(), StandardCharsets.UTF_8);
        }
        return fail("header.xml 이 없다");
    }

    @Test void charShadeIsExplicitWhiteNotTheWordNone() throws Exception {
        String h = header();
        assertFalse(h.contains("shadeColor=\"none\""), "글자 음영이 none 이면 한글이 검정으로 칠한다");
        assertTrue(h.contains("shadeColor=\"#FFFFFF\""));
    }

    /** 표 머리글 칸은 회색 바탕이라, 그 글자의 음영색이 흰색이면 글자 뒤에 흰 상자가 보인다 → 칸 바탕과 같은 색이어야 한다 */
    @Test void headerTextShadeMatchesItsCellFill() throws Exception {
        String h = header();
        String fill = java.util.regex.Pattern.compile("winBrush faceColor=\"(#[0-9A-Fa-f]{6})\"").matcher(h).results().map(m -> m.group(1)).findFirst().orElseThrow();
        var header = java.util.regex.Pattern.compile("<hh:charPr id=\"3\"[^>]*shadeColor=\"(#[0-9A-Fa-f]{6})\"").matcher(h);
        assertTrue(header.find(), h);
        assertEquals(fill, header.group(1));
        var body = java.util.regex.Pattern.compile("<hh:charPr id=\"0\"[^>]*shadeColor=\"(#[0-9A-Fa-f]{6})\"").matcher(h);
        assertTrue(body.find());
        assertEquals("#FFFFFF", body.group(1)); // 본문은 흰 종이 위
    }

    @Test void noColorValueIsTheWordNone() throws Exception {
        // 한글(확인한 PC)은 shadeColor·faceColor 의 "none" 을 검정으로 읽어 글자나 문단 전체가 검게 칠해진다
        String h = header();
        assertFalse(h.contains("shadeColor=\"none\""));
        assertFalse(h.contains("faceColor=\"none\""));
    }

    @Test void colorsReadTheSameInRgbAndBgrOrder() throws Exception {
        // 한글이 색을 파랑·빨강 순서를 바꿔 읽는 PC 에서도 같은 색으로 보이도록 회색 계열만 쓴다(R=B)
        var m = Pattern.compile("(?:textColor|faceColor)=\"#([0-9A-Fa-f]{2})([0-9A-Fa-f]{2})([0-9A-Fa-f]{2})\"").matcher(header());
        int n = 0;
        while (m.find()) { assertEquals(m.group(1).toUpperCase(), m.group(3).toUpperCase(), m.group()); n++; }
        assertTrue(n > 0);
    }
}
