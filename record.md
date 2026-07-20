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

## 2026-07-03 09:15 - 세션 복구: Frontend 검증 완료, Backend 재개

### Input
(이전 세션 중단 후 재개 — Frontend/Backend Sub-Agent가 완료 보고 없이 종료됨)

### Output
- Frontend 상태 확인 및 직접 검증: tsc 통과, vitest 12/12 통과, vite build 성공. 통합 계약 준수 확인 (window.configflow → env 폴백, X-ConfigFlow-Token 헤더, SSE token 쿼리 파라미터)
- Backend 상태 확인: domain/application/infrastructure 완성, bootstrap 모듈만 미구현 상태에서 중단 → Backend Sub-Agent 재개하여 M0-T4(bootstrap: 토큰 필터, health, SSE, settings API, Problem Details) 마무리 지시

## 2026-07-03 09:30 - M0 스캐폴딩 완료 (3트랙 통합 검증)

### Input
(Backend Sub-Agent 완료 보고 + 메인 에이전트 통합 검증)

### Output
- Backend 완성: Gradle 8.14.2 멀티모듈 8개, domain 모델/포트 전체, SQLite+Flyway persistence, bootstrap(토큰 필터·health·SSE·settings API·RFC 9457). 테스트 18/18 (ArchUnit 계층 규칙 포함)
- 메인 에이전트 독립 검증: gradlew build 성공, jar 기동 후 health 토큰 인증 200/무토큰 401, 127.0.0.1 바인딩 확인
- Frontend 검증(어제): tsc/vitest 12건/build 통과. Desktop 검증(어제): typecheck/build/스모크 통과
- 환경 이슈 발견: 사용자 JAVA_HOME이 삭제된 경로(D:\jdks\jdk-21.0.11+10)를 가리킴 → scripts/dev.ps1·build.ps1에 JDK 자동 탐색 보정 추가. 근본 해결은 사용자 환경변수 수정 필요 (실제 JDK 21: %USERPROFILE%\.jdks\ms-21.0.11)
- README 상태 갱신 (M0 완료, 개발 시작 가이드)
- M0 완료. 다음: M1 (Git 코어)

## 2026-07-03 09:38 - M1 (Git 코어) 착수

### Input
"다음단계 진행" — M1 시작 승인.

### Output
- Sub-Agent 3개 병렬 실행:
  - Git Engine Agent: M1-T1,T4,T5,T6,T12 (JGit Provider: detect/open/capabilities, working tree, commit/history, clone/fetch/pull/push+인증, 통합 테스트)
  - Backend Agent: M1-T2,T3,T7 (Repository UseCase+API, OperationQueue+SSE 이벤트, 자격증명 어댑터+401 재시도)
  - Frontend Agent: M1-T8~T11 (Welcome, Working Tree 패널, 히스토리 리스트, Operations/Console 패널, 자격증명 모달)
- 파일 소유 분리: vcs-git+domain/vcs ↔ application/bootstrap/persistence/credential ↔ frontend

## 2026-07-16 16:09 - 튜터 모드 전환 + M1 status 구현 + 브랜치/PR 리뷰 셋업

### Input
- 세션 복구 후 M1을 사용자가 직접 코딩(튜터 모드)으로 전환. "브랜치 따서 작업 + CodeRabbit 같은 PR 리뷰어 추가" 요청.

### Output
- 진척 분석: M0 완료·검증됨, M1은 이전 병렬 에이전트가 세션 한도로 즉시 중단되어 사실상 미착수(stub만 존재)로 확인.
- 튜터 모드로 M1 Git 코어 시작(사용자 직접 작성):
  - GitRepositoryAccess (JGit 저장소 열기 + 예외 번역)
  - GitWorkingTree.status() (JGit Status → 도메인 WorkingTreeStatus 매핑)
  - GitWorkingTreeTest (@TempDir 실제 저장소 기반 4개 테스트) — 초록불
  - 디버깅: staged 매핑 3줄이 모두 getAdded() 오타 → getChanged()/getRemoved()로 수정 (진단용 임시 테스트로 원인 규명 후 삭제)
- git 워크플로우 도입: feature/m1-git-core 브랜치 생성, 미커밋 작업을 3개 논리 커밋으로 정리.
- CodeRabbit 도입: 루트에 .coderabbit.yaml 추가(ko-KR, 아키텍처 규칙 path_instructions 포함). 앱 설치·PR 생성은 사용자 GitHub 계정에서 수행 예정(gh CLI 미설치).

## 2026-07-16 16:39 - M1 WorkingTree 조작 구현 + 커밋/푸시 정리 (세션 종료)

### Input
- "너가 마지막으로 보낸거 반영하고 푸시" / CodeRabbit 리뷰 노이즈 조정 / "오늘은 여기까지"

### Output
- GitWorkingTree에 stage/unstage/discard/ignore 구현(사용자 작성). IgnorePattern 접근자 오타(value→pattern) 수정.
- 테스트 4개 추가(메인 에이전트 반영): stage/unstage/discard(내용복원)/ignore(멱등). vcs-git 테스트 총 8개 초록불.
- 커밋 정리: feat(구현)/test(테스트)/chore(coderabbit) 분리. 실수로 구현 파일을 빠뜨리고 테스트만 커밋했다가(브랜치 tip 깨짐) 바로 feat 커밋으로 복구.
- CodeRabbit 튜닝: auto_incremental_review:false(매 푸시 재리뷰 방지), review_status:false, base_branches:[main]. 한국어+아키텍처 규칙 유지.
- 브랜치 feature/m1-git-core 전부 push 완료(5a05400). 작업트리 clean.

### 다음 재개 지점
- M1 Step 6: GitVcsProvider가 WorkingTreeOperations를 implements 하고 GitWorkingTree에 위임(겉-속 연결). 이후 commit/history → diff → clone/fetch/pull/push 순.

## 2026-07-20 09:20 - M1 Step 6/7: GitVcsProvider ↔ WorkingTreeOperations 연결

### Input
"어디까지 했고 이제 뭐해야하지?" → 상태 점검 후 Step 6 진행.

### Output
- GitVcsProvider가 WorkingTreeOperations를 implements 하고 GitWorkingTree에 전 메서드 위임(겉-속 연결 완성).
- 생성자 2개: public 무인자(프레임워크 배선용) + package-private(테스트 이음새, DI).
- GitVcsProviderTest 신규 4개: type/capability 선언, detect·open의 수용/거절 케이스, 그리고 포트 타입으로만 접근해 capability 확인 → instanceof 축소 → stage 수행하는 아키텍처 계약 검증.
- vcs-git 테스트 총 12개 초록불(Provider 4 + WorkingTree 8).

### 다음 재개 지점
- 택1: (a) CodeRabbit 지적 반영 — 쿼리파라미터 토큰 인증을 SSE 엔드포인트로만 제한(보안), (b) M1 계속 — commit/amend → history/show → diff → clone/fetch/pull/push.
