# Frontend Structure Refactor (2026-09-23)

Reorganisation of `frontend/src` so that code is grouped by product capability
instead of by technical file type. Behaviour, routes, API calls, store identity
and rendering are unchanged; the placement contract for future code lives in
`docs/FRONTEND_STRUCTURE.md`.

* Branch: `codex/frontend-structure-refactor`
* Base commit: `a22ed03`
* Scope: whole `frontend/` (sources, tests, config references). No backend,
  database, cross-language protocol or dependency changes.

## 1. Why

Before, a single screen's code was spread over up to six sibling directories:

| Concern | Was |
| --- | --- |
| Page | `views/`, `views/settings/` |
| Components | `components/`, `components/<domain>/`, `components/common/`, `components/ui/` |
| State | `stores/`, `stores/workspace/` |
| HTTP | `api/` |
| Display logic | `presentation/` |
| Helpers | `util/`, `composables/` |

Adding one feature meant touching all of them, and ownership was unreadable
(15 of 19 modules under `presentation/` belonged to a single feature).
Now each capability has one directory.

## 2. Target layout

See `docs/FRONTEND_STRUCTURE.md` for the full tree and the placement rules.

```text
src/
├── main.ts, App.vue, env.d.ts
├── app/          router · layouts · styles        (assembly layer)
├── features/     projects · workspace · global-assistant ·
│                 model-settings · skills · connections
├── shared/       ui · http · contracts · document · lib
└── test/         fixtures · setup · cross-feature boundary specs
```

## 3. Merges

Separated from the moves so each diff could be reviewed on its own.

| Action | Files | Reason |
| --- | --- | --- |
| Merged | `views/settings/ModelsSettingsView.vue` + `views/SettingsView.vue` → `features/model-settings/ModelSettingsView.vue` | The former was a pure pass-through: `<div data-test="settings-models-section"><SettingsView /></div>` — no props, no state, no logic. Both `data-test` hooks were preserved, so the rendered DOM is identical. |
| Merged | `components/providers/FreeOnlyToggle.vue` → inlined into `components/providers/ProviderModelField.vue` | Single consumer, no state, no test of its own; the parent already owned the `freeOnly` / `freeToggleId` props and the `update:freeOnly` event. The inlined markup reproduces the child's DOM, class and default `data-test` exactly. |
| Flattened | `components/common/` (one file) → `shared/ui/RichAssistantText.vue` | One-file directory with no distinct responsibility. |
| Flattened | `stores/workspace/` → `features/workspace/state/` | The subdirectory was a mirror of the `stores/` grouping that no longer exists. |
| Distributed | 15 of 19 `presentation/*.ts` → owning feature's `presentation/` | Each function had exactly one consumer feature. |

## 4. Deletions

All four were verified unreferenced: no static import, no dynamic
`import()`, no auto-registration, no route, no `data-test` consumer anywhere in
`src/` or `e2e/`. Confirmations were run before deletion and re-run after.

| File | Evidence |
| --- | --- |
| `components/ui/UiPageShell.vue` | 0 importers; not referenced by `uiFoundation.spec.ts`; the only `ui-page` `data-test` in the app was its own. |
| `components/ui/UiPageHeader.vue` | 0 importers; the only `ui-page-header*` classes in the app were its own. |
| `composables/useEscClose.ts` | 0 importers; escaping is handled by `UiDialogShell`, which has its own handler. |
| `components/RequirementStatePanel.vue` (+ its spec) | 0 production references; superseded by `features/workspace/components/RequirementDetailView.vue`, which renders the same backend claim groups and is wired into `WorkspaceInspector`. |

Removing `RequirementStatePanel.spec.ts` accounts for the only intentional test
delta: 821 → 817 tests at move time.

## 5. Deliberately kept separate

| Kept | Reason |
| --- | --- |
| `shared/lib/managementCopy.ts` | Cross-feature display vocabulary (skill source labels, connection kind labels, byte formatting) for frozen backend enums, consumed by both `skills/` and `connections/`. Splitting it would fragment one responsibility across two features and duplicate the shared formatter. |
| `shared/lib/statusCopy.ts` | Same: knowledge/runtime status labels (workspace) plus connection status text (connections). |
| `shared/contracts/types.ts` | Kept whole: it mirrors the frozen backend HTTP contract, so splitting it by feature would fragment a single external contract and invite drift. |
| `features/skills/state/raceGuard.ts` | Single consumer today, but it is a self-contained pure helper with no feature knowledge; kept as a sibling rather than inlined. |
| `features/workspace/components/RequirementDetailView.vue` vs `NodeInspector.vue` | Different interaction models (dedicated secondary view vs inline inspector), each with its own spec. |
| `shared/document/*` | Genuinely cross-feature: `FilePreviewDialog` and `ResourceBody` are used by both the workspace and skills; text extraction/OCR is used by the resource node dialog. |
| `public/`, `e2e/`, `tools/` | Kept in place — they have distinct build/runtime roles and gain nothing from being moved for symmetry. |

