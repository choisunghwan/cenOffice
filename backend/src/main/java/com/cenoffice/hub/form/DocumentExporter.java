package com.cenoffice.hub.form;

import com.cenoffice.common.api.ApiException;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xslf.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 도구 결과(Markdown 부분집합: 제목·문단·목록·표)를 xlsx/docx/pptx/pdf 파일로 변환한다. 외부 호출 없음. */
@Component
public class DocumentExporter {
    public record Block(String type, int level, String text, List<List<String>> rows) {}
    public record Exported(byte[] data, String contentType, String extension) {}

    private final String configuredFont;
    public DocumentExporter(@Value("${office.docs.pdf-font:}") String configuredFont) { this.configuredFont = configuredFont; }

    // ───── Markdown 파싱 ─────
    static List<Block> parse(String md) {
        List<Block> out = new ArrayList<>();
        List<List<String>> table = null;
        StringBuilder para = new StringBuilder();
        Runnable flushPara = () -> { if (para.length() > 0) { out.add(new Block("p", 0, para.toString().trim(), null)); para.setLength(0); } };
        for (String raw : md.replace("\r", "").split("\n")) {
            String line = raw.strip();
            if (line.startsWith("|")) {
                flushPara.run();
                if (line.matches("^\\|[\\s:|-]+\\|?$")) continue; // 구분선
                String body = line.substring(1, line.endsWith("|") ? line.length() - 1 : line.length());
                List<String> cells = Arrays.stream(body.split("\\|", -1)).map(String::strip).toList();
                if (table == null) { table = new ArrayList<>(); out.add(new Block("table", 0, "", table)); }
                table.add(cells);
                continue;
            }
            table = null;
            if (line.isEmpty()) { flushPara.run(); continue; }
            if (line.startsWith("#")) {
                flushPara.run();
                int lv = 0; while (lv < line.length() && line.charAt(lv) == '#') lv++;
                out.add(new Block("h", Math.min(lv, 3), clean(line.substring(lv)), null));
            } else if (line.matches("^[-*•]\\s+.*")) { flushPara.run(); out.add(new Block("li", 0, clean(line.substring(1)), null)); }
            else if (line.matches("^\\d{1,3}[.)]\\s+.*")) { flushPara.run(); out.add(new Block("li", 1, clean(line), null)); }
            else { if (para.length() > 0) para.append(' '); para.append(clean(line)); }
        }
        flushPara.run();
        return out;
    }

    private static String clean(String s) { return s.strip().replace("**", "").replace("`", "").replace("_(", "(").replace(")_", ")"); }

