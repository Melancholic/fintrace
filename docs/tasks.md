# fintrace — Task Breakdown

**Companion to:** `roadmap.md`, `design-decisions.md`

> **Confidence decreases down this document.** M0–M2 are concrete: the design is settled and
> the work is knowable. M3 is mostly mechanical. **M4–M6 are placeholders** — by the time you
> reach them, half the detail will have changed. Re-decompose those milestones when you get
> to them rather than treating these as commitments.

Each task aims to be a single sitting (1–3 h). Tasks marked **[spike]** are investigations
with no deliverable code.

---

## M0 — Walking skeleton

**Goal:** one operation, end to end, with the ES machinery working as a whole.

- [x] ~~**0.1** Kotlin + Spring Boot project skeleton, Gradle, package structure~~
- [x] ~~**0.2** Docker Compose: Postgres + Core. Pin `TZ` explicitly on both (§6.4)~~
- [x] ~~**0.3** Migration tool wired up (Flyway or Liquibase), empty baseline migration~~
- [x] ~~**0.4** Migration: `events` table per §4.11, with `events(workspace_id, id)` index~~
- [x] ~~**0.5** Migration: minimal `operations` projection table (§4.13) — signed `amount`,~~
      ~~`occurred_at`, `recorded_at`, UUID PK~~
- [x] ~~**0.6** UUIDv7 generation **in application code**, never a database default — a rebuild~~
      ~~must reproduce the same ids (§4.12). JUG, since the JDK has no v7 factory~~
- [x] ~~**0.7** Event payload envelope: `version` field, JSONB serialisation, round-trip test~~
- [x] ~~**0.8** `CreateOperation` command → validation → event append → handler → projection,~~
      ~~all in one transaction~~
- [x] ~~**0.9** `POST /workspaces/{id}/operations` + `GET /workspaces/{id}/operations`, enough~~
      ~~to exercise 0.8 by curl~~
- [x] ~~**0.10** **Single** identity-resolution point returning a fixed stub — the one slot real~~
      ~~auth fills later (§7.4). One place, because the CLI (M7) arrives as a second client~~
- [x] ~~**0.11** **Full-rebuild procedure**: clear projection for a workspace, replay events in~~
      ~~`id` order through *the same handler* as the online path~~
- [x] ~~**0.12** Rebuild-equality test: create N operations, snapshot projection, rebuild,~~
      ~~assert identical~~
- [x] ~~**0.13** Integration test setup (Testcontainers) so 0.12 runs against real Postgres~~

**Done when:** you can create operations over HTTP, wipe the projection, rebuild it from
events, and the projection rows are identical.

---

## M1 — Domain model in Core

**Goal:** all entities, invariants and CRUD on the M0 foundation.

### Workspace

- [ ] **1.1** Migration: `workspaces` (UUID PK, name/slug, `status`, `default_currency`)
- [ ] **1.2** Create workspace → `NEW`; seed the four system categories in the same
      transaction (§4.7): both roots, both `Others`
- [ ] **1.3** Emptiness check as a single reusable function scanning every table carrying
      `workspace_id` (§4.2)
- [ ] **1.4** Status transitions (§4.1.1): `NEW → ACTIVE` ("start empty" action, and the hook
      import will use), `ACTIVE ↔ ARCHIVED`, `→ DELETED`; `ARCHIVED` read-only enforced once at
      the command entry point
- [ ] **1.5** Delete workspace — soft, a `DELETED` status; anchors remain the only physical
      deletion in the system
- [ ] **1.5b** Retention job: hard-delete workspaces `DELETED` longer than a configurable
      window (default 30 days), one transaction each, via `ON DELETE CASCADE`. Refuses a
      non-positive window; the cutoff is a parameter so the test need not wait a month
- [ ] **1.6** `workspace_id` enforced on every query — decide the mechanism now (explicit
      parameter vs. a repository-level guard) and apply it consistently

### Accounts

