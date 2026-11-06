# Folder workbench contract

## Goal and preserved boundaries

Improve the existing Android organizer: offline automatic folder suggestions, easier per-folder editing and a clearer native mobile layout. Keep the current Shizuku transport, EXML validation and manual Home Up backup/restore model. Do not gain root, change launcher databases, restore automatically, add network/telemetry or publish actual backups/app inventories. Preserve exact package/activity keys, source bytes and existing user plans. This extends the existing light blue native utility identity rather than inventing a brand.

## Observable behavior

- Opening a saved source does not silently reclassify anything. Existing folders remain the default.
- Automatic suggestions offer **unfiled only** and explicitly chosen **all apps** modes. Generate a proposal first; show moved-app count, destination folder counts and uncertainty/reasons. Cancel makes no plan changes. Apply changes only the local plan; one-step undo restores it exactly.
- Classification is offline and deterministic, using a small generic rule set plus installed Android app categories. It is a recommendation, not an AI/cloud inference or verified app purpose. Unknowns are explicitly marked for review, never lost. Handle tiny groups transparently; output validation must still block singleton folders.
- Folders are the primary wayfinding surface, with app count and a clear way into a folder. Support search, multi-select and moving selected apps together into an existing/new folder, rename and merge. Remove empty folders without losing components. Avoid a stack of full-screen-height utility button rows.
- Keep a discoverable secondary menu for source import, plan import/export, ADB help, disconnect and original reset. A single clear primary action prepares a new file, never claims the launcher was updated.
- Native system Back, safe system/IME insets, >=48dp targets, readable Korean text and light/dark roles. Render real Android screenshots; no browser mockup as proof of native rendering.

## Pure Java interface (owned by planning worker)

New package `dev.local.oneuifolders.planning`, Java 8, no Android/dependencies. Public final class `FolderPlanner`:

- `AppInfo(String key, String packageName, String label, int category)` with public final fields of those names. Android category numeric values are passed as data; -1 means unknown. Do not normalize `key`, which is package + TAB + exact activity.
- `static Proposal suggest(List<AppInfo> apps, LinkedHashMap<String,List<String>> current, boolean regroupAll)`
- `Proposal` exposes public final `LinkedHashMap<String,List<String>> folders`, `Map<String,String> reasons`, `List<String> uncertainKeys`, `int movedCount`, `int singletonCount`. Returned collections must not alias inputs. Every component exactly once; deterministic ordering.
- `static LinkedHashMap<String,List<String>> copy(Map<String,List<String>> plan)` deep copy.
- `static LinkedHashMap<String,List<String>> move(LinkedHashMap<String,List<String>> current, Collection<String> keys, String target)` validates all requested keys before changing anything; preserve relative order, keep unrelated folders, remove empty groups. Existing target/no-op must not reorder its existing members. New target allowed if valid name.
- `static LinkedHashMap<String,List<String>> rename(LinkedHashMap<String,List<String>> current, String from, String to)` rejects duplicate/blank/reserved `전체`, preserves folder/member order.
- `static LinkedHashMap<String,List<String>> merge(LinkedHashMap<String,List<String>> current, String from, String into)` appends source members to existing target, rejects invalid names/same target, removes source.
- `static void validate(List<AppInfo> apps, Map<String,List<String>> plan)` exact coverage and unique/nonempty folder names; draft singleton folders allowed, duplicates/unknown/missing keys rejected. Actual EXML still requires >=2 per folder.

Tests: `android/organizer/tests/dev/local/oneuifolders/planning/FolderPlannerTest.java`, runnable `main`. Include malformed/duplicate/missing data, independent copies, exact strings/multiple activities, category/rule precedence, unknowns, preserve-mode and all-mode, singleton transparency, move/rename/merge/no-op/order, and ExmlEditor output compatibility using synthetic fixture.

## Ownership and integration

- Planning worker isolated branch/worktree owns ONLY `android/organizer/src/dev/local/oneuifolders/planning/` and matching `tests/.../planning/`.
- Android UX worker isolated branch/worktree owns ONLY `MainActivity.java` and optional `android/organizer/src/dev/local/oneuifolders/ui/`. Do not change BackupService, core, build, manifest, device-tests or docs. Use the interface above; compile with planning code only after sequential integration. Parent may provide a temporary copy in ignored build directory for compile, never commit a duplicate/stub.
- Coordinator owns parent worktree, integration, build/manifest/CI/docs/device-tests and final verification. No concurrent writer shares a worktree. Workers commit only their owned files, do not push/publish, touch devices or start agents. No parent HC address is assumed.
- Reviewer is read-only against the frozen integrated candidate; evidence-based findings only. Parent validates findings and all final artifacts.

## Acceptance

Run Python regressions and Java core/planning regressions; build/sign/inspect APK; exercise automatic preview/cancel/apply/undo and bulk editing on Android, verify exact component coverage and protected bytes, preserve a pre-existing saved plan, keep Binder negative-path checks. Capture native empty/source, folders/detail, auto preview and selection states, including dark theme/font scale where possible without interfering with a user's device. Keep private evidence outside public Git. Publish only the tested feature branch/PR, never silently merge the default branch.