    public Exported export(String format, String title, String markdown) {
        if (markdown == null || markdown.isBlank() || markdown.length() > 200_000) throw new ApiException(HttpStatus.BAD_REQUEST, "변환할 내용이 없거나 너무 깁니다");
        String t = title == null || title.isBlank() ? "결과" : title.strip().substring(0, Math.min(80, title.strip().length()));
        List<Block> blocks = parse(markdown);
        try {
            return switch (format) {
                case "xlsx" -> new Exported(xlsx(blocks), "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx");
                case "docx" -> new Exported(docx(t, blocks), "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx");
                case "pptx" -> new Exported(pptx(t, blocks), "application/vnd.openxmlformats-officedocument.presentationml.presentation", "pptx");
                case "hwpx" -> new Exported(HwpxWriter.write(t, blocks), "application/vnd.hancom.hwpx", "hwpx");
                case "pdf" -> new Exported(pdf(t, blocks), "application/pdf", "pdf");
                default -> throw new ApiException(HttpStatus.BAD_REQUEST, "지원 형식: xlsx, docx, pptx, pdf, hwpx");
            };
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "파일을 만들지 못했습니다"); }
    }

    /** 엑셀 수식 주입 방지: 문자열은 항상 문자열 셀로 쓰고, 수식 시작 문자는 앞에 ' 없이 문자열 타입으로만 저장한다. */
    private byte[] xlsx(List<Block> blocks) throws Exception {
        try (var wb = new XSSFWorkbook(); var bos = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("결과");
            CellStyle head = wb.createCellStyle();
            Font bold = wb.createFont(); bold.setBold(true); head.setFont(bold);
            head.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex()); head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            CellStyle wrap = wb.createCellStyle(); wrap.setWrapText(true); wrap.setVerticalAlignment(VerticalAlignment.TOP);
            int r = 0, maxCols = 1;
            for (Block b : blocks) {
                if (b.type().equals("table")) {
                    boolean first = true;
                    for (List<String> row : b.rows()) {
                        Row xr = sheet.createRow(r++);
                        for (int c = 0; c < row.size(); c++) { Cell cell = xr.createCell(c, CellType.STRING); cell.setCellValue(row.get(c)); cell.setCellStyle(first ? head : wrap); }
                        maxCols = Math.max(maxCols, row.size());
                        first = false;
                    }
                    r++;
                } else {
                    Row xr = sheet.createRow(r++);
                    Cell cell = xr.createCell(0, CellType.STRING);
                    cell.setCellValue((b.type().equals("li") ? "• " : "") + b.text());
                    if (b.type().equals("h")) cell.setCellStyle(head);
                    if (b.type().equals("p") || b.type().equals("li")) { cell.setCellStyle(wrap); }
                }
            }
            for (int c = 0; c < maxCols; c++) sheet.setColumnWidth(c, c == 0 ? 12000 : 9000);
            wb.write(bos);
            return bos.toByteArray();
        }
    }

    private byte[] docx(String title, List<Block> blocks) throws Exception {
        try (var doc = new XWPFDocument(); var bos = new ByteArrayOutputStream()) {
            run(doc.createParagraph(), title, 20, true, null);
            for (Block b : blocks) {
                switch (b.type()) {
                    case "h" -> run(doc.createParagraph(), b.text(), b.level() == 1 ? 17 : b.level() == 2 ? 14 : 12, true, "1A62D6");
                    case "li" -> run(doc.createParagraph(), (b.level() == 1 ? "" : "• ") + b.text(), 10.5, false, null);
                    case "table" -> {
                        List<List<String>> rows = b.rows();
                        int cols = rows.stream().mapToInt(List::size).max().orElse(1);
                        XWPFTable tb = doc.createTable(rows.size(), cols);
                        for (int i = 0; i < rows.size(); i++) for (int c = 0; c < cols; c++) {
                            XWPFTableCell cell = tb.getRow(i).getCell(c);
                            cell.removeParagraph(0);
                            run(cell.addParagraph(), c < rows.get(i).size() ? rows.get(i).get(c) : "", 10, i == 0, null);
                            if (i == 0) cell.setColor("E3EEFF");
                        }
                        doc.createParagraph();
                    }
                    default -> run(doc.createParagraph(), b.text(), 10.5, false, null);
                }
            }
            doc.write(bos);
            return bos.toByteArray();
        }
    }

    private static void run(XWPFParagraph p, String text, double size, boolean bold, String color) {
        XWPFRun r = p.createRun();
        r.setText(text); r.setFontSize((int) Math.round(size)); r.setBold(bold); r.setFontFamily("맑은 고딕");
        if (color != null) r.setColor(color);
    }

    private byte[] pptx(String title, List<Block> blocks) throws Exception {
        try (var ppt = new XMLSlideShow(); var bos = new ByteArrayOutputStream()) {
            ppt.setPageSize(new java.awt.Dimension(960, 540));
            XSLFSlide cover = ppt.createSlide();
            textBox(cover, title, 60, 190, 840, 90, 36, true, new Color(0x1A, 0x62, 0xD6));
            textBox(cover, "AI HUB 결과 초안 · 검토 후 사용하세요", 60, 290, 840, 40, 16, false, Color.GRAY);
            String heading = title;
            List<String> lines = new ArrayList<>();
            Runnable flush = () -> {};
            List<Object[]> slides = new ArrayList<>(); // [heading, lines, table]
            for (Block b : blocks) {
                if (b.type().equals("h")) {
                    if (!lines.isEmpty()) { slides.add(new Object[]{heading, new ArrayList<>(lines), null}); lines.clear(); }
                    heading = b.text();
                } else if (b.type().equals("table")) {
                    if (!lines.isEmpty()) { slides.add(new Object[]{heading, new ArrayList<>(lines), null}); lines.clear(); }
                    List<List<String>> rows = b.rows();
                    for (int i = 0; i < Math.max(1, rows.size()); i += 9) {
                        List<List<String>> part = new ArrayList<>();
                        part.add(rows.get(0));
                        part.addAll(rows.subList(Math.max(i, 1), Math.min(rows.size(), i + 9 + (i == 0 ? 0 : 0))));
                        slides.add(new Object[]{heading, List.of(), part});
                        if (rows.size() <= 9) break;
                    }
                } else {
                    lines.add((b.type().equals("li") ? "• " : "") + b.text());
                    if (lines.size() >= 8) { slides.add(new Object[]{heading, new ArrayList<>(lines), null}); lines.clear(); }
                }
            }
            if (!lines.isEmpty()) slides.add(new Object[]{heading, new ArrayList<>(lines), null});
            for (Object[] s : slides) {
                XSLFSlide sl = ppt.createSlide();
                textBox(sl, (String) s[0], 40, 24, 880, 50, 24, true, new Color(0x1A, 0x62, 0xD6));
                @SuppressWarnings("unchecked") List<String> ls = (List<String>) s[1];
                @SuppressWarnings("unchecked") List<List<String>> tb = (List<List<String>>) s[2];
                if (tb != null) {
                    int cols = tb.stream().mapToInt(List::size).max().orElse(1);
                    XSLFTable t = sl.createTable();
                    t.setAnchor(new java.awt.Rectangle(40, 90, 880, 40 * tb.size()));
                    for (int i = 0; i < tb.size(); i++) {
                        XSLFTableRow row = t.addRow(); row.setHeight(36);
                        for (int c = 0; c < cols; c++) {
                            XSLFTableCell cell = row.addCell();
                            XSLFTextRun r = cell.addNewTextParagraph().addNewTextRun();
                            r.setText(c < tb.get(i).size() ? tb.get(i).get(c) : ""); r.setFontSize(11.0); r.setFontFamily("맑은 고딕"); r.setBold(i == 0);
                            if (i == 0) cell.setFillColor(new Color(0xE3, 0xEE, 0xFF));
                        }
                    }
                } else {
                    textBox(sl, String.join("\n", ls), 50, 100, 860, 400, 18, false, new Color(0x20, 0x30, 0x47));
                }
            }
            ppt.write(bos);
            return bos.toByteArray();
        }
    }

    private static void textBox(XSLFSlide s, String text, int x, int y, int w, int h, double size, boolean bold, Color color) {
        XSLFTextBox box = s.createTextBox();
        box.setAnchor(new java.awt.Rectangle(x, y, w, h));
        box.setWordWrap(true);
        boolean first = true;
        for (String line : text.split("\n")) {
            XSLFTextParagraph p = first ? box.addNewTextParagraph() : box.addNewTextParagraph();
            first = false;
            XSLFTextRun r = p.addNewTextRun();
            r.setText(line); r.setFontSize(size); r.setBold(bold); r.setFontColor(color); r.setFontFamily("맑은 고딕");
        }
    }

    // ───── PDF (한글 폰트 필요) ─────
    private File findFont() {
        List<String> candidates = new ArrayList<>();
        if (!configuredFont.isBlank()) candidates.add(configuredFont);
        candidates.addAll(List.of("C:/Windows/Fonts/malgun.ttf", "/usr/share/fonts/truetype/nanum/NanumGothic.ttf", "/usr/share/fonts/nanum/NanumGothic.ttf",
                "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc", "/System/Library/Fonts/AppleSDGothicNeo.ttc", "/Library/Fonts/AppleGothic.ttf"));
        return candidates.stream().map(File::new).filter(File::isFile).findFirst().orElse(null);
    }

    private byte[] pdf(String title, List<Block> blocks) throws Exception {
        File fontFile = findFont();
        if (fontFile == null) throw new ApiException(HttpStatus.NOT_IMPLEMENTED, "PDF 한글 폰트를 찾을 수 없습니다. office.docs.pdf-font 에 TTF 경로를 설정하세요");
        var base = com.lowagie.text.pdf.BaseFont.createFont(fontFile.getPath() + (fontFile.getName().endsWith(".ttc") ? ",0" : ""), com.lowagie.text.pdf.BaseFont.IDENTITY_H, com.lowagie.text.pdf.BaseFont.EMBEDDED);
        var normal = new com.lowagie.text.Font(base, 10.5f);
        var bold = new com.lowagie.text.Font(base, 11f, com.lowagie.text.Font.BOLD, new Color(0x20, 0x30, 0x47));
        try (var bos = new ByteArrayOutputStream()) {
            var doc = new com.lowagie.text.Document(com.lowagie.text.PageSize.A4, 48, 48, 48, 48);
            com.lowagie.text.pdf.PdfWriter.getInstance(doc, bos);
            doc.open();
            var tf = new com.lowagie.text.Font(base, 20f, com.lowagie.text.Font.BOLD, new Color(0x1A, 0x62, 0xD6));
            var tp = new com.lowagie.text.Paragraph(title, tf); tp.setSpacingAfter(12); doc.add(tp);
            for (Block b : blocks) {
                switch (b.type()) {
                    case "h" -> { var p = new com.lowagie.text.Paragraph(b.text(), new com.lowagie.text.Font(base, b.level() == 1 ? 16f : 13f, com.lowagie.text.Font.BOLD, new Color(0x1A, 0x62, 0xD6))); p.setSpacingBefore(10); p.setSpacingAfter(4); doc.add(p); }
                    case "li" -> { var p = new com.lowagie.text.Paragraph((b.level() == 1 ? "" : "• ") + b.text(), normal); p.setIndentationLeft(10); p.setSpacingAfter(2); doc.add(p); }
                    case "table" -> {
                        int cols = b.rows().stream().mapToInt(List::size).max().orElse(1);
                        var t = new com.lowagie.text.pdf.PdfPTable(cols); t.setWidthPercentage(100); t.setSpacingBefore(4); t.setSpacingAfter(8);
                        for (int i = 0; i < b.rows().size(); i++) for (int c = 0; c < cols; c++) {
                            var cell = new com.lowagie.text.pdf.PdfPCell(new com.lowagie.text.Phrase(c < b.rows().get(i).size() ? b.rows().get(i).get(c) : "", i == 0 ? bold : normal));
                            cell.setPadding(4); if (i == 0) cell.setBackgroundColor(new Color(0xE3, 0xEE, 0xFF)); t.addCell(cell);
                        }
                        doc.add(t);
                    }
                    default -> { var p = new com.lowagie.text.Paragraph(b.text(), normal); p.setSpacingAfter(4); doc.add(p); }
                }
            }
            doc.close();
            return bos.toByteArray();
        }
    }
}