## 6. Cross-feature dependencies

After the refactor the whole tree has exactly two cross-feature runtime imports,
both pre-existing and both explicit single-file references:

```text
features/workspace/graph/useSkillSlashPicker.ts -> features/skills/state/skillsStore.ts
features/workspace/state/workspaceLoader.ts     -> features/projects/api/projects.ts
```

Type-only cross-feature references (erased at build time) are unchanged in kind
and are excluded from the runtime cycle check.

## 7. Verification

Baseline was captured on `a22ed03` before any edit.

| Check | Baseline | After |
| --- | --- | --- |
| `vue-tsc --noEmit` | 0 errors | 0 errors |
| `vite build` | success | success |
| `vitest run` | 100 files / 821 tests / 0 failed | 100 files / 820 tests / 0 failed |
| Runtime import cycles | 0 | 0 |
| `shared/` → `features/`/`app/` | 0 | 0 |
| Stale in-repo specifiers | — | 0 (277 files scanned) |

Interface parity, measured between the pre-refactor worktree and the refactored
tree:

* **69 of the 71** post-refactor `.vue` files have byte-identical templates
  (whitespace-normalised) to their pre-refactor counterparts. The two
  exceptions are the intentional merges above, both shown to be DOM-equivalent.
* **613 / 613** user-visible strings in the built bundles are identical.
* The built CSS bundle is **byte-identical** after normalising only Vue's
  path-derived `data-v-*` / `@keyframes` hash suffixes.

The new `src/test/architectureBoundaries.spec.ts` enforces the cycle and
`shared/` isolation rules against the live source tree, so these cannot silently
regress.

## 8. Known gaps

* `frontend/e2e` coverage in this environment is limited by two sandbox
  constraints, recorded in the handover notes: Playwright wipes its output
  directory before running (mitigated with `--output`), and one failing spec can
  stall its worker. Backend-dependent project/workspace specs need the local
  Postgres + backend stack running.
* `providerSettings.css` still carries `.provider-card__free-toggle` rules that
  no template references (pre-existing dead CSS, left untouched to avoid
  changing stylesheet semantics in a structural change).

## 9. Appendices

### 9.1 Full path mapping

#### app/layouts

| 原位置 | 新位置 |
| --- | --- |
| `views/SettingsLayout.vue` | `app/layouts/SettingsLayout.vue` |
| `views/__tests__/SettingsShell.spec.ts` | `app/layouts/__tests__/SettingsShell.spec.ts` |

#### app/router

| 原位置 | 新位置 |
| --- | --- |
| `router/__tests__/settingsRoutes.spec.ts` | `app/router/__tests__/settingsRoutes.spec.ts` |
| `router/index.ts` | `app/router/index.ts` |

#### app/styles

| 原位置 | 新位置 |
| --- | --- |
| `style.css` | `app/styles/style.css` |
| `styles/mgmt.css` | `app/styles/mgmt.css` |
| `styles/providerSettings.css` | `app/styles/providerSettings.css` |

#### features/connections

| 原位置 | 新位置 |
| --- | --- |
| `api/__tests__/connections.spec.ts` | `features/connections/api/__tests__/connections.spec.ts` |
| `api/connectionTypes.ts` | `features/connections/api/connectionTypes.ts` |
| `api/connections.ts` | `features/connections/api/connections.ts` |
| `components/connections/ConnectionCapabilityBrowser.vue` | `features/connections/components/ConnectionCapabilityBrowser.vue` |
| `components/connections/ConnectionCreateDialog.vue` | `features/connections/components/ConnectionCreateDialog.vue` |
| `components/connections/ConnectionEditDialog.vue` | `features/connections/components/ConnectionEditDialog.vue` |
| `components/connections/ConnectionLifecycleAction.vue` | `features/connections/components/ConnectionLifecycleAction.vue` |
| `components/connections/ConnectionsList.vue` | `features/connections/components/ConnectionsList.vue` |
| `components/connections/__tests__/ConnectionCapabilityBrowser.spec.ts` | `features/connections/components/__tests__/ConnectionCapabilityBrowser.spec.ts` |
| `components/connections/__tests__/ConnectionLifecycleAction.spec.ts` | `features/connections/components/__tests__/ConnectionLifecycleAction.spec.ts` |
| `components/connections/__tests__/ConnectionsList.spec.ts` | `features/connections/components/__tests__/ConnectionsList.spec.ts` |
| `stores/__tests__/connectionsStore.spec.ts` | `features/connections/state/__tests__/connectionsStore.spec.ts` |
| `stores/connectionsStore.ts` | `features/connections/state/connectionsStore.ts` |
| `views/settings/ConnectionDetailView.vue` | `features/connections/ConnectionDetailView.vue` |
| `views/settings/ConnectionsListView.vue` | `features/connections/ConnectionsListView.vue` |
| `views/settings/__tests__/ConnectionDetailView.spec.ts` | `features/connections/__tests__/ConnectionDetailView.spec.ts` |
| `views/settings/__tests__/ConnectionsListView.spec.ts` | `features/connections/__tests__/ConnectionsListView.spec.ts` |

