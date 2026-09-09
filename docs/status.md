# fintrace — current status

**Last updated:** 2026-09-09 · **Milestone:** M1 (in progress) · **Suite:** green
(`cd fintrace-core && ./gradlew clean test`)

> **This file is regenerated, not appended to.** It records where development stands right now;
> the reasoning lives in `design-decisions.md`, the ordering in `roadmap.md` and `tasks.md`, and
> the per-milestone plan in `plans/`. If this file disagrees with `design-decisions.md`, the
> design record wins and this file is stale.

---

## Where the code is

**M0 complete** (0.1–0.13). **M1: 15 of 27 task items.** The three aggregates now meet: an
operation names an account and a category, and every rule tying them together is checked at
command time. Replay covers all three, and tenant scoping is now enforced by a test rather than
by care.

| Area               | State                                                                                    |
|--------------------|------------------------------------------------------------------------------------------|
| Workspaces         | CRUD, four-status lifecycle, ownership, optimistic locking, seven documented endpoints   |
| Operations         | full §4.13 field set, create / revise / cancel, cross-aggregate validation, REST, replay |
| Accounts           | CRUD, archive via `DELETE` and `POST /{id}/restore`, six documented endpoints            |
| Categories         | tree with all invariants, archive cascade, seeding by system code, six endpoints         |
| Identity           | `t_users` as a projection of the IdP; `IdentityProvider` resolves the caller by subject  |
| Transfers, anchors | not started                                                                              |
| Importer, BFF, web | later milestones                                                                         |

### Done in M1

- **1.1, 1.2** `t_workspaces` + `t_users`, ownership, versioning; creation seeds the four system
  categories through the dispatcher, so a new workspace stays `NEW`
- **1.4, 1.5** every status transition, implicit `NEW → ACTIVE` at the first write, guards on both
  facades, soft delete guarded by the caller's `version`
- **1.7, 1.8, 1.10** accounts end to end. 1.9 (opening balance as the first anchor) waits for anchors
- **1.11–1.15** categories: adjacency-list tree, `findSubtreeIds`, create / revise / move / archive,
  every invariant in §4.7, CRUD endpoints
- **1.16, 1.17** operations complete: the full field set, revise / cancel, and the validation that
  ties an operation to the two aggregates beneath it
- **1.6** `workspace_id` on every query, by explicit parameter, guarded by `WorkspaceScopingTest`

Beyond the task list: identity and ownership pulled forward from M5, optimistic locking on
workspaces (§10.0.1), a validation service per area, OpenAPI across all five controllers, and the
conventions below.

### What 1.16 settled

**The sign is an internal convention, not part of the API.** A request carries a magnitude plus a
`kind`; `OperationKind.signedAmount` applies the sign in the handler, so an expense is stored
negative and a balance stays `SUM(amount)` with no `CASE` (§4.13). The response is absolute again,
so a client can read an operation and write it straight back. Doing the conversion in the handler
rather than the mapper is deliberate: the CLI and the importer build commands directly and would
bypass anything living in the web layer.

**`kind = TRANSFER` is refused on `/operations`.** A leg written there would have no counterpart
and no `transfer_id` — a half-transfer no rebuild can repair. This is 1.19 arriving early, and it
is what keeps 1.18 free to assume both legs always exist.

**A null category is resolved at command time**, to its branch's `Others`, so the event names the
category it chose. Resolving at projection time would leave the log and a rebuild disagreeing
about what happened. An *explicit* category that does not exist is still a 404 — the fallback is
for an absent category, never a wrong one.

**Categories are identified by `system_code`, not by name.** The `system` boolean became a
nullable enum (`INCOME_ROOT` / `INCOME_OTHERS` / `EXPENSE_ROOT` / `EXPENSE_OTHERS`) with a partial
unique index per workspace. A boolean could not tell a root from an `Others`, and the previous
name-based check refused to nest anything under a *user* category called "Others". The unique
index is what makes the fallback lookup a single unambiguous query.

