# 옛 방식 → 요즘 방식

## 대화상자·팝업
| 옛 방식 | 요즘 방식 | 좋아지는 점 |
|---|---|---|
| div 모달 + 직접 만든 포커스 가두기·ESC·배경 클릭 처리 | `<dialog>` + `showModal()` | 포커스·ESC·배경 막기를 브라우저가 처리 |
| 펼침 메뉴·툴팁을 JS 로 위치 계산해 띄우기 | `popover` 속성(+ 필요하면 앵커 위치 지정) | 바깥 클릭 닫기·맨 위 표시 기본 제공 |
| 직접 만든 펼치기·접기 | `<details>`/`<summary>` | 키보드·화면 읽기 기본 지원 |

## 레이아웃
| 옛 방식 | 요즘 방식 | 좋아지는 점 |
|---|---|---|
| float, 절대 위치 계산, JS 로 높이 맞추기 | Flexbox·Grid | 코드 감소, 반응형 쉬움 |
| 화면 너비 미디어 쿼리로 부품 크기 바꾸기 | 컨테이너 쿼리(`@container`) | 부품이 놓인 자리 크기에 맞춤 |
| padding-top 비율 꼼수 | `aspect-ratio` | 의도가 드러남 |
| 가운데 정렬용 transform 꼼수 | Grid `place-items: center` | 한 줄 |

## 선택자·스타일
| 옛 방식 | 요즘 방식 | 좋아지는 점 |
|---|---|---|
| 자식 상태에 따라 부모를 꾸미려고 JS 로 클래스 붙이기 | `:has()` | JS 불필요 |
| 같은 접두어를 반복하는 깊은 선택자 | CSS 중첩 | 읽기 쉬움 |
| 전처리기로만 하던 색 섞기 | `color-mix()`, CSS 변수 | 실행 중 테마 변경 가능 |
| `!important` 로 우선순위 싸움 | `@layer` | 순서가 명확 |

## 스크롤·애니메이션
| 옛 방식 | 요즘 방식 | 좋아지는 점 |
|---|---|---|
| 스크롤 위치를 계산해 나타나기·지연 로딩 | `IntersectionObserver` | 성능, 코드 감소 |
| 이미지 지연 로딩 라이브러리 | `loading="lazy"` | 속성 하나 |
| 화면 전환 효과를 직접 구현 | View Transitions(지원 범위 확인) | 간단한 전환 효과 |
| 부드러운 스크롤 라이브러리 | `scroll-behavior: smooth` | CSS 한 줄 |

## 자바스크립트
| 옛 방식 | 요즘 방식 | 좋아지는 점 |
|---|---|---|
| XMLHttpRequest, jQuery.ajax | `fetch` + `AbortController` | 취소·async/await |
| 깊은 복사용 JSON 왕복 | `structuredClone()` | 날짜·Map 등 보존 |
| 날짜·숫자·통화 직접 포맷 | `Intl` (DateTimeFormat, NumberFormat) | 지역 표기 자동 |
| 배열 마지막 요소 `arr[arr.length - 1]` | `arr.at(-1)` | 짧음 |
| 고유 id 직접 생성 | `crypto.randomUUID()` | 충돌 걱정 없음 |

## 폼·접근성
| 옛 방식 | 요즘 방식 | 좋아지는 점 |
|---|---|---|
| JS 로 짠 필수·형식 검증 | `required`·`pattern`·`type` + `:user-invalid` | 기본 메시지·접근성 |
| 클릭 가능한 div | `<button>` | 키보드·화면 읽기 지원 |
| 라벨 없는 입력칸 | `<label>` 연결 | 누르기 쉬움, 접근성 |
