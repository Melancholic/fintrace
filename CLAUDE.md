# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository status

**M0 complete** (tasks 0.1–0.13 — see `docs/plans/M0.md`); **M1 in progress**, per
`docs/plans/M1.md` and `docs/tasks.md`.

`fintrace-core` runs the operation aggregate end to end — create, revise and cancel:
`POST` / `GET` / `PUT` / `DELETE /api/v1/workspaces/{workspaceId}/operations[/{operationId}]` →
`CommandFacade.processCommand` → dispatcher → handler → event + projection, in one transaction;
and `POST /admin/api/v1/workspaces/{id}/replay` clears the projection and rebuilds it from the
event log. Only Core exists — the importer, BFF and web are later milestones.

**Done in M1 so far:** task 1.17 (revise / cancel, with the cancelled row removed rather than
flagged) plus its HTTP surface, and command-time validation — the operation must exist in the
command's workspace, and `occurredAt` may not be in the future. **Not yet started:** everything
workspace-, account-, category-, transfer- and anchor-shaped (1.1–1.16, 1.18–1.26), so
`t_workspaces` still does not exist and `workspace_id` remains an unconstrained `uuid`.

Two agreed shapes from `docs/plans/M1.md` are **not** implemented yet, both deliberately deferred
rather than rejected: the `occurredAt` split (every command still declares one, so cancel carries
a business date it has no use for), and `ProjectionChange` / `ProjectionApplier` — a deletion is
currently modelled as a `DeleteOperationProjection`, so handlers still call the projection DAO
directly and `AdminFacade` keeps a `when` that gains a branch per aggregate.

Verify with `./gradlew clean test` (95 tests). Prefer `clean` — incremental builds have twice
masked a genuine compile error in the test sources.

**Not yet enforced, and assumed by nothing:** workspace *ownership* (any authenticated caller can
reach any workspace) and real authentication. Both land at M5; `/admin/**` is already gated on the
`ADMIN` role.

`IdentityProvider` in `security/` is the single place caller identity is resolved (task 0.10) —
M5 replaces its implementation, and nothing else may read the security context.

The command pipeline is the shape every later aggregate copies: a sealed `Command<R>` hierarchy
(create returns `UUID`, revise/cancel return `Unit`), routed by `CommandDispatcher` to a handler
that validates, appends the event, then writes the projection. `CommandFacade` owns the
transaction boundary and is where workspace-ownership checks will land at M5.

```bash
cd fintrace-core
./gradlew test          # unit + Testcontainers integration tests
./gradlew test --tests '*SchemaMigrationTest'   # a single test class
./gradlew bootTestRun   # run locally; starts its own Postgres via Testcontainers
./gradlew bootJar       # required before building the Docker image

cd deploy               # needs deploy/.env (see .env.example)
docker compose up -d --build
```

`bootRun` does **not** work — `application.yml` carries no datasource, so local runs go through
`bootTestRun` (Testcontainers supplies Postgres) or Compose (which supplies `SPRING_DATASOURCE_*`).
The Dockerfile copies a pre-built jar rather than building one, so `bootJar` must run first or the
image ships stale code.

The design is settled and recorded. Before writing code, read the relevant section of
`docs/design-decisions.md` — it is the authority, and most "why is it done this way" questions
are answered there with the alternatives that were rejected. `docs/roadmap.md` orders the work
into milestones; `docs/tasks.md` breaks M0–M3 into single-sitting tasks (M4–M6 are explicit
placeholders to be re-decomposed later). `docs/plans/` holds the agreed per-milestone
implementation plan — start with `docs/plans/M0.md`, which settles the toolchain, schema and
package layout the rest of Core builds on.

The old bot being replaced lives at `~/projects/moneyok-analyzer-telegram-bot` — it is the source
of the M2 port and of every file path cited in the dump reference.

`docs/mok-dump-format.md` (cited as "the dump reference") is the source-data reference for the
MoneyOK export: recovered DDL, per-event-type payload keys, and §10's catalogue of traps. Read it
before touching importer code. Two things about how to use it:

- **Its confidence markers are load-bearing.** Claims are labelled **[FACT]** (proven by code or
  fixture), **[INFERRED]** (reasoning from that evidence) or **[UNKNOWN]**. Don't flatten an
  inference into a fact.
- **Its file paths point at the old bot's repository, not this one** — `StatisticsProvider.kt`,
  `MokOperations.kt`, the deobfuscated vendor client `mok-obf-3_0_2.deobf.js`, `example.sqlite`,
  `test-scenario.md`. None of them exist here. The vendor client is authoritative for *format
  semantics*; the old bot's code is authoritative only for *what the old bot does*, including its
  defects.

**Where the two docs disagree, `design-decisions.md` §2.3 wins.** The dump reference's §11 open
questions (and §8.2 of the design record) were answered against the real production dump: no
`type = 110`, no file attachments, no category deletions, no uncategorised operations. That spike is
done — don't re-run it.

## Planned layout

Monorepo, one developer. Directory names match Compose service names and GHCR image names
exactly, so a directory, a service and an image are always the same string.

