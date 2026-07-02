# 04. 디렉터리 구조 설계 (Directory Structure)

## 최상위 구조 (Monorepo)

```
configFlow/
├── backend/          # Spring Boot (Gradle 멀티모듈)
├── frontend/         # React + TypeScript + Vite + Tailwind
├── desktop/          # Electron (main / preload / builder 설정)
├── docs/             # 설계 문서, ADR
├── scripts/          # 빌드·개발·릴리스 스크립트
├── installer/        # 플랫폼별 인스톨러 설정 (electron-builder 리소스)
├── record.md
└── README.md
```

## backend/ — Gradle 멀티모듈 (계층 = 모듈)

컴파일 타임에 Clean Architecture 의존 규칙을 강제하기 위해 계층을 Gradle 모듈로 분리한다.

```
backend/
├── settings.gradle.kts
├── build.gradle.kts                  # 공통 컨벤션 (java 21, 정적분석)
├── domain/                           # ★ 순수 Java, 외부 의존 0
│   └── src/main/java/dev/configflow/domain/
│       ├── repository/               # Repository 애그리거트 (등록/즐겨찾기 도메인)
│       ├── vcs/
│       │   ├── model/                # Revision, FileChange, WorkingTreeStatus, RefLabel ...
│       │   ├── port/                 # VcsProvider + 역할별 Operations 인터페이스
│       │   └── capability/           # VcsCapability enum, CapabilitySet
│       ├── operation/                # Operation, OperationQueue 포트, 이벤트 모델
│       ├── credential/               # CredentialStore 포트
│       ├── ai/                       # AiProvider 포트, DiffContext, 마스킹 규칙
│       └── settings/                 # 설정 도메인 모델
├── application/                      # UseCase 계층 (Spring 미의존, 순수 Java + domain)
│   └── src/main/java/dev/configflow/application/
│       ├── repository/               # RegisterRepository, CloneRepository, ...UseCase
│       ├── workingtree/              # GetStatus, Stage, Discard, Ignore ...
│       ├── commit/                   # Commit, GetHistory, SearchHistory ...
│       ├── branch/                   # CreateBranch, Checkout, Merge, Compare ...
│       ├── sync/                     # Fetch, Pull, Push, SvnUpdate ...
│       ├── conflict/                 # ListConflicts, Resolve, GetThreeWay ...
│       ├── graph/                    # BuildCommitGraph (레인 배치 알고리즘)
│       ├── operation/                # OperationQueueService, 이벤트 발행
│       └── ai/                       # GenerateCommitMessage 등 (Provider 위임)
├── infrastructure/
│   ├── vcs-git/                      # JGit 구현 (GitProvider + Operations 구현체)
│   ├── vcs-svn/                      # SVNKit 구현 (SvnProvider + …)
│   ├── persistence/                  # SQLite: JDBC + 마이그레이션(Flyway), 리포지토리 구현
│   ├── credential/                   # OS Credential Store 어댑터
│   └── ai-providers/                 # NoopAiProvider (v1), claude/openai (v2)
└── bootstrap/                        # 실행 모듈: Spring Boot 조립 + api
    └── src/main/java/dev/configflow/
        ├── api/                      # REST Controller, DTO, SSE, 예외 매핑
        │   ├── repository/  ├── workingtree/  ├── commit/  ├── branch/
        │   ├── sync/        ├── conflict/     ├── graph/   ├── settings/
        │   └── event/                # SSE 엔드포인트
        └── config/                   # Bean 조립, 보안(토큰 필터), CORS
```

의존 규칙 (settings.gradle + ArchUnit로 강제):

```
bootstrap → application, infrastructure/*, domain
infrastructure/* → domain            (application 참조 금지)
application → domain
domain → (없음)
infrastructure 모듈끼리 상호 참조 금지
```

## frontend/ — Feature-Sliced 구조

```
frontend/
├── src/
│   ├── app/                  # 엔트리, 라우팅, 전역 Provider, 테마
│   ├── shared/
│   │   ├── api/              # API 클라이언트, SSE 구독, 생성된 타입
│   │   ├── ui/               # 디자인 시스템 컴포넌트 (Button, Panel, Tree ...)
│   │   ├── lib/              # 유틸, hooks
│   │   └── i18n/             # ko/en 리소스
│   ├── entities/             # 도메인 모델 표현 (repository, revision, fileChange ...)
│   ├── features/             # 사용자 액션 단위 (stage-files, create-branch, resolve-conflict ...)
│   ├── widgets/              # 조합 블록 (RepositorySidebar, CommitGraph, DiffViewer,
│   │                         #            WorkingTreePanel, ConsolePanel, MergeEditor)
│   └── pages/                # WelcomePage, RepositoryPage, SettingsPage
├── index.html
├── vite.config.ts
├── tailwind.config.ts
└── package.json
```

- 상태 관리: **TanStack Query**(서버 상태) + **Zustand**(UI 상태). SSE 이벤트로 쿼리 무효화.
- Diff/에디터: CodeMirror 6 (Monaco 대비 경량, 커스텀 diff 렌더링 유리) — 구현 단계에서 PoC 후 확정.

## desktop/

```
desktop/
├── src/
│   ├── main/                 # 창 관리, backend 프로세스 스포너, 메뉴, 자동업데이트
│   │   ├── backend-launcher.ts   # JRE+jar 기동, 포트/토큰 핸드셰이크, 헬스체크
│   │   └── ipc/              # 파일 다이얼로그, 셸 열기 등 네이티브 IPC 핸들러
│   ├── preload/              # contextBridge 최소 API 노출
│   └── shared/               # main↔renderer 공유 타입
├── electron-builder.yml
└── package.json
```

## docs/

```
docs/
├── 01-requirements-analysis.md
├── 02-feature-definition.md
├── 03-architecture.md
├── 04-directory-structure.md
├── 05-data-model.md
├── 06-ui-design.md
├── 07-api-design.md
├── 08-task-breakdown.md
└── adr/                      # Architecture Decision Records
```

## scripts/ · installer/

```
scripts/    # dev.ps1 (backend+frontend+electron 동시 기동), build.ps1, release.ps1
installer/  # 아이콘, 라이선스, electron-builder 플랫폼 리소스, jlink JRE 생성 스크립트
```

## 모듈 역할 요약

| 모듈 | 역할 | 핵심 원칙 |
|---|---|---|
| backend/domain | VCS 도메인 언어와 포트 정의 | 프레임워크 무의존, 100% 단위 테스트 가능 |
| backend/application | UseCase 오케스트레이션, 작업 큐 | 포트만 사용, 구현 모름 |
| backend/infrastructure/vcs-git | JGit 어댑터 | GitProvider 교체 가능(Native CLI) |
| backend/infrastructure/vcs-svn | SVNKit 어댑터 | 〃 |
| backend/bootstrap | HTTP 계약 + DI 조립 | 얇게 유지 (로직 금지) |
| frontend/shared/ui | 디자인 시스템 | 도메인 무의존 |
| frontend/widgets | 화면 블록 | Capability 기반 조건부 렌더링 |
| desktop | 셸 + 프로세스 관리 | UI/비즈니스 로직 금지 |
