# 06. UI 설계 (UI Design)

참고: SourceTree(정보 밀도), GitKraken(그래프 시각화), VS Code(패널 시스템·테마). 복제하지 않고 재해석한다.

## 1. 메인 레이아웃

```
┌──────────────────────────────────────────────────────────────────────┐
│ TitleBar  [ConfigFlow]  [repo 탭1][repo 탭2]…            ─ □ ✕       │
├──────────────────────────────────────────────────────────────────────┤
│ Toolbar   [Pull][Push][Fetch][Branch][Merge][Stash]  ···  [검색] [⚙] │
│           (열린 repo의 Capability에 따라 버튼 구성 변경)                 │
├────────────┬──────────────────────────────────┬──────────────────────┤
│ Sidebar    │ Center                           │ Right Panel          │
│            │                                  │                      │
│ WORKSPACE  │  [History | Working Tree] 탭     │  Diff Viewer         │
│  파일 상태  │                                  │   [Side-by-Side|     │
│  History   │  Commit Graph                    │    Inline] 토글      │
│  검색      │  ●─┐ main  커밋메시지  작성자 날짜  │                      │
│            │  │ ●  feature/x …               │  또는                 │
│ BRANCHES   │  ●─┘ merge …                    │  커밋 상세/파일 목록    │
│  ▾ local   │  │                              │                      │
│  ▾ remote  │  (가상 스크롤)                    │                      │
│ TAGS       │                                  │                      │
│ STASHES*   │                                  │                      │
│ SVN LOCKS* │                                  │                      │
├────────────┴──────────────────────────────────┴──────────────────────┤
│ Bottom Panel (접이식): [Console] [Operations] [Log]                   │
├──────────────────────────────────────────────────────────────────────┤
│ StatusBar: 현재 브랜치 ⑂ main · ↑2 ↓1 · 진행 중 작업 스피너 · 알림      │
└──────────────────────────────────────────────────────────────────────┘
```

`*` 표시 섹션은 Capability에 따라 표시/숨김 (STASH → Git만, FILE_LOCKING → SVN만).

- 모든 패널 경계는 드래그 리사이즈, 상태는 `ui_state`에 저장.
- Repository 탭: 여러 저장소를 동시에 열고 탭으로 전환 (탭별 독립 상태).

## 2. 주요 화면 흐름

### Welcome (repo 미선택)
즐겨찾기 그룹 + 최근 목록 그리드, [Clone] [Add Local] [Init] 3개 주 액션. 드래그&드롭으로 폴더 등록.

### Working Tree 뷰 (Center 탭)
- Git: `Staged` / `Changes` 두 리스트 + 커밋 메시지 박스(제목/본문 분리, 글자 수 가이드)
- SVN: 단일 `Changes` 리스트 + 체크박스 선택 커밋 (Stage 개념 자체를 노출하지 않음)
- 파일 클릭 → 우측 Diff, 더블클릭 → 외부 에디터
- 컨텍스트 메뉴: Discard, Ignore, (SVN) Lock/Unlock, 히스토리 보기

### Commit Graph (Center 탭)
- Canvas 기반 레인 렌더링 + 가상 스크롤 (행 데이터는 서버 페이지네이션)
- 행: 그래프 · refs 칩(브랜치/태그) · 메시지 · 작성자 아바타 · 상대 시간
- 행 클릭 → 우측에 커밋 상세(메타 + 파일 목록 → 파일 클릭 시 diff)
- 컨텍스트 메뉴: Checkout, Merge into current, Rebase, Cherry-pick, Tag, Reset, Revert, Copy SHA
- 상단 필터 바: 브랜치 선택, Author, 날짜 범위, 경로, 텍스트 검색

### Merge Editor (충돌 시 전체 화면 모달)
```
┌─ conflicted: src/foo.ts (2/5 해결) ────────────────────┐
│ [Mine ✓] [Theirs] [Both]        [이전 충돌] [다음 충돌]  │
├────────────────┬────────────────┬─────────────────────┤
│ MINE (현재)     │ BASE           │ THEIRS (병합 대상)    │
├────────────────┴────────────────┴─────────────────────┤
│ RESULT (편집 가능)                                      │
│                        [파일 해결 완료] [AI 제안*]       │
└────────────────────────────────────────────────────────┘
```
`*` AI 제안 버튼은 v1에서 disabled + 툴팁("추후 지원").

### SVN Repository Browser (모달/탭)
서버 URL 트리 + 리비전 선택기, 파일 미리보기, 로그 보기, checkout 시작점 지정.

### Settings
좌측 카테고리(General / VCS / SSH / Proxy / AI / 단축키) + 우측 폼. 변경 즉시 저장.

## 3. 디자인 시스템

### 색상 토큰 (다크 기본)

| 토큰 | Dark | 용도 |
|---|---|---|
| `bg-base` | `#1e1f24` | 앱 배경 |
| `bg-panel` | `#26272e` | 패널 |
| `bg-elevated` | `#2e3038` | 카드, 팝오버 |
| `border` | `#3a3d47` | 경계선 |
| `text-primary` / `text-muted` | `#e6e7eb` / `#9a9daa` | |
| `accent` | `#4f8cff` | 선택, 주 버튼 |
| `vcs-added` | `#3fb950` | 추가 |
| `vcs-modified` | `#d29922` | 수정 |
| `vcs-deleted` | `#f85149` | 삭제 |
| `vcs-conflicted` | `#ff7b72` | 충돌 |
| `vcs-renamed` | `#a371f7` | 이름 변경 |

- Tailwind 테마로 토큰화(`tailwind.config.ts`)하고 CSS 변수로 다크/라이트 전환.
- 그래프 브랜치 레인 색: 8색 순환 팔레트 (색약 안전 조합으로 선정, 구현 시 검증).

### 타이포그래피
- UI: 시스템 폰트 스택 (Segoe UI / SF Pro / Noto Sans KR)
- 코드·diff·해시: `JetBrains Mono` 동봉, 13px 기본

### 컴포넌트 인벤토리 (shared/ui)
Button, IconButton, Input, Select, Checkbox, Tabs, Tree, VirtualList, SplitPane,
ContextMenu, Modal, Toast, Tooltip, Badge(파일 상태 칩), ProgressBar, EmptyState, Spinner

## 4. 상호작용 원칙

1. **작업은 절대 UI를 막지 않는다** — 장기 작업은 StatusBar 스피너 + Operations 패널에서 추적, 취소 가능.
2. **파괴적 작업은 2단계 확인** — reset --hard, force push, 디스크 삭제는 대상 명시 확인 모달 (예: 브랜치명 타이핑).
3. **모든 주요 액션에 단축키** — Commit `Ctrl+Enter`, Pull `Ctrl+Shift+L`, Push `Ctrl+Shift+P`, 검색 `Ctrl+F`, 커맨드 팔레트 `Ctrl+Shift+K` (P1).
4. **Capability 기반 렌더링** — VCS별 if 분기 대신 `useCapability(repo, 'STASH')` 훅으로 노출 제어.
5. **빈 상태 설계** — 모든 목록/패널은 EmptyState (안내 + 다음 행동 버튼) 제공.
6. **에러는 복구 행동과 함께** — "Push 실패: 원격에 새 커밋" → [Pull 후 재시도] 버튼 제공.

## 5. 접근성·i18n

- 전 UI 키보드 내비게이션 (Tree/List는 roving tabindex)
- 색상만으로 상태를 전달하지 않음 (아이콘 + 텍스트 병행)
- 문자열 전부 i18n 리소스화 (`shared/i18n/ko.json`, `en.json`), 날짜·상대시간 로케일 처리