```
fintrace-core/            Kotlin + Spring Boot — domain, event store, projections, statistics
fintrace-mok-importer/    Kotlin — MoneyOK chronicle interpreter
fintrace-bff/             Go — JWT validation, routing, aggregation
fintrace-web/             frontend
fintrace-cli/             Go — terminal client (post-MVP, M7)
deploy/                   docker-compose, Portainer
```

CI uses path filters so a frontend commit does not rebuild Core. A single stack-wide semver;
everything deploys together.

## Architecture

Three services. **Core is the only service that connects to PostgreSQL** — the others are
stateless. Requests flow Web/CLI → BFF (Go) → Core (Kotlin). The importer calls Core's import
API like any other client.

The identity provider (Keycloak, Google federated inside it) is deliberately **outside this
stack** — its own Portainer stack, shared with other NAS services. It must never appear in
`deploy/docker-compose.yml`; fintrace knows only an `issuer-uri` and speaks plain OIDC via
discovery. No provider-specific SDK anywhere.

### Event sourcing (strict, every entity inside a workspace)

Events are the only thing written directly; projection tables (`t_operations`, and at M1
`t_accounts`, `t_categories`, `t_anchors`) are derived and rebuildable. The non-negotiables:

- **Nothing writes to a projection except the event handler.** One stray `UPDATE` desyncs
  projection from events, silently.
- **All validation happens at command time, before the event is appended.** An event has
  already happened and cannot be rejected. Drifting into validating inside handlers is the
  predicted failure mode — watch for it.
- **Event and projection update share one transaction.** Single writer, no async projection
  machinery.
- **Every payload carries a `version` field** from day one. A payload class is immutable once
  written: `OperationCreatedV1` is never edited, because rows on disk are in that shape and every
  rebuild re-reads them. A shape change means adding `…V2` beside it under
  `domain/event/payload/`, registered in `EventPayload`'s `@JsonSubTypes`, and keeping V1 forever.
- Events carry the **full resulting state**, never deltas — deliberately not copying MoneyOK's
  delta encoding, which is the direct cause of the current bot's complexity. Three event kinds
  suffice: `CREATED` / `REVISED` / `CANCELLED`, produced by `Create…` / `Revise…` / `Cancel…`
  commands. (Earlier drafts of the design record called these *superseded* and *voided*; the
  meanings are unchanged.)
- **The full-rebuild procedure must share the same handler as the online path**, and exists
  from M0 rather than "when needed". The rebuild-equality test (rebuild, assert the projection
  is identical) is the cheap guard that keeps handlers idempotent.

Single `t_events` table for all aggregate types — splitting buys nothing at this volume and
costs global ordering.

**Database naming convention (§4.13.1):** `t_` tables, `v_` views, `idx_` indexes. M1 adds
`t_workspaces`, `t_accounts`, `t_categories`, `t_anchors`. Kotlin names stay unprefixed
(`OperationProjection`, not `TOperation`).

**UUIDv7 for every entity, generated in code, not by the database.** The reason is
rebuildability: a database-assigned id would force insert-then-event ordering, and a rebuild would assign
different numbers, breaking inter-entity references stored in earlier events.

Event sourcing does **not** leak into the API: the REST surface is conventional
(`PUT /operations/{id}` with a full body). Clients never see revisions or commands.

### Workspaces

Every domain entity carries `workspace_id`, in every table and every query, from day one. All
API resources are nested under `/workspaces/{workspaceId}/`.

Statuses are `NEW` → `ACTIVE` ↔ `ARCHIVED`, and `DELETED` from either (terminal); `NEW` is the
former `DRAFT`. Import is permitted **only** into a `NEW` workspace and is permanently closed
afterwards. `ARCHIVED` is read-only **system-wide** — every command is rejected, checked once at
the command entry point rather than per handler. Deletion is soft but terminal — there is no restore, and a retention job hard-deletes `DELETED`
workspaces after a configurable window (default 30 days) by cascade. Archiving is the recoverable
path and belongs in the main UI; deletion belongs in settings, warned as unrecoverable and
confirmed by typing the workspace name — a UI affordance only, the API takes no confirmation
parameter. Status is stored explicitly,
never inferred from emptiness ("start empty" produces an `ACTIVE` workspace with no data), and
emptiness excludes the four seeded system categories or a fresh workspace fails its own check.

**The workspace record is the one thing that is not event-sourced** — `t_workspaces` is an
authoritative table written directly, never rebuilt. It is the tenant boundary, not an entity
inside it; as an aggregate it made replay circular.

This single decision is what removes re-import conflicts, import-vs-manual duplicate
detection, override layers, and the imported-vs-own operation distinction. Don't reintroduce
them. A failed import must leave a *genuinely* empty `NEW` — hence one transaction for the
whole import.

### Domain rules that are easy to get wrong

- **Transfers are two linked ledger entries**, not one record: shared `transfer_id`, each
  pointing at the other via `counterpart_id`. Read them through `/operations` (uniform account
  feed); write them through `/transfers` (the pair is an invariant — create/revise/cancel must
  always affect both). `PUT /operations/{id}` on a transfer leg is rejected.
