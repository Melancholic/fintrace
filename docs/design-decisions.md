# fintrace — Design Decision Record

**Status:** design settled; M0 implemented against it (see `plans/M0.md`)
**Date:** 2026-08-29, schema names and event kinds revised during M0
**Author:** (owner / sole developer)

This document records decisions made during the architecture discussion, the reasoning
behind them, and the questions still open. It is a working document, not a specification.

---

## 0. Context

### 0.1 What exists today

A Kotlin + Spring Boot Telegram bot. The user exports a SQLite dump from **MoneyOK**
(a third-party mobile personal-finance app), sends it to the bot, and the bot replays the
dump and returns statistics and charts.

### 0.2 What is being built

A full rewrite as a small distributed system, replacing the bot.

### 0.3 Goals, in order of honesty

| Goal | Type | Notes |
|---|---|---|
| Replace the existing bot as a working daily tool | Product | Pilot must be usable |
| Practise system design and microservice architecture | Learning | Primary motivation |
| Learn Go from zero | Learning | 1–2 services in Go |
| Practise OAuth2 / OIDC | Learning | Auth is not a product requirement |

### 0.4 Constraints

| Constraint | Value |
|---|---|
| Users | 1 (multi-user allowed in the data model, not required in the product) |
| Developer capacity | ~8 h/week, single developer |
| Pilot target | 1–2 months (~60–100 h total) |
| Deployment | Local NAS (UGREEN DXP4800 Plus, 16 GB), Docker Compose via Portainer |
| Resource budget for this stack | ~4 GB RAM (soft target) |
| Network access | Private Tailscale network only |
| CI/CD | Build on commit to `main`, semver, images published to private GHCR |
| Data volume | ~6k source events; growth of a few thousand events per year |
| Import frequency | A few times per month today; more often once a Web UI exists |
| Processing time today | Full parse + aggregation: 1–2 seconds |

### 0.5 Working rule adopted during the discussion

> **Every complication must be labelled either "needed to work" or "needed to learn".**

Learning-driven complexity is allowed and expected — this is a learning project. It just
has to be named as such, so it can be weighed against the pilot deadline rather than
justified by imaginary technical necessity.

Applied consequences: Kafka was dropped (never justified). A message queue is deferred to
iteration 2 and explicitly labelled as learning. Event sourcing is kept and labelled as
learning. A multi-step chunked import protocol was dropped as premature.

---

## 1. Scale is not an architectural driver

At ~6k records and a handful of imports per month, **no decision in this system can be
justified by performance.** Full recomputation of every projection takes milliseconds.

The two real drivers are:

1. **Learning** — the reason the project exists.
2. **Operability on constrained hardware** — the only hard external limit.

This is written down deliberately: it prevents retrofitting performance justifications
onto choices that were actually made for learning reasons.

---

## 2. Source data: MoneyOK dump

A full reference document for the dump format exists separately. The architecturally
relevant facts:

### 2.1 It is not a relational schema

The file contains **one business table**, `Chronicle`, holding an append-only log of
JSON-encoded mutation events:

```sql
CREATE TABLE Chronicle(
    uid    INTEGER PRIMARY KEY AUTOINCREMENT,
    badge  INTEGER,
    type   INTEGER,
    params TEXT
);
```

Accounts, categories, groups, operations and transfers **do not exist as stored entities**.
They exist only as projections computed by replaying the log in `uid` order.

### 2.2 It is a rich, delta-encoded event log

- 26 event types with distinct semantics.
- `UPDATE_*` events carry **only changed fields**. A missing key means "unchanged",
  never "set to null".
- Intent flags (`moneyBack`, `moneyBack2`) gate whether a field change also moves an
  account balance. The dump records *user intent*, not just data.
- Field meaning depends on sibling fields in at least five places.
- Deletion has four different semantics depending on entity type; category deletion
  cascades to its transactions and can move balances.
- Two category root groups (uid 1 and 2) are implicit and never appear in the log.
- Roughly half the event types carry no timestamp at all.

### 2.3 What the real dump actually contains

Event type counts from the production dump, closing several previously open questions:

| Type | Count | Meaning |
|---|---|---|
| `1` | 1 | Chronicle version (v3) |
| `10` / `11` / `12` | 3961 / 2899 / 79 | Operation create / update / delete |
| `20` / `21` / `22` | 10 / 80 / 1 | Account create / update / delete |
| `30` | 4 | Group create |
| `40` / `41` / `45` | 66 / 11 / 16 | Category create / update / move |
| `50` / `51` / `52` | 444 / 41 / 4 | Transfer create / update / delete |

**Absent entirely — traps that turn out to be theoretical for this data:**

- `90`/`92`/`93` file attachments — the case that crashes the current bot outright
- `42` category deletion — the destructive cascade that deletes transactions and moves
  balances
- `32` group deletion, `100` tombstone, `33`/`43`/`44` legacy codes
- `110` balance verification — **none at all**

**Also verified:** every one of the 3961 operations carries a `catUid`. Uncategorised
operations do not occur, so the "null → `Others`" rule (§5.1) is a safeguard rather than a
live path.

**What this reshapes:**

- No `anchors` section is needed in the import contract — only accounts' opening balances.
- Deletion handling is minor: 79 operations and 4 transfers, no categories or groups.
- **`type = 11` at 2899 events is the centre of gravity** — nearly one update per two
  operations, so the `moneyBack` / `moneyBack2` balance gates are heavily exercised.
- Transfers at 444 are routine, not an edge case.

### 2.4 Undated balance assignments

The 80 `type = 21` events are almost all `{uid, money}` — pure balance assignments, with no
name or currency change. `money` on `type = 21` is an **absolute assignment**, so these are
functionally balance corrections; the app simply records them as account edits rather than as
`type = 110` verifications.

**The problem:** account events carry no timestamp (dump reference §7.3), but an anchor
requires a date — it determines which operations fall before it and which after.

**Decision: inherit the timestamp of the preceding dated event, plus an epsilon** — the same
approach the current bot takes. Rationale: it preserves the information (80 points where a
real balance was observed), and it reproduces the existing bot's behaviour, which matters for
the figure-by-figure comparison in M3.

Rejected alternatives: dropping them and importing only a final opening balance (loses the
history); adding a `date_approximate` flag (a permanent field for data that can never occur
again after import).

> **Fix two defects while porting**, both documented in the dump reference §7.3: the current
> implementation's carry-forward depends on JDBC row order rather than `uid`, and it throws on
> a log containing no dated events at all.

### 2.5 Balance gates: amounts and balances diverge on purpose

`applyUpdateOperation` in the existing implementation changes an operation's amount
unconditionally, but adjusts the account balance **only when `moneyBack == true`**. The same
applies to `moneyBack2` when an operation moves between accounts.

So the source deliberately permits an operation's amount and its account's balance to
disagree — and at 2899 `type = 11` events, this is routine rather than exceptional.

**The new model cannot represent that directly:** balance is derived from operations (§4.6), so
changing an amount necessarily changes the balance.

**Anchors resolve it.** The interpreter keeps tracking balances by the source's own rules, then
emits a **final anchor per account** carrying the balance the source believes in. The gap
between "sum of the imported operations" and "the source's balance" becomes that anchor's
unexplained difference — precisely what anchors exist for.

Useful side effect: imported figures will match what the mobile app displays.

