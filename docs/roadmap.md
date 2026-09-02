# fintrace — Implementation Roadmap

**Companion to:** `design-decisions.md`
**Status:** working plan — expected to change as reality intervenes

**Repository layout** (monorepo — one developer, three services, shared contracts):

```
docs/                     design-decisions, roadmap, tasks, mok-dump-format
fintrace-core/            Kotlin — domain, ES, statistics
fintrace-mok-importer/    Kotlin — MoneyOK interpreter
fintrace-bff/             Go — JWT, routing, aggregation
fintrace-web/             frontend
fintrace-cli/             Go — terminal client (post-MVP, M7)
deploy/                   docker-compose, Portainer
```

Module directory names match image names exactly, so a directory, a Compose service and a
GHCR image are always the same string.

**No shared importer module.** Extracting one before a second importer exists means guessing
which parts are actually common — and guessing wrong. Extract it when writing the second
importer and the duplication is visible. Naming importers by source
(`fintrace-mok-importer`) already leaves room for that.

**Importers are named by source** (`fintrace-mok-importer`, later possibly
`fintrace-csv-importer`) because the plugin boundary (§3.2) anticipates more than one. The
`common` module exists so the second importer does not start as a copy-paste of the first —
even though only one exists today.
Use path filters in GitHub Actions so a frontend commit does not rebuild Core. A single
stack-wide semver is simpler than per-service versions, since everything deploys together.

Design decisions are recorded and stable. This plan is not: at 8 h/week a detailed
task-by-task plan for two months would be wrong by week three. Milestones are ordered and
sized; **break down one milestone at a time**, not the whole project up front.

---

## Definition of done for the pilot

A Web UI with a basic set of charts and statistics, plus the ability to add new operations —
replacing the current Telegram bot for daily use.

## Security posture

**Design secure, implement later.** Authentication is not needed for a single user behind
Tailscale, but the shape must not have to change when it arrives.

Concretely, from day one:

- Every resource is nested under a workspace, and workspace ownership is checked.
- Core has a **single** place where caller identity is resolved — returning a fixed stub for
  now. One place, because a second client (the CLI, M7) will use the same slot (§7.4).
- The BFF exists in the topology and carries requests, even if it validates nothing yet.

Then enabling real auth means filling in prepared slots rather than restructuring.

> The failure mode this avoids: "secure by design, insecure in practice" quietly becoming
> "neither". If the stub is never replaced, that is a conscious deferral — not an accident.

---

## Ordering principle

**Front-load risk.** The MoneyOK interpreter is the one component where a defect does not fail
loudly but quietly distorts figures. Its scope is now known (see M2), but the risk profile is
unchanged: wrong numbers here stay wrong silently.

Everything else is comparatively predictable work.

---

## Milestones

### M0 — Walking skeleton

One operation, end to end: HTTP request → command → event → projection → HTTP response.

Deliberately thin: no import, no statistics, no UI. The point is to have the ES machinery
working as a whole rather than as parts.

**Includes:**

- Postgres, schema for `events` + `operations`, migrations
- Command → event → handler → projection path, in one transaction
- **The full-rebuild procedure**, sharing the handler with the online path (§4.10)
- The rebuild-equality test: rebuild, assert the projection rows are identical
- Docker Compose skeleton, `TZ` pinned explicitly (§6.4)

**Why the rebuild belongs here and not later:** written after the fact, it usually turns out
handlers were not idempotent and a stray direct write happened somewhere.

### M1 — Domain model in Core

The rest of the entities on the M0 foundation.

- Workspaces with the `NEW` / `ACTIVE` / `ARCHIVED` / `DELETED` lifecycle (§4.1.1), including
  the emptiness check and the system-wide read-only guard
- Accounts, archiving
- Categories: adjacency-list tree, system roots and both `Others`, soft delete, cycle and
  branch-move validation
- Operations, transfers as two linked legs, anchors
- CRUD API (§10)

**Watch for:** command-time validation is where all invariants live now (§4.10). Easy to
drift into validating inside handlers, where it is too late.

### M2 — MoneyOK interpreter

**The highest-risk milestone. Schedule it early even though it is not needed to demo
anything.**

The existing Kotlin implementation is being ported, but the target model has changed
substantially since it was written:

| Then | Now |
|---|---|
| Transfer as one record with two sides | Two linked ledger entries |
| Balance corrections replayed sequentially with retroactive patching | Absolute anchors, no fold |
| Single temporal axis, back-dating patched after the fact | Bitemporal; statistics query by `occurred_at` |
| Categories ignored entirely | Categories central |

**Assessment done (task 2.4): this is a port with additions, not a rewrite.** The separation
in `StatisticsProvider` turns out to be clean — cleaner than the interleaving suggested.

`buildStatisticsSnapshot` contains two sequential `when (operation)` blocks:

| Block | Fate |
|---|---|
| First `when` + all `applyX` methods | **The interpreter.** Touches only `accounts` / `operations` / `transfers`. Ports as-is. |
| Second `when` + everything around `stateByDate` | **Statistics.** Deleted outright. |

