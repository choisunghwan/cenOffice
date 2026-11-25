# 예시: div 로 만든 모달

## 입력
버튼을 누르면 `.modal` div 를 `display: block` 으로 켜고, keydown 에서 ESC 를 직접 처리하고,
바깥 클릭을 감지해 닫고, 열릴 때 첫 입력칸에 포커스를 옮기는 코드(약 40줄).

## 결과
**요약**: 바꿀 곳 1군데 — `<dialog>` 로 바꾸면 약 30줄이 줄어듭니다.

| 지금 코드 | 바꿀 방식 | 좋아지는 점 | 브라우저 지원 |
|---|---|---|---|
| div 모달 + ESC·포커스 직접 처리 | `<dialog>` + `showModal()` | ESC 닫기·포커스 가두기·배경 막기 기본 제공 | 모두 지원 |

```html
<dialog id="edit"><form method="dialog"> … <button>닫기</button></form></dialog>
<button onclick="edit.showModal()">편집</button>
```
