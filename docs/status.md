# fintrace — current status

**Last updated:** 2026-09-07 · **Milestone:** M1 (in progress) · **Suite:** 199 tests green — every area on the same
response convention
(`cd fintrace-core && ./gradlew clean test`)

> **This file is regenerated, not appended to.** It records where development stands right now;
> the reasoning lives in `design-decisions.md`, the ordering in `roadmap.md` and `tasks.md`, and
> the per-milestone plan in `plans/`. If this file disagrees with `design-decisions.md`, the
> design record wins and this file is stale.

---

## Where the code is

**M0 complete** (0.1–0.13). **M1: 6 of 27 task items**, and both shapes every later aggregate
copies — the workspace lifecycle and the operation aggregate — are finished.

| Area                           | State                                                                                                                 |
|--------------------------------|-----------------------------------------------------------------------------------------------------------------------|
| Operations                     | create / revise / cancel end to end: command → validation → event → projection, one transaction, plus REST and replay |
| Workspaces                     | CRUD, four-status lifecycle, ownership, optimistic locking, seven documented endpoints                                |
| Identity                       | `t_users` as a projection of the IdP; `IdentityProvider` resolves the caller by subject                               |
| Accounts                       | CRUD, archive/unarchive, validation, six documented endpoints                                                         |
| Categories, transfers, anchors | not started                                                                                                           |
| Importer, BFF, web             | later milestones                                                                                                      |

### Done in M1

- **1.1** `t_workspaces` + `t_users`, `owner_id`, `version`, `updated_at`; `workspace_id` is a
  real foreign key with `ON DELETE CASCADE`
- **1.2** *partial* — create → `NEW` works; seeding the four system categories waits for
  categories (1.11–1.13)
- **1.4** every transition, including implicit `NEW → ACTIVE` at the first write;
  `requireWritable` guards the command path, `requireReadable` the read path
- **1.5** soft delete, guarded by the caller's `version`
- **1.7, 1.8, 1.10** accounts: migration, projection, create / rename / archive / unarchive, CRUD
  endpoints. 1.9 (opening balance as the first anchor) waits for anchors
- **1.17** operation revise / cancel, with the cancelled row removed rather than flagged
- Every mutation answers with its resulting state across all three areas (§10.0); only a removal
  answers 204
- **Step A** — the two shapes deferred from step 1 of `plans/M1.md`, done ahead of accounts:
  `Projection` / `TemporalProjection` and `Command` / `TemporalCommand` split so a non-temporal
  aggregate cannot be forced to invent a business date, and `ProjectionChange` /
  `ProjectionApplier` replacing `asProjection()` and `DeleteOperationProjection`
- Not on the task list: identity and ownership pulled forward from M5, optimistic locking
  (§10.0.1), a validation service per area, OpenAPI across all three controllers

### Layering as built

Controller (`@Valid`, request shape) → facade (transaction boundary, resolves identity once) →
service (domain rules, `userId` explicit, `@Transactional(MANDATORY)`) → DAO (SQL, returns row
counts the service turns into 404 / 409 / 500). Accounts and categories should copy this.

---

## What is next

**Categories (1.11–1.15)** — the first aggregate with structural invariants: same-branch moves,
no move into a descendant, `Others` stays a leaf, system categories immutable, plus the recursive
descendants query M3 reuses (1.12). Then operations' full field set (1.16), transfers, anchors.

**Carried over:** record the `DELETE /accounts/{id}/archive` deviation from §10.1 in the design
record — the API uses the archive/unarchive pair rather than §10.1's `DELETE /accounts/{id}`.

*Previously:* **Accounts (1.7, 1.8, 1.10) — unblocked.** Step A is done, so the second event-sourced aggregate
can be added without carrying a business date it has no use for, and `ProjectionApplier`'s
exhaustive `when` will refuse to compile until `AccountProjection` is wired into it. `V0006`, payloads, create / rename / archive / unarchive, `DELETE`
archives, and the rebuild-equality test extended to a second aggregate — the first real proof the
replay path generalises. Decisions to make while writing it: archive is `REVISED` not `CANCELLED`;
no optimistic locking (a stale-view rename is cheap to correct); extract the ISO-4217 currency
check so accounts and workspaces share one; archived accounts reject writes (§4.8), which
operations will call at 1.16.

**Then:** categories (1.11–1.15) → operations' full field set (1.16) → transfers (1.18–1.20) →
anchors and balances (1.21–1.26) → the emptiness check (1.3) and retention job (1.5b), which can
land any time.

---

## Pace

From git history, 2026-08-30 → 2026-09-06: **17 commits over 5 active days** (8 calendar days),
+5,340/−272 lines of code and +4,754/−172 of documentation. Commits land in batches at the end of
a session, so their timestamps say when work was committed, not how long it took — no hour figures
are inferred here.

Tree: 2,017 LOC main across 52 files, 2,873 LOC tests across 21, ~4,700 lines of docs. Test-to-code
ratio **1.46 : 1**. Nearly half of everything written is documentation, which for a project whose
first goal is practising system design is on-plan rather than overhead.

**Percent done**, three ways, because one number misleads:

| Basis | Done | Total | % |
|---|---|---|---|
| All tasks, incl. the M4–M6 placeholders | 19.5 | 117 | 17% |
| Concrete milestones only (M0–M3) | 19.5 | 85 | 23% |
| M1 alone | 4.5 | 27 | 17% |

The M1 figure understates progress: a task count weights a one-line migration like the whole
workspace lifecycle, and the two shapes every later aggregate copies are finished. The overall
figure flatters it in the other direction: M2 contains the highest-risk work in the system, and
M4 is a frontend with nothing to lean on.

At ~3.9 tasks per active day and ~2.5 active days per week, **M1–M3 is roughly 7 weeks out** —
which agrees, within its error bars, with the roadmap's own 60–100 h at 8 h/week. Both assume
tasks are interchangeable; 2.8 and the M4 items are each worth several typical M1 items.

*Recompute with:* `git log --format='%ad' --date=short | sort -u | wc -l` (active days) and the
task counts in `tasks.md`.

---

## Known gaps

Flagged, agreed, not yet done — none of them blocking:

| Gap | Where it bites |
|---|---|
| `ProjectionApplier.clear` only clears `t_operations`, with no compiler help | the first two-aggregate rebuild-equality test; a shared DAO interface plus `List<…>` injection would cover new tables by construction |
| `ProjectionTarget` lists four values, only `OPERATION` wired, so `remove` needs an `else -> error` | trimming the enum to what exists makes a missing branch a compile error |
| Emptiness check (1.3) | M2 import cannot verify a `NEW` workspace is empty |
| Retention job (1.5b) | `DELETED` workspaces accumulate; the cascade for it exists |
| 1.6's DAO guard test | nothing fails if a new query omits `workspace_id` |
| `WorkspaceResponse` exposes `ownerId` | the caller is always the owner; the field says nothing |
| Dead code: `as List<Workspace>` cast, unused `STATUS_CONFLICT_MSG` | cosmetic |
| No `NEW → ACTIVE` for a workspace nobody writes to | by design — activation is implicit |

## Deliberately deferred

- **No-op `PUT` suppression** on operations (§4.4) — appended for now; changes no API contract.
- **Pessimistic locking** — rejected as a replacement for versions, kept for M2's import, where
  activation depends on an emptiness check spanning statements.
- **Versioning on other aggregates** — decide per aggregate whether a stale-view write is
  actually harmful, rather than copying the workspace pattern.
