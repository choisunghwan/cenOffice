package com.cenoffice.hub.form;

import com.cenoffice.hub.form.DocumentExporter.Block;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 한컴 한글 최신 문서 형식(HWPX = OWPML 을 담은 ZIP)을 만든다. 제목·문단·목록·표만 지원하는 최소 구현이다.
 * 구조는 한글이 저장하는 HWPX 의 필수 부분(mimetype, version, container, content.hpf, header, section0)만 담는다.
 */
final class HwpxWriter {
    private HwpxWriter() {}

    private static final String NS = "xmlns:ha=\"http://www.hancom.co.kr/hwpml/2011/app\" xmlns:hp=\"http://www.hancom.co.kr/hwpml/2011/paragraph\" "
            + "xmlns:hp10=\"http://www.hancom.co.kr/hwpml/2016/paragraph\" xmlns:hs=\"http://www.hancom.co.kr/hwpml/2011/section\" "
            + "xmlns:hc=\"http://www.hancom.co.kr/hwpml/2011/core\" xmlns:hh=\"http://www.hancom.co.kr/hwpml/2011/head\" "
            + "xmlns:hhs=\"http://www.hancom.co.kr/hwpml/2011/history\" xmlns:hm=\"http://www.hancom.co.kr/hwpml/2011/master-page\" "
            + "xmlns:hpf=\"http://www.hancom.co.kr/schema/2011/hpf\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:opf=\"http://www.idpf.org/2007/opf/\" "
            + "xmlns:ooxmlchart=\"http://www.hancom.co.kr/hwpml/2016/ooxmlchart\" xmlns:hwpunitchar=\"http://www.hancom.co.kr/hwpml/2016/HwpUnitChar\" "
            + "xmlns:epub=\"http://www.idpf.org/2007/ops\" xmlns:config=\"urn:oasis:names:tc:opendocument:xmlns:config:1.0\"";
    private static final String XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>";
    private static final int TEXT_WIDTH = 42520;
    private static final String PAPER = "#FFFFFF", HEADER_FILL = "#E6E6E6"; // 종이 바탕색 · 표 머리글 칸 바탕색

