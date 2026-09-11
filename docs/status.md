# fintrace — current status

**Last updated:** 2026-09-11 · **Milestone:** M1 (in progress) · **Suite:** green
(`cd fintrace-core && ./gradlew clean test`)

> **This file is regenerated, not appended to.** It records where development stands right now;
> the reasoning lives in `design-decisions.md`, the ordering in `roadmap.md` and `tasks.md`, and
> the per-milestone plan in `plans/`. If this file disagrees with `design-decisions.md`, the
> design record wins and this file is stale.

---

## Where the code is

**M0 complete** (0.1–0.13). **M1: 18 of 27 task items.** Transfers landed: the first command that
writes two projection rows from one event, and the first aggregate whose invariants are enforced
by the schema rather than only by handlers. What remains in M1 is anchors, balances, and two
small items that can land any time.

| Area               | State                                                                                    |
|--------------------|------------------------------------------------------------------------------------------|
| Workspaces         | CRUD, four-status lifecycle, ownership, optimistic locking, seven documented endpoints   |
| Operations         | full §4.13 field set, create / revise / cancel, cross-aggregate validation, REST, replay |
| Accounts           | CRUD, archive via `DELETE` and `POST /{id}/restore`, six documented endpoints            |
| Categories         | tree with all invariants, archive cascade, seeding by system code, six endpoints         |
| Transfers          | one event → two legs, create / revise / cancel, `/transfers` REST, replay-equal          |
| Identity           | `t_users` as a projection of the IdP; `IdentityProvider` resolves the caller by subject  |
| Anchors, balances  | not started                                                                              |
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
- **1.18, 1.19, 1.20** transfers: one event over a `TRANSFER` aggregate carrying both legs,
  `V0009`'s three pair constraints, `/operations` refusing both `PUT` and `DELETE` on a leg, and
  four documented `/transfers` endpoints
- **1.6** `workspace_id` on every query, by explicit parameter, guarded by `WorkspaceScopingTest`

### What 1.18 settled

**One event, two rows.** `TransferCreatedV1` carries the full state of both legs; `aggregate_type
= TRANSFER`, `aggregate_id = transfer_id`, and `projectionChange()` upserts two `t_operations`
rows. Two events — one per leg — was rejected: the aggregate boundary is whatever can change
independently, and a leg cannot (that is what 1.19 enforces). With one event, a half-transfer is
unrepresentable in the log rather than merely prevented by every code path remembering to append
both.

**Leg ids belong to the slot, not to the account.** A revise reads the newest `TRANSFER` event and
reuses both leg ids — outgoing keeps the outgoing id — even when the request changes the accounts
or swaps them. `Upsert` is keyed by `(workspace_id, id)`, so this is the only thing that makes a
revision land on the existing rows; fresh ids would leave the previous pair orphaned with nothing
in the log to remove them. The same read carries `externalRef` forward.

**Cancel names both legs in its payload.** `OperationCanceledV1` gets away with carrying only its
`id` because for an operation the aggregate id *is* the row id. For a transfer it is not, and at
replay the projection is being rebuilt and cannot be asked which rows to drop.

**The sign comes from the slot, not from a kind.** Both legs are `kind = TRANSFER`;
`OperationKind.signedAmount` still throws for it, which is what keeps the operation path from ever
producing a leg, so the transfer handler negates the outgoing amount itself.

**`TRANSFER` stayed an `OperationKind`, with the redundancy made a schema invariant.** Dropping it
and identifying a leg by `transfer_id IS NOT NULL` would make a leg through `/operations`
unrepresentable rather than validated — genuinely attractive — but it moves a transfer's identity
out of the column statistics group by, so a forgotten filter would inflate income and expense
alike instead of producing a visible `TRANSFER` bucket. `V0009` closes the redundancy gap instead:
`(kind = 'TRANSFER') = (transfer_id IS NOT NULL)`, the same for `counterpart_id`, and
`(transfer_id IS NOT NULL) = (category_id IS NULL)`.

**A transfer has no projection of its own.** It is assembled by `TransferLoader` from the two legs
plus their accounts' currencies, and returned as a plain `Transfer` — no table, no DAO, no
`ProjectionApplier` branch. The conversion rate is derived on the way out (`target / source`,
6 dp) and never stored: the two amounts are the only source of truth, and a stored rate would
disagree with them after a revise. It is not an input either — the vendor made the same call
(dump reference §6.2: the rate is implied by `money` / `moneyTo`, never stored).

**An archived account keeps its own rows editable but may not receive new ones.** Revise rejects
an account that is archived *and* different from the one on the current leg. The same rule was
missing on operations — `checkAccountOnRevise` only checked existence, so an operation could be
moved onto an archived account — and now holds identically for both.

### Conventions the next aggregate should copy

**Layering.** Controller (`@Valid`, request shape) → facade (transaction boundary, resolves
identity once) → service (domain rules, `userId` explicit, `@Transactional(MANDATORY)`) → DAO
(SQL, returns row counts the service turns into 404 / 409 / 500).

