package com.cenoffice.hub;

import java.io.IOException;
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
    public ResponseEntity<TemplateService.Info> get(@PathVariable Long id, Authentication a) {
        return templates.info(id, a.getName(), HubSkillController.admin(a)).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @DeleteMapping @ResponseStatus(HttpStatus.NO_CONTENT) @PreAuthorize(WRITE)
    public void delete(@PathVariable Long id, Authentication a) { templates.delete(id, a.getName(), HubSkillController.admin(a)); }
}