Deleted entirely: `opAttribution`, the `before` snapshot, `ensureDate`, `addBalanceForward`,
`setBalanceForward`, `relocateForward`, `removeAccountForward`, `addBalanceChangesForward`,
`TransferContribution`, `createDailyStatistics`. All the retroactive relocation machinery —
the source of that commit history — is replaced by queries on `occurred_at` (§6.2).

Roughly half the file ports, half is deleted.

### The one genuine complication: balance gates

`applyUpdateOperation` changes an operation's amount unconditionally but adjusts the account
balance **only if `moneyBack == true`**. The source therefore deliberately lets an operation's
amount and its account's balance diverge — and with 2899 `type = 11` events, this is routine,
not an edge case.

The new model cannot represent that directly: balance is derived from operations, so changing
an amount necessarily changes the balance.

**Anchors resolve it.** The interpreter keeps tracking balances by the source's rules (so the
balance-mutating code in the `applyX` methods stays), and emits a **final anchor per account**
carrying the balance the source believes in. The gap between "sum of my operations" and "the
source's balance" becomes the anchor's unexplained difference — exactly what anchors exist for
(§4.6).

A useful side effect: imported figures will match what the mobile app shows.

### Genuinely new code

- **Categories** — `normalize()` currently discards types 30/31/32/40/41/42/45 and `catUid`
  entirely. Written from scratch, but self-contained.
- Emitting anchors from `UpdatedAccount.money` (§2.4)
- Archiving instead of `accounts.remove` (§4.8)
- Log-and-skip for unknown types and currencies, replacing the current throw
- `Int` → `Long` for uid

**M2 returns to a middling milestone**, not the wildcard. The old-bot comparison (3.13)
remains valuable, but as a check rather than the only safety net.

**Includes:**

- Port the interpreter; keep the source's own quirks contained here (§3.2)
- Run the existing 22 tests and `test-scenario.md` as a conformance suite
- Import contract (§5.1), atomic, into a `NEW` workspace
- Import optimisation: write events, then rebuild once, rather than replaying 6k events
  individually

**Answer first, before writing code** — two SQL queries against a real dump (§8.2):

- Does `type = 110` carry a `date`?
- Can an operation be uncategorised (`catUid` absent or `0`)?
- Bonus: `SELECT type, COUNT(*) FROM Chronicle GROUP BY type` — reveals which documented
  traps are real for your data and which are theoretical.

### M3 — Statistics

- Five endpoints (§11.4)
- Recursive category aggregation, both modes
- Currency conversion at today's rate, Frankfurter, in-memory cache (§9)
- Indexes (§4.14)

**Validation opportunity:** the old bot's output on the same dump is a reference. Numbers
should match — where they do not, one of the two is wrong, and finding out which is valuable
either way.

### M4 — Web UI

The pilot deliverable. Charts, statistics, adding operations.

Range selection on the chart driving granularity (§11.3) is the interaction that matters most
and is worth prototyping before committing to a chart library.

### M5 — BFF in Go

**The Go learning milestone**, deliberately last.

Deferring it is a trade-off: Go is a stated goal of the project, and by M5 the schedule
pressure will be highest. If learning Go matters more than hitting the pilot date, consider
doing a thin version of this earlier — even a pass-through service — so the language is not
squeezed out entirely.

- Self-hosted Keycloak as a separate Portainer stack, with Google federated inside it (§7.4)
- JWT validation against it via OIDC discovery
- Routing, response aggregation
- Replacing the identity stub from M0

### M6 — CI/CD

- Build on push to `main`, semver, images to private GHCR
- Deploy via Portainer

**Can move earlier.** Doing it around M1 means every later milestone is deployed and exercised
on the NAS rather than only on the dev machine — which surfaces JVM memory tuning and TZ
issues early instead of at the end.

---

## Dependencies

```
M0 ──> M1 ──> M2 ──> M3 ──> M4 ──> pilot
                      │
                      └──> M5 (independent; can slot in anywhere after M1)
                             │
                             └──> M7 (CLI; post-MVP)

M6 can run in parallel from M1 onward
```

M5 is the only genuinely independent milestone. Everything else is a chain.

---

## Budget reality check

~60–100 hours to the pilot at 8 h/week over 1–2 months.

Rough shape of the work: M0 and M1 are foundation, M2 is a port plus category support, M3 is
mostly mechanical, M4 is a whole frontend.

**M4 is now the milestone most likely to be underestimated** — interactive charts are never as
quick as they look, and it is the only milestone with no existing code to lean on.

With M2 confirmed as a port, the 1–2 month pilot is plausible again. If the schedule slips,
the honest levers are: drop currency conversion back out of the MVP (§9.2 already halved it),
simplify the charts, or defer M5 further.

The lever *not* to pull is cutting corners in M2 — a quietly wrong interpreter produces
plausible numbers that stay wrong for months.

---

## Deferred (from `design-decisions.md`)

Historical-rate conversion · compensating operations for anchor differences · operation
version history in the API · transfers as their own report · period-over-period comparison ·
splitting transport from parser in the importer · watch-directory and Google Drive transports ·
NATS async import · file attachments from the source · CLI client (M7)