| Fact | Consequence |
|---|---|
| One event log, no entities | Import cannot be split by entity type before replay |
| Replay is a fold | Chunks are order-dependent — no independent chunking is possible |
| `Chronicle.uid` is renumbered on export (`sqlite_sequence.seq = 5448` vs `max(uid) = 50`) | **Incremental import by `uid > last_seen` is unsafe** — see §5.3 |
| `params.uid` may be reused after deletion | Not a durable identity on its own |
| Delta encoding + intent gates | Interpretation is a state machine, not a field mapping |
| Back-dating is normal | Drove the temporal model — see §6 |

---

### 2.6 Consequences that changed the design

| Fact | Consequence |
|---|---|
| One event log, no entities | Import cannot be split by entity type before replay |
| Replay is a fold | Chunks are order-dependent — no independent chunking is possible |
| `Chronicle.uid` is renumbered on export (`sqlite_sequence.seq = 5448` vs `max(uid) = 50`) | **Incremental import by `uid > last_seen` is unsafe** — see §5.3 |
| `params.uid` may be reused after deletion | Not a durable identity on its own |
| Delta encoding + intent gates | Interpretation is a state machine, not a field mapping |
| Back-dating is normal | Drove the temporal model — see §6 |

---

## 3. Service topology

Three services plus infrastructure.

```
┌──────────────┐
│   Web UI     │
└──────┬───────┘
       │ HTTPS (via Tailscale)
┌──────▼───────┐
│     BFF      │  Go
│              │  auth, JWT validation, aggregation, routing
└──────┬───────┘
       │
┌──────▼───────────────────────────────────┐
│  Core                                    │  Kotlin + Spring Boot
│  domain, event store, projections,       │
│  statistics, import API                  │
└──────┬───────────────────────────────────┘
       │ owns
┌──────▼───────┐        ┌──────────────────┐
│  PostgreSQL  │        │  MoK Importer    │  Kotlin
└──────────────┘        │  transport +     │
       ▲                │  MoneyOK parser  │
       └────────────────┤  + replay        │
         import API     └──────────────────┘
```

### 3.1 Core — Kotlin + Spring Boot

**Responsibilities:** owns the domain, owns the database, owns the import contract,
stores events, builds projections, computes statistics.

**Why Kotlin:** all non-trivial domain logic lives here, and this is where existing
expertise translates directly into pilot velocity.

**Data ownership:** Core is the **only** service that connects to PostgreSQL.

### 3.2 MoK Importer — Kotlin

**Responsibilities:**

1. Obtain the source file from a transport (HTTP upload; watch-directory next; Google
   Drive later).
2. Replay the MoneyOK chronicle and resolve it to concrete entities.
3. Call Core's import API with normalised facts.

**Decision: transport and parser are one service for the MVP**, with a clean internal
boundary between them (separate interfaces). There is currently one transport and one
format. Splitting is a day's work later, once the transport × format matrix has more than
one cell in each dimension.

**Decision: replay happens in the importer, not in Core.** Core must never learn the words
`moneyBack`, `badge`, `opType` or `Chronicle`. This is the whole point of the plugin
boundary: it isolates the most trap-laden logic in the system behind one interface, and
keeps a second source format from requiring a second interpreter inside the domain.

### 3.3 BFF — Go

**Responsibilities:** JWT validation against the external IdP, request routing, response
aggregation for the UI.

**Why Go here:** minimal domain logic, heavy I/O — the right place to learn a new language
without risking data correctness. Also the cheapest service to keep resident (tens of MB
vs hundreds for a JVM).

### 3.4 Identity Provider — out of scope

Deliberately **not** part of this stack. Other services on the NAS need an IdP; one shared
instance will serve all of them. This stack consumes it, does not deploy it.

### 3.5 Language allocation and its rationale

| Service | Language | Reason |
|---|---|---|
| Core | Kotlin / Spring Boot | All the domain complexity; existing expertise; pilot deadline |
| MoK Importer | **Kotlin** | Contains the MoneyOK state machine — see decision below |
| BFF | Go | Learning target; low domain risk; low memory footprint |

**Decision (Q1, resolved): the MoneyOK interpreter stays in Kotlin.**

Rationale: it is the only component where a defect does not fail loudly but quietly
distorts figures, surfacing months later as wrong statistics. A working implementation
already exists, with 22 tests and a hand-worked back-dating scenario acting as a portable
conformance suite. Rewriting it in an unfamiliar language would take the highest-risk
component in the system and add language risk on top, against a 60–100 hour budget.

Go learning moves entirely to the BFF, which is a substantial service in its own right:
authentication, JWT validation, routing, response aggregation, status delivery.

**Considered and deferred:** splitting the importer into a Go transport service and a Kotlin
interpreter service — a split by *risk* rather than by the transport × format matrix. This
is a stronger argument than the one rejected earlier, and gives two Go services on
zero-risk work. Rejected for the MVP only on cost: an extra service and an extra hop,
including file handoff between them, for an operation that takes about a second. Revisit
when a second transport lands.

---

## 4. Data model

### 4.1 Workspace

A **workspace** is the container for one coherent set of financial data: accounts,
categories, operations, transfers, corrections. Every domain entity carries a
`workspace_id`.

| Property | Decision |
|---|---|
| Primary key | UUID |
| Cardinality | Multiple workspaces supported from the start |
| API exposure | Workspace in the path: `/workspaces/{id}/...` |
| Human reference | Optional name/slug; the UUID is the identity |

**Multi-tenancy note:** this is what makes the "designed for multiple users, used by one"
requirement real rather than aspirational. Data isolation is by workspace; user-to-workspace
assignment is a thin layer on top and is not needed for the pilot.

**Implementation requirement:** `workspace_id` belongs in every table and every query from
day one. Retrofitting it later is painful.

### 4.1.1 Workspace lifecycle

A workspace has an explicit status. The transition is one-way.

| Status | Meaning |
|---|---|
| `DRAFT` | Created, no data yet. The only state in which import is permitted. |
| `ACTIVE` | Operational. Data enters only through the service API. Import is permanently closed. |

**Status is stored explicitly, not inferred from whether entities exist.** The "start empty"
path (below) produces an `ACTIVE` workspace containing nothing, which is indistinguishable
from a `DRAFT` one by data alone.

**Two ways to leave `DRAFT`:**

1. **Successful import** — the initialisation path described in §4.2.
2. **Start empty** — an explicit user action for workspaces that do not originate from
   MoneyOK. Without this, creating a fresh workspace later would require importing a dummy
   dump.

**User flow:**

```
[+ New workspace]  →  DRAFT
                        │
                        ├── drag & drop dump  →  importing  ──┬── success  →  ACTIVE
                        │                                     └── failure  →  DRAFT (retry)
                        │
                        └── "start empty"  ───────────────────────────────→  ACTIVE
```

**Import must be atomic.** On failure the workspace has to return to a genuinely empty
`DRAFT`, not a visually empty one — otherwise partially written entities make it non-empty
and the emptiness check will reject every retry. A single transaction covering the whole
import is the simplest guarantee, and 6k records makes that unproblematic.

**Transient states** (`importing`, `import failed`) belong to the import job, not to the
workspace. The UI needs to distinguish them — "not started yet" and "failed with reason X"
are different screens — but they are not workspace statuses.

