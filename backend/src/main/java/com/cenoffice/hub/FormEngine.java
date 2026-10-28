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
            return ALL.stream().filter(e -> e.format().equals(f)).findFirst().<FormEngine>map(Guarded::new)
                    .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "지원하지 않는 양식 형식입니다: " + format));
        }

        /** 파일 이름의 확장자로 처리기를 찾는다. 모르는 확장자면 null */
        static FormEngine byFileName(String name) {
            String lower = name == null ? "" : name.strip().toLowerCase();
            return ALL.stream().filter(e -> lower.endsWith("." + e.format())).findFirst().<FormEngine>map(Guarded::new).orElse(null);
        }
    }

    /** 아주 깊게 중첩된 문서로 서버 스레드가 죽지 않도록, 스택이 넘치면 "너무 복잡한 문서"(400)로 바꿔 돌려준다 */
    final class Guarded implements FormEngine {
        private final FormEngine inner;
        Guarded(FormEngine inner) { this.inner = inner; }
        public String format() { return inner.format(); }
        public String label() { return inner.label(); }
        public String mime() { return inner.mime(); }
        private static ApiException tooComplex() { return new ApiException(HttpStatus.BAD_REQUEST, "문서 구조가 너무 복잡해서 읽을 수 없습니다. 단순한 양식으로 저장해 올려 주세요."); }
        public List<HwpxTemplate.Field> analyze(byte[] data) { try { return inner.analyze(data); } catch (StackOverflowError e) { throw tooComplex(); } }
        public byte[] fill(byte[] data, Map<String, String> values) { try { return inner.fill(data, values); } catch (StackOverflowError e) { throw tooComplex(); } }
        public List<HwpxTemplate.PreviewBlock> preview(byte[] data, Map<String, String> values) { try { return inner.preview(data, values); } catch (StackOverflowError e) { throw tooComplex(); } }
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
