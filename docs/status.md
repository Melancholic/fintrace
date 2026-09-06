# fintrace — current status

**Last updated:** 2026-09-07 · **Milestone:** M1 (in progress) · **Suite:** 199 tests green
(`cd fintrace-core && ./gradlew clean test`)

> **This file is regenerated, not appended to.** It records where development stands right now;
> the reasoning lives in `design-decisions.md`, the ordering in `roadmap.md` and `tasks.md`, and
> the per-milestone plan in `plans/`. If this file disagrees with `design-decisions.md`, the
> design record wins and this file is stale.

---

## Where the code is

**M0 complete** (0.1–0.13). **M1: 9 of 27 task items**, and every shape a later aggregate copies
is now settled — the workspace lifecycle, the event/projection machinery, and two event-sourced
aggregates built on it.

| Area                           | State                                                                                     |
|--------------------------------|-------------------------------------------------------------------------------------------|
| Workspaces                     | CRUD, four-status lifecycle, ownership, optimistic locking, seven documented endpoints    |
| Operations                     | create / revise / cancel end to end, plus REST and replay                                 |
| Accounts                       | CRUD, archive via `DELETE` and `POST /{id}/restore`, validation, six documented endpoints |
| Identity                       | `t_users` as a projection of the IdP; `IdentityProvider` resolves the caller by subject   |
| Categories, transfers, anchors | not started                                                                               |
| Importer, BFF, web             | later milestones                                                                          |

### Done in M1

- **1.1** `t_workspaces` + `t_users`, `owner_id`, `version`, `updated_at`; `workspace_id` is a real
  foreign key with `ON DELETE CASCADE`
- **1.2** *partial* — create → `NEW` works; seeding the four system categories waits for 1.11–1.13
- **1.4** every transition, including implicit `NEW → ACTIVE` at the first write; `requireWritable`
  guards the command path, `requireReadable` the read path
- **1.5** soft delete, guarded by the caller's `version`
- **1.7, 1.8, 1.10** accounts: migration, projection, create / rename / archive / restore, CRUD
  endpoints. 1.9 (opening balance as the first anchor) waits for anchors
- **1.17** operation revise / cancel, with the cancelled row removed rather than flagged

Beyond the task list: identity and ownership pulled forward from M5, optimistic locking on
workspaces (§10.0.1), a validation service per area, OpenAPI across all four controllers, and the
response convention below.

### Conventions the next aggregate should copy

**Layering.** Controller (`@Valid`, request shape) → facade (transaction boundary, resolves
identity once) → service (domain rules, `userId` explicit, `@Transactional(MANDATORY)`) → DAO
(SQL, returns row counts the service turns into 404 / 409 / 500).

**Commands return the state they produced**, so every mutation answers with the resulting resource
and only a removal answers 204 (§10.0). The handler already holds the row, having built the
payload.

**A handler reads prior state from the newest event, never from a projection** — payloads carry
full state, so that is one indexed row rather than a fold, and a defect in derived data can never
reach the permanent log.

**`ProjectionApplier` is the only writer of projection rows.** Its `when` over the sealed
`Projection` hierarchy refuses to compile when a new row type is added unwired.

---

## What is next

**Categories (1.11–1.15)** — the first aggregate whose difficulty is structural rather than
CRUD: moves stay within a branch, a category cannot move into its own descendant, `Others` stays a
leaf, system categories are immutable, and 1.12's `WITH RECURSIVE` descendants query is shared by
the move check and M3's collapsed-mode statistics (3.8). Completing it also closes **1.2**, since
workspace creation can finally seed its four system categories — through the dispatcher, not the
command facade, or a workspace would activate itself at birth.

**Then:** operations' full field set (1.16) → transfers (1.18–1.20) → anchors and balances
(1.21–1.26). The emptiness check (1.3) and the retention job (1.5b) can land any time.

---

## Pace

From git history, 2026-08-30 → 2026-09-07: **20 commits over 6 active days** (9 calendar days).
Commits land in batches at the end of a session, so their timestamps say when work was committed,
not how long it took — no hour figures are inferred here.

Tree: 2,829 LOC main across 71 files, 3,665 LOC tests across 24, ~4,700 lines of docs.
Test-to-code ratio **1.30 : 1**.

**Percent done**, three ways, because one number misleads:

| Basis                                   | Done | Total | %   |
|-----------------------------------------|------|-------|-----|
| All tasks, incl. the M4–M6 placeholders | 22.5 | 117   | 19% |
| Concrete milestones only (M0–M3)        | 22.5 | 85    | 26% |
| M1 alone                                | 9.5  | 27    | 35% |

The M1 figure has moved fastest because accounts were largely repetition of shapes that already
existed — which is also the reason to expect categories to be slower per task: its invariants are
new, not copied. The overall figure still flatters progress, since M2 holds the highest-risk work
in the system and M4 is a frontend with nothing to lean on.

*Recompute with:* `git log --format='%ad' --date=short | sort -u | wc -l` (active days) and the
checkbox counts in `tasks.md`.

---

## Known gaps

Flagged, agreed, not yet done — none of them blocking:

| Gap                                                                                               | Where it bites                                                               |
|---------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------|
| `ProjectionTarget` declares four values, only two are wired, so `remove` keeps an `else -> error` | trimming the enum to what exists would make a missing branch a compile error |
| Emptiness check (1.3)                                                                             | M2 import cannot verify a `NEW` workspace is empty                           |
| Retention job (1.5b)                                                                              | `DELETED` workspaces accumulate; the cascade for it exists                   |
| 1.6's DAO guard test                                                                              | nothing fails if a new query omits `workspace_id`                            |
| `WorkspaceResponse` exposes `ownerId`                                                             | the caller is always the owner; the field says nothing                       |

## Deliberately deferred

- **No-op `PUT` suppression** on operations (§4.4) — appended for now; changes no API contract.
- **Intent on payloads** — full-state events give up "what the user meant" (§4.4). Recoverable
  later only for events written after the field exists, so worth deciding if a history view lands.
- **Pessimistic locking** — rejected as a replacement for versions, kept for M2's import, where
  activation depends on an emptiness check spanning statements.
- **Versioning on other aggregates** — decide per aggregate whether a stale-view write is actually
  harmful, rather than copying the workspace pattern.
