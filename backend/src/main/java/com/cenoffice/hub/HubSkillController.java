package com.cenoffice.hub;


import com.cenoffice.api.ApiException;
import com.cenoffice.hub.HubDtos.*;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * AI HUB — Skill API. 조회는 인증된 사용자(가시 범위 내), 등록/수정은 USER·ADMIN(=제작자), 승인·반려·롤백은 ADMIN.
 * 접근 권한 검사는 서비스(공개 범위·소유자)에서도 한 번 더 수행한다.
 */
@RestController
@RequestMapping("/api/hub")
public class HubSkillController {
    private static final String WRITE = "hasAnyRole('ADMIN','USER')";
    private static final String ADMIN = "hasRole('ADMIN')";

    private final SkillService skills;

    public HubSkillController(SkillService skills) {
        this.skills = skills;
    }

    public static boolean admin(Authentication a) { return a.getAuthorities().stream().anyMatch(x -> x.getAuthority().equals("ROLE_ADMIN")); }

    private static List<String> csv(String s) {
        return s == null || s.isBlank() ? null : Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }

    @GetMapping("/categories")
    public List<CategoryView> categories() {
        return skills.categories();
    }

    /** 현재 사용자의 AI HUB 권한 요약 (CREATOR 는 별도 역할이 아니라 USER 가 등록한 리소스의 제작자) */
    @GetMapping("/me")
    public Map<String, Object> me(Authentication a) {
        return skills.profile(a.getName(), admin(a));
    }

    @GetMapping("/skills")
    public PageView<SkillSummary> list(@RequestParam(required = false) String q, @RequestParam(required = false) String category, @RequestParam(required = false) String owner,
                                       @RequestParam(required = false) String tag, @RequestParam(required = false) String status, @RequestParam(defaultValue = "false") boolean mine,
                                       @RequestParam(defaultValue = "recent") String sort, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "12") int size,
                                       Authentication a) {
        return skills.search(new SkillService.SearchQuery(q, category, owner, tag, status, mine, sort, page, size), a.getName(), admin(a));
    }

    @GetMapping("/skills/{id}")
    public SkillDetail get(@PathVariable Long id, @RequestParam(required = false) Long versionId, Authentication a) { return skills.detail(id, versionId, a.getName(), admin(a)); }

    @PostMapping("/skills") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize(WRITE)
    public SkillDetail create(@RequestBody RegisterRequest r, Authentication a) {
        var p = skills.parseMarkdown(r.skillMd(), a.getName());
        return skills.register(a.getName(), p, r.category(), r.tags(), r.visibility(), Boolean.TRUE.equals(r.submit()));
    }

    @PostMapping(value = "/skills/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE) @ResponseStatus(HttpStatus.CREATED) @PreAuthorize(WRITE)
    public SkillDetail upload(@RequestParam("file") MultipartFile file, @RequestParam(required = false) String category, @RequestParam(required = false) String tags,
                              @RequestParam(required = false) String visibility, @RequestParam(defaultValue = "false") boolean submit, Authentication a) throws IOException {
        var p = skills.parseUpload(file.getOriginalFilename(), file.getBytes(), a.getName());
        return skills.register(a.getName(), p, category, csv(tags), visibility, submit);
    }

    @PutMapping("/skills/{id}") @PreAuthorize(WRITE)
    public SkillDetail update(@PathVariable Long id, @RequestBody MetaUpdate m, Authentication a) { return skills.updateMeta(id, a.getName(), admin(a), m); }

    @DeleteMapping("/skills/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) @PreAuthorize(WRITE)
    public void archive(@PathVariable Long id, Authentication a) { skills.archive(id, a.getName(), admin(a)); }

    @GetMapping("/skills/{id}/versions")
    public List<VersionView> versions(@PathVariable Long id, Authentication a) { return skills.versions(id, a.getName(), admin(a)); }

    @PostMapping("/skills/{id}/versions") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize(WRITE)
    public SkillDetail addVersion(@PathVariable Long id, @RequestBody RegisterRequest r, Authentication a) {
        return skills.addVersion(id, a.getName(), admin(a), skills.parseMarkdown(r.skillMd(), a.getName()), Boolean.TRUE.equals(r.submit()));
    }

    @PostMapping(value = "/skills/{id}/versions/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE) @ResponseStatus(HttpStatus.CREATED) @PreAuthorize(WRITE)
    public SkillDetail addVersionUpload(@PathVariable Long id, @RequestParam("file") MultipartFile file, @RequestParam(defaultValue = "false") boolean submit,
                                        Authentication a) throws IOException {
        return skills.addVersion(id, a.getName(), admin(a), skills.parseUpload(file.getOriginalFilename(), file.getBytes(), a.getName()), submit);
    }

    @PostMapping("/skills/{id}/versions/{vid}/submit") @PreAuthorize(WRITE)
    public SkillDetail submit(@PathVariable Long id, @PathVariable Long vid, Authentication a) { return skills.submitVersion(id, vid, a.getName(), admin(a)); }

    @PostMapping("/skills/{id}/versions/{vid}/approve") @PreAuthorize(ADMIN)
    public SkillDetail approve(@PathVariable Long id, @PathVariable Long vid, @RequestBody(required = false) CommentRequest c, Authentication a) {
        return skills.approve(id, vid, a.getName(), c == null ? null : c.comment());
    }

    @PostMapping("/skills/{id}/versions/{vid}/reject") @PreAuthorize(ADMIN)
    public SkillDetail reject(@PathVariable Long id, @PathVariable Long vid, @RequestBody(required = false) CommentRequest c, Authentication a) {
        return skills.reject(id, vid, a.getName(), c == null ? null : c.comment());
    }

    @PostMapping("/skills/{id}/rollback") @PreAuthorize(ADMIN)
    public SkillDetail rollback(@PathVariable Long id, @RequestBody RollbackRequest r, Authentication a) {
        if (r.versionId() == null) throw new ApiException(HttpStatus.BAD_REQUEST, "versionId 가 필요합니다");
        return skills.rollback(id, r.versionId(), a.getName(), r.comment());
    }

    @GetMapping("/skills/{id}/history")
    public List<HistoryView> history(@PathVariable Long id, Authentication a) { return skills.history(id, a.getName(), admin(a)); }

    @GetMapping("/review-queue") @PreAuthorize(ADMIN)
    public List<Map<String, Object>> reviewQueue() { return skills.reviewQueue(); }

    @GetMapping("/skills/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable Long id, @RequestParam(required = false) Long versionId, Authentication a) {
        var d = skills.download(id, versionId, a.getName(), admin(a));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(d.filename()).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(d.contentType()))
                .body(d.data());
    }

    @GetMapping("/skills/{id}/reviews")
    public List<ReviewView> reviews(@PathVariable Long id, Authentication a) { return skills.reviews(id, a.getName(), admin(a)); }

    @PostMapping("/skills/{id}/reviews") @PreAuthorize(WRITE)
    public ReviewView review(@PathVariable Long id, @RequestBody ReviewRequest r, Authentication a) { return skills.review(id, a.getName(), admin(a), r.rating(), r.comment()); }
}
