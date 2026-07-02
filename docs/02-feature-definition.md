# 02. 기능 정의 (Feature Definition)

각 기능을 **Epic → Feature** 단위로 정의한다. 우선순위는 `P0(필수) > P1(중요) > P2(추후)`.

## E1. Repository 관리 (P0)

| ID | 기능 | 설명 | 비고 |
|---|---|---|---|
| E1-F1 | Repository 등록 | 로컬 경로의 기존 Git repo / SVN working copy를 앱에 등록. VCS 종류 자동 감지(`.git`/`.svn`) | |
| E1-F2 | Clone (Git) | 원격 URL → 로컬 clone. 진행률 표시, 취소 가능 | 인증 지원 |
| E1-F3 | Checkout (SVN) | SVN URL → working copy 생성 | 인증 지원 |
| E1-F4 | Init | 새 Git repository 초기화 | SVN init은 서버 기능이므로 제외 |
| E1-F5 | Remove | 앱 등록 해제 (기본) / 디스크 삭제 (명시적 확인 후) | 파괴적 작업은 2단계 확인 |
| E1-F6 | Open | 등록된 Repository를 탭/워크스페이스로 열기 | 다중 탭 지원 |
| E1-F7 | 최근 Repository | 최근 연 순서 정렬 목록 | SQLite 저장 |
| E1-F8 | 즐겨찾기 | 고정 목록, 그룹핑 | SQLite 저장 |

## E2. Git 작업 (P0)

| ID | 기능 | 세부 |
|---|---|---|
| E2-F1 | Fetch | remote 선택, prune 옵션 |
| E2-F2 | Pull | fetch + merge / fetch + rebase 선택 |
| E2-F3 | Push | force-with-lease 옵션, upstream 설정, tag push |
| E2-F4 | Branch 생성/삭제 | 시작점 지정, 원격 브랜치 삭제 포함 |
| E2-F5 | Checkout | 브랜치/태그/커밋(detached) 전환, 미커밋 변경 처리 안내 |
| E2-F6 | Merge | fast-forward 옵션, squash, 충돌 시 Conflict 플로우 진입 |
| E2-F7 | Rebase | onto 지정, 충돌 시 continue/abort/skip |
| E2-F8 | Cherry-Pick | 다중 커밋 선택 지원 |
| E2-F9 | Stash | save(메시지/untracked 포함), list, apply, pop, drop |
| E2-F10 | Tag | lightweight/annotated 생성, 삭제, push |
| E2-F11 | Reset | soft/mixed/hard, hard는 2단계 확인 |
| E2-F12 | Revert | 단일/다중 커밋, merge 커밋 revert(-m) |

## E3. SVN 작업 (P0)

| ID | 기능 | 세부 |
|---|---|---|
| E3-F1 | Update | 특정 리비전 지정 가능, 충돌 시 Conflict 플로우 |
| E3-F2 | Commit | 파일 선택 커밋, 메시지, keep-lock 옵션 |
| E3-F3 | Cleanup | working copy 잠금 해제, pristine 정리 |
| E3-F4 | Revert | 파일/디렉터리 단위, 재귀 옵션 |
| E3-F5 | Resolve | mine/theirs/manual 선택 |
| E3-F6 | Lock / Unlock | 잠금 코멘트, 강제 해제(break lock) |
| E3-F7 | Repository Browser | 서버 리비전 트리 탐색, 파일 내용/로그 보기 |

## E4. Working Tree (P0)

- 변경 파일 목록: 상태별(Added/Modified/Deleted/Renamed/Conflicted/Untracked/Ignored/Locked) 아이콘·색상 표시
- Stage / Unstage: 파일 단위 + **hunk/라인 단위**(Git, P1). SVN은 Capability 미지원 → UI에서 changelist로 대체 표현
- Ignore: `.gitignore` / `svn:ignore` 편집 지원, 패턴 추가 컨텍스트 메뉴
- Diff: 파일 선택 시 우측 패널에 즉시 표시
- 새로고침: 파일 시스템 감시(watcher) 기반 자동 갱신 (P1)

## E5. Commit (P0)

- Commit: 메시지 편집기(제목/본문 분리, 컨벤션 가이드), Amend(Git)
- History: 무한 스크롤 커밋 목록, 커밋 상세(변경 파일, 메타데이터, diff)
- 검색: Author, Message, Hash/Revision, 날짜 범위, 파일 경로