    static synchronized byte[] write(String title, List<Block> blocks) throws Exception {
        var bos = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bos)) {
            storedFirst(zip, "mimetype", "application/hwp+zip");
            put(zip, "version.xml", XML + "<hv:HCFVersion xmlns:hv=\"http://www.hancom.co.kr/hwpml/2011/version\" tagetApplication=\"WORDPROCESSOR\" major=\"5\" minor=\"0\" micro=\"5\" buildNumber=\"0\" os=\"1\" xmlVersion=\"1.4\" application=\"Hancom Office Hangul\" appVersion=\"9, 1, 1, 5656 WIN32LEWindows_Unknown_Version\"/>");
            put(zip, "settings.xml", XML + "<ha:HWPApplicationSetting " + NS + "><ha:CaretPosition listIDRef=\"0\" paraIDRef=\"0\" pos=\"0\"/></ha:HWPApplicationSetting>");
            put(zip, "META-INF/container.xml", XML + "<ocf:container xmlns:ocf=\"urn:oasis:names:tc:opendocument:xmlns:container\" xmlns:hpf=\"http://www.hancom.co.kr/schema/2011/hpf\"><ocf:rootfiles><ocf:rootfile full-path=\"Contents/content.hpf\" media-type=\"application/hwpml-package+xml\"/></ocf:rootfiles></ocf:container>");
            put(zip, "META-INF/manifest.xml", XML + "<odf:manifest xmlns:odf=\"urn:oasis:names:tc:opendocument:xmlns:manifest:1.0\"/>");
            put(zip, "Contents/content.hpf", XML + "<opf:package " + NS + " version=\"\" unique-identifier=\"\" id=\"\"><opf:metadata><opf:title>" + esc(title) + "</opf:title><opf:language>ko</opf:language></opf:metadata>"
                    + "<opf:manifest><opf:item id=\"header\" href=\"Contents/header.xml\" media-type=\"application/xml\"/><opf:item id=\"section0\" href=\"Contents/section0.xml\" media-type=\"application/xml\"/><opf:item id=\"settings\" href=\"settings.xml\" media-type=\"application/xml\"/></opf:manifest>"
                    + "<opf:spine><opf:itemref idref=\"header\" linear=\"yes\"/><opf:itemref idref=\"section0\" linear=\"yes\"/></opf:spine></opf:package>");
            put(zip, "Contents/header.xml", header());
            put(zip, "Contents/section0.xml", section(title, blocks));
        }
        return bos.toByteArray();
    }

    private static void storedFirst(ZipOutputStream zip, String name, String text) throws Exception {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        var e = new ZipEntry(name);
        e.setMethod(ZipEntry.STORED); e.setSize(data.length); e.setCompressedSize(data.length);
        var crc = new CRC32(); crc.update(data); e.setCrc(crc.getValue());
        zip.putNextEntry(e); zip.write(data); zip.closeEntry();
    }

    private static void put(ZipOutputStream zip, String name, String xml) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(xml.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                default -> { if (c >= 0x20 || c == '\t') b.append(c); }
            }
        }
        return b.toString();
    }

    // charPr: 0 본문, 1 제목1, 2 제목2, 3 표 머리글(굵게), 4 문서 제목
    /** shade: 글자 음영색. "none" 은 한글에서 검은 상자로 보이므로 쓰지 않고, 글자가 놓이는 바탕과 같은 색을 줘서 눈에 띄지 않게 한다(흰 종이는 흰색, 회색 머리글 칸은 그 회색). */
    private static String charPr(int id, int height, boolean bold, String color, String shade) {
        return "<hh:charPr id=\"" + id + "\" height=\"" + height + "\" textColor=\"" + color + "\" shadeColor=\"" + shade + "\" useFontSpace=\"0\" useKerning=\"0\" symMark=\"NONE\" borderFillIDRef=\"1\">"
                + "<hh:fontRef hangul=\"0\" latin=\"0\" hanja=\"0\" japanese=\"0\" other=\"0\" symbol=\"0\" user=\"0\"/>"
                + "<hh:ratio hangul=\"100\" latin=\"100\" hanja=\"100\" japanese=\"100\" other=\"100\" symbol=\"100\" user=\"100\"/>"
                + "<hh:spacing hangul=\"0\" latin=\"0\" hanja=\"0\" japanese=\"0\" other=\"0\" symbol=\"0\" user=\"0\"/>"
                + "<hh:relSz hangul=\"100\" latin=\"100\" hanja=\"100\" japanese=\"100\" other=\"100\" symbol=\"100\" user=\"100\"/>"
                + "<hh:offset hangul=\"0\" latin=\"0\" hanja=\"0\" japanese=\"0\" other=\"0\" symbol=\"0\" user=\"0\"/>"
                + (bold ? "<hh:bold/>" : "")
                + "<hh:underline type=\"NONE\" shape=\"SOLID\" color=\"#000000\"/><hh:strikeout shape=\"NONE\" color=\"#000000\"/><hh:outline type=\"NONE\"/><hh:shadow type=\"NONE\" color=\"#B2B2B2\" offsetX=\"10\" offsetY=\"10\"/></hh:charPr>";
    }

    private static String border(String type, String width) {
        return "type=\"" + type + "\" width=\"" + width + "\" color=\"#000000\"";
    }

    private static String header() {
        StringBuilder fonts = new StringBuilder("<hh:fontfaces itemCnt=\"7\">");
        for (String lang : new String[]{"HANGUL", "LATIN", "HANJA", "JAPANESE", "OTHER", "SYMBOL", "USER"})
            fonts.append("<hh:fontface lang=\"").append(lang).append("\" fontCnt=\"1\"><hh:font id=\"0\" face=\"맑은 고딕\" type=\"TTF\" isEmbedded=\"0\"/></hh:fontface>");
        fonts.append("</hh:fontfaces>");
        String slash = "<hh:slash type=\"NONE\" Crooked=\"0\" isCounter=\"0\"/><hh:backSlash type=\"NONE\" Crooked=\"0\" isCounter=\"0\"/>";
        String borderFills = "<hh:borderFills itemCnt=\"3\">"
                + "<hh:borderFill id=\"1\" threeD=\"0\" shadow=\"0\" centerLine=\"NONE\" breakCellSeparateLine=\"0\">" + slash
                + "<hh:leftBorder " + border("NONE", "0.1 mm") + "/><hh:rightBorder " + border("NONE", "0.1 mm") + "/><hh:topBorder " + border("NONE", "0.1 mm") + "/><hh:bottomBorder " + border("NONE", "0.1 mm") + "/><hh:diagonal type=\"SOLID\" width=\"0.1 mm\" color=\"#000000\"/></hh:borderFill>"
                + "<hh:borderFill id=\"2\" threeD=\"0\" shadow=\"0\" centerLine=\"NONE\" breakCellSeparateLine=\"0\">" + slash
                + "<hh:leftBorder " + border("SOLID", "0.12 mm") + "/><hh:rightBorder " + border("SOLID", "0.12 mm") + "/><hh:topBorder " + border("SOLID", "0.12 mm") + "/><hh:bottomBorder " + border("SOLID", "0.12 mm") + "/><hh:diagonal type=\"SOLID\" width=\"0.1 mm\" color=\"#000000\"/></hh:borderFill>"
                + "<hh:borderFill id=\"3\" threeD=\"0\" shadow=\"0\" centerLine=\"NONE\" breakCellSeparateLine=\"0\">" + slash
                + "<hh:leftBorder " + border("SOLID", "0.12 mm") + "/><hh:rightBorder " + border("SOLID", "0.12 mm") + "/><hh:topBorder " + border("SOLID", "0.12 mm") + "/><hh:bottomBorder " + border("SOLID", "0.12 mm") + "/><hh:diagonal type=\"SOLID\" width=\"0.1 mm\" color=\"#000000\"/>"
                + "<hc:fillBrush><hc:winBrush faceColor=\"" + HEADER_FILL + "\" hatchColor=\"#000000\" alpha=\"0\"/></hc:fillBrush></hh:borderFill></hh:borderFills>";
        String chars = "<hh:charProperties itemCnt=\"5\">" + charPr(0, 1000, false, "#000000", PAPER) + charPr(1, 1500, true, "#222222", PAPER) + charPr(2, 1200, true, "#222222", PAPER)
                + charPr(3, 1000, true, "#000000", HEADER_FILL) + charPr(4, 2000, true, "#222222", PAPER) + "</hh:charProperties>";
        String para = "<hh:paraProperties itemCnt=\"1\"><hh:paraPr id=\"0\" tabPrIDRef=\"0\" condense=\"0\" fontLineHeight=\"0\" snapToGrid=\"1\" suppressLineNumbers=\"0\" checked=\"0\">"
                + "<hh:align horizontal=\"JUSTIFY\" vertical=\"BASELINE\"/><hh:heading type=\"NONE\" idRef=\"0\" level=\"0\"/>"
                + "<hh:breakSetting breakLatinWord=\"KEEP_WORD\" breakNonLatinWord=\"KEEP_WORD\" widowOrphan=\"0\" keepWithNext=\"0\" keepLines=\"0\" pageBreakBefore=\"0\" lineWrap=\"BREAK\"/>"
                + "<hh:autoSpacing eAsianEng=\"0\" eAsianNum=\"0\"/>"
                + "<hh:margin><hc:intent value=\"0\" unit=\"HWPUNIT\"/><hc:left value=\"0\" unit=\"HWPUNIT\"/><hc:right value=\"0\" unit=\"HWPUNIT\"/><hc:prev value=\"0\" unit=\"HWPUNIT\"/><hc:next value=\"0\" unit=\"HWPUNIT\"/></hh:margin>"
                + "<hh:lineSpacing type=\"PERCENT\" value=\"160\" unit=\"HWPUNIT\"/>"
                + "<hh:border borderFillIDRef=\"1\" offsetLeft=\"0\" offsetRight=\"0\" offsetTop=\"0\" offsetBottom=\"0\" connect=\"0\" ignoreMargin=\"0\"/></hh:paraPr></hh:paraProperties>";
        String styles = "<hh:styles itemCnt=\"1\"><hh:style id=\"0\" type=\"PARA\" name=\"바탕글\" engName=\"Normal\" paraPrIDRef=\"0\" charPrIDRef=\"0\" nextStyleIDRef=\"0\" langID=\"1042\" lockForm=\"0\"/></hh:styles>";
        return XML + "<hh:head " + NS + " version=\"1.4\" secCnt=\"1\"><hh:beginNum page=\"1\" footnote=\"1\" endnote=\"1\" pic=\"1\" tbl=\"1\" equation=\"1\"/>"
                + "<hh:refList>" + fonts + borderFills + chars + "<hh:tabProperties itemCnt=\"1\"><hh:tabPr id=\"0\" autoTabLeft=\"0\" autoTabRight=\"0\"/></hh:tabProperties>" + para + styles + "</hh:refList>"
                + "<hh:compatibleDocument targetProgram=\"HWP201X\"><hh:layoutCompatibility/></hh:compatibleDocument><hh:docOption><hh:linkinfo path=\"\" pageInherit=\"0\" footnoteInherit=\"0\"/></hh:docOption></hh:head>";
    }

    private static int pid = 0;

    private static String p(int charPr, String text, String extraFirstRun) {
        return "<hp:p id=\"" + (pid++) + "\" paraPrIDRef=\"0\" styleIDRef=\"0\" pageBreak=\"0\" columnBreak=\"0\" merged=\"0\">"
                + "<hp:run charPrIDRef=\"" + charPr + "\">" + extraFirstRun + "<hp:t>" + esc(text) + "</hp:t></hp:run></hp:p>";
    }

    private static final String SEC_PR = "<hp:secPr id=\"\" textDirection=\"HORIZONTAL\" spaceColumns=\"1134\" tabStop=\"8000\" tabStopVal=\"4000\" tabStopUnit=\"HWPUNIT\" outlineShapeIDRef=\"0\" memoShapeIDRef=\"0\" textVerticalWidthHead=\"0\" masterPageCnt=\"0\">"
            + "<hp:grid lineGrid=\"0\" charGrid=\"0\" wonggojiFormat=\"0\"/><hp:startNum pageStartsOn=\"BOTH\" page=\"0\" pic=\"0\" tbl=\"0\" equation=\"0\"/>"
            + "<hp:visibility hideFirstHeader=\"0\" hideFirstFooter=\"0\" hideFirstMasterPage=\"0\" border=\"SHOW_ALL\" fill=\"SHOW_ALL\" hideFirstPageNum=\"0\" hideFirstEmptyLine=\"0\" showLineNumber=\"0\"/>"
            + "<hp:lineNumberShape restartType=\"0\" countBy=\"0\" distance=\"0\" startNumber=\"0\"/>"
            + "<hp:pagePr landscape=\"WIDELY\" width=\"59528\" height=\"84188\" gutterType=\"LEFT_ONLY\"><hp:margin header=\"4252\" footer=\"4252\" gutter=\"0\" left=\"8504\" right=\"8504\" top=\"5668\" bottom=\"4252\"/></hp:pagePr>"
            + "</hp:secPr><hp:ctrl><hp:colPr id=\"\" type=\"NEWSPAPER\" layout=\"LEFT\" colCount=\"1\" sameSz=\"1\" sameGap=\"0\"/></hp:ctrl>";

    private static String section(String title, List<Block> blocks) {
        pid = 0;
        StringBuilder s = new StringBuilder(XML + "<hs:sec " + NS + ">");
        s.append(p(4, title, SEC_PR));
        int tbl = 1;
        for (Block b : blocks) {
            switch (b.type()) {
                case "h" -> s.append(p(b.level() == 1 ? 1 : 2, b.text(), ""));
                case "li" -> s.append(p(0, (b.level() == 1 ? "" : "- ") + b.text(), ""));
                case "table" -> s.append(table(b.rows(), tbl++));
                default -> s.append(p(0, b.text(), ""));
            }
        }
        return s.append("</hs:sec>").toString();
    }

    private static String table(List<List<String>> rows, int id) {
        int cols = Math.max(1, rows.stream().mapToInt(List::size).max().orElse(1));
        int w = TEXT_WIDTH / cols, rowH = 1800;
        StringBuilder t = new StringBuilder();
        t.append("<hp:p id=\"").append(pid++).append("\" paraPrIDRef=\"0\" styleIDRef=\"0\" pageBreak=\"0\" columnBreak=\"0\" merged=\"0\"><hp:run charPrIDRef=\"0\">")
                .append("<hp:tbl id=\"").append(id).append("\" zOrder=\"0\" numberingType=\"TABLE\" textWrap=\"TOP_AND_BOTTOM\" textFlow=\"BOTH_SIDES\" lock=\"0\" dropcapstyle=\"None\" pageBreak=\"CELL\" repeatHeader=\"1\" rowCnt=\"")
                .append(rows.size()).append("\" colCnt=\"").append(cols).append("\" cellSpacing=\"0\" borderFillIDRef=\"2\" noAdjust=\"0\">")
                .append("<hp:sz width=\"").append(w * cols).append("\" widthRelTo=\"ABSOLUTE\" height=\"").append(rowH * rows.size()).append("\" heightRelTo=\"ABSOLUTE\" protect=\"0\"/>")
                .append("<hp:pos treatAsChar=\"0\" affectLSpacing=\"0\" flowWithText=\"1\" allowOverlap=\"0\" holdAnchorAndSO=\"0\" vertRelTo=\"PARA\" horzRelTo=\"COLUMN\" vertAlign=\"TOP\" horzAlign=\"LEFT\" vertOffset=\"0\" horzOffset=\"0\"/>")
                .append("<hp:outMargin left=\"0\" right=\"0\" top=\"0\" bottom=\"283\"/><hp:inMargin left=\"141\" right=\"141\" top=\"141\" bottom=\"141\"/>");
        for (int r = 0; r < rows.size(); r++) {
            t.append("<hp:tr>");
            for (int c = 0; c < cols; c++) {
                String text = c < rows.get(r).size() ? rows.get(r).get(c) : "";
                t.append("<hp:tc name=\"\" header=\"").append(r == 0 ? 1 : 0).append("\" hasMargin=\"0\" protect=\"0\" editable=\"0\" dirty=\"0\" borderFillIDRef=\"").append(r == 0 ? 3 : 2).append("\">")
                        .append("<hp:subList id=\"\" textDirection=\"HORIZONTAL\" lineWrap=\"BREAK\" vertAlign=\"CENTER\" linkListIDRef=\"0\" linkListNextIDRef=\"0\" textWidth=\"0\" textHeight=\"0\" hasTextRef=\"0\" hasNumRef=\"0\">")
                        .append(p(r == 0 ? 3 : 0, text, "")).append("</hp:subList>")
                        .append("<hp:cellAddr colAddr=\"").append(c).append("\" rowAddr=\"").append(r).append("\"/><hp:cellSpan colSpan=\"1\" rowSpan=\"1\"/>")
                        .append("<hp:cellSz width=\"").append(w).append("\" height=\"").append(rowH).append("\"/><hp:cellMargin left=\"141\" right=\"141\" top=\"141\" bottom=\"141\"/></hp:tc>");
            }
            t.append("</hp:tr>");
        }
        return t.append("</hp:tbl></hp:run></hp:p>").append(p(0, "", "")).toString();
    }
}
