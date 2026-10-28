package com.cenoffice.hub;

import com.cenoffice.api.ApiException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** 결과물 양식 한 형식(한글·워드·엑셀·파워포인트)을 읽고 채우는 처리기 */
interface FormEngine {
    String format();           // hwpx / docx / xlsx / pptx
    String label();            // 화면에 보이는 이름
    String mime();             // 내려받을 때의 Content-Type
    List<HwpxTemplate.Field> analyze(byte[] data);
    byte[] fill(byte[] data, Map<String, String> values);
    List<HwpxTemplate.PreviewBlock> preview(byte[] data, Map<String, String> values);

    /** 형식 이름 → 처리기 */
    final class Registry {
        private Registry() {}

        static final List<FormEngine> ALL = List.of(new Hwpx(), OoxmlText.DOCX, XlsxTemplate.INSTANCE, OoxmlText.PPTX);

        static FormEngine get(String format) {
            String f = format == null ? "hwpx" : format.strip().toLowerCase();
            return ALL.stream().filter(e -> e.format().equals(f)).findFirst()
                    .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "지원하지 않는 양식 형식입니다: " + format));
        }

        /** 파일 이름의 확장자로 처리기를 찾는다. 모르는 확장자면 null */
        static FormEngine byFileName(String name) {
            String lower = name == null ? "" : name.strip().toLowerCase();
            return ALL.stream().filter(e -> lower.endsWith("." + e.format())).findFirst().orElse(null);
        }
    }

    /** 한글: 기존 HwpxTemplate 를 그대로 쓴다 */
    final class Hwpx implements FormEngine {
        public String format() { return "hwpx"; }
        public String label() { return "한글"; }
        public String mime() { return "application/vnd.hancom.hwpx"; }
        public List<HwpxTemplate.Field> analyze(byte[] data) { return HwpxTemplate.analyze(data); }
        public byte[] fill(byte[] data, Map<String, String> values) { return HwpxTemplate.fill(data, values); }
        public List<HwpxTemplate.PreviewBlock> preview(byte[] data, Map<String, String> values) { return HwpxTemplate.preview(data, values); }
    }
}