**Recreating a workspace** is only needed when an import *succeeded* but produced wrong
data: the workspace is already `ACTIVE`, so the only path is delete and create again. A
failed import does not require it — the workspace simply stays in `DRAFT`.

### 4.2 Import is workspace initialisation, not synchronisation

**Decision: a workspace can only be imported into while it is in `DRAFT` (§4.1.1). After
initialisation it becomes `ACTIVE` and data enters exclusively through the service API (Web
UI today; other importers later).**

`DRAFT` additionally implies **empty**, defined as: no entity anywhere references this
`workspace_id`. The workspace record itself exists — creating a workspace and importing into
it are two distinct actions. Emptiness should be a single reusable check, so that adding a
new entity type later cannot silently break it.

**What this decision eliminates:**

| Problem | Why it disappears |
|---|---|
| Re-import overwriting a manual edit | There is no re-import |
| Import-vs-manual duplicate detection | They cannot overlap in time |
| Override layer over immutable imported facts | Not needed |
| Conflict resolution rules on every import | Not needed |
| Two classes of operation (imported vs own) | An operation is just an operation |

**Failed import:** the workspace stays in `DRAFT` and can be retried directly — provided the
import was atomic (§4.1.1).

**Successful import that produced wrong data:** the workspace is already `ACTIVE`, so the
only path is to delete and recreate it. Expected to be frequent during interpreter
development.

> **Noted trade-off (recreating with the same UUID):** reusing the identifier keeps external
> references and bookmarks working, but means a UUID no longer uniquely identifies a set of
> data — a stale reference stays syntactically valid while pointing at different content.
> The alternative is a fresh UUID per workspace with a stable human-facing slug.

**Out of scope:** incremental or partial import after initialisation. If operations are
entered in the source app after the switch, they are re-entered manually through the UI —
which is why retrospective entry (§6) is a hard requirement.

**Keep the door open:** operations retain an `external_ref` field even though nothing
populates it after the initial import. A later "partial import with explicit selection and
mandatory preview" feature would need it.

### 4.3 Two layers: events and projection

| Layer | Content | Mutability |
|---|---|---|
| **Events** | Every state change, whatever its origin | Append-only |
| **Projection** | Current state: operations, balances, statistics | Fully derived; recomputable from scratch |

There is no mutable layer. What the user experiences as "editing" is a new event plus a
projection update.

**Immutability is preserved inside a workspace**, and it applies uniformly — origin is a
field (`origin`, `external_ref`), not a separate class of entity with different rules.

### 4.4 Event granularity: full state, not deltas

**Decision: events carry the complete resulting state of the entity, not a diff.**

Three universal event kinds are enough for the MVP: *created*, *revised* (replaced by a new
version), *cancelled*.

Explicitly **not** copying MoneyOK's model here. Its delta encoding plus intent gates is
precisely what makes the current bot hard to get right. Full-state events mean applying an
event is just "take the body as the new version".

**Price accepted:** larger event bodies, and intent is not recoverable — you can diff two
versions to see *what* changed, but not *what the user meant*. Irrelevant at this scale.

### 4.5 Transfers: two ledger entries, not one record

**Decision: a transfer is stored as two linked operations** — a debit on the source account
and a credit on the target — sharing a `transfer_id`.

This deliberately differs from the source, which stores a transfer as a single record with
both legs (`accFrom`/`accTo`, `money`/`moneyTo`). Normalising into paired entries is a
transformation the source does not perform, and performing it is the importer's job.

**Why two entries:**

| Aspect | One record | Two entries |
|---|---|---|
| "All movements on account X" | `WHERE account_from = X OR account_to = X` | `WHERE account_id = X` |
| Account balance | `CASE` selecting side and sign | `SUM(amount)` |
| Cross-currency | Two amounts in two currencies in one row | Each entry in its own account's currency — natural |
| Orphaned leg after account deletion | Special semantics (source uses `uid = 0` sentinel) | Simply an entry whose counterpart is gone |

Every query touching accounts becomes uniform, which is the main win. Multi-currency
transfers stop being a special case.

**Price accepted:**

- The pair is an invariant: create, revise and cancel must always affect both entries.
  Enforced in one place in the domain layer.
- The UI presents a transfer as one thing, so the two entries are joined back on read.

**Excluding transfers from analytics:** operations carry a `kind` (income / expense /
transfer), so category statistics filter with `WHERE kind <> 'TRANSFER'`. A single
condition, not branching logic.

### 4.6 Balance corrections: anchors

A balance correction records an **observation**: "I counted the cash, there is 1500". It is
an absolute assignment, not a delta.

**Decision: store the absolute value.**

Storing a delta instead would mean the delta has to be recalculated whenever a retrospective
operation is inserted before it — which is exactly the source of the recurring balance bugs
in the current bot. An absolute anchor never changes: the fact "on 15 March there was 1500"
stays true regardless of what is remembered later.

**Decision: corrections cannot be back-dated.** A correction is entered on the day it is
made, so `occurred_at` always equals `recorded_at`. There is nothing to record
retrospectively, because the count did not happen retrospectively. This falls out of the
semantics rather than being an imported constraint.

**An account's opening balance is an anchor** at account creation. No separate concept
needed.

#### Balance calculation

```
balance(account, date) = anchor.value
                       + SUM(operations
                             WHERE account
                               AND occurred_at >  anchor.date
                               AND occurred_at <= date)

where anchor = the latest anchor for that account with anchor.date <= date
```

One subquery instead of a plain `SUM`. Negligible at this volume.

**All temporal fields are timestamps** (date *and* time), so ordering between an anchor and
same-day operations is unambiguous and needs no tie-breaking rule.

> **No sequential fold.** The current bot replays the log in insertion order and then
> retroactively patches already-computed days. That is not reproduced here: balances are
> computed by query from the nearest preceding anchor, so there is nothing to patch.

#### The key rule

> **An anchor affects balance calculation only. It never affects income/expense statistics.**

Period statistics are `SUM(operations WHERE occurred_at IN period AND kind = ...)` — anchors
do not appear in that query at all.

This resolves what looks like a contradiction. Insert a purchase dated 10 March after an
anchor was set on 15 March:

| Question | Effect |
|---|---|
| Balance after 15 March | **Unchanged** — the anchor is an observed fact and absorbs it |
| Balance between 10 and 15 March | **Changes** — correctly; there really was less money then |
| March expenses | **Increases** — the operation belongs to March by `occurred_at` |

#### Unexplained difference

After an anchor, the balance and the accumulated sum of operations no longer agree. The gap
is what the anchor absorbed:

```
diff = anchor.value − (balance computed from operations at the anchor's point)
```

Sign is meaningful: positive means more money than the books show (a forgotten income),
negative means less (a forgotten expense).

**UI requirement: the difference must be visible**, both at the moment of correction and in
account history — not silently swallowed. It also changes retroactively: entering the 10
March purchase shrinks the 15 March gap, which is useful feedback that part of the
discrepancy has been explained.

**Decision: account balances are never stored, always computed** from the nearest preceding
anchor. A stored balance would duplicate derivable state and could drift out of sync; at this
volume the query is free.

#### Confirming a balance

A one-click action creating an anchor with the **currently computed** balance — i.e. an
anchor whose difference is zero at the moment of creation.

