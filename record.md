# ConfigFlow 작업 기록

## 2026-07-02 14:29 - 프로젝트 설계 단계 시작 (설계 문서 작성)

### Input
SourceTree/TortoiseSVN을 대체하는 통합 형상관리 클라이언트 "ConfigFlow" 개발 시작.
개발 방식에 따라 구현 전 설계 단계(요구사항 분석 → 기능 정의 → 아키텍처 → 디렉터리 구조 → 데이터 모델 → UI → API → Task 분리 → 우선순위)를 먼저 수행.

### Output
- `record.md` 생성 (작업 기록 시작)
- `docs/` 하위에 설계 문서 작성:
  - `01-requirements-analysis.md` — 요구사항 분석
  - `02-feature-definition.md` — 기능 정의
  - `03-architecture.md` — 아키텍처 설계 (Clean Architecture + DDD + VCS 추상화)
  - `04-directory-structure.md` — 디렉터리/모듈 구조
  - `05-data-model.md` — 데이터 모델 (SQLite + Domain 모델)
  - `06-ui-design.md` — UI 설계 (레이아웃, 디자인 시스템, 다크모드)
  - `07-api-design.md` — API 설계 (REST + SSE)
  - `08-task-breakdown.md` — 작업 분리 및 우선순위
  - `adr/ADR-001-desktop-framework.md` — Electron vs Tauri 비교/결정
- 프로젝트 스켈레톤 디렉터리 생성 (backend / frontend / desktop / docs / scripts / installer)
- README.md 생성

## 2026-07-02 14:41 - M0 스캐폴딩 착수 (3트랙 병렬)

### Input
"시작해" — 설계 승인, M0(스캐폴딩) 구현 시작 지시.

### Output
- 환경 확인: Node v25.8.2, npm 11.11.1, git 2.53.0 OK. 시스템 Java는 18 → Gradle Toolchain(foojay)으로 JDK 21 자동 프로비저닝 방식 채택.
- Sub-Agent 3개 병렬 실행:
  - Backend Agent: M0-T1~T4 (Gradle 멀티모듈, domain 모델/포트, SQLite+Flyway, 토큰 인증+health+SSE)
  - Frontend Agent: M0-T5~T6 (Vite+React+TS+Tailwind, 디자인 토큰, 레이아웃 셸, API/SSE/Query)
  - Desktop Agent: M0-T7 (Electron main/preload, backend-launcher)
- 통합(M0-T8: dev 스크립트, 검증)은 에이전트 완료 후 메인 에이전트가 수행 예정.

## 2026-07-02 14:52 - M0 Desktop 트랙 완료

### Input
(Desktop Sub-Agent 완료 보고)

### Output
- desktop/ 완성: Electron main/preload/shared, backend-launcher(빈 포트+랜덤 토큰, java 탐색, health 폴링, taskkill 트리 종료), IPC(dialog/shell), electron-builder.yml 스켈레톤
- 토큰 전달을 CLI 인자 대신 동기 IPC 핸드셰이크로 구현 (로컬 프로세스의 명령줄 노출로 인한 토큰 유출 방지) — 설계 개선으로 승인
- 검증: typecheck/build 통과, dev-attach 모드 스모크 테스트 통과
- 메인 에이전트: scripts/dev.ps1, scripts/build.ps1 작성 (M0-T8)