## E6. Branch 시각화 (P0)

- Tree View: 로컬/원격/태그 계층 트리, 컨텍스트 메뉴로 주요 작업 실행
- Graph View: 커밋 그래프에 브랜치 레인 표시
- Compare: 두 브랜치/리비전 간 커밋·파일 diff
- Merge: 그래프/트리에서 드래그 또는 컨텍스트 메뉴로 실행

## E7. Diff Viewer (P0)

- Side-by-Side / Inline 전환
- Syntax Highlight (언어 자동 감지)
- 라인 번호, Added/Removed/Modified 색상 표시
- 대용량 파일 가상 스크롤, 바이너리/이미지 파일 처리 (이미지는 미리보기, P1)
- Word-level diff 하이라이트 (P1)

## E8. History / Commit Graph (P0)

- SourceTree 수준 그래프: 브랜치 레인, merge 라인, 브랜치/태그 라벨
- 가상 스크롤 + 서버 사이드 페이지네이션 (대용량 대응)
- Filter: 브랜치, Author, 날짜, 경로
- SVN: 선형 리비전 히스토리로 렌더링 (그래프 알고리즘 공유, 레인 1개)

## E9. Conflict 해결 (P0, AI 제안은 P2)

- 충돌 파일 목록 및 상태 표시
- 3-way 비교 (Base / Mine / Theirs)
- Merge Editor: hunk 단위 선택(mine/theirs/both), 직접 편집
- 해결 완료 처리: Git `add` / SVN `resolve`
- AI 해결 제안 (P2): AI Provider 인터페이스 경유

## E10. Settings (P0)

- Git 실행 경로 (JGit 기본, Native CLI 전환 옵션)
- SVN 실행 경로 (SVNKit 기본)
- SSH Key 관리 (키 등록, known_hosts)
- Theme (Dark 기본 / Light)
- Language (ko / en)
- Proxy (HTTP/SOCKS)
- AI Provider 설정 (P2): Provider 선택, API Key, 데이터 전송 동의

## E11. AI 기능 (인터페이스: P0, 구현: P2)

인터페이스와 추상화는 처음부터 설계에 포함하되, 실제 Provider 구현은 후순위.

| 기능 | 입력 | 출력 |
|---|---|---|
| Commit Message 생성 | staged diff | Conventional Commits 형식 메시지 |
| 변경사항 요약 | diff / 커밋 범위 | 자연어 요약 |
| Merge Conflict 해결 | base/mine/theirs | 병합 제안 + 근거 |
| 코드 리뷰 | diff | 이슈 목록 (심각도 포함) |
| PR 설명 생성 | 커밋 목록 + diff | PR 본문 |
| 변경 영향 분석 | diff + 참조 그래프 | 영향 범위 리포트 |

## E12. 공통 인프라 기능

- **Console/Log 패널** (P0): 실행된 VCS 명령과 결과를 시간순 표시 (SourceTree의 커맨드 로그와 동일 개념)
- **작업 큐** (P0): Repository당 순차 실행, 진행률/취소, 앱 전체 작업 상태 표시
- **알림** (P1): 작업 완료/실패 토스트
- **자격 증명 관리** (P0): OS Credential Store 연동, Repository·호스트별 저장

## Capability 매트릭스 (VCS 추상화의 기준)

| Capability | Git | SVN | UI 동작 |
|---|---|---|---|
| STAGING | ✅ | ❌ | SVN이면 Stage 영역 숨김, 커밋 시 파일 선택으로 대체 |
| BRANCHING_LOCAL | ✅ | ⚠️(서버 copy) | SVN은 branch = 서버 URL copy로 표현 |
| STASH | ✅ | ❌ | 미지원 시 메뉴 숨김 |
| REBASE / CHERRY_PICK | ✅ | ❌ | 〃 |
| FILE_LOCKING | ⚠️(LFS) | ✅ | Git 기본 숨김, SVN 노출 |
| ATOMIC_REVISION | 커밋 | 리비전 | 공통 `Revision` 모델로 통합 |
| PARTIAL_CHECKOUT | ❌(sparse는 추후) | ✅ | SVN만 노출 |
| OFFLINE_HISTORY | ✅ | ❌(서버 필요) | SVN 히스토리는 캐시 + 온라인 조회 |
