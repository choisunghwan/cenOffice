---
name: cen-modern-web
title: 모던 웹 코드 가이드
description: HTML·CSS·자바스크립트 코드를 붙여 넣으면 옛날 방식(라이브러리·우회 코드)을 찾아 요즘 브라우저 기본 기능으로 바꾸는 방법을 알려 줍니다.
version: 1.1.0
category: dev
tags: [웹, 프론트엔드, 리팩터링]
icon: 🌐
---

# 모던 웹 코드 가이드

## 언제 쓰나요
- 오래된 화면 코드를 손볼 때, 지금은 브라우저가 기본으로 해 주는 일을 코드로 직접 하고 있지 않은지 보고 싶을 때
- 새 화면을 만들 때 요즘 권장 방식을 먼저 확인하고 싶을 때

## 입력
- HTML·CSS·자바스크립트(또는 리액트 등) 코드, 또는 "모달을 어떻게 만들지?" 같은 질문
- 선택: 지원해야 하는 브라우저(예: 사내 크롬·엣지 최신)

## 진행 방법
1. `references/patterns.md` 의 표에서 입력 코드에 해당하는 "옛 방식"을 찾는다.
2. 바꿀 방식마다 `references/browser-support.md` 기준으로 지원 범위를 확인하고 적는다.
3. 결과는 `templates/report.md` 형식으로 쓴다. 예시는 `examples/` 폴더를 참고한다.

## 파일 구성
- `references/patterns.md` — 옛 방식 → 요즘 방식 대응표(분야별)
- `references/browser-support.md` — 브라우저 지원을 판단하고 적는 원칙
- `templates/report.md` — 결과 형식
- `examples/modal-dialog.md` — 입력과 결과 예시

## 주의할 점
- 잘 동작하는 코드를 유행 때문에 바꾸라고 하지 않는다. 코드가 줄거나, 접근성·성능이 나아지는 경우만 권한다.
- 동작이 같은지 꼭 확인하도록 안내한다. 결과는 검토용 제안이며 실행해 보지 않았다.
- 참고: Chrome·Edge·Firefox·MDN 팀의 공개 프로젝트 Modern Web Guidance 의 취지를 참고해 사내용으로 새로 썼다. 전체 안내서가 필요하면 Claude Code 에 공식 플러그인을 직접 설치해 쓸 수 있다.
