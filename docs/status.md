# fintrace — current status

**Last updated:** 2026-09-11 · **Milestone:** M1 (in progress) · **Suite:** green
(`cd fintrace-core && ./gradlew clean test`)

> **This file is regenerated, not appended to.** It records where development stands right now;
> the reasoning lives in `design-decisions.md`, the ordering in `roadmap.md` and `tasks.md`, and
> the per-milestone plan in `plans/`. If this file disagrees with `design-decisions.md`, the
> design record wins and this file is stale.

---

## Where the code is

**M0 complete** (0.1–0.13). **M1: 21 of 27 task items.** Balance anchors landed, so every
M1 aggregate now exists. What remains is arithmetic — the balance query, the unexplained
difference and confirm-balance — plus the opening balance (1.9) and two items that can land any
time.

| Area               | State                                                                                    |
|--------------------|------------------------------------------------------------------------------------------|
| Workspaces         | CRUD, four-status lifecycle, ownership, optimistic locking, seven documented endpoints   |
| Operations         | full §4.13 field set, create / revise / cancel, cross-aggregate validation, REST, replay |
| Accounts           | CRUD, archive via `DELETE` and `POST /{id}/restore`, six documented endpoints            |
| Categories         | tree with all invariants, archive cascade, seeding by system code, six endpoints         |
| Transfers          | one event → two legs, create / revise / cancel, `/transfers` REST, replay-equal          |
| Balance anchors    | create / list / read / delete-latest, four documented endpoints, replay-equal            |
| Identity           | `t_users` as a projection of the IdP; `IdentityProvider` resolves the caller by subject  |
| Balances           | not started — 1.24–1.26, the last M1 work                                                |
| Importer, BFF, web | later milestones                                                                         |

### Done in M1

- **1.1, 1.2** `t_workspaces` + `t_users`, ownership, versioning; creation seeds the four system
  categories through the dispatcher, so a new workspace stays `NEW`
- **1.4, 1.5** every status transition, implicit `NEW → ACTIVE` at the first write, guards on both
  facades, soft delete guarded by the caller's `version`
- **1.7, 1.8, 1.10** accounts end to end. 1.9 (opening balance as the first anchor) is now unblocked
- **1.11–1.15** categories: adjacency-list tree, `findSubtreeIds`, create / revise / move / archive,
  every invariant in §4.7, CRUD endpoints
- **1.16, 1.17** operations complete: the full field set, revise / cancel, and the validation that
  ties an operation to the two aggregates beneath it
- **1.18, 1.19, 1.20** transfers: one event over a `TRANSFER` aggregate carrying both legs,
  `V0009`'s three pair constraints, `/operations` refusing both `PUT` and `DELETE` on a leg, and
  four documented `/transfers` endpoints
- **1.21, 1.22, 1.23** balance anchors: an absolute observation per account, never back-dated,
  deleted only at the head — the sole physical deletion in the system
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

### What 1.21–1.23 settled

**An anchor is an observation, so the usual amount rules do not apply.** The value may be negative
(an overdraft is a real reading) and zero is meaningful — §4.8's remedy for a closed account is an
anchor at zero. Nothing validates its sign.

**Both timestamps come from one clock read.** §4.6 says a count happens now, so
`CreateBalanceAnchorCommand` is deliberately **not** a `TemporalCommand` — there is no
client-supplied date to reject as back-dated. `occurred_at` is stored anyway, equal to
`recorded_at`, rather than dropped: the balance query compares it against operations'
`occurred_at`, and joining business time to record time would be a category error that happens to
work. That is the same reasoning decision 3 applied to the event envelope.

**Deleting is an event.** Replay is *clear the projection, then apply every event*, so a deletion
that lived only in the projection would be undone by the next rebuild and every balance after it
would silently change. The event is `CANCELLED` (decision 5), matching every other removal in the
system, and the payload needs only the anchor's id — unlike a transfer, an anchor's aggregate id
*is* its row id.

**Only the head may go, and the two refusals say different things.** Deleting from the middle
would shift every balance after it (§10.4), so it is a 409 — with its own message, distinct from
the 409 for an archived account, because the remedies differ.

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

**A resource nested two levels deep needs an HTTP-level test.** Anchors are the first — the path
constant lost a `/` before `{accountId}`, and the `Location` builder passed two values for three
path variables. Both were invisible to facade-level tests and broke every request; both were
caught the moment a `MockMvc` test existed. Anything under `/accounts/{accountId}/…` gets one.

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

**Balances (1.24–1.26), the last of M1.** The query is §4.6's formula — the nearest preceding
anchor plus `SUM(amount)` after it — parameterised by `asOf` from the first line, because M3's
`GET /balances/accounts` takes exactly that (§11.4) and a today-only version is work thrown away.
Transfer legs are included; they move money. Then the unexplained difference for an anchor
(`value − computed`, where *computed* excludes the anchor being measured, or it is zero by
construction) and confirm-balance, which is an ordinary anchor at the computed value and needs no
new machinery. Read paths belong in `ProjectionFacade` and the SQL in the anchor and operation
DAOs — this is the seam where M3 begins, so resist inventing a statistics layer here.

**1.9 is unblocked**: the opening balance becomes a `CreateBalanceAnchorCommand` inside the
create-account transaction. An omitted opening balance means *no anchor*, not one at zero. It
makes account creation the first command to append two events of different aggregate types, so
the rebuild-equality test wants extending with it.

**Then:** the emptiness check (1.3) and the retention job (1.5b), both of which can land any time.

## Pace

From git history, 2026-08-30 → 2026-09-11: **26 commits over 9 active days** (13 calendar days).
Commits land in batches at the end of a session, so their timestamps say when work was committed,
not how long it took — no hour figures are inferred here. The balance anchor work above is in the
working tree and not yet committed, so it is not in that count.

Tree: 5,582 LOC main across 121 files, 6,708 LOC tests across 31, plus 10 migrations.
Test-to-code ratio **1.20 : 1**.

**Percent done**, three ways, because one number misleads:

| Basis                                   | Done | Total | %   |
|-----------------------------------------|------|-------|-----|
| All tasks, incl. the M4–M6 placeholders | 36   | 117   | 31% |
| Concrete milestones only (M0–M3)        | 36   | 85    | 42% |
| M1 alone                                | 21   | 27    | 78% |

M1's remaining quarter is balances — the arithmetic, and the seam where M3 begins. The overall
figure still flatters progress: M2 holds the highest-risk work in the system and M4 is a frontend
with nothing to lean on.

*Recompute with:* `git log --format='%ad' --date=short | sort -u | wc -l` (active days) and
`grep -c '^- \[x\]' docs/tasks.md` (done tasks).

---

## Known gaps

Flagged, agreed, not yet done — none of them blocking:

| Gap                                                           | Where it bites                                                                                                  |
|---------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------|
| `TransferLoader` does not verify the counterpart cross-link   | it checks three softer fields but not the one defining the pair                                                 |
| Emptiness check (1.3)                                         | M2 import cannot verify a `NEW` workspace is empty                                                              |
| Retention job (1.5b)                                          | `DELETED` workspaces accumulate; the cascade for it exists                                                      |

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