#### features/global-assistant

| 原位置 | 新位置 |
| --- | --- |
| `api/__tests__/globalAssistant.spec.ts` | `features/global-assistant/api/__tests__/globalAssistant.spec.ts` |
| `api/__tests__/globalAssistantSteer.spec.ts` | `features/global-assistant/api/__tests__/globalAssistantSteer.spec.ts` |
| `api/globalAssistant.ts` | `features/global-assistant/api/globalAssistant.ts` |
| `api/globalAssistantEvents.ts` | `features/global-assistant/api/globalAssistantEvents.ts` |
| `components/global-assistant/AssistantComposer.vue` | `features/global-assistant/components/AssistantComposer.vue` |
| `components/global-assistant/AssistantMessage.vue` | `features/global-assistant/components/AssistantMessage.vue` |
| `components/global-assistant/AssistantPanel.vue` | `features/global-assistant/components/AssistantPanel.vue` |
| `components/global-assistant/ConversationHistory.vue` | `features/global-assistant/components/ConversationHistory.vue` |
| `components/global-assistant/ConversationTimeline.vue` | `features/global-assistant/components/ConversationTimeline.vue` |
| `components/global-assistant/GlobalAssistantShell.vue` | `features/global-assistant/components/GlobalAssistantShell.vue` |
| `components/global-assistant/ProjectResourceList.vue` | `features/global-assistant/components/ProjectResourceList.vue` |
| `components/global-assistant/ToolActivityItem.vue` | `features/global-assistant/components/ToolActivityItem.vue` |
| `components/global-assistant/__tests__/conversationHistory.spec.ts` | `features/global-assistant/components/__tests__/conversationHistory.spec.ts` |
| `components/global-assistant/__tests__/gaActivityAntiHardcoding.spec.ts` | `features/global-assistant/components/__tests__/gaActivityAntiHardcoding.spec.ts` |
| `components/global-assistant/__tests__/gaRichResources.spec.ts` | `features/global-assistant/components/__tests__/gaRichResources.spec.ts` |
| `components/global-assistant/__tests__/globalAssistantComponents.spec.ts` | `features/global-assistant/components/__tests__/globalAssistantComponents.spec.ts` |
| `presentation/__tests__/conversationLibrary.spec.ts` | `features/global-assistant/presentation/__tests__/conversationLibrary.spec.ts` |
| `presentation/__tests__/globalAssistantPresentation.spec.ts` | `features/global-assistant/presentation/__tests__/globalAssistantPresentation.spec.ts` |
| `presentation/capabilityPresentation.ts` | `features/global-assistant/presentation/capabilityPresentation.ts` |
| `presentation/conversationLibrary.ts` | `features/global-assistant/presentation/conversationLibrary.ts` |
| `presentation/globalAssistantPresentation.ts` | `features/global-assistant/presentation/globalAssistantPresentation.ts` |
| `stores/__tests__/gaAnswerStream.spec.ts` | `features/global-assistant/state/__tests__/gaAnswerStream.spec.ts` |
| `stores/__tests__/globalAssistantHistory.spec.ts` | `features/global-assistant/state/__tests__/globalAssistantHistory.spec.ts` |
| `stores/__tests__/globalAssistantSteer.spec.ts` | `features/global-assistant/state/__tests__/globalAssistantSteer.spec.ts` |
| `stores/__tests__/globalAssistantStore.spec.ts` | `features/global-assistant/state/__tests__/globalAssistantStore.spec.ts` |
| `stores/__tests__/globalAssistantSuccessorHandoff.spec.ts` | `features/global-assistant/state/__tests__/globalAssistantSuccessorHandoff.spec.ts` |
| `stores/globalAssistantStore.ts` | `features/global-assistant/state/globalAssistantStore.ts` |

#### features/model-settings

