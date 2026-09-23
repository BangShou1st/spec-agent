# Frontend Structure

This document is the placement contract for `frontend/src`. It exists so new
code lands in an obvious place instead of accumulating another `components/`
grab bag.

Authoritative for behaviour: `AGENT.md`, `docs/PRODUCT_SPEC.md`,
`docs/ARCHITECTURE.md`. This file only governs *where code lives*.

## 1. Layout

```text
frontend/src/
├── main.ts                     app bootstrap (styles + pinia + router)
├── App.vue                     app shell: header, nav, global assistant host
├── env.d.ts
├── app/                        assembly layer — knows about features, nothing else does
│   ├── router/                 route table, names, guards, scroll behaviour
│   ├── layouts/                page shells (SettingsLayout)
│   └── styles/                 global stylesheets (style.css, mgmt.css, providerSettings.css)
├── features/                   business capabilities, self-contained
│   ├── projects/              项目列表：创建 / 重命名 / 删除 / 搜索
│   ├── workspace/            工作区：画布、路线、回答、Spec、需求状态
│   │   ├── WorkspaceView.vue  feature entry view
│   │   ├── components/        workspace UI (sidebar, inspector, spec dock, dialogs)
│   │   ├── graph/             canvas: components/ + layout/projection/viewport/interaction
│   │   ├── state/             Pinia stores + narrow slice modules
│   │   ├── api/               workspace HTTP calls
│   │   └── presentation/      route/recovery/agent/spec display logic
│   ├── global-assistant/     全局助手
│   ├── model-settings/       模型设置（Provider 配置）
│   ├── skills/               Skills 管理
│   └── connections/          Connections 管理
├── shared/                     bottom layer — must not import features/ or app/
│   ├── ui/                    design-system components + dialog form composable
│   ├── http/                  api client, error contract, product error copy
│   ├── contracts/             frozen backend DTO types
│   ├── document/              text extraction, preview, OCR, resource rendering
│   └── lib/                   framework-free helpers (time, storage, status copy)
└── test/                      test infrastructure + cross-feature boundary specs
```

A feature directory contains only the subdirectories it actually needs. There is
no obligation to create `api/`, `presentation/`, or `components/` when a feature
has none.

## 2. Dependency rules

| Layer | May import |
| --- | --- |
| `app/` | `features/`, `shared/` |
| `features/*` | `shared/`, other features (explicit, minimal — see below) |
| `shared/` | `shared/` only |
| `test/` | anything (never shipped) |

* `shared/` must **never** import `features/` or `app/`. Business logic must not
  be pushed into `shared/` to dodge a dependency; if it is business logic it
  belongs to a feature, even if two features use it.
* Runtime import cycles are forbidden. `import type` / all-`type` named imports
  are erased by `verbatimModuleSyntax`, so type-only cycles are permissible —
  but they still should not be introduced casually.
* Cross-feature imports must be few and explicit, pointing at a real file. As of
  the last refactor there are exactly two:

  | From | To | Why |
  | --- | --- | --- |
  | `workspace/graph/useSkillSlashPicker.ts` | `skills/state/skillsStore.ts` | the `/` picker can only offer *enabled* skills |
  | `workspace/state/workspaceLoader.ts` | `projects/api/projects.ts` | opening a workspace reads its project |

`src/test/architectureBoundaries.spec.ts` enforces the cycle rule and the
`shared/` isolation rule against the real source tree, so a violation fails
`npm test` rather than being caught in review.

## 3. Naming

* Feature directories: lowercase, hyphenated (`model-settings`).
* Vue components: `PascalCase.vue`.
* Other TypeScript modules: `camelCase.ts`.
* Pinia stores: `xxxStore.ts`. Vue composables: `useXxx.ts`.
* Plain helper functions are **not** composables — put them in a feature's
  `presentation/` (or `shared/lib/` if genuinely framework-free and shared).
* Avoid new `common/`, `helpers/`, `manager/` directories: they have no owner.

## 4. Where does new code go?

1. Does it belong to one product capability? → `features/<capability>/`, in the
   subdirectory matching its role (`components`, `state`, `api`, `presentation`,
   `graph`).
2. Is it a canvas concern (node rendering, edges, layout, viewport, selection,
   projection, undo wiring)? → `features/workspace/graph/`.
3. Is it reusable across features with no business meaning (a dialog shell, a
   toggle, a date formatter, a stored-key helper)? → `shared/`.
4. Is it document parsing/preview/OCR? → `shared/document/`.
5. Is it a backend DTO that mirrors the frozen HTTP contract? →
   `shared/contracts/types.ts` (keep it whole; it tracks a frozen API, not a
   feature).
6. Is it route wiring or a page shell? → `app/`.

Do not create a pass-through component or directory: if a module only re-exports
or only renders a child with no added DOM, props, or behaviour, fold it into its
consumer.

## 5. Tests

* Unit specs live in a `__tests__/` directory beside the code they cover, named
  after the module under test.
* Feature-specific specs move with their feature.
* Cross-feature/architecture specs (`managementBoundaries`, `architectureBoundaries`)
  live in `src/test/` because they are about the whole tree, not one feature.
* Shared test fixtures and setup stay in `src/test/`.

## 6. Verification commands

Run from `frontend/`:

```bash
npm run typecheck     # vue-tsc --noEmit
npm run test          # vitest run
npm run build         # vue-tsc --noEmit && vite build
npm run test:e2e      # needs a running backend, see playwright.config.ts
```

`npm run typecheck` matters even when `npm run test` is green: vitest does not
type-check, so type errors (including broken `@ts-expect-error` contracts such as
`features/workspace/state/__tests__/slicesReadonly.spec.ts`) surface only there.
