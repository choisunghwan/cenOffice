package com.cenoffice.hub;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** 스킬의 결과물 양식(hwpx): 등록·조회·삭제 */
@RestController
@RequestMapping("/api/hub/skills/{id}/template")
public class TemplateController {
    private static final String WRITE = "hasAnyRole('ADMIN','USER')";
    private final TemplateService templates;

    public TemplateController(TemplateService templates) { this.templates = templates; }

    @PutMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE) @PreAuthorize(WRITE)
    public TemplateService.Info put(@PathVariable Long id, @RequestParam("file") MultipartFile file, Authentication a) throws IOException {
        return templates.put(id, a.getName(), HubSkillController.admin(a), file.getOriginalFilename(), file.getBytes());
    }

    @GetMapping
    public ResponseEntity<TemplateService.Info> get(@PathVariable Long id, @RequestParam(defaultValue = "hwpx") String format, Authentication a) {
        return templates.info(id, a.getName(), HubSkillController.admin(a), format).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** 형식별 칸(한글·워드·엑셀·파워포인트): 지원 여부와 올려 둔 양식 */
    @GetMapping("/formats")
    public List<TemplateService.Slot> formats(@PathVariable Long id, Authentication a) { return templates.slots(id, a.getName(), HubSkillController.admin(a)); }

    @DeleteMapping @ResponseStatus(HttpStatus.NO_CONTENT) @PreAuthorize(WRITE)
    public void delete(@PathVariable Long id, @RequestParam(defaultValue = "hwpx") String format, Authentication a) { templates.delete(id, a.getName(), HubSkillController.admin(a), format); }

    public record PlanRequest(String format, String result) {}
    public record DraftRequest(String format, String result, String key) {}
    public record DraftResponse(String value) {}
    public record ValuesRequest(String format, Map<String, String> values) {}
    public record PreviewResponse(List<HwpxTemplate.PreviewBlock> blocks) {}
    public record RenderRequest(String format, Map<String, String> values, String title) {}

    /** 스킬 결과에서 양식 항목별 값을 찾는다. 근거가 없는 항목은 MISSING 으로 비워 둔다. */
    @PostMapping("/plan") @PreAuthorize(WRITE)
    public TemplateService.Plan plan(@PathVariable Long id, @RequestBody PlanRequest r, Authentication a) { return templates.plan(id, a.getName(), HubSkillController.admin(a), r.format(), r.result()); }

    /** 칸 하나의 AI 초안 */
    @PostMapping("/draft") @PreAuthorize(WRITE)
    public DraftResponse draft(@PathVariable Long id, @RequestBody DraftRequest r, Authentication a) { return new DraftResponse(templates.draft(id, a.getName(), HubSkillController.admin(a), r.format(), r.result(), r.key())); }

    @PostMapping("/preview") @PreAuthorize(WRITE)
    public PreviewResponse preview(@PathVariable Long id, @RequestBody ValuesRequest r, Authentication a) { return new PreviewResponse(templates.preview(id, a.getName(), HubSkillController.admin(a), r.format(), r.values())); }

    /** 값을 채운 hwpx 파일 내려받기 */
    @PostMapping("/render") @PreAuthorize(WRITE)
    public ResponseEntity<byte[]> render(@PathVariable Long id, @RequestBody RenderRequest r, Authentication a) {
        FormEngine engine = FormEngine.Registry.get(r.format());
        byte[] data = templates.render(id, a.getName(), HubSkillController.admin(a), engine.format(), r.values());
        String title = r.title() == null || r.title().isBlank() ? "result" : r.title().strip().replaceAll("[\\\\/:*?\"<>|\r\n]", "_");
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(engine.mime()))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + URLEncoder.encode(title + "." + engine.format(), StandardCharsets.UTF_8).replace("+", "%20"))
            .body(data);
    }
}