| 原位置 | 新位置 |
| --- | --- |
| `api/__tests__/modelSettings.spec.ts` | `features/model-settings/api/__tests__/modelSettings.spec.ts` |
| `api/modelProviders.ts` | `features/model-settings/api/modelProviders.ts` |
| `api/modelSettings.ts` | `features/model-settings/api/modelSettings.ts` |
| `components/providers/CustomProviderDialog.vue` | `features/model-settings/components/CustomProviderDialog.vue` |
| `components/providers/CustomProviderForm.vue` | `features/model-settings/components/CustomProviderForm.vue` |
| `components/providers/CustomProviderSettings.vue` | `features/model-settings/components/CustomProviderSettings.vue` |
| `components/providers/FreeOnlyToggle.vue` | `features/model-settings/components/FreeOnlyToggle.vue` |
| `components/providers/OpenCodeProviderSettings.vue` | `features/model-settings/components/OpenCodeProviderSettings.vue` |
| `components/providers/OpenRouterProviderSettings.vue` | `features/model-settings/components/OpenRouterProviderSettings.vue` |
| `components/providers/ProviderCard.vue` | `features/model-settings/components/ProviderCard.vue` |
| `components/providers/ProviderModelField.vue` | `features/model-settings/components/ProviderModelField.vue` |
| `components/providers/ProviderSettingsSection.vue` | `features/model-settings/components/ProviderSettingsSection.vue` |
| `components/providers/ProviderStatePill.vue` | `features/model-settings/components/ProviderStatePill.vue` |
| `components/providers/ProviderSummaryItem.vue` | `features/model-settings/components/ProviderSummaryItem.vue` |
| `components/providers/__tests__/OpenRouterProviderSettings.spec.ts` | `features/model-settings/components/__tests__/OpenRouterProviderSettings.spec.ts` |
| `components/providers/__tests__/ProviderSettingsSection.spec.ts` | `features/model-settings/components/__tests__/ProviderSettingsSection.spec.ts` |
| `components/providers/__tests__/providerSettings.spec.ts` | `features/model-settings/components/__tests__/providerSettings.spec.ts` |
| `presentation/__tests__/providerPresentation.spec.ts` | `features/model-settings/presentation/__tests__/providerPresentation.spec.ts` |
| `presentation/providerPresentation.ts` | `features/model-settings/presentation/providerPresentation.ts` |
| `stores/__tests__/modelSettingsStore.spec.ts` | `features/model-settings/state/__tests__/modelSettingsStore.spec.ts` |
| `stores/__tests__/persistedModel.spec.ts` | `features/model-settings/state/__tests__/persistedModel.spec.ts` |
| `stores/__tests__/providerSettings.spec.ts` | `features/model-settings/state/__tests__/providerSettings.spec.ts` |
| `stores/customProviderStore.ts` | `features/model-settings/state/customProviderStore.ts` |
| `stores/modelSettingsStore.ts` | `features/model-settings/state/modelSettingsStore.ts` |
| `stores/openRouterStore.ts` | `features/model-settings/state/openRouterStore.ts` |
| `stores/providerSettingsStore.ts` | `features/model-settings/state/providerSettingsStore.ts` |
| `views/SettingsView.vue` | `features/model-settings/SettingsView.vue` |
| `views/__tests__/SettingsView.spec.ts` | `features/model-settings/__tests__/SettingsView.spec.ts` |
| `views/settings/ModelsSettingsView.vue` | `features/model-settings/ModelsSettingsView.vue` |

#### features/projects

| 原位置 | 新位置 |
| --- | --- |
| `api/projects.ts` | `features/projects/api/projects.ts` |
| `components/ProjectCreateForm.vue` | `features/projects/components/ProjectCreateForm.vue` |
| `presentation/__tests__/projectPresentation.spec.ts` | `features/projects/presentation/__tests__/projectPresentation.spec.ts` |
| `presentation/__tests__/projectSearch.spec.ts` | `features/projects/presentation/__tests__/projectSearch.spec.ts` |
| `presentation/projectPresentation.ts` | `features/projects/presentation/projectPresentation.ts` |
| `presentation/projectSearch.ts` | `features/projects/presentation/projectSearch.ts` |
| `stores/__tests__/projectStore.spec.ts` | `features/projects/state/__tests__/projectStore.spec.ts` |
| `stores/projectStore.ts` | `features/projects/state/projectStore.ts` |
| `views/ProjectsView.vue` | `features/projects/ProjectsView.vue` |
| `views/__tests__/ProjectsDelete.spec.ts` | `features/projects/__tests__/ProjectsDelete.spec.ts` |
| `views/__tests__/ProjectsView.spec.ts` | `features/projects/__tests__/ProjectsView.spec.ts` |

#### features/skills

