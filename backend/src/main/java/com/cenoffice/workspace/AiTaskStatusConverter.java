package com.cenoffice.workspace;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * 상태 값을 일반 문자열 컬럼으로 저장한다. @Enumerated(STRING) 은 Hibernate 가 허용 값 CHECK 제약을 만들고,
 * ddl-auto=update 는 이를 갱신하지 못해 새 상태를 추가할 때 기존 DB 에서 저장이 거부된다.
 */
@Converter
public class AiTaskStatusConverter implements AttributeConverter<WsEnums.AiTaskStatus, String> {
    @Override
    public String convertToDatabaseColumn(WsEnums.AiTaskStatus s) { return s == null ? null : s.name(); }

    @Override
    public WsEnums.AiTaskStatus convertToEntityAttribute(String v) { return v == null ? null : WsEnums.AiTaskStatus.valueOf(v); }
}