- **`amount` is signed**, expenses negative, so balance is `SUM(amount)` with no `CASE`.
- **Balances are never stored, always computed**: nearest preceding anchor + sum of operations
  after it.
- **Anchors are absolute observed values, never deltas, and cannot be back-dated.** An anchor
  affects balance calculation only — it never appears in income/expense statistics. Only the
  most recent anchor for an account may be deleted. Anchors are the sole exception to the
  no-physical-deletion rule.
- **Nothing referenced by an operation is ever physically deleted**: categories are
  soft-deleted, accounts archived, operations cancelled, workspaces set to `DELETED`. Anchors
  are the sole physical deletion in the system. Archiving an account is permitted with a non-zero
  balance and changes no computed figure — the remedy for a stale balance is an anchor at zero;
  writes to an archived account are rejected at command time.
- **Categories** are an adjacency-list tree of unlimited depth. Four immutable system
  categories (INCOME/EXPENSE roots and both `Others`) are seeded with the workspace. Moves stay
  within the same branch (crossing branches would rewrite the meaning of historical
  operations), `Others` stays a leaf, and the move command must check the target is not a
  descendant.
- **Statistics exclude transfers** via `kind <> 'TRANSFER'`.

### Temporal model

Bitemporal: `occurred_at` (business date, user-entered, back-dating is normal) and
`recorded_at` (when it entered the system). **All statistics query `occurred_at`; nothing is
folded incrementally.** The current bot's hardest problem — retroactively patching already
computed days — is not solved here, it is not created.

All temporal fields are timestamps (date *and* time), stored in the system timezone with no
zone attached. **`TZ` must be pinned explicitly in Docker Compose** — relying on the container
default means stored timestamps silently re-read as different wall-clock times after an image
or host change.

Source timestamps are floating local wall-clock with no zone: read them as `LocalDateTime` and
**never convert to UTC without an explicit user-supplied zone**.

### The MoneyOK importer

The source dump is one `Chronicle` table: an append-only, delta-encoded log of ~6k JSON
events with 26 types. Accounts, operations and transfers exist only as a fold over that log in
`uid` order.

**Core must never learn the words `moneyBack`, `badge`, `opType` or `Chronicle`.** All source
quirks stay behind the importer boundary. The importer emits normalised facts to
`POST /workspaces/{id}/import` — one request, sections in the body, cross-references by
external id, unresolvable references a hard failure.

This is the highest-risk component in the system: a defect here does not fail loudly, it
quietly distorts figures for months. It is a **port** of the existing bot's
`StatisticsProvider.kt`, not a rewrite — the first `when` block plus the `applyX` methods are
the interpreter and port as-is; the second `when` block and everything around `stateByDate`
is statistics and is deleted outright (statistics are queries now).

The one genuine complication: `moneyBack`/`moneyBack2` gates let the source's operation amounts
and account balances diverge on purpose (2899 such events — routine, not an edge case). The new
model can't represent that directly, so the interpreter keeps tracking balances by the source's
rules and emits a **final anchor per account** carrying the balance the source believes in; the
gap becomes that anchor's unexplained difference.

Other importer rules: order by `uid` explicitly, never JDBC row order (rowid order is not
guaranteed by SQL, and the old bot relies on it); unknown event types and currencies are logged and
skipped, never thrown (the old bot's fail-fast *loading* is a defect — its fail-fast *replay* is
sound); deleted accounts are emitted as archived; `uid` is `Long`, not `Int`.

Traps from the dump reference §10 that must survive the port: **absent key means unchanged, except
`moneyTo` on `UPDATE_TRANSFER`, which resets to `-1`** — a vendor bug, but reproduce it or balances
diverge from what the app shows; field meaning depends on a sibling field in five places; `uid = 0`
is never a valid entity id (it doubles as the orphaned-transfer-leg sentinel); `money` mixes integer
and decimal JSON in the same field. Cross-check `sqlite_sequence.seq` against `max(uid)` on ingest —
a mismatch means the dump was rewritten and its `uid`s are not comparable to any earlier export.

## Working constraints

- **Scale is never a valid justification.** ~6k records, a few imports per month, full
  recomputation in milliseconds. The two real drivers are **learning** (the reason the project
  exists) and **operability on constrained hardware** (~4 GB on a NAS). Every complication must
  be labelled either "needed to work" or "needed to learn" — don't retrofit performance
  reasoning onto a choice made for learning.
- ~8 h/week, single developer, 60–100 h to the pilot. Keep the client dumb: all aggregation,
  conversion and tree rollup happen in Core.
- Security is designed now, implemented at M5: workspace ownership checked everywhere, and a
  **single** identity-resolution point in Core returning a stub until real auth fills the slot.
- `docs/design-decisions.md` §13 lists decisions already rejected (Kafka, chunked import,
  incremental `uid`-watermark import, splitting the database, internal BFF-issued tokens,
  duplicate detection, …). Check it before proposing one of them again.