| 原位置 | 新位置 |
| --- | --- |
| `api/__tests__/skills.spec.ts` | `features/skills/api/__tests__/skills.spec.ts` |
| `api/skillTypes.ts` | `features/skills/api/skillTypes.ts` |
| `api/skills.ts` | `features/skills/api/skills.ts` |
| `components/skills/SkillImportDialog.vue` | `features/skills/components/SkillImportDialog.vue` |
| `components/skills/SkillImportReview.vue` | `features/skills/components/SkillImportReview.vue` |
| `components/skills/SkillResourceViewer.vue` | `features/skills/components/SkillResourceViewer.vue` |
| `components/skills/SkillsList.vue` | `features/skills/components/SkillsList.vue` |
| `components/skills/__tests__/SkillImportDialog.spec.ts` | `features/skills/components/__tests__/SkillImportDialog.spec.ts` |
| `components/skills/__tests__/SkillImportReview.spec.ts` | `features/skills/components/__tests__/SkillImportReview.spec.ts` |
| `components/skills/__tests__/SkillsList.spec.ts` | `features/skills/components/__tests__/SkillsList.spec.ts` |
| `stores/__tests__/skillsStore.spec.ts` | `features/skills/state/__tests__/skillsStore.spec.ts` |
| `stores/raceGuard.ts` | `features/skills/state/raceGuard.ts` |
| `stores/skillsStore.ts` | `features/skills/state/skillsStore.ts` |
| `views/settings/SkillDetailView.vue` | `features/skills/SkillDetailView.vue` |
| `views/settings/SkillsListView.vue` | `features/skills/SkillsListView.vue` |
| `views/settings/__tests__/SkillDetailView.spec.ts` | `features/skills/__tests__/SkillDetailView.spec.ts` |
| `views/settings/__tests__/SkillsListView.spec.ts` | `features/skills/__tests__/SkillsListView.spec.ts` |

#### features/workspace

