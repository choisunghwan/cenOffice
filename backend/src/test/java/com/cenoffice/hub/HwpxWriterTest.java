package com.cenoffice.hub;

import static org.junit.jupiter.api.Assertions.*;

import com.cenoffice.hub.DocumentExporter.Block;
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

    @Test void everyBorderFillStatesItsFill() throws Exception {
        String h = header();
        int fills = h.split("<hh:borderFill ", -1).length - 1;
        int brushes = h.split("<hc:fillBrush>", -1).length - 1;
        assertTrue(fills > 0);
        assertEquals(fills, brushes, "채움이 없는 borderFill 은 한글에서 검정으로 보인다");
    }

    @Test void colorsReadTheSameInRgbAndBgrOrder() throws Exception {
        // 한글이 색을 파랑·빨강 순서를 바꿔 읽는 PC 에서도 같은 색으로 보이도록 회색 계열만 쓴다(R=B)
        var m = Pattern.compile("(?:textColor|faceColor)=\"#([0-9A-Fa-f]{2})([0-9A-Fa-f]{2})([0-9A-Fa-f]{2})\"").matcher(header());
        int n = 0;
        while (m.find()) { assertEquals(m.group(1).toUpperCase(), m.group(3).toUpperCase(), m.group()); n++; }
        assertTrue(n > 0);
    }
}
