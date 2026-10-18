package com.cenoffice.pm;

import com.cenoffice.pm.PmTypes.PlanRequirement;
import com.cenoffice.pm.PmTypes.PlanTask;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 사용자가 검토한 계획과 적용 시점의 계획이 같은지 확인하기 위한 해시. */
public final class PlanHash {
    private PlanHash() {}

    public static String of(ObjectMapper mapper, String spec, List<PlanRequirement> reqs, List<PlanTask> tasks) {
        try {
            Map<String, Object> canonical = new LinkedHashMap<>();
            canonical.put("spec", spec);
            canonical.put("requirements", reqs);
            canonical.put("tasks", tasks);
            byte[] json = mapper.writeValueAsString(canonical).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (Exception e) {
            throw new IllegalStateException("계획 해시 계산 실패", e);
        }
    }
}