**Commands return the state they produced**, so every mutation answers with the resulting resource
and only a removal answers 204 (§10.0). A transfer returns a `Transfer` assembled from the pair.

**A handler reads prior state from the newest event, never from a projection** — payloads carry
full state, so that is one indexed row rather than a fold. Validation is the other way round:
*existence* is a projection question, which is what makes cancelling twice a 404.

**One command may append one event that writes several rows.** Archiving a category subtree writes
one event per node because each node is an entity with its own lifecycle; a transfer writes one
event for two rows because a leg has none.

**Every read path needs its transaction boundary.** `ProjectionFacade.getTransfer` shipped without
`@Transactional(readOnly = true)` and every call failed — `requireReadable` is
`@Transactional(MANDATORY)`. Worth checking on the next read method added.

**`workspace_id` is an explicit parameter on every query**, and `WorkspaceScopingTest` is what
makes that a rule rather than a habit.

**Four workspace fixtures, chosen deliberately.** `TestWorkspaces.create` inserts the row and
nothing else, so `t_events` starts empty; `createWithCategories` goes through `WorkspaceService`,
so the four system categories exist; `seedAccount` / `seedCategory` write a projection row with
**no event behind it**; `seedTransferPair` does the same for a pair of legs, which is how 1.19's
guard is testable at all. Rows seeded that way do not survive a replay — `AdminFacadeReplayTest`
excludes them from what it compares.

---

## What is next

**Anchors and balances (1.21–1.26)**, which also closes 1.9. `t_anchors(account_id, value,
occurred_at, recorded_at)`, create rejecting back-dating, delete permitted only for an account's
latest anchor — the sole physical deletion in the system (§10.4). Then balances as a query:
nearest preceding anchor + `SUM(amount)` after it, the unexplained difference, and "confirm
balance" as an ordinary anchor at the computed value. Read paths belong in `ProjectionFacade`, not
in a handler, and the SQL belongs in the anchor and operation DAOs rather than a new statistics
layer.

**Then:** the emptiness check (1.3) and the retention job (1.5b), both of which can land any
time.

---

## Pace

From git history, 2026-08-30 → 2026-09-09: **25 commits over 8 active days**. The transfer work
above is in the working tree and not yet committed, so it is not in that count. Commits land in
batches at the end of a session, so their timestamps say when work was committed, not how long it
took — no hour figures are inferred here.

Tree: 4,988 LOC main across 107 files, 6,193 LOC tests across 29, plus 9 migrations.
Test-to-code ratio **1.24 : 1**. Transfers added roughly as much test as production code, which is
what an aggregate whose substance is invariants should look like.

**Percent done**, three ways, because one number misleads:

| Basis                                   | Done | Total | %   |
|-----------------------------------------|------|-------|-----|
| All tasks, incl. the M4–M6 placeholders | 33   | 117   | 28% |
| Concrete milestones only (M0–M3)        | 33   | 85    | 39% |
| M1 alone                                | 18   | 27    | 67% |

M1's remaining third is anchors and balances — the arithmetic half, and the seam where M3 begins.
The overall figure still flatters progress: M2 holds the highest-risk work in the system and M4 is
a frontend with nothing to lean on.

*Recompute with:* `git log --format='%ad' --date=short | sort -u | wc -l` (active days) and
`grep -c '^- \[x\]' docs/tasks.md` (done tasks).

---

## Known gaps

Flagged, agreed, not yet done — none of them blocking:

| Gap                                                           | Where it bites                                                                                                  |
|---------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------|
| `ProjectionTarget` declares four values, only three are wired | trimming the enum would make a missing branch a compile error                                                   |
| `TransferLoader` does not verify the counterpart cross-link   | it checks three softer fields but not the one defining the pair                                                 |
| Emptiness check (1.3)                                         | M2 import cannot verify a `NEW` workspace is empty                                                              |
| Retention job (1.5b)                                          | `DELETED` workspaces accumulate; the cascade for it exists                                                      |
| `status.md` said the CLI "builds commands directly"           | it does not — it goes through the BFF to the same REST DTOs; the import endpoint is the path that bypasses them |

## Deliberately deferred

- **No-op `PUT` suppression** on operations and transfers (§4.4) — appended for now; changes no
  API contract.
- **Intent on payloads** — full-state events give up "what the user meant" (§4.4). Recoverable
  later only for events written after the field exists.
- **`externalRef` on the transfer and operation commands** — the payloads carry the field, but no
  caller can set it until the importer exists (M2), and its format is undecided. Transfers and
  operations have separate `uid` namespaces in the dump, so the prefix must name the namespace
  (`mok-transfer:50` vs `mok-op:50`) or the two can collide.
- **Splitting `OperationProjection` by shape** — `categoryId` is nullable because a leg has no
  category. A sealed pair of row types would make that unrepresentable, at the cost of a
  hand-written `RowMapper`; revisit if legs and operations diverge further.
- **Restoring a whole archived subtree** — restore returns only the target.
- **Pessimistic locking** — rejected as a replacement for versions, kept for M2's import.
- **Versioning on other aggregates** — decide per aggregate whether a stale-view write is harmful.
