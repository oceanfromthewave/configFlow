# ADR-001: 데스크톱 프레임워크 — Electron vs Tauri

- 상태: **승인 (Electron 채택)**
- 날짜: 2026-07-02

## 컨텍스트

ConfigFlow는 React 기반 UI를 데스크톱 앱으로 배포해야 하며, 핵심 특수 조건이 있다:
**백엔드가 Spring Boot(JVM) 사이드카 프로세스**라는 점이다. 데스크톱 셸의 역할은
(1) 웹 UI 호스팅, (2) JVM 프로세스 생명주기 관리, (3) 네이티브 다이얼로그/셸 연동이다.

## 비교

| 기준 | Electron | Tauri |
|---|---|---|
| 번들 크기 | ~80-100MB (Chromium 포함) | ~5-10MB (OS WebView) |
| 메모리 | 높음 | 낮음 |
| **사이드카 프로세스 관리** | Node `child_process`로 성숙, 스트림/종료 처리 용이 | sidecar 지원 있으나 JVM 관리 사례 적음 |
| 렌더링 일관성 | Chromium 고정 — 3개 OS에서 동일 | OS WebView 의존 — Windows(WebView2)/macOS(WKWebView) 간 차이 발생 가능 |
| 개발 언어 | JS/TS (팀 스택과 동일) | Rust (신규 학습 필요) |
| 생태계/레퍼런스 | VS Code, Slack 등 대규모 검증 | 성장 중 |
| 자동 업데이트/인스톨러 | electron-builder 성숙 | tauri-updater 있음 |

## 결정

**Electron을 채택한다.**

핵심 근거:
1. **번들 크기 이점이 무의미**: 어차피 JRE(~60-200MB)를 동봉해야 하므로 Tauri의 최대 장점(경량)이 상쇄된다.
2. **렌더링 일관성**: Diff Viewer, Commit Graph 등 픽셀 정밀 UI가 많아 Chromium 고정이 QA 비용을 크게 줄인다.
3. **JVM 사이드카 관리**: Node에서의 프로세스 관리·헬스체크·로그 파이프가 검증된 패턴이다.
4. **팀 스택 일치**: TS 단일 언어로 desktop 계층 유지보수 가능. Rust 도입 비용 회피.

## 결과 (Consequences)

- (+) 크로스 플랫폼 렌더링 QA 부담 감소, 빠른 개발
- (−) 메모리 사용량 큼 → JVM 힙 상한 설정, lazy 백엔드 기동으로 완화
- (−) 번들 크기 큼 → jlink로 최소 JRE 이미지 생성하여 완화
- 재검토 조건: GraalVM Native Image로 백엔드를 네이티브화할 경우 Tauri 재평가 가치 있음 (v2 이후)