**A revise carries forward what the command cannot express** — `external_ref`, `transfer_id`,
`counterpart_id` — read from the newest event. Without it, editing an imported operation would
silently drop its link to the source dump, and editing a transfer leg would orphan its pair.

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
because an event's `aggregate_id` identifies the entity it describes.

**`ProjectionApplier` is the only writer of projection rows**, and its `when` over the sealed
`Projection` hierarchy refuses to compile when a new row type is added unwired.

**A shared interface for the commands that carry full state.** `OperationStateCommand` holds what
create and revise have in common, so a validation rule is written once and cannot be applied to
one path and forgotten on the other. Cancel stays outside it, carrying only an id.

**`workspace_id` is an explicit parameter on every query**, and `WorkspaceScopingTest` is what
makes that a rule rather than a habit: an unscoped query returns a plausible answer from another
tenant, and nothing else in the suite notices.

**Inject the DAO you need, not the registry.** `ProjectionDAORegistry` exists for
`ProjectionApplier`, which dispatches on a projection's runtime type and cannot know the DAO at
compile time. Everywhere else the DAO is known, so asking the registry for it by class only turns
a compile error into a runtime one.

**Three workspace fixtures, chosen deliberately.** `TestWorkspaces.create` inserts the row and
nothing else, so `t_events` starts empty and a test can count events from zero;
`createWithCategories` goes through `WorkspaceService`, so the four system categories exist;
`seedAccount` / `seedCategory` write a projection row with **no event behind it**, which is what
lets a test satisfy 1.16's existence checks without disturbing an absolute event count. Rows
seeded that way do not survive a replay — `AdminFacadeReplayTest` excludes them from what it
compares, for exactly that reason.

---

## What is next

**Transfers (1.18–1.20)** — one event, two linked legs, sharing a `transfer_id` and pointing at
each other. The columns and the `/operations` guard are already in place, so what remains is the
command, the pair invariant and the `/transfers` endpoints. Cross-currency legs carry their own
amounts in their own accounts' currencies — do not assume the two match. The sign comes from the
leg's direction, not from `kind`: both legs are `TRANSFER`, one negative and one positive.

**Then:** anchors and balances (1.21–1.26), which also closes 1.9. The emptiness check (1.3) and
the retention job (1.5b) can land any time.

---

## Pace

From git history, 2026-08-30 → 2026-09-09: **23 commits over 8 active days** (11 calendar days).
Commits land in batches at the end of a session, so their timestamps say when work was committed,
not how long it took — no hour figures are inferred here.

Tree: 4,158 LOC main across 91 files, 5,329 LOC tests across 27, plus 8 migrations.
Test-to-code ratio **1.28 : 1**, up from 1.15 — 1.16 and 1.6 both added more test than production
code, which is what tasks whose substance is rules and guarantees should look like.

**Percent done**, three ways, because one number misleads:

| Basis                                   | Done | Total | %   |
|-----------------------------------------|------|-------|-----|
| All tasks, incl. the M4–M6 placeholders | 30   | 117   | 26% |
| Concrete milestones only (M0–M3)        | 30   | 85    | 35% |
| M1 alone                                | 15   | 27    | 56% |

M1 is half done by count, and the remaining half is the less repetitive half: transfers are the
first multi-row aggregate, and anchors bring the balance arithmetic. The overall figure still
flatters progress — M2 holds the highest-risk work in the system and M4 is a frontend with nothing
to lean on.

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

## Deliberately deferred

- **No-op `PUT` suppression** on operations (§4.4) — appended for now; changes no API contract.
- **Intent on payloads** — full-state events give up "what the user meant" (§4.4). Recoverable
  later only for events written after the field exists.
- **Restoring a whole archived subtree** — restore returns only the target. Doing it properly needs
  a correlation id or `archived_at` so a restore knows what *that* cascade touched.
- **Pessimistic locking** — rejected as a replacement for versions, kept for M2's import.
- **Versioning on other aggregates** — decide per aggregate whether a stale-view write is harmful.
- **`external_ref` format** — nullable and unindexed, written only by the importer at M2. Prefix
  the source (`mok:1234`) rather than storing a bare `uid`, so a second source cannot collide.
  It is never a matching key (§5.4).