| 原位置 | 新位置 |
| --- | --- |
| `api/__tests__/graph.spec.ts` | `features/workspace/api/__tests__/graph.spec.ts` |
| `api/__tests__/requirementState.spec.ts` | `features/workspace/api/__tests__/requirementState.spec.ts` |
| `api/agentRuns.ts` | `features/workspace/api/agentRuns.ts` |
| `api/graph.ts` | `features/workspace/api/graph.ts` |
| `api/graphCommands.ts` | `features/workspace/api/graphCommands.ts` |
| `api/requirementState.ts` | `features/workspace/api/requirementState.ts` |
| `api/routes.ts` | `features/workspace/api/routes.ts` |
| `api/spec.ts` | `features/workspace/api/spec.ts` |
| `api/workspace.ts` | `features/workspace/api/workspace.ts` |
| `components/ConfirmRouteActionDialog.vue` | `features/workspace/components/ConfirmRouteActionDialog.vue` |
| `components/RegenerateNodeDialog.vue` | `features/workspace/components/RegenerateNodeDialog.vue` |
| `components/ResourceDialog.vue` | `features/workspace/components/ResourceDialog.vue` |
| `components/RouteActionDialog.vue` | `features/workspace/components/RouteActionDialog.vue` |
| `components/__tests__/ConfirmRouteActionDialog.spec.ts` | `features/workspace/components/__tests__/ConfirmRouteActionDialog.spec.ts` |
| `components/__tests__/RegenerateNodeDialog.spec.ts` | `features/workspace/components/__tests__/RegenerateNodeDialog.spec.ts` |
| `components/__tests__/RouteActionDialog.spec.ts` | `features/workspace/components/__tests__/RouteActionDialog.spec.ts` |
| `components/graph/AdaptiveGraphEdge.vue` | `features/workspace/graph/components/AdaptiveGraphEdge.vue` |
| `components/graph/GraphCanvas.vue` | `features/workspace/graph/components/GraphCanvas.vue` |
| `components/graph/GraphKnowledgeNode.vue` | `features/workspace/graph/components/GraphKnowledgeNode.vue` |
| `components/graph/GraphNodeShell.vue` | `features/workspace/graph/components/GraphNodeShell.vue` |
| `components/graph/GraphQuestionNode.vue` | `features/workspace/graph/components/GraphQuestionNode.vue` |
| `components/graph/GraphRunProcessPanel.vue` | `features/workspace/graph/components/GraphRunProcessPanel.vue` |
| `components/graph/GraphStartPlaceholder.vue` | `features/workspace/graph/components/GraphStartPlaceholder.vue` |
| `components/graph/GraphToolbar.vue` | `features/workspace/graph/components/GraphToolbar.vue` |
| `components/graph/RelationProposalDialog.vue` | `features/workspace/graph/components/RelationProposalDialog.vue` |
| `components/graph/SkillSlashMenu.vue` | `features/workspace/graph/components/SkillSlashMenu.vue` |
| `components/graph/__tests__/AdaptiveGraphEdge.spec.ts` | `features/workspace/graph/components/__tests__/AdaptiveGraphEdge.spec.ts` |
| `components/graph/__tests__/GraphCanvas.spec.ts` | `features/workspace/graph/components/__tests__/GraphCanvas.spec.ts` |
| `components/graph/__tests__/GraphFileCard.spec.ts` | `features/workspace/graph/components/__tests__/GraphFileCard.spec.ts` |
| `components/graph/__tests__/GraphKnowledgeNode.spec.ts` | `features/workspace/graph/components/__tests__/GraphKnowledgeNode.spec.ts` |
| `components/graph/__tests__/GraphQuestionNode.spec.ts` | `features/workspace/graph/components/__tests__/GraphQuestionNode.spec.ts` |
| `components/graph/__tests__/GraphRunProcessPanel.spec.ts` | `features/workspace/graph/components/__tests__/GraphRunProcessPanel.spec.ts` |
| `components/graph/__tests__/GraphToolbar.spec.ts` | `features/workspace/graph/components/__tests__/GraphToolbar.spec.ts` |
| `components/graph/__tests__/RelationProposalDialog.spec.ts` | `features/workspace/graph/components/__tests__/RelationProposalDialog.spec.ts` |
| `components/workspace/AgentProposalCard.vue` | `features/workspace/components/AgentProposalCard.vue` |
| `components/workspace/NodeInspector.vue` | `features/workspace/components/NodeInspector.vue` |
| `components/workspace/ProjectSummary.vue` | `features/workspace/components/ProjectSummary.vue` |
| `components/workspace/RecoveryNotice.vue` | `features/workspace/components/RecoveryNotice.vue` |
| `components/workspace/RequirementDetailView.vue` | `features/workspace/components/RequirementDetailView.vue` |
| `components/workspace/ResizableSidebar.vue` | `features/workspace/components/ResizableSidebar.vue` |
| `components/workspace/RouteSidebar.vue` | `features/workspace/components/RouteSidebar.vue` |
| `components/workspace/SpecDock.vue` | `features/workspace/components/SpecDock.vue` |
| `components/workspace/WorkspaceInspector.vue` | `features/workspace/components/WorkspaceInspector.vue` |
| `components/workspace/__tests__/AgentProposalCard.spec.ts` | `features/workspace/components/__tests__/AgentProposalCard.spec.ts` |
| `components/workspace/__tests__/NodeInspector.spec.ts` | `features/workspace/components/__tests__/NodeInspector.spec.ts` |
| `components/workspace/__tests__/ProjectSummary.spec.ts` | `features/workspace/components/__tests__/ProjectSummary.spec.ts` |
| `components/workspace/__tests__/RecoveryNotice.spec.ts` | `features/workspace/components/__tests__/RecoveryNotice.spec.ts` |
| `components/workspace/__tests__/RequirementDetailView.spec.ts` | `features/workspace/components/__tests__/RequirementDetailView.spec.ts` |
| `components/workspace/__tests__/ResizableSidebar.spec.ts` | `features/workspace/components/__tests__/ResizableSidebar.spec.ts` |
| `components/workspace/__tests__/RouteSidebar.spec.ts` | `features/workspace/components/__tests__/RouteSidebar.spec.ts` |
| `components/workspace/__tests__/SpecDock.spec.ts` | `features/workspace/components/__tests__/SpecDock.spec.ts` |
| `components/workspace/__tests__/WorkspaceInspector.spec.ts` | `features/workspace/components/__tests__/WorkspaceInspector.spec.ts` |
| `composables/__tests__/useSkillSlashPicker.spec.ts` | `features/workspace/graph/__tests__/useSkillSlashPicker.spec.ts` |
| `composables/useSkillSlashPicker.ts` | `features/workspace/graph/useSkillSlashPicker.ts` |
| `graph/__tests__/graphEdgeRouting.spec.ts` | `features/workspace/graph/__tests__/graphEdgeRouting.spec.ts` |
| `graph/__tests__/graphInteraction.spec.ts` | `features/workspace/graph/__tests__/graphInteraction.spec.ts` |
| `graph/__tests__/graphLayout.spec.ts` | `features/workspace/graph/__tests__/graphLayout.spec.ts` |
| `graph/__tests__/graphLayoutStorage.spec.ts` | `features/workspace/graph/__tests__/graphLayoutStorage.spec.ts` |
| `graph/__tests__/graphProjection.spec.ts` | `features/workspace/graph/__tests__/graphProjection.spec.ts` |
| `graph/__tests__/graphViewport.safeRegion.spec.ts` | `features/workspace/graph/__tests__/graphViewport.safeRegion.spec.ts` |
| `graph/__tests__/graphViewport.spec.ts` | `features/workspace/graph/__tests__/graphViewport.spec.ts` |
| `graph/__tests__/graphVisualIdentity.spec.ts` | `features/workspace/graph/__tests__/graphVisualIdentity.spec.ts` |
| `graph/__tests__/phaseCopy.spec.ts` | `features/workspace/graph/__tests__/phaseCopy.spec.ts` |
| `graph/graphEdgeRouting.ts` | `features/workspace/graph/graphEdgeRouting.ts` |
| `graph/graphInteraction.ts` | `features/workspace/graph/graphInteraction.ts` |
| `graph/graphLayout.ts` | `features/workspace/graph/graphLayout.ts` |
| `graph/graphLayoutStorage.ts` | `features/workspace/graph/graphLayoutStorage.ts` |
| `graph/graphProjection.ts` | `features/workspace/graph/graphProjection.ts` |
| `graph/graphTypes.ts` | `features/workspace/graph/graphTypes.ts` |
| `graph/graphViewport.ts` | `features/workspace/graph/graphViewport.ts` |
| `graph/graphVisualIdentity.ts` | `features/workspace/graph/graphVisualIdentity.ts` |
| `graph/nodeActions.ts` | `features/workspace/graph/nodeActions.ts` |
| `graph/phaseCopy.ts` | `features/workspace/graph/phaseCopy.ts` |
| `presentation/__tests__/agentPresentation.spec.ts` | `features/workspace/presentation/__tests__/agentPresentation.spec.ts` |
| `presentation/__tests__/recoveryPresentation.spec.ts` | `features/workspace/presentation/__tests__/recoveryPresentation.spec.ts` |
| `presentation/agentPresentation.ts` | `features/workspace/presentation/agentPresentation.ts` |
| `presentation/recoveryPresentation.ts` | `features/workspace/presentation/recoveryPresentation.ts` |
| `presentation/routePresentation.ts` | `features/workspace/presentation/routePresentation.ts` |
| `presentation/specPresentation.ts` | `features/workspace/presentation/specPresentation.ts` |
| `stores/__tests__/answerDraftCleanup.spec.ts` | `features/workspace/state/__tests__/answerDraftCleanup.spec.ts` |
| `stores/__tests__/graphUiStore.spec.ts` | `features/workspace/state/__tests__/graphUiStore.spec.ts` |
| `stores/__tests__/inputDraftStore.spec.ts` | `features/workspace/state/__tests__/inputDraftStore.spec.ts` |
| `stores/__tests__/runRegistryStore.spec.ts` | `features/workspace/state/__tests__/runRegistryStore.spec.ts` |
| `stores/__tests__/workspaceAnswerSessions.spec.ts` | `features/workspace/state/__tests__/workspaceAnswerSessions.spec.ts` |
| `stores/__tests__/workspaceChainPolling.spec.ts` | `features/workspace/state/__tests__/workspaceChainPolling.spec.ts` |
| `stores/__tests__/workspaceCleanupOwnership.spec.ts` | `features/workspace/state/__tests__/workspaceCleanupOwnership.spec.ts` |
| `stores/__tests__/workspaceProjectSession.spec.ts` | `features/workspace/state/__tests__/workspaceProjectSession.spec.ts` |
| `stores/__tests__/workspaceRouteStore.spec.ts` | `features/workspace/state/__tests__/workspaceRouteStore.spec.ts` |
| `stores/__tests__/workspaceStore.spec.ts` | `features/workspace/state/__tests__/workspaceStore.spec.ts` |
| `stores/graphUiStore.ts` | `features/workspace/state/graphUiStore.ts` |
| `stores/inputDraftStore.ts` | `features/workspace/state/inputDraftStore.ts` |
| `stores/runRegistryStore.ts` | `features/workspace/state/runRegistryStore.ts` |
| `stores/workspace/__tests__/slicesReadonly.spec.ts` | `features/workspace/state/__tests__/slicesReadonly.spec.ts` |
| `stores/workspace/graphUndo.ts` | `features/workspace/state/graphUndo.ts` |
| `stores/workspace/proposals.ts` | `features/workspace/state/proposals.ts` |
| `stores/workspace/resources.ts` | `features/workspace/state/resources.ts` |
| `stores/workspace/routeCommands.ts` | `features/workspace/state/routeCommands.ts` |
| `stores/workspace/shared.ts` | `features/workspace/state/shared.ts` |
| `stores/workspace/slices.ts` | `features/workspace/state/slices.ts` |
| `stores/workspace/specDock.ts` | `features/workspace/state/specDock.ts` |
| `stores/workspace/types.ts` | `features/workspace/state/types.ts` |
| `stores/workspace/workspaceLoader.ts` | `features/workspace/state/workspaceLoader.ts` |
| `stores/workspace/workspaceRuns.ts` | `features/workspace/state/workspaceRuns.ts` |
| `stores/workspaceStore.ts` | `features/workspace/state/workspaceStore.ts` |
| `views/WorkspaceView.vue` | `features/workspace/WorkspaceView.vue` |
| `views/__tests__/WorkspaceCenterStatus.spec.ts` | `features/workspace/__tests__/WorkspaceCenterStatus.spec.ts` |
| `views/__tests__/WorkspaceView.spec.ts` | `features/workspace/__tests__/WorkspaceView.spec.ts` |

