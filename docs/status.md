# fintrace — current status

**Last updated:** 2026-09-08 · **Milestone:** M1 (in progress) · **Suite:** 242 tests green
(`cd fintrace-core && ./gradlew clean test`)

> **This file is regenerated, not appended to.** It records where development stands right now;
> the reasoning lives in `design-decisions.md`, the ordering in `roadmap.md` and `tasks.md`, and
> the per-milestone plan in `plans/`. If this file disagrees with `design-decisions.md`, the
> design record wins and this file is stale.

---

## Where the code is

**M0 complete** (0.1–0.13). **M1: 13 of 27 task items.** Three event-sourced aggregates now sit on
the same machinery, and the hardest one — the category tree, with the only structural invariants in
the system — is finished. Replay covers all three.

| Area               | State                                                                                   |
|--------------------|-----------------------------------------------------------------------------------------|
| Workspaces         | CRUD, four-status lifecycle, ownership, optimistic locking, seven documented endpoints  |
| Operations         | create / revise / cancel end to end, plus REST and replay                               |
| Accounts           | CRUD, archive via `DELETE` and `POST /{id}/restore`, six documented endpoints           |
| Categories         | tree with all invariants, archive cascade, seeding, six documented endpoints            |
| Identity           | `t_users` as a projection of the IdP; `IdentityProvider` resolves the caller by subject |
| Transfers, anchors | not started                                                                             |
| Importer, BFF, web | later milestones                                                                        |

### Done in M1

- **1.1, 1.2** `t_workspaces` + `t_users`, ownership, versioning; creation seeds the four system
  categories through the dispatcher, so a new workspace stays `NEW`
- **1.4, 1.5** every status transition, implicit `NEW → ACTIVE` at the first write, guards on both
  facades, soft delete guarded by the caller's `version`
- **1.7, 1.8, 1.10** accounts end to end. 1.9 (opening balance as the first anchor) waits for anchors
- **1.11–1.15** categories: adjacency-list tree, `findSubtreeIds`, create / revise / move / archive,
  every invariant in §4.7, CRUD endpoints
- **1.17** operation revise / cancel, with the cancelled row removed rather than flagged

Beyond the task list: identity and ownership pulled forward from M5, optimistic locking on
workspaces (§10.0.1), a validation service per area, OpenAPI across all five controllers, and the
response convention below.

### Conventions the next aggregate should copy

**Layering.** Controller (`@Valid`, request shape) → facade (transaction boundary, resolves
identity once) → service (domain rules, `userId` explicit, `@Transactional(MANDATORY)`) → DAO
(SQL, returns row counts the service turns into 404 / 409 / 500).

**Commands return the state they produced**, so every mutation answers with the resulting resource
and only a removal answers 204 (§10.0).

**A handler reads prior state from the newest event, never from a projection** — payloads carry
full state, so that is one indexed row rather than a fold, and a defect in derived data can never
reach the permanent log. The exception is a *structural* query the log cannot answer: the archive
cascade resolves its subtree from the projection, then still takes each node's state from its own
newest event.

**One command may append several events.** Archiving a category subtree writes one event per node,
because an event's `aggregate_id` identifies the entity it describes — recording a child's change
on the parent's stream would leave the child's own state stale. `AdminFacadeReplayTest` now pins
this: a rebuilt subtree comes back archived only because each node has its own event.

**`ProjectionApplier` is the only writer of projection rows**, and its `when` over the sealed
`Projection` hierarchy refuses to compile when a new row type is added unwired.

**Two workspace fixtures, chosen deliberately.** `TestWorkspaces.create` inserts the row and
nothing else, so `t_events` starts empty and a test can count events from zero;
`createWithCategories` goes through `WorkspaceService`, so the four system categories exist and the
count starts at four. From 1.16 onward, anything touching an operation needs the second.

---

## What is next

**Operations' full field set (1.16)** — `kind`, `account_id`, `category_id`, `comment`,
`external_ref`, and the validation that ties them to the two aggregates just built: the account
must exist and not be archived, the category must exist, not be archived, and match the operation's
kind. This is where the three aggregates finally meet.

**Then:** transfers (1.18–1.20) — one event, two linked legs — and anchors with balances
(1.21–1.26), which also closes 1.9. The emptiness check (1.3) and the retention job (1.5b) can land
any time.

---

## Pace

From git history, 2026-08-30 → 2026-09-08: **22 commits over 7 active days** (10 calendar days).
Commits land in batches at the end of a session, so their timestamps say when work was committed,
not how long it took — no hour figures are inferred here. The working tree is clean: the whole
category area is in `cdc9a2a`, so the commit count and the tree agree.

Tree: 3,887 LOC main across 89 files, 4,482 LOC tests across 26, plus 7 migrations.
Test-to-code ratio **1.15 : 1**.

**Percent done**, three ways, because one number misleads:

| Basis                                   | Done | Total | %   |
|-----------------------------------------|------|-------|-----|
| All tasks, incl. the M4–M6 placeholders | 28   | 117   | 24% |
| Concrete milestones only (M0–M3)        | 28   | 85    | 33% |
| M1 alone                                | 13   | 27    | 48% |

The two M2 spikes (2.1–2.4, the dump inspection and the `StatisticsProvider.kt` read) count as
done — the previous revision of this file missed them, which is where the +2 comes from, not new
work.

M1 is roughly half done by count, and the remaining half is less repetitive than the last:
transfers introduce the first multi-row aggregate, and anchors bring the balance arithmetic. The
overall figure still flatters progress — M2 holds the highest-risk work in the system and M4 is a
frontend with nothing to lean on.

*Recompute with:* `git log --format='%ad' --date=short | sort -u | wc -l` (active days) and
`grep -c '^- \[x\]' docs/tasks.md` (done tasks).

---

## Known gaps

Flagged, agreed, not yet done — none of them blocking:

| Gap                                                           | Where it bites                                                |
|---------------------------------------------------------------|---------------------------------------------------------------|
| `ProjectionTarget` declares four values, only three are wired | trimming the enum would make a missing branch a compile error |
| Emptiness check (1.3)                                         | M2 import cannot verify a `NEW` workspace is empty            |
| Retention job (1.5b)                                          | `DELETED` workspaces accumulate; the cascade for it exists    |
| 1.6's DAO guard test                                          | nothing fails if a new query omits `workspace_id`             |
| `WorkspaceResponse` exposes `ownerId`                         | the caller is always the owner; the field says nothing        |

## Deliberately deferred

- **No-op `PUT` suppression** on operations (§4.4) — appended for now; changes no API contract.
- **Intent on payloads** — full-state events give up "what the user meant" (§4.4). Recoverable
  later only for events written after the field exists.
- **Restoring a whole archived subtree** — restore returns only the target. Doing it properly needs
  a correlation id or `archived_at` so a restore knows what *that* cascade touched.
- **Pessimistic locking** — rejected as a replacement for versions, kept for M2's import.
- **Versioning on other aggregates** — decide per aggregate whether a stale-view write is harmful.