Purpose: a checkpoint asserting "on this date the books matched reality". If a discrepancy
shows up later, it is known to have originated after this point rather than having accumulated
for years.

Behaviourally it is an ordinary anchor and needs no special handling.

> **Known consequence, deliberately left unhandled for now:** entering an operation dated
> before a confirmation point does not change the balance — the anchor absorbs it — so an
> anchor created with a zero difference can acquire a non-zero one retroactively. Semantically
> correct (the confirmation really happened; it was simply based on incomplete data), but
> potentially surprising in the UI.
>
> Options considered and deferred: reject the retrospective operation (bad — loses data the
> user wants to record, and discourages confirming at all); void the anchor (bad — rewrites an
> observed fact); or treat "confirmed" as a derived property (`diff = 0` right now) that
> silently clears and can return once compensated. The last is the likely answer, but the
> question is better settled after living with it.

#### Follow-up: compensating operations

A mechanism to convert an unexplained difference into a real operation — "book the
unexplained 200 to *Misc*, dated at the anchor".

Without it, unexplained money never reaches category analytics: it sits in the balance and is
invisible in reports.

**Semantics, decided now even though the feature is deferred:**

- The **anchor remains** after compensation. It is an observed fact; explaining it does not
  un-observe it. Removing it would be rewriting history.
- The compensating operation is a **separate fact**: "I have decided those 200 were spent on
  misc".
- Result: anchor intact, difference zero, an expense appears in March statistics, balance
  unchanged — the compensation exactly fills the gap.

### 4.7 Categories

Categories form a tree of **unlimited depth**. This differs from the source, which is
effectively two levels; the importer maps the source structure one-to-one, and any
restructuring afterwards is done by the user in the UI.

Categories are a **dynamic entity**: create, update and delete are ordinary business
operations.

#### Fixed categories

Four immutable categories are created together with the workspace:

| Category | `parent_id` | Role |
|---|---|---|
| `INCOME` root | `null` | Root of the income branch |
| `EXPENSE` root | `null` | Root of the expense branch |
| `Others` (income) | `INCOME` root | Uncategorised income |
| `Others` (expense) | `EXPENSE` root | Uncategorised expense |

All four are immutable: they cannot be renamed, moved or deleted. Both `Others` categories
are **leaves** — they may not have children ("Uncategorised → Food" is meaningless).

Every other category descends from one of the two roots.

#### Type

A category's type (income / expense) is determined by which root branch it belongs to, not
stored separately on each row.

**Moving a category is permitted only within its own branch.** Moving a category across
branches would change its type, and therefore retroactively change the meaning of every
historical operation filed under it.

#### Deletion

**Decision: soft delete.** A deleted category disappears from selection in the UI, but
historical operations continue to reference it and past statistics stay intact.

Explicitly **not** copying the source, where deleting a category cascades into deleting its
transactions and can move account balances. Tidying up a category list must never rewrite
history.

#### Storage: adjacency list

**Decision: `parent_id` on each category**, with recursive queries for tree traversal.
Materialized path and closure table were considered; at ~20 categories and a depth of two or
three levels the performance difference is nil, and adjacency list is by far the simplest.

`WITH RECURSIVE` is needed in two places — collapsed-mode category statistics (§11.2, gathering
all descendants of a node) and rendering the tree in the UI. Worth writing once as a reusable
query or view.

**Cycle protection:** moving a category could in principle create a loop by moving a parent
into its own descendant. The move command must verify the target is not a descendant of the
node being moved — the same recursive query.

**Invariants enforced at command time** (before the event is written, §4.10): moves stay
within the same branch; `Others` categories are leaves; roots and `Others` are immutable.

#### Renaming

A category's `id` is stable, so historical operations follow the rename. Reports show the
**current** name, not the name as of the reporting period.

Categories also carry an `icon` field — cosmetic, reserved in the schema now.

### 4.8 Accounts

Fields beyond the source's minimum (name, currency, balance):

| Field | Notes |
|---|---|
| `icon` | Cosmetic, but reserved in the schema now to avoid a later migration |
| `archived` | See below |

Account **type** (cash / card / savings) is not modelled — no need identified.

Balance is not a stored field (§4.6): it is computed from the nearest preceding anchor.

#### Archiving

**Decision: accounts are archived, never deleted.** An archived account disappears from
selection but remains in reports, charts and statistics.

- Archived accounts are not offered when creating an operation or a transfer, on either side.
- **Unarchiving is supported** — archiving is a flag, so reversing a mistake is cheap.

**Open:** what to do about a non-zero balance at archiving time — display as-is, require it to
be zeroed first, or ignore. Also whether archived accounts count toward a total-across-accounts
figure (probably not, but then the total jumps at the moment of archiving). In practice an
account is archived once it is already empty; the general case still needs an answer before
implementation.

#### Principle: no physical deletion

> **Entities that operations may reference are never physically deleted.** Categories are
> soft-deleted (§4.7); accounts are archived. The only true deletion in the system is deleting
> an entire workspace.

This removes cascade rules and dangling references as a class of problem. It also means the
source's orphaned-transfer-leg situation — where one side points at a deleted account and is
represented by a `uid = 0` sentinel — cannot arise here: the account always exists.

### 4.9 Editability

Since import happens once into an empty workspace, there is no imported/own distinction and
no override layer. **Category and comment are ordinary fields on an operation**, edited like
any other field: by recording a new version.

### 4.10 Event sourcing: scope and honest labelling

Event sourcing is kept, labelled **learning-driven**.

**Decision: strict event sourcing, across all entities.** Events are the only thing written
directly; projection tables are derived and may be rebuilt from scratch at any time.

The alternative — entity tables as primary storage with an append-only audit log alongside —
was considered and rejected. It is roughly half the moving parts, but it is an ordinary
application with auditing, not event sourcing, and the practice was the point.

#### What this requires

| Requirement | Detail |
|---|---|
| **Nothing writes to projections directly** | Only the event handler does. A single stray `UPDATE` desynchronises projection from events, silently. |
| **Validation happens before the event** | An event has already happened and cannot be rejected. Rules like "only the most recent anchor may be deleted" are checked at command time. |
| **Event and projection update share a transaction** | Single process, single writer — no asynchronous projection machinery is needed. |
| **Payloads are versioned from day one** | An event written today will be read in two years. Every payload carries a `version` field. |

#### Two distinct mechanisms

**Incremental application — the normal path.** Adding an operation writes an event, and the
handler applies that single event to the projection: one insert. Not a rebuild. Milliseconds.
This is what happens 99% of the time.

**Full rebuild — a recovery tool.** Clear the projection and replay every event from the
start. Used when a handler bug is fixed and history must be recomputed, when a new projection
column needs backfilling, when projection and events have diverged — and as an optimisation
during import, where replaying 6k events one at a time is pointless.

> **Build the rebuild procedure first, not "when needed".** It is the one real payoff for the
> complexity ES costs; without it, ES degenerates into an audit log with extra steps. Written
> late, it usually turns out that handlers were not idempotent and a stray direct write
> happened somewhere, making replay impossible.

**The rebuild must use the same handler as the online path** — shared code is what keeps the
two from drifting. A cheap and valuable test: rebuild and assert the projection is identical.

#### Costs accepted knowingly

Projections must be built and maintained; event schemas are versioned forever; debugging is
harder than a single `SELECT`; roughly twice the moving parts per action; and ES does not
solve back-dating — §6 does.

