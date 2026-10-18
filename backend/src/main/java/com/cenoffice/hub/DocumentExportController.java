package com.cenoffice.hub;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/hub/documents")
public class DocumentExportController {
    public record ExportRequest(String format, String title, String markdown) {}
    private final DocumentExporter exporter;
    public DocumentExportController(DocumentExporter exporter) { this.exporter = exporter; }

    /** 도구 결과(Markdown)를 xlsx/docx/pptx/pdf 로 변환해 내려준다. 결과는 저장하지 않는다. */
    @PostMapping("/export")
    @PreAuthorize("hasAnyRole('ADMIN','USER')")
    public ResponseEntity<byte[]> export(@RequestBody ExportRequest r) {
        var out = exporter.export(r.format() == null ? "" : r.format(), r.title(), r.markdown());
        String name = (r.title() == null || r.title().isBlank() ? "result" : r.title().strip().replaceAll("[\\/:*?\"<>|\r\n]", "_")) + "." + out.extension();
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(out.contentType()))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20"))
            .body(out.data());
    }
}