- [ ] **1.7** Migration + projection: `name`, `currency`, `icon`, `archived`
- [ ] **1.8** Create / rename / archive / unarchive commands and events
- [ ] **1.9** Opening balance becomes the first anchor (§4.6)
- [ ] **1.10** CRUD endpoints; `DELETE` archives

### Categories

- [ ] **1.11** Migration: `parent_id`, `kind`, `deleted`, `system`, `icon`
- [ ] **1.12** Recursive descendants query (`WITH RECURSIVE`) as a reusable component —
      needed by both statistics and the UI tree
- [ ] **1.13** Create / rename / move / soft-delete commands
- [ ] **1.14** Move validation: same branch only; target is not a descendant (cycle guard);
      `Others` stays a leaf; `system` categories immutable
- [ ] **1.15** CRUD endpoints

### Operations, transfers, anchors

- [ ] **1.16** Extend `operations` projection with the full field set (§4.13)
- [x] ~~**1.17** Operation revise and cancel commands; cancelled disappears from listings (§10.2)~~
      — done ahead of order, with `PUT` / `DELETE` endpoints and command-time validation
      (operation exists in this workspace; `occurredAt` not in the future).
      A revise with an unchanged body still appends an event at M1 — suppression is deferred
      (§4.4)
- [ ] **1.18** Transfer create/revise/cancel → two linked legs, atomically, sharing
      `transfer_id`, each pointing at the other via `counterpart_id`
- [ ] **1.19** Reject `PUT /operations/{id}` on a transfer leg (§10.3)
- [ ] **1.20** `/transfers` write endpoints; transfer legs readable via `/operations`
- [ ] **1.21** Migration + projection: `anchors`
- [ ] **1.22** Anchor create; reject back-dating (§4.6)
- [ ] **1.23** Anchor delete: only the most recent for that account
      (`ORDER BY occurred_at DESC LIMIT 1`)
- [ ] **1.24** Balance calculation: nearest preceding anchor + `SUM(amount)` after it
- [ ] **1.25** Unexplained-difference calculation for an anchor
- [ ] **1.26** "Confirm balance" action — anchor at the currently computed value

**Watch for:** every invariant belongs at command time, before the event is written (§4.10).
Validating inside a handler is too late.

---

## M2 — MoneyOK interpreter

**Highest risk. Start early. The [spike] tasks come first and may change everything after.**

- [x] ~~**2.1–2.3 [spike]** Dump inspection — **done**, results in §2.3–2.4 of~~
      ~~`design-decisions.md`. No `type = 110`, no file attachments, no category deletions, no~~
      ~~uncategorised operations. 2899 operation updates and 80 undated balance assignments are~~
      ~~the real work.~~
- [x] ~~**2.4 [spike]** Read `StatisticsProvider.kt` — **done. It is a port, not a rewrite.**~~
      ~~The first `when` block plus all `applyX` methods are the interpreter and port as-is; the~~
      ~~second `when` block and everything around `stateByDate` is statistics and is deleted.~~
      ~~See the M2 section of `roadmap.md`~~

### Importer service

- [ ] **2.5** Kotlin project skeleton for the importer; internal boundary between transport and
      parser kept clean even though they ship as one service (§3.2)
- [ ] **2.5b** Shape the interpreter as a **pure function**: events in, entities out. Note it
      still tracks account balances internally — the source's gate semantics require it (see
      2.10c) — but emits no statistics; those are queries now (§6.2)
- [ ] **2.5c** Delete the statistics half while porting: `opAttribution`, the `before`
      snapshot, `ensureDate`, `addBalanceForward`, `setBalanceForward`, `relocateForward`,
      `removeAccountForward`, `addBalanceChangesForward`, `TransferContribution`,
      `createDailyStatistics`, and the second `when` block