#### shared/contracts

| 原位置 | 新位置 |
| --- | --- |
| `api/types.ts` | `shared/contracts/types.ts` |

#### shared/document

| 原位置 | 新位置 |
| --- | --- |
| `components/FilePreviewDialog.vue` | `shared/document/FilePreviewDialog.vue` |
| `components/ResourceBody.vue` | `shared/document/ResourceBody.vue` |
| `presentation/__tests__/readerZoom.spec.ts` | `shared/document/__tests__/readerZoom.spec.ts` |
| `presentation/readerZoom.ts` | `shared/document/readerZoom.ts` |
| `presentation/resourceKind.ts` | `shared/document/resourceKind.ts` |
| `util/__tests__/documentText.spec.ts` | `shared/document/__tests__/documentText.spec.ts` |
| `util/documentText.ts` | `shared/document/documentText.ts` |

#### shared/http

| 原位置 | 新位置 |
| --- | --- |
| `api/__tests__/client.spec.ts` | `shared/http/__tests__/client.spec.ts` |
| `api/__tests__/errorCopy.spec.ts` | `shared/http/__tests__/errorCopy.spec.ts` |
| `api/client.ts` | `shared/http/client.ts` |
| `api/displayError.ts` | `shared/http/displayError.ts` |
| `api/errorCopy.ts` | `shared/http/errorCopy.ts` |