### 4.11 Event store shape

A single events table for all aggregates:

```
t_events(
  id             bigint,        -- GENERATED ALWAYS AS IDENTITY; global ordering, used by rebuild
  workspace_id   uuid,
  aggregate_type text,          -- account | category | operation | transfer | anchor
  aggregate_id   uuid,
  event_type     text,          -- created | revised | cancelled
  payload        jsonb,         -- full resulting state (§4.4) + version
  occurred_at    timestamp,
  recorded_at    timestamp
)
```

Splitting by aggregate type buys nothing at this volume and costs global ordering.

**Projection tables:** `t_accounts`, `t_categories`, `t_operations`, `t_anchors` — written only by the
event handler.

### 4.12 Identifiers

**Decision: UUID for every entity**, column type `uuid` (16 bytes, not `text`'s 36).
**UUIDv7 preferred** — it is time-ordered, so it appends to B-tree indexes sequentially like a
sequence would, without giving up UUID properties. Postgres 18 provides `uuidv7()` natively;
earlier versions need a library.

**The reason is rebuildability, not unguessability.** With a database-assigned id the order
becomes: insert into the projection, get the id, then write the event —
backwards for ES. Worse, a full rebuild (§4.10) would assign *different* numbers, breaking
every inter-entity reference stored in earlier events. With UUIDs the id is generated in code,
travels inside the event, and a rebuild reproduces exactly the same ids.

**On unguessability:** it is not the deciding factor, because every resource is nested under a
workspace whose access is checked. An operation is reachable only by someone authorised for
its workspace, so enumeration gains nothing regardless of id type.

**On size:** UUID costs 8 bytes more per id, roughly 32 bytes per operation row — about 190 KB
at 6k operations. The usual UUID performance objection concerns random v4 keys fragmenting
indexes under heavy insert load, which does not apply at a few dozen writes per month, and v7
removes it anyway.

### 4.13 Projection tables

Fields follow from the domain model; only the non-obvious ones are noted.

**`operations`** — income, expenses **and transfer legs** in one table, since transfers are read
through the same surface (§10.3).

| Field | Notes |
|---|---|
| `kind` | `INCOME` / `EXPENSE` / `TRANSFER` |
| `amount` | **Signed** — expenses negative. Balance is then `SUM(amount)` with no `CASE`. The source stores magnitude plus a type flag; that is its problem, not ours. |
| `account_id` | |
| `category_id` | Null for transfer legs |
| `transfer_id` | Shared by the two legs of a transfer; null otherwise |
| `counterpart_id` | The other leg, for the UI's jump-to-counterpart link; null otherwise |
| `comment` | |
| `occurred_at`, `recorded_at` | §6 |
| `external_ref` | §4.2 |

Transfer-only columns are null for ordinary operations. Accepted: the alternative — a separate
transfers table — contradicts reading everything through `/operations`.

**`anchors`** — `account_id`, `value`, `occurred_at`, `recorded_at`.

**`accounts`** — `name`, `currency`, `icon`, `archived`. No stored balance (§4.6).

**`categories`** — `name`, `icon`, `parent_id`, `kind`, `deleted`, `system`.
`kind` is derivable from the branch but stored anyway — cheaper than walking to the root on
every query. `system` protects the roots and both `Others` from modification.

Every table carries `workspace_id`.

### 4.13.1 Database naming convention

`t_` for tables, `v_` for views, `idx_` for indexes — `t_events`, `t_operations`,
`idx_t_events_workspace_id_id`. Kotlin names stay unprefixed; the prefix is a database-side
convention only. `flyway_schema_history` is Flyway's own and keeps its name.

### 4.14 Indexes

| Index | Serves |
|---|---|
| `t_operations(workspace_id, occurred_at)` | All period statistics |
| `t_operations(workspace_id, account_id, occurred_at)` | Balance calculation from an anchor |
| `t_operations(workspace_id, category_id)` | Category breakdown |
| `t_anchors(workspace_id, account_id, occurred_at)` | Finding the nearest preceding anchor |
| `t_events(workspace_id, id)` | Full rebuild in order |

---

## 5. Import pipeline

### 5.1 Contract shape

`POST /workspaces/{workspaceId}/import` — a single request carrying the whole payload.

The workspace must be in `DRAFT` (§4.1.1); the request is rejected otherwise. `importId` is
returned in the response.

**One request, sections inside the body** — not a multi-step protocol. Atomicity is the
deciding argument: a transaction spanning one request is obvious, one spanning a sequence of
calls is not, and §4.1.1 requires a failed import to leave a genuinely empty `DRAFT`.
Processing order is Core's business, since Core knows the dependencies.

**Sections:**

| Section | Contents |
|---|---|
| `accounts` | Name, currency, icon, opening balance (which becomes the first anchor), archived flag |
| `categories` | Flat list with `parentExternalId`; Core assembles the tree |
| `operations` | Income and expense entries: date, amount, account, category, comment |
| `transfers` | One object per transfer, both sides and both amounts — Core expands it into two ledger entries (§4.5) |
| `anchors` | Balance corrections carried over from the source |

No `currencies` section: currency is a code on an account, not an entity.

**Cross-references use external identifiers** (`accountExternalId`, `parentExternalId`), since
internal ids do not exist yet. Core builds the external-to-internal mapping as it goes.

**Categories:** the roots and both `Others` categories already exist from workspace creation
(§4.7), so the importer maps source groups onto children of the appropriate root.

**Uncategorised operations:** the importer sends `null` for the category plus the operation's
kind (income/expense); **Core** assigns the matching `Others`. Keeping this rule in Core means
it holds for every input path, not just this importer.

**Unresolvable references are a hard failure.** If an operation references an account that is
not in the payload, the import fails. This is not the source's tolerated dangling-reference
case: the importer replays the entire chronicle and therefore knows every account that ever
existed, including deleted ones, which it emits as archived (§4.8). A dangling reference in
the contract is an importer bug, and failing loudly is correct.

Tolerance for the source's own dangling references stays inside the importer — including
transfer legs whose account was deleted, which the source represents with a `uid = 0`
sentinel.

### 5.2 Synchronous first, queue later

| Iteration | Transport | Rationale |
|---|---|---|
| 1 (MVP) | HTTP, one request, whole payload | 6k records in 1–2 s is an ordinary request, not a long-running job |
| 2 | NATS JetStream + claim-check | Learning: async pipeline, ack, retry, dead-letter |

**Design requirement:** one import use case, two thin transport entrypoints. The HTTP
controller and the NATS consumer must both be thin wrappers over the same logic, or they will
drift.

**Contract requirement:** an `importId` is issued in iteration 1, even though the
synchronous call returns its result inline. This keeps the contract stable when the async
entrypoint arrives.

**Rejected: chunked/multi-step import.** Split by entity section is not viable
asynchronously (sections have ordering dependencies and no independent chunk is possible),
and split by `uid` range is not viable at all because replay is a fold. Chunking also
introduces server-side session state, transaction-boundary questions, and idempotency
problems it does not pay for at this volume. When payload size eventually matters, the
answer is claim-check (file in storage, reference in the message), not chunking.

### 5.3 Idempotency and identity

Under the workspace model (§4.2) import runs **once into an empty workspace**, which removes
most of the difficulty. Idempotency is enforced at the workspace level: an import into a
non-empty workspace is rejected.

**Why watermark-based incremental import was abandoned:** the source renumbers `Chronicle.uid`
on export (`sqlite_sequence.seq = 5448` vs `max(uid) = 50`), so the same events can reappear
with different numbers. Any `uid > last_seen` strategy silently loses or duplicates data. This
was one of the reasons for moving to one-shot import.

**Identity of an entity carried over from the source:** `external_ref`, retained for
traceability and to keep a future partial-import feature possible. It is not used for
matching after the initial import.

**Safety check:** on ingest, compare `sqlite_sequence.seq` against `max(uid)`. A large
mismatch indicates a rebuilt or renumbered dump. Cheap to implement, saves a debugging
session.

### 5.4 Duplicate detection — not needed

Previously planned to handle "same operation entered both in the mobile app and the Web UI".
The workspace model makes this impossible by construction: import and manual entry never
overlap in time within a workspace.

Revisit only if partial import is ever implemented.

---

## 6. Temporal model

### 6.1 The problem

Operations are frequently entered retrospectively — accumulated for a week, then entered
in FIFO order. Back-dated records are the norm, not the exception.

MoneyOK stores only the business date; recording time exists solely as a sequence number,
with no absolute timestamp anywhere. The current bot therefore replays forward and
retroactively patches already-computed days. Its commit history
(`wrong calculation` → `revert` → `almost correct` → `finally correct`) documents the cost.

### 6.2 Decision: bitemporal, and no incremental aggregation

Store both times explicitly:

| Field | Meaning |
|---|---|
| `occurred_at` | When the operation happened (user-entered) |
| `recorded_at` | When the record entered this system (automatic) |

**Statistics are computed by querying, not by folding.** A period aggregate is
`WHERE occurred_at BETWEEN ... GROUP BY ...`. A back-dated record simply lands in its own
period because the filter is on `occurred_at`, not on insertion order.

The current bot's hardest problem is an artefact of in-memory forward replay. Once the
data is in a relational store and queried, retrospective entry stops meaning anything.
The problem is not solved so much as **not created**.

### 6.3 What `recorded_at` is actually for

Not statistics. Three things:

1. **Reproducibility** — "what did January look like when I checked on 1 February?"
   (`occurred_at IN january AND recorded_at <= '2026-02-01'`).
2. **Import audit** — what arrived, and when.
3. **Cache invalidation**, if caching ever becomes necessary. Not needed at 6k rows.

### 6.4 Timestamp convention

**All temporal fields are timestamps** — date and time, never date-only. This makes ordering
unambiguous everywhere, including between an anchor and operations on the same day.

**All timestamps are stored in the system timezone**, with no zone attached.

> **Fix `TZ` explicitly in Docker Compose** rather than relying on the container default.
> "System timezone" is only safe as a deliberate choice: if the container's zone changes —
> new image, restored on a different host — previously stored timestamps silently start
> reading as a different wall-clock time. This is the same defect the vendor's own MoneyOK
> client has, where a dump parses to different instants depending on where it is opened.

### 6.5 Source timezone handling

The source carries **floating local wall-clock time** with no zone information, written
from local-time accessors. `LocalDateTime` (no zone) is the faithful reading and is more
correct than the vendor's own client, which re-anchors to the reader's offset.

**Rule: never convert source timestamps to UTC without an explicit, user-supplied zone.**

---

## 7. Infrastructure

### 7.1 Resource budget

| Component | Notes |
|---|---|
| PostgreSQL | One instance. Realistically 50–100 MB at this workload and client count |
| Core (JVM) | Tunable to ~150–200 MB via `MaxRAMPercentage`, trimmed autoconfiguration, CDS |
| Go services | Tens of MB each |
| NATS (iteration 2) | ~20 MB |

**Kafka is explicitly out of scope.** It was introduced into the discussion as a negative
example and should never have been on the table.

**CPU note:** Docker Compose `cpus` is a **per-service** limit, not a stack-wide one.
A stack-wide ceiling has to be expressed as a deliberate allocation across containers.
Under a low CPU limit the JVM selects `SerialGC` and single-threaded pools — appropriate
here, but worth knowing rather than discovering.

### 7.2 Database layout

One PostgreSQL instance. Only Core connects to it.

Recognised trade-off: this makes Core the sole stateful service, with the others as
stateless helpers. That is the right shape for this domain — splitting the database to
manufacture "real microservices" would be artificial. It does mean the distributed-data
problems (cross-service consistency, data duplication for autonomy) are **not** exercised
by this project.

### 7.3 Import job state

Owned by Core, consistent with "only Core touches PostgreSQL". The importer stays stateless.

**Accepted cost:** if Core is down, uploads cannot be accepted, which partially negates the
availability decoupling a queue would otherwise provide. Acceptable for a single-user pilot.

### 7.4 Identity, roles and authorisation

#### Authentication

**Sign-in with a Google account, federated through the self-hosted IdP.** fintrace speaks
plain OIDC to Keycloak and knows nothing about Google.

No provider-specific SDK anywhere: adding further providers (Okta, other IdPs, eventually
email/password) is then Keycloak configuration rather than application changes.

The IdP itself stays **outside this stack** (§3.4) — it is shared with other services on the
NAS. It supplies identity and nothing else.

**Decision: a self-hosted IdP from the pilot onward** — Keycloak (or Authentik), running as
its own stack on the NAS. Google becomes an identity provider *inside* it rather than
something fintrace talks to directly, so the Google account is how you sign in to the IdP and
fintrace only ever sees one issuer.

Doing this up front avoids changing the issuer later, which would invalidate existing tokens
and mean redoing the configuration. It is also needed for other NAS services regardless.

**It must not live in `deploy/docker-compose.yml`.** A separate Portainer stack; fintrace
knows only an `issuer-uri`. Putting it in fintrace's Compose file would reintroduce through
deployment exactly the coupling §3.4 removes.

**Resource note:** Keycloak on the JVM idles at roughly 400–600 MB and needs its own database
(a separate schema in the existing Postgres is acceptable). That is a real share of the ~4 GB
budget (§7.1). Authentik is lighter as a process but pulls in Redis and a worker, so compare
totals rather than assuming it wins.

#### Roles

Two roles for now: `admin` and `user`. `user` is the default and grants access only to one's
own workspaces; `admin` additionally reaches the admin surface (`/admin/` endpoints and an
admin UI — both post-MVP).

**Roles, not an `isAdmin` flag.** A flag cannot grow without a migration; a role set grows by
adding a row. The distinction is currently technical rather than business-meaningful, but the
model is the one that extends.

**Roles live in Core, not in the IdP.** Managing them as IdP groups would mean configuring
Google or Okta group claims for a single user — disproportionate. The IdP answers "who is
this"; Core answers "what may they do".

#### Authorisation

Enforced **server-side in Core**, always. Clients may hide commands they cannot use, but a
client never decides what its user is allowed to do — this matters as soon as the CLI (M7)
exists alongside the web UI.

#### Bootstrapping the first admin

On first start, Core emits a random token to the log. The operator signs in through SSO, then
enters that token once — which **binds their existing SSO identity to the `admin` role**.

> **Explicitly not:** a wizard that creates an admin *login and password*. That would mean a
> second authentication system living inside fintrace — password hashing, reset, rotation,
> brute-force protection — running in parallel with SSO, which is precisely what SSO exists to
> avoid. It would also contradict §3.4, where identity is deliberately owned by a shared
> external IdP.
>
> The simpler alternative — "the first user to sign in becomes admin" — was considered and is
> perfectly workable; the log token was kept only as cheap protection against an accidental
> first sign-in claiming the role.

#### Token propagation

**Decision: the BFF forwards the IdP token and Core validates it. This is the target solution,
not a pilot shortcut.**

The earlier plan was to move later to internal tokens issued by the BFF, because forwarding
makes Core depend directly on an external IdP. **That objection disappears once the IdP is
self-hosted** (Keycloak or Authentik on the NAS): it is no longer an external dependency but a
piece of your own infrastructure.

A self-hosted IdP also absorbs the other reason internal tokens looked attractive: **adding
providers.** Keycloak federates Google, email/password and anything else behind a single
unchanging issuer, so the applications never learn which provider was used — that becomes IdP
configuration rather than application code.

What would still argue for internal tokens: the BFF needing to add claims the IdP cannot
supply, or very short-lived internal tokens. Both are hypothetical, so **internal token
issuance is dropped from the plan** rather than deferred.

**Roles stay in Core regardless.** Keycloak could carry them as claims, but they describe
domain access (which workspaces, which admin surface), not identity — putting them in the IdP
would split authorisation across two systems.

**Still keep identity extraction in one place in Core** (task 0.10) — because the CLI (M7)
will arrive as a second client.

#### Libraries

| Component | Choice |
|---|---|
| BFF (Go) | `coreos/go-oidc` over `golang.org/x/oauth2` — discovery, JWKS caching, ID-token verification. Low-level enough to see what is happening, which suits the learning goal |
| Core (Kotlin) | Spring Security OAuth2 Resource Server — `issuer-uri` in config, JWKS and validation handled; roles resolved from the local database via a custom converter |

**Take the issuer from configuration and rely on OIDC discovery**
(`/.well-known/openid-configuration`) rather than hardcoding the issuer. That is what makes
"adding a provider is configuration" actually true — and it keeps the door open if the IdP is
ever moved or replaced.

### 7.5 Inter-service authentication

Core's import API is not public, but it is not unauthenticated either. OAuth2 **client
credentials** flow is the natural fit and matches the learning goal.

### 7.6 Status delivery to the UI

Polling. At this import frequency, once per second is more than enough and requires no
persistent connection. SSE is a legitimate Go learning exercise but should be recognised as
such rather than as a requirement.

---

## 8. Open questions

### 8.1 Highest impact

> **Q1 (interpreter language) — resolved: Kotlin.** See §3.5.

**Q2. Does Core store the raw MoneyOK events, or only the replay result?**

Storing the raw source is cheap at this volume and means an interpreter bug can be fixed
and reprocessed without re-uploading files. Weighed against it: raw foreign events inside
Core sit uncomfortably next to the §3.2 boundary rule. Possible resolution: store raw
payloads as opaque blobs attached to the import record, never interpreted by the domain.

### 8.2 Requires inspecting a real dump

Both affect the data model directly and should be answered before the schema is drawn:

**Q3.** Does `type = 110` (balance verification) actually carry a `date`? The vendor client
never reads it; only hand-written test fixtures assert it. If it does not, balance
corrections are undatable and historical balance reconstruction changes shape.

**Q4.** Can a transaction be uncategorised — is `catUid` ever `0` or absent? Determines
whether the category reference is nullable.

Secondary: do accounts really have a `note` field (probably not); does `type = 1` ever
appear more than once; do transaction `opType` and category `opType` ever disagree.

### 8.3 Deferred

- Historical exchange rates. The MVP converts at **today's rate only** (§9). Rates as of a
  transaction's own date — needed for "balance dynamics over 4 years in EUR" — are deferred,
  along with the persistent rate cache they require.
- Period-over-period comparison — desirable, deferred past the MVP.
- Compensating operations for unexplained anchor differences (§4.6) — semantics decided,
  implementation deferred.
- Splitting transport from parser in the importer.
- Telegram as a second interface — currently dropped in favour of Web UI only.

---

## 9. Currency conversion

The source carries **no FX rates and no base currency** — a multi-currency user has *n*
parallel unconverted series. Any consolidated figure requires an external rate source and an
as-of policy this system defines itself.

### 9.1 Default currency

A workspace has a **default currency** (e.g. EUR). Reports may be requested either
unconverted (each account in its own currency) or converted to a target currency.

Target currency is an optional request parameter; the workspace default applies when it is
omitted.

### 9.2 Scope: today's rate only in the MVP

| Iteration | Behaviour |
|---|---|
| MVP | Conversion at **today's rate**. In-memory cache. |
| Later | Rates as of each transaction's own date; persistent cache. |

Rationale: historical conversion is materially larger than an optional parameter — it needs
a fetcher, a durable cache, weekend/holiday fallback, behaviour when the provider is
unreachable, and a policy for currencies outside the provider's coverage. "Balances in EUR"
works from day one; "four years of balance history in EUR" waits.

> **Note for the later iteration:** an in-memory cache is the wrong shape for historical
> rates — a past date's rate never changes, yet a restart discards it. Historical rates belong
> in the database; in-memory should hold only today's. Otherwise a four-year chart becomes
> ~1400 outbound requests.

### 9.3 Rate provider

**Frankfurter** (`api.frankfurter.dev`) — free, open source, **no API key**, current and
historical rates. Critically, it is **self-hostable via Docker**, which on a NAS removes the
external dependency and any rate limiting entirely.

**Coverage — verified.** AMD (Armenian dram), RSD (Serbian dinar), RUB and USD all return
rates. The newer API is broader than ECB reference rates, so no fallback provider is needed.

```
GET /v2/rates?base=EUR&quotes=USD,RSD,AMD,RUB&date=2025-03-15
→ [{"date":"2025-03-15","base":"EUR","quote":"AMD","rate":426.26}, ...]
```

**Weekend/holiday handling — verified.** ECB-based rates publish once per business day, but
the API carries the last published rate forward: 15 and 16 March 2025 (Saturday and Sunday)
both return USD 1.0863. No fallback logic is needed on our side.

> Note: the `date` field echoes the **requested** date, not the date the rate was actually
> published — so a carried-forward rate is indistinguishable from a fresh one in the response.
> Irrelevant for this use case, but worth knowing.

Response shape is a flat array, one object per pair.

**Unsupported currency:** the account is shown **unconverted, with a marker**, rather than
being dropped from the report. A converted total therefore has to communicate that it does
not cover everything.

## 10. Core API (CRUD)

### 10.0 Event sourcing does not leak into the API

**Decision: a conventional REST surface.** `PUT /operations/{id}` carries a full body; the
server records the event and updates the projection internally. The client never sees
revisions, commands or event semantics.

A command-style surface (`POST /operations/{id}/revisions`) was rejected: it is the same
operation under a less familiar name, and it would make the UI pay for an implementation
choice made inside Core.

### 10.1 Resources

All under `/workspaces/{workspaceId}/`.

| Resource | Notes |
|---|---|
| `/accounts` | CRUD; `DELETE` archives rather than deletes (§4.8) |
| `/accounts/{id}/anchors` | `POST` to create, `DELETE` to remove — see below |
| `/categories` | CRUD; `DELETE` is a soft delete (§4.7) |
| `/operations` | CRUD; **read surface for transfer legs as well** |
| `/transfers` | Write surface for transfers |

### 10.2 Deletion semantics

`DELETE /operations/{id}` **cancels** the operation. A cancelled operation disappears from
listings and statistics entirely — it is not shown struck through. Filtering it out of every
query by hand would be error-prone.

Version history is retained in the event store but is **not exposed in the MVP**. A
`GET /operations/{id}/history` endpoint is cheap to add later, but the UI for it is not
MVP work.

### 10.3 Transfers: read via `/operations`, write via `/transfers`

The asymmetry is deliberate and reflects the model (§4.5):

- **Reading** — a transfer's two legs appear in `/operations` like any other entry, so an
  account's activity feed is uniform. Each leg carries a link to its counterpart, which the
  UI can present nicely.
- **Writing** — a transfer is created and edited as one thing: two accounts, two amounts,
  atomically producing two linked entries. That cannot be expressed by posting a single leg.
  Editing a transfer's amount must update both legs, so `PUT /operations/{id}` on a single
  leg is rejected.

The alternative — a single `POST /operations` whose body shape varies by `kind` — was
rejected as the worse trade.

### 10.4 Anchors

`POST /accounts/{id}/anchors` creates a balance correction or confirmation (§4.6).

**No update.** An anchor is an observation; correcting it is not meaningful.

**`DELETE` removes only the most recent anchor**, for typo recovery. Since anchors cannot be
back-dated, every new anchor is later than all previous ones, so "most recent" is simply
`ORDER BY occurred_at DESC LIMIT 1` for that account — creation order and date order coincide,
and timestamps carry time (§6.4), so there are no ties. Deleting an anchor from the middle of
history would shift every balance after it, so this is not permitted.

**What deletion means:** balance calculation falls back to the previous anchor (or the account's
opening balance) and sums operations forward from there. The balance after the deleted anchor
changes by exactly the difference that anchor was absorbing. Semantically: "I withdraw that
observation — compute from the operations."

Since anchors cannot be updated, deleting and re-creating is the only way to fix a mistyped
value.

> **Sole exception to the no-physical-deletion principle (§4.8):** anchors are deleted
> physically. Nothing references an anchor — it affects calculation but is not a parent to any
> entity — so there is nothing to orphan.

## 11. Statistics

### 11.1 Report types

| Report | Dimensions |
|---|---|
| Account balances | Per account, in own currency or converted |
| Balance dynamics | Series over time: year / month / week / day |
| Income & expenses | Series over time: year / month / week / day |
| Category breakdown | For a period; collapsed or expanded (§11.2) |

### 11.2 Category tree aggregation

**Both modes required.** Spending 100 on *Food → Cafés* and 200 on *Food → Groceries* can be
reported either as `Food 300` (rolled up) or as two separate leaf rows. The mode is a request
parameter.

### 11.3 Granularity

Granularity is a **request parameter**, not derived server-side from the range. The UI drives
it: viewing all time might request monthly buckets (~48 points over 4 years); zooming into a
few months switches to weekly. Range selection directly on the chart, Kibana-style.

### 11.4 API shape

**Separate endpoints per report, sharing a common filter set.** A single parameterised
endpoint was rejected: the four reports differ in *response shape* (flat list, time series,
tree), not just in parameters, and one endpoint returning three different structures is
awkward for both clients and OpenAPI-generated types.

All under `/workspaces/{workspaceId}/statistics/`.

| Endpoint | Returns |
|---|---|
| `GET /balances/accounts` | Balance per account, as of a date |
| `GET /balances/currencies` | Totals grouped by currency, as of a date |
| `GET /balance-series` | Balance over time |
| `GET /cashflow` | Income and expenses over time |
| `GET /categories` | Category breakdown for a period |

**Common query parameters:**

| Parameter | Notes |
|---|---|
| `from`, `to` | Period bounds on `occurred_at` |
| `accountIds` | Optional account filter |
| `targetCurrency` | Optional; workspace default applies when omitted. Absent means unconverted |
| `includeArchived` | Archived accounts stay in reports, so this defaults to true here |
| `granularity` | `balance-series` and `cashflow` only: day / week / month / year |
| `mode` | `categories` only: collapsed or expanded (§11.2) |
| `kind` | `categories` only: income or expense — one tree at a time |

The balance endpoints take a single `asOf` date (default today) instead of `from`/`to`. Not
forcing them into the common shape is deliberate.

**Balances are always expressed in the account's own currency** on `/balances/accounts`.
Conversion is the concern of `/balances/currencies`; mixing a converted figure into a list of
accounts would be confusing.

**Shared currency-result type**, reused across reports rather than reinvented per endpoint:

```
byCurrency: [ { currency, total } ]
converted:  { currency, total, coveredCurrencies }   // present only when conversion requested
```

`coveredCurrencies` states which currencies the converted total actually includes — a
converted total must not silently imply full coverage when an unsupported currency was left
out (§9.3).

### 11.5 Deferred

- **Transfers as their own report** ("how much moved between accounts"). Transfers are
  excluded from income/expense statistics via `kind`; a dedicated view is a next-iteration
  item.

## 12. MVP scope

**In:**

- Feature parity with the current bot: statistics and charts by day / week / month / year.
- **Categories** — present in the source, ignored by the current bot, required now.
- Multi-currency: per-currency aggregation, plus conversion to a target currency at today's
  rate (§9).
- HTTP upload; manual entry, correction and annotation via Web UI.
- Authentication via the external IdP.

**Out:**

- Budgets, alerts, forecasts.
- Historical-rate conversion (today's rate only — §11.2).
- Message queue (iteration 2).
- Google Drive transport.
- File attachments from the source.

---

## 13. Decisions rejected along the way

Recorded so they are not silently revisited.

| Rejected | Reason |
|---|---|
| Kafka | Introduced as a counter-example, never justified by any requirement |
| Chunked / multi-step import protocol | Solves a volume problem that does not exist; not viable async |
| Batching, sequence numbers, total-reconciliation, stall timeouts | Distributed bookkeeping for a 1–2 second operation |
| Incremental import via `uid` watermark | Unsafe — the source renumbers `uid` on export |
| Separate transport and parser services in the MVP | One transport, one format — premature |
| Splitting the database across services | Artificial for this domain; Core is the sole data owner |
| Copying MoneyOK's delta-encoded event model | The direct cause of the current bot's complexity |
| "ES because the import is append-only" | The importer exists to break that dependency; the mapping does not hold anyway |
| Self-hosted IdP inside this stack | One shared IdP serves all NAS services |
| Internal tokens issued by the BFF | Dropped, not deferred: a self-hosted IdP removes both reasons for them — external dependency and multi-provider support (§7.4) |
| Admin login+password created by a first-run wizard | A second auth system inside fintrace, parallel to SSO — password storage, reset, brute-force protection. The log token now binds an SSO identity to the admin role instead (§7.4) |
| Separate imported-vs-own operation classes | Import happens once into an empty workspace — the distinction has no purpose |
| Override layer for category and comment | Same reason; category and comment are ordinary editable fields |
| Import/manual duplicate detection | Impossible by construction under the workspace model |
| Incremental import after initialisation | Out of scope; manual re-entry via UI instead |