- [ ] **2.6** SQLite reading, `ORDER BY uid` explicitly — never rely on natural row order
- [ ] **2.7** Chronicle replay: accounts, categories, groups
- [ ] **2.8** Chronicle replay: operations, including `moneyBack` / `moneyBack2` gates
- [ ] **2.9** Chronicle replay: transfers, including the `moneyTo` reset-to-`-1` quirk
- [ ] **2.10** Chronicle replay: deletions — only operations (79) and transfers (4) occur;
      categories and groups are never deleted in this data
- [ ] **2.10b** `type = 21` balance assignments → anchors, dated by carry-forward from the
      preceding dated event + epsilon (§2.4). Fix the two defects in `buildNumToDateMap`:
      order by `uid` not JDBC row order, and handle a log with no dated events
- [ ] **2.10c** **Emit a final anchor per account** carrying the balance the source believes
      in. Required because `moneyBack` / `moneyBack2` let the source's operation amounts and
      account balances diverge deliberately (2899 such events). The gap becomes the anchor's
      unexplained difference (§4.6), and imported figures then match the mobile app
- [ ] **2.10d** Archive deleted accounts instead of removing them (§4.8) — replaces
      `applyDeleteAccount`'s `accounts.remove` and the `uid = 0` transfer orphaning
- [ ] **2.10e** `Int` → `Long` for uid throughout
- [ ] **2.11** Unknown `type` codes and unknown currencies: log and skip, never throw
      (the current bot's fail-fast loading is a defect, §10.4 of the dump reference)
- [ ] **2.12** Deleted accounts → emitted as archived, so no dangling references leave the
      importer (§5.1)
- [ ] **2.12b** **Category support — genuinely new code.** `normalize()` currently discards
      types 30/31/32/40/41/42/45 and never reads `catUid`. Self-contained, but written from
      scratch
- [ ] **2.13** Map source tree onto your categories: groups → children of the correct root
- [ ] **2.14** Uncategorised operations → emit `null` + kind; Core assigns `Others`
- [ ] **2.15** Transfers → one object per transfer; Core expands into two legs
- [ ] **2.16** Read the 22 existing tests and `test-scenario.md` as a **specification of
      source behaviour**, then write new tests against the new model. They are no longer a
      safety net for a port — the ones asserting day-by-day balances belong at M3 (3.13)
      rather than here
- [ ] **2.17** Renumbering detector: compare `sqlite_sequence.seq` against `max(uid)`, warn

### Import contract

- [ ] **2.18** `POST /workspaces/{id}/import` — one request, sections in body (§5.1). No
      `anchors` section from the source apart from opening balances; imported balance
      assignments arrive via 2.10b
- [ ] **2.19** Reject import into a non-`NEW` workspace
- [ ] **2.20** External-id → internal-id mapping during import
- [ ] **2.21** Hard failure on unresolvable references
- [ ] **2.22** Atomicity: whole import in one transaction; failure leaves a genuinely empty
      `NEW`
- [ ] **2.23** Import optimisation: append events, then rebuild once (§4.10)
- [ ] **2.24** `importId` in the response; import job record
- [ ] **2.25** HTTP upload transport; `NEW → ACTIVE` on success
- [ ] **2.26** End-to-end test: real dump → populated workspace

---

## M3 — Statistics

- [ ] **3.1** Indexes per §4.14
- [ ] **3.2** `GET /balances/accounts` — per account, own currency, as of a date
- [ ] **3.3** `GET /balances/currencies` — grouped by currency
- [ ] **3.4** Shared currency-result type: `byCurrency` + optional `converted` with
      `coveredCurrencies` (§11.4)
- [ ] **3.5** `GET /balance-series` with `granularity`
- [ ] **3.6** `GET /cashflow` with `granularity`
- [ ] **3.7** `GET /categories` — expanded mode
- [ ] **3.8** `GET /categories` — collapsed mode via the recursive query from 1.12
- [ ] **3.9** Transfers excluded from income/expense statistics (`kind <> 'TRANSFER'`)
- [ ] **3.10** Frankfurter client, today's rate only, in-memory cache (§9.2)
- [ ] **3.11** Unsupported currency → shown unconverted with a marker; `coveredCurrencies`
      reflects it
- [ ] **3.12** Behaviour when the rate provider is unreachable — decide and implement
- [ ] **3.13** **Validation against the old bot**: same dump, compare every figure. A
      mismatch means one of the two is wrong; finding out which is valuable either way
- [ ] **3.14** OpenAPI spec published, ready for frontend type generation

---

## M4 — Web UI *(placeholder — re-decompose when you get here)*

Highest estimation risk: the only milestone with no existing code to lean on.

- [ ] **4.1 [spike]** Chart library choice, driven by range-selection-on-chart (§11.3)
- [ ] **4.2** Frontend skeleton, generated API types from 3.14
- [ ] **4.3** Workspace list, create, import screen (drag & drop)
- [ ] **4.3b** Workspace lifecycle UI: archive / unarchive prominent; delete hidden in settings,
      warned as unrecoverable, confirmed by typing the workspace name (§4.1.1)
- [ ] **4.4** Accounts screen with balances
- [ ] **4.5** Operations feed; transfers shown as one thing with a jump to the counterpart
- [ ] **4.6** Add / edit / delete operation, including retrospective `occurred_at`
- [ ] **4.7** Add transfer
- [ ] **4.8** Category tree management
- [ ] **4.9** Balance dynamics chart with range selection driving granularity
- [ ] **4.10** Cashflow chart
- [ ] **4.11** Category breakdown, both modes
- [ ] **4.12** Anchor UI: confirm balance, correct balance, unexplained difference visible

> **Keep the client dumb.** All aggregation, conversion and tree rollup already happen in the
> API. When numbers look wrong, you want to know immediately that the answer is in Core —
> where your expertise is — rather than debugging two unfamiliar layers at once.

---

## M5 — BFF in Go *(placeholder)*

- [ ] **5.1** Go service skeleton, routing
- [ ] **5.1b** Stand up Keycloak as its own Portainer stack — realm, client for the BFF,
      Google as a federated identity provider. **Not** in fintrace's Compose file (§7.4)
- [ ] **5.2** JWT validation via OIDC discovery against Keycloak's issuer — plain OIDC, no
      provider-specific SDK
- [ ] **5.2b** First-admin bootstrap: random token to the log on first start, redeemed once
      after SSO sign-in to bind that identity to the `admin` role (§7.4)
- [ ] **5.3** Replace the M0 identity stub. The BFF forwards the IdP token; Core validates it
      (§7.4). This is the target design — no internal token issuance is planned
- [ ] **5.4** Workspace ownership enforcement
- [ ] **5.5** Client credentials flow for importer → Core (§7.4)
- [ ] **5.6** Response aggregation where the UI needs it

---

## M6 — CI/CD *(consider pulling forward to M1)*

- [ ] **6.1** Build on push to `main`
- [ ] **6.2** Semver tagging
- [ ] **6.3** Publish images to private GHCR
- [ ] **6.4** Compose stack deployed via Portainer on the NAS
- [ ] **6.5** JVM memory tuning: `MaxRAMPercentage`, trimmed autoconfiguration, CDS (§7.1)

**Pulling this forward** means every later milestone runs on the NAS rather than only on the
dev machine — surfacing memory and timezone issues early instead of at the end.

---

## M7 — CLI client in Go *(post-MVP placeholder)*

- [ ] **7.1** Go CLI skeleton, config, auth against the BFF
- [ ] **7.2** Roles in Core (`admin` / `user`), authorisation enforced server-side (§7.4)
- [ ] **7.3** Add operation from the terminal, including retrospective `occurred_at`
- [ ] **7.4** Balances and statistics as ASCII charts — rendering only, no aggregation
- [ ] **7.5** Admin commands gated by role: trigger projection rebuild, workspace management
- [ ] **7.6** Note any endpoint the CLI needed that the web UI did not — it likely means
      logic had leaked into the frontend
