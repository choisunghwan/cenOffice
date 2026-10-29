package com.cenoffice;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.data.repository.Repository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.RestController;

/** 구조 규칙: Controller → Service → Repository/JdbcTemplate. 컨트롤러가 DB 접근 도구를 직접 갖지 않는다. */
class LayeringTest {
    @Test
    void controllersDoNotTouchTheDatabaseDirectly() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<String> violations = new ArrayList<>();
        var found = scanner.findCandidateComponents("com.cenoffice");
        assertTrue(found.size() > 10, "컨트롤러를 찾지 못했습니다(검사가 비어 있음): " + found.size());
        for (BeanDefinition bd : found) {
            Class<?> c = Class.forName(bd.getBeanClassName());
            for (Field f : c.getDeclaredFields()) {
                if (Repository.class.isAssignableFrom(f.getType()) || JdbcTemplate.class.isAssignableFrom(f.getType()))
                    violations.add(c.getSimpleName() + "." + f.getName());
            }
        }
        assertTrue(violations.isEmpty(), "컨트롤러가 DB를 직접 사용합니다. 서비스를 거치게 하세요: " + violations);
    }
}