#### shared/lib

| 原位置 | 新位置 |
| --- | --- |
| `composables/timing.ts` | `shared/lib/timing.ts` |
| `presentation/__tests__/managementCopy.spec.ts` | `shared/lib/__tests__/managementCopy.spec.ts` |
| `presentation/formatTime.ts` | `shared/lib/formatTime.ts` |
| `presentation/managementCopy.ts` | `shared/lib/managementCopy.ts` |
| `presentation/statusCopy.ts` | `shared/lib/statusCopy.ts` |
| `util/safeStorage.ts` | `shared/lib/safeStorage.ts` |

#### shared/ui

| 原位置 | 新位置 |
| --- | --- |
| `components/ApiErrorBanner.vue` | `shared/ui/ApiErrorBanner.vue` |
| `components/AppIcon.vue` | `shared/ui/AppIcon.vue` |
| `components/BackLink.vue` | `shared/ui/BackLink.vue` |
| `components/__tests__/ApiErrorBanner.spec.ts` | `shared/ui/__tests__/ApiErrorBanner.spec.ts` |
| `components/__tests__/BackLink.spec.ts` | `shared/ui/__tests__/BackLink.spec.ts` |
| `components/common/RichAssistantText.vue` | `shared/ui/RichAssistantText.vue` |
| `components/ui/ToggleSwitch.vue` | `shared/ui/ToggleSwitch.vue` |
| `components/ui/UiConfirmDialog.vue` | `shared/ui/UiConfirmDialog.vue` |
| `components/ui/UiDialogShell.vue` | `shared/ui/UiDialogShell.vue` |
| `components/ui/UiFilePicker.vue` | `shared/ui/UiFilePicker.vue` |
| `components/ui/UiFormField.vue` | `shared/ui/UiFormField.vue` |
| `components/ui/__tests__/uiFoundation.spec.ts` | `shared/ui/__tests__/uiFoundation.spec.ts` |
| `composables/useDialogForm.ts` | `shared/ui/useDialogForm.ts` |

#### test

| 原位置 | 新位置 |
| --- | --- |
| `__tests__/managementBoundaries.spec.ts` | `test/managementBoundaries.spec.ts` |



### 9.2 Reproducing the checks

```bash
# from the repository root
python scratch/frontend-refactor-map.py            # the mapping (source of truth)
python scratch/frontend-verify-paths.py            # no stale in-repo specifiers
python scratch/frontend-dep-graph.py               # cycles + layering + cross-feature edges
python scratch/ui-surface-fingerprint.py <src> <out.json>
python scratch/ui-surface-diff.py before.json after.json

# from frontend/
npm run typecheck && npm run test && npm run build
```
