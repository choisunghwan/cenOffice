package com.cenoffice.hub;

import com.cenoffice.api.ApiException;
import com.cenoffice.hub.HubDtos.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/hub/builtins")
public class BuiltinToolController {
    private final HubSkillRepository skills;
    private final SkillService registry;
    private final BusinessDocumentReader reader;
    private final ExecutionService executions;
    public BuiltinToolController(HubSkillRepository skills, SkillService registry, BusinessDocumentReader reader, ExecutionService executions) {
        this.skills = skills; this.registry = registry; this.reader = reader; this.executions = executions;
    }
    @GetMapping
    public List<SkillDetail> list(Authentication a) {
        return BuiltinSkillSeeder.DOCUMENT_TOOLS.stream().map(skills::findByName).flatMap(Optional::stream).filter(BuiltinSkillSeeder::trusted)
            .map(s -> registry.detail(s.id, null, a.getName(), HubSkillController.admin(a))).toList();
    }
    @PostMapping(value = "/{id}/documents", consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.ACCEPTED) @PreAuthorize("hasAnyRole('ADMIN','USER')")
    public ExecutionView run(@PathVariable Long id, @RequestParam("files") List<MultipartFile> files,
                             @RequestParam(defaultValue = "") String instructions, Authentication a) throws java.io.IOException {
        var s = registry.visible(id, a.getName(), HubSkillController.admin(a));
        if (!BuiltinSkillSeeder.trusted(s) || !BuiltinSkillSeeder.DOCUMENT_TOOLS.contains(s.name)) throw new ApiException(HttpStatus.BAD_REQUEST, "내장 도구만 지원합니다");
        if (files.isEmpty() || files.size() > 3 || instructions.length() > 2000)
            throw new ApiException(HttpStatus.BAD_REQUEST, "자료 1~3개와 2,000자 이하 요청을 입력하세요");
        StringBuilder input = new StringBuilder();
        int chars = 0; long lines = 0;
        for (var file : files) {
            String text = reader.read(file.getOriginalFilename(), file.getBytes());
            chars += text.length(); lines += text.lines().count();
            if (chars > 12000 || lines > 80) throw new ApiException(HttpStatus.BAD_REQUEST, "전체 자료를 12,000자·80행 이하로 나누어 주세요");
            String name = Objects.toString(file.getOriginalFilename(), "자료").replaceAll("[\\r\\n\\[\\]]", "_");
            input.append("[자료: ").append(name).append("]\n").append(text).append('\n');
        }
        if (!instructions.isBlank()) input.append("[추가 요청]\n").append(instructions);
        return executions.requestDocuments(a.getName(), HubSkillController.admin(a), new ExecuteRequest(id, null, input.toString()));
    }
}
