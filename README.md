# ConfigFlow

Git과 SVN을 하나의 앱에서 — SourceTree와 TortoiseSVN을 대체하는 통합 형상관리 데스크톱 클라이언트.

## 특징 (목표)

- **통합 VCS**: Git(JGit)과 SVN(SVNKit)을 단일 UX로 관리, Capability 기반 UI
- **플러그인 아키텍처**: `VcsProvider` 인터페이스로 신규 VCS(Mercurial 등) 추가 가능
- **SourceTree급 시각화**: Commit Graph, GitHub급 Diff Viewer(Side-by-Side/Inline), 3-way Merge Editor
- **AI 준비**: Commit Message 생성·충돌 해결 등 AI Provider 추상화 선탑재
- **다크모드 기본**, 한국어/영어 지원

## 기술 스택

| 영역 | 스택 |
|---|---|
| Backend | Java 21 · Spring Boot · JGit · SVNKit · SQLite |
| Frontend | React · TypeScript · Vite · Tailwind CSS |
| Desktop | Electron ([ADR-001](docs/adr/ADR-001-desktop-framework.md)) |
| 아키텍처 | Clean Architecture · DDD · Gradle 멀티모듈 |

## 저장소 구조

```
backend/     Spring Boot 멀티모듈 (domain / application / infrastructure / bootstrap)
frontend/    React SPA (Feature-Sliced)
desktop/     Electron 셸 (backend 프로세스 관리 + 네이티브 연동)
docs/        설계 문서 · ADR
scripts/     개발·빌드 스크립트
installer/   인스톨러 리소스
```

## 설계 문서

| 문서 | 내용 |
|---|---|
| [01-requirements-analysis](docs/01-requirements-analysis.md) | 요구사항 분석, NFR, 리스크 |
| [02-feature-definition](docs/02-feature-definition.md) | 기능 정의, Capability 매트릭스 |
| [03-architecture](docs/03-architecture.md) | 계층 구조, VCS 추상화, 작업 큐, 테스트 전략 |
| [04-directory-structure](docs/04-directory-structure.md) | 모듈 구조와 의존 규칙 |
| [05-data-model](docs/05-data-model.md) | 도메인 모델, SQLite 스키마 |
| [06-ui-design](docs/06-ui-design.md) | 레이아웃, 디자인 시스템, 상호작용 원칙 |
| [07-api-design](docs/07-api-design.md) | REST + SSE 계약 |
| [08-task-breakdown](docs/08-task-breakdown.md) | 마일스톤, Task, 병렬 실행 계획 |

## 상태

**설계 단계 완료 → M0(스캐폴딩) 착수 대기.** 로드맵은 [08-task-breakdown](docs/08-task-breakdown.md) 참조.
