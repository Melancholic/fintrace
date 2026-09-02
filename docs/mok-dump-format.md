# MoneyOK dump format — source-data reference

**Scope.** Everything below describes the SQLite file a MoneyOK user exports and sends to this bot
(`.mok`, `.db`, `.sqlite`, `.sqlite3` — `handlers/MokDumpFileMessageHandler.kt:137`). It is derived
strictly from evidence in this repository.

**Evidence base.** Three independent sources, cited throughout:

| Source | What it is | Strength |
|---|---|---|
| `src/main/kotlin/**` | This bot's reader. Shows what *this* implementation does — including what it gets wrong. | Authoritative for "current code behaviour", not for the format. |
| `mok-obf-3_0_2.deobf.js` | The **vendor's own MoneyOK web client**, deobfuscated (v3.0.2, `LOADER_VERSION = 3`). Contains `DDChronicleToDbConverter`, the reference replay implementation. | **Authoritative for format semantics.** |
| `example.sqlite` | A real (trimmed) export in the repo root: 50 rows, 6 distinct operation types. | Authoritative for physical layout; a *narrow* sample. |
| `src/test/kotlin/**/StatisticsProviderTest.kt` | 22 tests with hand-written payload fixtures. | Shows the developer's understanding, not the vendor's. |

**Confidence markers** used below: **[FACT]** = proven by code/fixture in this repo · **[INFERRED]**
= my reasoning from that evidence · **[UNKNOWN]** = not determinable here.

> ⚠️ The single most important structural fact: **this is not a relational schema.** There is exactly
> one business table, `Chronicle`, holding an append-only log of JSON-encoded mutation events. Every
> entity — accounts, categories, groups, transactions, transfers — exists only as a *projection* you
> compute by replaying that log in order. Nothing is stored in "current state" form anywhere.

---

## 0. Recovered DDL **[FACT]**

Recovered verbatim from `example.sqlite` (`sqlite_master`). This is the complete schema — there are
no other user tables, no indices, no views, no triggers, no foreign keys.

```sql
CREATE TABLE Chronicle(
    uid    INTEGER PRIMARY KEY AUTOINCREMENT,
    badge  INTEGER,
    type   INTEGER,
    params TEXT
);

CREATE TABLE android_metadata (locale TEXT);

-- created implicitly by AUTOINCREMENT
CREATE TABLE sqlite_sequence(name, seq);
```

File-level facts from `example.sqlite`:

| Property | Value | Note |
|---|---|---|
| SQLite format | 3, page size 4096 | plain rollback journal (`journal_mode = delete`) |
| `encoding` | `UTF-8` | |
| `application_id` / `user_version` | `0` / `0` | **no format version marker in the file header** |
| `auto_vacuum` | `0` (off) | see §10.1 |
| `integrity_check` | ok | |

---

## 1. Table inventory

### 1.1 `Chronicle` — the operation log **[FACT]**

**Business purpose:** the complete, ordered history of every change the user has ever made in the
app. It is the *only* source of business data.

| Aspect | Detail |
|---|---|
| Read by current code? | **Yes, fully** — `services/impl/MokOperationsLoaderImpl.kt:30` does `SELECT * FROM Chronicle` |
| Columns actually consumed | `uid`, `type`, `params` (`MokOperationsLoaderImpl.kt:32-35`) |
| Columns ignored | **`badge`** — never referenced anywhere in `src/` |
| Row-count magnitude | `example.sqlite` has 50 rows, but its `sqlite_sequence.seq = 5448` → the source install had issued ≈5.4k events. `MokOperationsLoaderImpl.kt:24-27` counts rows up-front and cross-checks, implying the author expected non-trivial sizes. **[INFERRED]** realistic magnitude is **10²–10⁵ rows** for a multi-year personal install; a household ledger grows a few thousand events per year. |

### 1.2 `android_metadata` — Android SQLite housekeeping **[FACT]**

Single column `locale TEXT`, single row. In `example.sqlite` the value is a POSIX locale string
(language + region). Created automatically by the Android SQLite framework, not by MoneyOK.

- **Read by current code?** **No — ignored entirely.**
- **[INFERRED]** Its presence proves the export is a **verbatim copy of the app's own Android
  database file**, not a purpose-built export format. See §5.4 — the region half of this locale is a
  weak fingerprint of the source installation.
- **[UNKNOWN]** Whether the iOS build of MoneyOK produces this table. If it does not, its absence is
  a platform discriminator.

### 1.3 `sqlite_sequence` — AUTOINCREMENT high-water mark **[FACT]**

- **Read by current code?** **No — ignored entirely.**
- Holds `('Chronicle', <highest uid ever issued>)`. In `example.sqlite`: `seq = 5448` while
  `max(uid) = 50`. See §5.1 and §10.2 — this is the strongest available signal that a dump has been
  truncated or rebuilt.

### 1.4 Tables that do **not** exist **[FACT]**

There is **no** table for accounts, categories, groups, operations, transfers, currencies, exchange
rates, budgets, users, devices, settings, or attachments. Confirmed by `sqlite_master` on
`example.sqlite` and by `MokOperationsLoaderImpl` reading only `Chronicle`.

---

## 2. Column-level detail

### 2.1 `Chronicle` **[FACT]**

| Column | SQLite type | Nullable | Sentinel / default | Semantics | Used by current code? |
|---|---|---|---|---|---|
| `uid` | `INTEGER PRIMARY KEY AUTOINCREMENT` (rowid alias) | no | — | **Global monotonic sequence number of the event.** The only ordering the format guarantees. Read as `Int` at `MokOperationsLoaderImpl.kt:32`, exposed as `MokOperation.num` (`models/MokOperations.kt:8`). | **Yes** — the replay sorts by it (`StatisticsProvider.kt:73`). |
| `badge` | `INTEGER` | yes (declared) | never observed null | **Random per-event token.** Generated as `Math.floor(Math.random() * 1999999999) + 1` (`mok-obf-3_0_2.deobf.js:3545`). In `example.sqlite` all 50 values are distinct, in `[7.5e7, 1.94e9]`, all `< 2³¹`. **[INFERRED]** it is a sync de-duplication / idempotency key for the vendor's cloud sync — the client can recognise "this is the same logical event I already have" after a re-download, since `uid` alone can collide across devices. **It carries no business meaning.** | **No — ignored.** |
| `type` | `INTEGER` | yes (declared) | never observed null | Event discriminator. Full enumeration in §9.2. Read at `MokOperationsLoaderImpl.kt:33`. | **Yes** — but see §10.4, unknown codes crash. |
| `params` | `TEXT` | yes (declared) | never observed null or `''` (checked across all 50 rows of `example.sqlite`) | **A JSON object**, always. The event payload. Its key set is a function of `type`. Read at `MokOperationsLoaderImpl.kt:34`, parsed at `StatisticsProvider.kt:29`. | **Yes**, partially — see §2.2. |

**Declared vs. real nullability [INFERRED]:** `badge`, `type` and `params` are all nominally
nullable (no `NOT NULL` in the DDL) but the vendor client and the observed data both treat them as
mandatory. The vendor client does guard against unparseable `params`
(`mok-obf-3_0_2.deobf.js:149-152`, `if (!parsed) continue`) — so a null/garbage `params` row is a
contingency the vendor anticipated. **This bot does not guard it** and would throw.

### 2.2 `params` — payload keys, per event type

The canonical key names are declared as constants at `mok-obf-3_0_2.deobf.js:881-905`:

```js
version, name, uid, money, currency, note, date, opType, groupUid, icon,
catUid, accUid, moneyBack, moneyBack2, index, isGroup, newGroupUid, file,
accFrom, accTo, fromIndex, toIndex, color, targetUid
```

**Critical structural rule [FACT]:** for every `UPDATE_*` event, **a key is present only if that
field changed.** The vendor client tests `if (KEY in params)` before applying each field
(`mok-obf-3_0_2.deobf.js:262-275`, `:376-385`, `:576-604`, `:723-741`). Confirmed in
`example.sqlite`: the four `type = 21` (update account) rows have two distinct key sets —
`{uid, money}` (×3) and `{uid, name, money, currency}` (×1). **A missing key means "unchanged",
never "set to null".** The exception is `moneyTo` on transfers — see §10.5.

Below, ✅ = this bot parses it, ❌ = this bot ignores it.

#### `type = 1` — chronicle version

| Key | JSON type | Req. | Meaning | Used |
|---|---|---|---|---|
| `version` | int | yes | Format/schema version of the whole log. `example.sqlite` carries `3`. The vendor client refuses the file if `LOADER_VERSION < version` (`mok-obf-3_0_2.deobf.js:161-167`, `LOADER_VERSION = 3` at `:2122`), and logs a complaint if `version == 0`. | ❌ — `StatisticsProvider.kt:45` maps it to `null` and drops it |

**[INFERRED]** This is a *forward*-compatibility gate only: a v3 client reads v1 and v2 logs
unconditionally. So versions 1 and 2 exist historically and are still readable — meaning **older
dumps in the wild may lack event types introduced later** (files, colors). See §10.9.
**[INFERRED]** It appears once, as the first row, in `example.sqlite`. **[UNKNOWN]** whether a
version bump mid-log is possible (i.e. more than one `type = 1` row).

#### `type = 10` — create operation (income / expense transaction)

Observed key set in `example.sqlite` (all 22 rows identical): `{uid, catUid, accUid, money, opType, date}`.

| Key | JSON type | Req. | Meaning | Used |
|---|---|---|---|---|
| `uid` | int | yes | Transaction identity, own namespace | ✅ `MokOperations.kt:141` |
| `opType` | int | yes | `1` = expense, `2` = income (`mok-obf-3_0_2.deobf.js:68-69`) | ✅ `MokOperations.kt:141` → `CategoryType` |
| `accUid` | int | yes | Account this hits | ✅ `MokOperations.kt:142` |
| `catUid` | int | yes | **Category this is filed under** | ❌ **Parsed nowhere.** The only mention in `src/` is a sample comment at `models/MokOperations.kt:127`. `AddNewTransaction` has no field for it. |
| `money` | number | yes | **Unsigned magnitude.** Sign comes from `opType`. `example.sqlite`: mixed int/float in the same column | ✅ `MokOperations.kt:143` |
| `date` | string | yes | Business date-time, `"yyyy.MM.dd HH:mm:ss"` | ✅ `MokOperations.kt:139` |
| `note` | string | **no** | Free text. **Omitted entirely when empty** — `createOperationAction` only writes it `if (op.note)` (`mok-obf-3_0_2.deobf.js:3596-3598`). Absent from all 22 rows in `example.sqlite`. | ✅ defaulted to `""` (`MokOperations.kt:144`) |

#### `type = 11` — update operation

All keys except `uid` optional (present ⇔ changed).

| Key | Meaning | Used |
|---|---|---|
| `uid` | which transaction | ✅ |
| `date` | new business date — **this is how back-dating happens**, see §4.4 | ✅ |
| `money` | new amount | ✅ |
| `moneyBack` | int 0/1. **Gates whether the amount change is applied to the account balance** (`mok-obf-3_0_2.deobf.js:576-583`). Read as `it == 1` at `MokOperations.kt:169`. | ✅ |
| `accUid` | move to another account | ✅ |
| `moneyBack2` | int 0/1. **Gates whether the account move is applied to balances** (`mok-obf-3_0_2.deobf.js:586-596`) | ✅ |
| `catUid` | **re-categorise** (`mok-obf-3_0_2.deobf.js:601-603`) | ❌ **not parsed** — `UpdateTransaction` has no such field |
| `note` | new note | ✅ |
| `opType` | Parsed by this bot (`MokOperations.kt:166`) — **but the vendor client never applies it** (`_0x35b00a` has no `opType` branch). **[INFERRED]** the bot reads a field the format does not actually carry here; an expense cannot be flipped to income by an update. | ✅ (parsed, then unused in replay) |

#### `type = 12` — delete operation

| Key | Meaning | Used |
|---|---|---|
| `uid` | which transaction | ✅ |
| `moneyBack` | int 0/1 — **gates the balance revert** (`mok-obf-3_0_2.deobf.js:611-616`) | ✅ `MokOperations.kt:188` |
| `date` | Parsed by this bot (`MokOperations.kt:186`); **the vendor client ignores it**. **[INFERRED]** likely absent in practice. | ✅ (parsed) |

#### `type = 20` — create account

Observed key set in `example.sqlite` (both rows): `{uid, name, money, currency}`.

| Key | JSON type | Req. | Meaning | Used |
|---|---|---|---|---|
| `uid` | int | yes | Account identity, own namespace | ✅ |
| `name` | string | yes | Display name. Vendor rejects the event if falsy (`:241-244`) | ✅ |
| `money` | number | yes | **Opening balance.** `StatisticsProvider.kt:311-321` stores it as both `balance` and `initBalance` | ✅ |
| `currency` | int | yes | ISO-4217 **numeric** code; `0` = unspecified — see §6.2 | ✅ (`MokOperations.kt:34`, defaults to `0` if absent) |
| `note` | string | **no** | Parsed at `MokOperations.kt:35`, defaulted `""`. **Not present on any account row in `example.sqlite`, and the vendor's `DDAccount` (`mok-obf-3_0_2.deobf.js:88-92`) has no `note` field at all.** **[INFERRED]** this field probably does not exist for accounts; the illustrative comment at `MokOperations.kt:20` showing a `note` looks hand-written rather than copied from a dump. **Verify against a real dump before relying on it.** | ✅ (probably always `""`) |
| **no date** | — | — | **Account creation carries no timestamp.** See §7.3 and §10.6. | — |

#### `type = 21` — update account

Keys: `uid` (required) plus any of `name`, `money`, `currency` (`mok-obf-3_0_2.deobf.js:262-275`).
`note` is also read by this bot (`MokOperations.kt:56`) — same caveat as above. **No `date`.**

Note `money` here is an **absolute balance assignment**, not a delta
(`mok-obf-3_0_2.deobf.js:266-268`).

#### `type = 22` — delete account

| Key | Meaning | Used |
|---|---|---|
| `uid` | which account | ✅ |
| `moneyBack` | Parsed at `MokOperations.kt:202`. **The vendor client's delete-account handler (`_0x40fbe3`, `:276-308`) never reads it.** **[INFERRED]** speculative/absent. | ✅ (parsed, unused) |

#### `type = 23` — reorder accounts

| Key | Meaning | Used |
|---|---|---|
| `uid` | account being moved | ❌ |
| `fromIndex` | old position in `accountsOrder`; `< 0` = "not currently in the list" | ❌ |
| `toIndex` | new position; `< 0` = "remove, don't reinsert" | ❌ |

**Purely presentational** — display order of accounts (`mok-obf-3_0_2.deobf.js:310-340`). This bot
drops it (`StatisticsProvider.kt:45`). If the new system wants to render accounts in the user's own
order, this is the event to replay.

#### `type = 30` — create category group

Observed key set in `example.sqlite`: `{uid, name, opType}`.

| Key | Meaning | Used |
|---|---|---|
| `uid` | group identity, own namespace | ❌ |
| `name` | display name | ❌ |
| `opType` | `1` expense / `2` income. **Also determines the parent**: `parentGroupUid = (opType == 1 ? 1 : 2)` (`mok-obf-3_0_2.deobf.js:439`) — the parent is *derived*, never stored | ❌ |

#### `type = 31` / `32` — update / delete group

- **31**: `{uid, name?}` — **only `name` can be updated** (`mok-obf-3_0_2.deobf.js:453-465`). ❌
- **32**: `{uid}` — detach from parent, delete. **Does not cascade to its categories** (see §10.7). ❌

#### `type = 40` — create category

Observed key sets in `example.sqlite`: `{uid, name, opType, icon, groupUid}` (15 rows) and the same
plus `color` (5 rows).

| Key | JSON type | Req. | Meaning | Used |
|---|---|---|---|---|
| `uid` | int | yes | category identity, own namespace | ❌ |
| `name` | string | yes | display name | ❌ |
| `opType` | int | yes | `1` expense / `2` income | ❌ |
| `icon` | string | yes | Icon reference. **Two shapes coexist in one dump**: a bare filename (`item*.png`, legacy/default set) and a slash-prefixed pack path (`/<Pack>/<Icon Name>.png`). In `example.sqlite` both appear under the same group. `otherItem.png` is the client's default (`mok-obf-3_0_2.deobf.js:3505`). | ❌ |
| `groupUid` | int | yes | Parent group. **May reference an implicit root group (1 or 2) that has no `type = 30` event** — see §3.3 | ❌ |
| `color` | int | **no** | Packed RGB as a decimal integer (observed values are 24-bit: `0x62_A2_72`-scale). Default in `DDCategory` is `-1` = "no colour" (`mok-obf-3_0_2.deobf.js:99`). Present on 5 of 20 categories in `example.sqlite`. | ❌ |

#### `type = 41` — update category

Keys: `uid` plus any of `name`, `icon`, `color` (`mok-obf-3_0_2.deobf.js:366-385`).
**`opType` and `groupUid` are NOT updatable here** — see §8.3. ❌

#### `type = 42` — delete category

| Key | Meaning | Used |
|---|---|---|
| `uid` | which category | ❌ |
| `moneyBack` | **Gates a balance revert** — if truthy, every transaction in that category is reversed on its account before being deleted (`mok-obf-3_0_2.deobf.js:404-419`) | ❌ |

⚠️ **This event deletes transactions and can move balances.** This bot ignores it →
see §10.7.

#### `type = 45` — move item (re-parent / reorder a category or group)

| Key | Meaning | Used |
|---|---|---|
| `uid` | item being moved | ❌ |
| `isGroup` | truthy ⇒ the item is a group, else a category | ❌ |
| `groupUid` | source parent | ❌ |
| `fromIndex` | position within source parent | ❌ |
| `newGroupUid` | destination parent | ❌ |
| `toIndex` | position within destination parent | ❌ |

**This — not `type = 41` — is how a category changes its parent group**
(`mok-obf-3_0_2.deobf.js:491-536`; `_0x7e0276.parentGroupUid = _0x2c1092.uid` at `:534`).

#### `type = 50` — create transfer

| Key | JSON type | Req. | Meaning | Used |
|---|---|---|---|---|
| `uid` | int | yes | transfer identity, own namespace | ✅ |
| `money` | number | yes | **Amount debited from the source** | ✅ |
| `moneyTo` | number | **no** | **Amount credited to the target.** Absent ⇒ same as `money`. Sentinel `-1` also means "same as `money`" (`DDTransfer.valueOfMoneyTo`, `mok-obf-3_0_2.deobf.js:129-135`; mirrored at `models/Transfer.kt:11-15`) | ✅ |
| `accFrom` | int | yes | source account | ✅ |
| `accTo` | int | yes | target account | ✅ |
| `date` | string | yes | business date-time | ✅ |
| `note` | string | no | free text | ✅ (defaulted `""`) |

#### `type = 51` / `52` — update / delete transfer

- **51**: `uid` + any of `date`, `money`, `moneyTo`, `accFrom`, `accTo`, `note`. ✅ — **but see the
  `moneyTo` reset trap in §10.5.**
- **52**: `{uid}`; unconditional balance revert on both sides — **no `moneyBack` gate**, unlike
  transactions (`mok-obf-3_0_2.deobf.js:749-767`). ✅ (`date` is also parsed by this bot,
  `MokOperations.kt:120`, and ignored by the vendor)

#### `type = 90 / 92 / 93` — add / remove / move file attachment

| Key | Meaning | Used |
|---|---|---|
| `name` | filename | ❌ |
| `opType` | must be non-zero, otherwise rejected | ❌ |
| `targetUid` | **the transaction the file belongs to** | ❌ |
| `fromIndex`, `toIndex` | ordering, `type = 93` only | ❌ |

Handled at `mok-obf-3_0_2.deobf.js:769-866`; the file list lives on `DDOperation.files`
(`:120`) / `filenames`. ⚠️ **These three codes are absent from `models/OperationType.kt` — a dump
containing any of them crashes this bot outright.** See §10.4.

#### `type = 100` — "deleted"

Payload unknown. The vendor client explicitly matches it and does **nothing**
(`mok-obf-3_0_2.deobf.js:168-169`, `case DD_CH__DELETED: break;`). **[INFERRED]** a tombstone for a
*chronicle row itself* — a way to void an event without physically removing it, preserving `uid`
density. Declared in `models/OperationType.kt:39`, dropped by `normalize()`.
**[UNKNOWN]** its payload shape and whether it names a target `uid`.

#### `type = 110` — account money verification (balance correction)

| Key | Meaning | Used |
|---|---|---|
| `uid` | account | ✅ |
| `money` | **absolute** new balance. Vendor rejects the event if the key is missing (`mok-obf-3_0_2.deobf.js:661-664`) | ✅ |
| `date` | business date-time | ✅ (`MokOperations.kt:216`) |

⚠️ The **vendor client never reads `date` here** either. This bot does, and treats the verification
as an anchor at that date (`StatisticsProvider.kt:155-156`). **[UNKNOWN]** whether `date` is really
present on `type = 110` rows — it does not appear in `example.sqlite` (no such rows) and the only
evidence is this bot's test fixtures, which are hand-written.

#### `type = 33`, `43`, `44` — declared but dead

`DD_CH__MOVE_GROUP = 33`, `DD_CH__MOVE_CATEGORY = 43`, `DD_CH__CATEGORIES_LIST = 44` are declared at
`mok-obf-3_0_2.deobf.js:868/872/873` but **have no `case` in the v3 converter's switch** — they fall
through to `default: console.log("Unknown action type…")`. **[INFERRED]** 33 and 43 are the
pre-v3 ancestors of `MOVE_ITEM = 45`; a legacy log may still contain them and the v3 client
silently drops them. `44` never shipped. 33 and 43 are declared in `models/OperationType.kt:26,31`;
**44 is not, and will crash this bot.**

---

## 3. Keys and relationships

### 3.1 Primary keys **[FACT]**

- **Physical PK:** `Chronicle.uid` — a rowid alias, therefore the physical clustering key too.
- **Logical PKs:** `params.uid`, scoped per entity kind — see §5.2.

**Stability across exports [INFERRED]:** *Within one installation's continuous history*, both are
stable — the log is append-only and `AUTOINCREMENT` guarantees a value is never reused
(`sqlite_sequence` retains the high-water mark even after deletes). **Across exports from different
installations or after a cloud restore, assume nothing.** `sqlite_sequence.seq = 5448` vs
`max(uid) = 50` in `example.sqlite` proves that a dump can be *rebuilt with renumbered `uid`s*
(see §10.2). Do not use `Chronicle.uid` as a cross-dump identity.

### 3.2 Foreign keys **[FACT]**

**Zero foreign keys are declared.** Every relationship is a bare integer inside a JSON string,
enforced only by replay code. The complete implicit set:

| From (event) | Field | To | Enforcement in vendor client |
|---|---|---|---|
| `10` create op | `accUid` | account | rejected if account missing (`:558-562`) |
| `10` create op | `catUid` | category | **not validated at all** |
| `11` update op | `accUid` | account | assumed present, would throw |
| `11` update op | `catUid` | category | not validated |
| `40` create category | `groupUid` | group | logged-and-continued if missing (`:355-357`) — **category still created** |
| `30` create group | (derived) | root group 1 or 2 | logged if missing |
| `45` move item | `groupUid`, `newGroupUid` | groups | validated; event skipped on mismatch |
| `50/51` transfer | `accFrom`, `accTo` | accounts | **tolerated if missing** — `"possibly not error"` (`:692`, `:698`) |
| `90/92/93` file | `targetUid` | operation (`type=10`) | rejected if operation missing (`:781-785`) |
| `12/22/32/42/52` delete | `uid` | the entity | logged-and-skipped if absent, with the comment `"possibly not error"` (`:373`, `:392`, `:459`) |

**[INFERRED]** the recurring `"possibly not error"` comments are the vendor admitting that
**dangling references are normal in real logs** — a consequence of multi-device sync producing
out-of-order or partially-merged histories. A new implementation must be tolerant, not strict.

### 3.3 Implicit root groups **[FACT]**

`DDMoneyOKDB`'s constructor pre-creates two groups before any event is replayed
(`mok-obf-3_0_2.deobf.js:72-87`):

| uid | name | role |
|---|---|---|
| `1` | `expenseRoot` | `PMM_ROOT_GROUP_UID_EXPENSE` |
| `2` | `incomeRoot` | `PMM_ROOT_GROUP_UID_INCOME` |

Confirmed empirically: `example.sqlite` has categories referencing `groupUid` 1, 2 and 3, but only
**one** `type = 30` event, for uid 3. **Groups 1 and 2 are never logged.** Any implementation that
builds groups purely from `type = 30` events will produce dangling categories.

Also confirmed: `opType` is perfectly consistent with the root — every category under `groupUid = 1`
had `opType = 1`; every one under `groupUid = 2` had `opType = 2`; the user-created group 3 had
`opType = 1` and its categories `opType = 1`.

### 3.4 Dependency graph / valid insertion order **[INFERRED]**

Derived from the vendor's validation order. `→` = "must exist first".

```
                    ┌─────────────────────────┐
                    │ root groups 1, 2        │  implicit, never in the log
                    └───────────┬─────────────┘
                                │
        ┌───────────────────────▼───────────────────────┐
        │ Group        (30, 31, 32)                     │
        │   parent derived from opType                  │
        └───────────────────────┬───────────────────────┘
                                │ groupUid
        ┌───────────────────────▼───────────────────────┐
        │ Category     (40, 41, 42, 45)                 │
        └───────────────────────┬───────────────────────┘
                                │ catUid
   ┌────────────────┐           │
   │ Account        │           │
   │ (20,21,22,23)  │           │
   └───────┬────────┘           │
           │ accUid / accFrom / accTo
           │                    │
   ┌───────▼────────────────────▼──────────────────────┐
   │ Operation    (10, 11, 12)   ── requires BOTH      │
   └───────────────────────┬───────────────────────────┘
                           │ targetUid
   ┌───────────────────────▼───────────────────────────┐
   │ File attachment  (90, 92, 93)                     │
   └───────────────────────────────────────────────────┘

   ┌───────────────────────────────────────────────────┐
   │ Transfer     (50, 51, 52)   ── requires 2 Accounts│
   │ Verification (110)          ── requires 1 Account │
   └───────────────────────────────────────────────────┘
```

Valid load order: **root groups (synthetic) → groups → categories → accounts → operations &
transfers & verifications → file attachments.** Categories and accounts are independent of each
other.

⚠️ **This ordering is a logical dependency, not a guarantee about the log.** The physical log is
ordered by `uid` only, and nothing prevents a `type = 10` row referencing a `catUid` whose `type =
40` row comes later (or never). See §3.2.

---

## 4. Event-log semantics

### 4.1 Is it append-only? **[FACT] — Verified. Yes.**

Every mutation is a new row. Evidence:

1. There is no other table to mutate; the *only* representation of state is the event stream.
2. `uid INTEGER PRIMARY KEY AUTOINCREMENT` — new rows always get a fresh, higher `uid`.
3. Both replay implementations are pure folds over the row sequence:
   `mok-obf-3_0_2.deobf.js:147-232` and `StatisticsProvider.kt:73-209`.
4. The vendor's writer only ever appends: `createAction` builds `{uid: lastAction.uid + 1, …}`
   (`mok-obf-3_0_2.deobf.js:3537-3548`).
5. `type = 100 DELETED` exists as a *tombstone* rather than a physical removal.

**Caveat [INFERRED]:** append-only is a property of *how the app writes*, not a property enforced by
the file. The file is a plain read-write SQLite database with no triggers, so anything can edit it
after export. And §10.2 shows an export that clearly *was* rewritten.

### 4.2 How an edit to a past record is represented **[FACT]**

A new row with the corresponding `UPDATE_*` type (`11`, `21`, `31`, `41`, `51`) whose `params`
contains the target `uid` **plus only the changed fields**. Never a rewrite of the original row.

The subtlety that makes this format unusual: **an amount edit does not necessarily change any
balance.** For transactions, `moneyBack` / `moneyBack2` are user answers to an in-app prompt
("should I also correct the account balance?"):

| Edit | Gate | If gate absent/false |
|---|---|---|
| change `money` | `moneyBack` | field updated, **balance untouched** (`:576-583`) |
| move to another account (`accUid`) | `moneyBack2` | field updated, **balances untouched** (`:586-596`) |
| change `date`, `note`, `catUid` | none | always applied, never affects balances |

Mirrored in this bot at `StatisticsProvider.kt:374-404`, and covered by two tests
(`StatisticsProviderTest.kt:214`, `:265`). **Transfers have no such gate** — a `type = 51` always
rolls back and re-applies (`StatisticsProvider.kt:437-452`, `mok-obf-3_0_2.deobf.js:702-747`).

### 4.3 How a deletion is represented **[FACT]**

A new row with a `DELETE_*` type. There are **four distinct deletion semantics** — do not assume
one:

| Type | Balance effect | Cascade |
|---|---|---|
| `12` delete operation | **Gated on `moneyBack`** (`:611-616`) | none |
| `52` delete transfer | **Always** reverted on both sides | none |
| `22` delete account | **None directly.** Its operations vanish *without* reverting. Its transfers are **orphaned, not deleted**: the deleted side's uid is set to **`0`** and the surviving side's balance is left as-is; the transfer row is dropped only once *both* sides are `0` (`:276-308`, mirrored at `StatisticsProvider.kt:526-550`) | operations deleted, transfers orphaned |
| `42` delete category | **Gated on `moneyBack`** — reverts every transaction in the category | **its transactions are deleted** (`:400-424`) |
| `32` delete group | none | **no cascade at all** — its categories are left dangling |

**The `0` sentinel [FACT]:** account uid `0` means "this side of the transfer refers to a deleted
account". Explicitly documented in this bot at `StatisticsProvider.kt:421` and `:259`. `0` is also
used as an "invalid uid" rejection value throughout the vendor client (`if (params.uid == 0) return`,
e.g. `:235`, `:257`, `:342`) — so **no entity may legitimately have uid `0`.**

### 4.4 How current effective state is determined **[FACT]**

Fold the whole log, in ascending `uid` order, into in-memory maps.

- Vendor: `DDMoneyOKDB` holds `accounts`, `categories`, `groups`, `transfers`, `operations`,
  `accountsOrder`, all keyed by uid; `convertActions` iterates and mutates
  (`mok-obf-3_0_2.deobf.js:138-232`).
- This bot: identical shape — `LinkedHashMap<Int, Account/Operation/Transfer>`
  (`StatisticsProvider.kt:55-57`), replayed at `:73-209`.

**Ordering is by `uid`, explicitly and only.** `StatisticsProvider.kt:72-73` carries the comment
*"num order is the only ordering the DB guarantees"*. Business `date` is **not** a valid replay
order.

**Deletes are hard, not soft:** the entity is removed from the map (`delete db.operations[uid]`,
`operations.remove(uid)`). A later event referencing it hits the dangling-reference path in §3.2 —
this bot degrades to a silent no-op (`StatisticsProvider.kt:135-143`, tested at
`StatisticsProviderTest.kt:475`).

### 4.5 Back-dating: insertion order vs. business date **[FACT] — these diverge, badly**

Two orderings coexist and are independent:

| | Insertion order | Business order |
|---|---|---|
| Key | `Chronicle.uid` | `params.date` |
| Guaranteed monotonic? | **Yes** | **No** |
| Present on every event? | **Yes** | **No** — see §7.3 |

Back-dating happens in two ways:

1. **At creation** — a `type = 10` or `50` row can carry any `date`, past or future. The app lets
   you enter yesterday's coffee today.
2. **By edit** — a `type = 11` / `51` row carrying a new `date` **relocates an already-recorded
   effect to a different day.**

Case 2 is the hard one, and it is what most of this bot's complexity exists for. From
`StatisticsProvider.kt:179-208`:

> *"A past re-date relocates the contribution to the earlier day; a forward re-date is a no-op (the
> change was already recorded above), keeping event semantics."*

The bot tracks each operation's current `(date, account, signed amount)` in an `opAttribution` map
(`:66`, `:170-172`) and, when a re-date moves it earlier, subtracts the amount forward from the old
date and adds it forward from the new one (`relocateForward`, `:260-270`). Transfers get the same
treatment for both legs (`:192-208`). This is covered by `test-scenario.md` and the test at
`StatisticsProviderTest.kt:678`.

**[INFERRED]** — implication for the new system: **you cannot compute a historical balance series by
a single forward scan of the log in `uid` order.** Either do a two-pass approach (resolve every
entity to its final state, then bucket by final `date`), or model the log as bitemporal. The
existing bot chose a third, awkward path — single-pass with retroactive patching — and the git
history (`"Some changes - wrong calculation"` → revert → `"Almost correct daily results"` →
`"Finally correct result"`) shows how expensive that was.

---

## 5. Identifiers

### 5.1 `Chronicle.uid` **[FACT]**

| Property | Value |
|---|---|
| Type | `INTEGER PRIMARY KEY AUTOINCREMENT` → 64-bit rowid alias. **This bot narrows it to `Int` (32-bit)** at `MokOperationsLoaderImpl.kt:32`. |
| Monotonicity | Strictly increasing. `createAction` uses `lastAction.uid + 1` (`:3538-3547`); `AUTOINCREMENT` additionally forbids reuse of any value ever issued, even after deletion. |
| Scope | **Global** across the whole log — one sequence for all entity kinds. |
| Reused? | **No.** `AUTOINCREMENT` (as opposed to a bare `INTEGER PRIMARY KEY`) exists precisely to prevent rowid reuse; `sqlite_sequence` stores the high-water mark. |
| Gaps? | Possible in principle (a deleted row leaves a hole). `example.sqlite` has none: uid 1..50 contiguous. |

### 5.2 `params.uid` — per-entity-kind identity **[FACT]**

A **separate, independent counter per entity kind.** Confirmed in `example.sqlite`, where all of
these coexist without collision:

| Entity | uid range observed |
|---|---|
| transactions | 1 … 22 |
| accounts | 1 … 2 |
| categories | 1 … 20 |
| groups | 3 (plus implicit 1, 2) |

**[INFERRED]** allocation is `max(existing) + 1` — the vendor's category creator does exactly that
(`mok-obf-3_0_2.deobf.js:3474-3485`), and `ddSyncGlobalVar.maxOpUid` (`:2148`, updated at `:549-551`)
tracks the same for operations. So `params.uid` is **not globally unique** — `(type-family, uid)` is
the identity, and `uid = 0` is reserved (§4.3).

**[INFERRED]** Because allocation is `max+1` over *live* entities, and deletions remove entities from
the live map, **`params.uid` values could in principle be reused after a deletion.** The vendor
guards creation against collision with a *live* entity (`if (db.accounts[uid] != null) return`,
`:245-248`) but not against a *previously deleted* one. Treat `params.uid` as unique only among
currently-live entities, never as a stable historical key.

### 5.3 Other identifiers **[FACT]**

- **`Chronicle.badge`** — random 1…1,999,999,999 per event (§2.1). Not an entity id.
- **`dbUid`** — a cloud-sync database identifier, held only in the client's runtime state
  (`ddSyncGlobalVar.dbUid`, `mok-obf-3_0_2.deobf.js:2146`) and sent in sync requests (`:3195`,
  `:3626`, `:3650`). **It is not stored in the dump.**
- **No UUIDs anywhere.** No `uuid`, `guid`, `deviceId`, `installationId`, `imei` or `udid` token
  appears in the client source or in any payload.

### 5.4 What could identify the source installation **[INFERRED]**

Nothing is *designed* to, but the dump leaks several weak fingerprints:

| Signal | Where | Strength |
|---|---|---|
| `android_metadata.locale` | table row | region + language of the device |
| `sqlite_sequence.seq` vs `max(uid)` | table row | reveals total lifetime event count of the install |
| SQLite header fields | file bytes | page size, schema cookie, change counter |
| **Free-list page residue** | file bytes | ⚠️ **see §10.1 — potentially the actual deleted data** |
| Export filename | out of band | the untracked dumps in this repo are named `YYYY-MM-DD_HH-MM.sqlite`, i.e. **the export timestamp in device-local time** |
| Timestamp clustering | `params.date` | typical hours-of-day reveal a timezone even though none is stored (§7.2) |

**[UNKNOWN]** whether the vendor's cloud sync stamps anything (a login, `dbUid`) into the file. The
web client does not.

---

## 6. Money and currency

### 6.1 Amount storage **[FACT]**

**JSON numbers — IEEE-754 doubles. Not integer minor units, not a scaled decimal, not a string.**

- Written as a raw JS `Number` (`createOperationAction`, `mok-obf-3_0_2.deobf.js:3589`).
- In `example.sqlite`, `money` values across 28 payloads deserialise as a **mix of JSON ints and
  JSON floats in the same field** (12 int / 10 float on transactions; ints on accounts). The writer
  does not normalise to a fixed number of decimals.
- Read as `Double` throughout this bot (`MokOperations.kt:33,78,143,…`; `models/Account.kt:11`).

**Scale: 2 decimal places, enforced at read time, not at write time [FACT].** The vendor's very
first act on any payload is:

```js
if ("money" in params) { params.money = Math.round(params.money * 100) / 100; }
```

(`mok-obf-3_0_2.deobf.js:154-157`) — applied *uniformly to every event type* before dispatch. So the
stored value may carry more precision than 2dp and the canonical reading is "round half up to 2dp".
Empirically `example.sqlite` holds at most 1 decimal place.

⚠️ **This bot does not perform that rounding.** It sums raw `Double`s across the entire history
(`StatisticsProvider.kt:352-353`, `:410-411`), so accumulated float drift is a real risk on long
logs; `addBalanceChangesForward` even compares `delta != 0.0` exactly (`:302`).

**Sign convention [FACT]:** `money` is always a **non-negative magnitude**. Direction comes from
context:

| Context | Sign source |
|---|---|
| transaction | `opType`: `1` expense → `−`, `2` income → `+` (`CategoryType.kt:4-5`, rate `−1.0` / `+1.0`; vendor `:551-556`) |
| transfer | positional: `money` debits `accFrom`, `moneyTo` credits `accTo` |
| account create / update / verification | absolute assignment; **can legitimately be negative** (a credit card) |

Confirmed: no negative `money` in `example.sqlite`, and zero *is* present (opening balances).

### 6.2 Multi-currency model **[FACT]**

**Currency lives on the account and nowhere else.** There is no currency field on a transaction, a
transfer, a category, or a group.

- `currency` is an **ISO-4217 numeric code as an integer** (e.g. 840 USD, 978 EUR, 643 RUB, 941 RSD,
  51 AMD). The client's table has **171 entries** with numeric `uid` plus symbol
  (`mok-obf-3_0_2.deobf.js:938`ff), including a **non-standard `BTC = 10001`**.
  ⚠️ In the JS table the codes are **strings** (`uid: "840"`); in the dump they are **integers**.
- **`0` = unspecified.** Not in the vendor's currency table; the client's `currencySymbolForUid`
  returns `null` for it (`:927-935`). This bot models it explicitly as
  `Currency.NOT_SPECIFIED(0, "N/A")` (`models/Currency.kt:4`). **Observed in `example.sqlite`: one
  of the two accounts has `currency = 0`.** This is a real, common value — not an error.
- ⚠️ **This bot only knows 6 currencies** (`models/Currency.kt`) and `Currency.from` **throws** on
  anything else (`:16`) — see §10.4.

**Cross-currency transfers [FACT]:** modelled by the `money` / `moneyTo` pair on a single `type = 50`
row. `money` is in the *source* account's currency, `moneyTo` in the *target*'s. The implied rate is
`moneyTo / money`, recorded per transfer, never stored as a rate. Same-currency transfers omit
`moneyTo` (or set it to `-1`). Tested at `StatisticsProviderTest.kt:103` and `:427`.

### 6.3 Exchange rates **[FACT] — there are none**

Searched the entire vendor client for `exchange`, `rate`, `convert`, `курс`: **zero hits.** The only
currency machinery is the code→symbol lookup table.

**Consequences:**

- The dump carries **no FX rates, no rate history, and no rate source**.
- The *only* FX information in the whole file is implicit, in cross-currency transfers' `money` /
  `moneyTo` ratio — a rate the user effectively typed, **as of that transfer's business date**.
- This bot never converts. `CurrenciesStatistics` groups accounts by currency and sums **within each
  currency separately** (`models/statistics/results/CurrenciesStatistics.kt:25-31`); the Excel report
  emits one row per currency abbreviation (`ExcelReportService.kt:118-124`).

### 6.4 Base / reporting currency **[FACT] — the concept does not exist**

There is no "main currency" setting anywhere in the dump or the client. A multi-currency user has
*n* parallel, unconverted balance series. **[INFERRED]** if the new system wants a consolidated
figure it must source rates externally and decide the as-of policy itself; the dump will not help,
and there is no user-declared preference to honour.

---

## 7. Dates and time

### 7.1 Storage format **[FACT]**

**Exactly one temporal representation exists in the entire dump:**

```
"yyyy.MM.dd HH:mm:ss"      e.g.  "2025.01.05 23:06:24"
```

- A **string**, inside `params`, under the key `date`. Never an epoch number, never ISO-8601.
- Written by `dateToString` (`mok-obf-3_0_2.deobf.js:3567-3582`) from `Date.getFullYear()`,
  `getMonth()+1`, `getDate()`, `getHours()`, `getMinutes()`, `getSeconds()` — **all local-time
  accessors**, zero-padded.
- Parsed by this bot with `DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss")` into a
  **`LocalDateTime`** (`helpers/CommonHelper.kt:21-24`).
- Verified against `example.sqlite`: all 22 `date` values are exactly 19 characters and match the
  pattern. Second-level resolution, no sub-second component, and all 22 values had distinct
  times-of-day (so seconds are real, not zero-padded filler).

There are **no other temporal columns.** `Chronicle` has no `created_at`. See §7.3.

### 7.2 Timezone handling **[FACT] — no timezone is carried, and the reader injects its own**

The vendor's parser (`_0xbf7e1d`, `mok-obf-3_0_2.deobf.js:623-652`) reconstructs a `Date` by:

1. rewriting `"2025.01.05 23:06:24"` → `"2025-01-05T23:06:24"`, then
2. **appending the offset of the machine currently reading the file**, computed live from
   `new Date().getTimezoneOffset()`.

So the same dump parses to **different absolute instants depending on where it is opened.** The
client even logs a complaint if the reader's offset is not a whole or half hour (`:640-642`).

**[INFERRED]** the format's intent is *floating local wall-clock time*: "23:06 on Jan 5" as the user
experienced it, deliberately not anchored to UTC. This bot's choice of `LocalDateTime` is the
faithful reading — and is more correct than the vendor's own client, which re-anchors to the
reader's zone.

⚠️ **Traps this creates:** a user who travels across zones produces a log whose wall-clock times are
in *different* implicit zones with nothing to distinguish them. Daily bucketing (`toLocalDate()`,
`StatisticsProvider.kt:76`) is therefore only as meaningful as "the day the user thought it was".
DST transitions are invisible. **Never convert these to UTC without an explicit, user-supplied
zone.**

### 7.3 "When it happened" vs "when it was recorded" **[FACT]**

This distinction is present but **asymmetric and incomplete** — arguably the format's largest gap.

| | Business date ("when it happened") | Recording order ("when it was recorded") |
|---|---|---|
| Representation | `params.date` | `Chronicle.uid` |
| Type | local wall-clock string | integer sequence |
| **Absolute recording time** | — | ⚠️ **NOT STORED. Anywhere.** |

**Which event types carry `date`:**

| Carries `date` | Never carries `date` |
|---|---|
| `10` create operation (**required**) | `20` create account |
| `11` update operation (optional) | `21` update account |
| `50` create transfer (**required**) | `22` delete account |
| `51` update transfer (optional) | `23` reorder accounts |
| `110` verification — *per this bot; unconfirmed, see §2.2* | `30`–`33` all group events |
| `12`, `52` — *parsed by this bot, ignored by the vendor* | `40`–`45` all category events |
| | `1` version, `100` deleted, `90/92/93` files |

**Consequence [FACT]:** account and category lifecycle events are **undatable**. This bot works
around it in `buildNumToDateMap` (`StatisticsProvider.kt:490-507`): an undated event **inherits the
timestamp of the preceding dated event, plus one nanosecond** to preserve ordering. The test at
`StatisticsProviderTest.kt:78` documents the effect — an account created and updated before any
dated event *appears to have been created on the day of the first dated event in the log.*

⚠️ Two defects in that workaround, both worth knowing before reimplementing:

1. `resultMap.values.first()` on a `TreeMap` keyed by `num` (`:498`) takes the value of the
   *lowest-numbered dated* event, but the seeding loop then iterates `mokOperations` in **list
   order, not `num` order** (`:501`) — so the carry-forward depends on the JDBC row order rather
   than on `uid`.
2. `buildNumToDateMap` throws `NoSuchElementException` on a log with **no dated events at all**
   (accounts and categories only) — a plausible fresh-install export.

---

## 8. Categories — **entirely unused by the current service**

### 8.0 Status in the current code **[FACT]**

| | |
|---|---|
| Category events (`30`–`33`, `40`–`45`) | Reach `normalize()` and are **mapped to `null` and dropped** (`StatisticsProvider.kt:45`) |
| `catUid` on transactions | **Never parsed.** Only appears in a comment (`models/MokOperations.kt:127`) |
| `models/Category.kt` | Declares `Category(uid, name, type, note)` — **dead code, never instantiated anywhere** (verified: zero constructor call sites) |
| `models/CategoryType.kt` | *Is* used — but only as the transaction's income/expense discriminator, never as a category reference |

So: everything in this section comes from the **vendor client** and from `example.sqlite`, not from
this repo's Kotlin.

### 8.1 The hierarchy **[FACT]**

Three levels, of which the top is synthetic:

```
Level 0   root group 1 "expenseRoot"      root group 2 "incomeRoot"     ← implicit, never logged
             │                                     │
Level 1      ├── Group (type 30)                   ├── Group (type 30)   ← parent = derived from opType
             │      │                              │
Level 2      │      └── Category (type 40)         └── Category (type 40)
             └── Category (type 40)  ← categories may hang directly off a root
```

**Depth in practice: 2 levels** (group → category), plus the synthetic root. Confirmed in
`example.sqlite`: 20 categories across `groupUid` ∈ {1, 2, 3}, where 1 and 2 are roots and 3 is a
single user group.

**[INFERRED] deeper nesting is technically reachable**: `MOVE_ITEM` (`type = 45`) can move an item
with `isGroup = true` into any `newGroupUid`, and it unconditionally sets
`item.parentGroupUid = destination.uid` (`mok-obf-3_0_2.deobf.js:534`) — there is no depth check.
Whether the app's UI permits it is **[UNKNOWN]**. A new implementation should model
`parent_group_uid` as an arbitrary-depth tree and defend against cycles rather than assume depth 2.

### 8.2 Parent–child representation **[FACT]**

**Stored one way, maintained two ways** — this is the thing most likely to be got wrong:

| | |
|---|---|
| **On the child** | `Category.parentGroupUid`, from `params.groupUid` (`:346`). For a *group*, `parentGroupUid` is **not in the payload at all** — it is *derived* from `opType` (`:439`). |
| **On the parent** | `Group.items` — an **ordered array** of `{itemUid, isGroup}` (`DDGroupItem`, `:108-111`), maintained in parallel on every create/delete/move. |

The array is the **display order**; the back-pointer is the membership. `MOVE_ITEM` updates both.
A reimplementation that stores only the back-pointer loses the user's ordering; one that stores only
the array loses re-parenting done by a `type = 41`-less path. **You need both.**

### 8.3 Are categories typed? **[FACT] — yes, and the type is immutable**

`opType` on both groups and categories: `1 = PMM_OPTYPE_EXPENSE`, `2 = PMM_OPTYPE_INCOME`
(`mok-obf-3_0_2.deobf.js:68-69`).

- **There is no transfer category type.** Transfers (`type = 50`) have no `catUid` field at all —
  they are a wholly separate entity, outside the category system. Similarly, balance verifications
  (`110`) are uncategorised.
- ⚠️ **`opType` cannot be changed.** `UPDATE_CATEGORY` (41) applies only `name`, `icon`, `color`
  (`:366-385`); `UPDATE_GROUP` (31) applies only `name` (`:453-465`). Neither touches `opType`.
- ⚠️ **`opType` is duplicated on the transaction.** A `type = 10` row carries *both* `catUid` and its
  own `opType`. The vendor derives the balance sign from the **transaction's** `opType`
  (`:563`), not the category's. So a transaction and its category could in principle disagree.
  **[UNKNOWN]** whether such rows occur in real data — worth auditing.

### 8.4 Rename / delete / merge **[FACT]**

| Operation | Supported? | How |
|---|---|---|
| **Rename** | ✅ | `type = 41` with `name`. Category `uid` is stable — **historical transactions keep pointing at the renamed category**, so a category's name is time-varying and reports must decide whether to show the name as-of-then or as-of-now. |
| **Re-icon / re-colour** | ✅ | `type = 41` with `icon` / `color` |
| **Re-parent** | ✅ | `type = 45` (`MOVE_ITEM`) — **not** `type = 41` |
| **Delete** | ✅ | `type = 42`. ⚠️ **Cascades: every transaction in the category is deleted**, and if `moneyBack` is set, each is first reverted on its account (`:400-424`) |
| **Merge** | ❌ **No merge event exists.** | **[INFERRED]** a merge in the UI would surface as *N* × `type = 11` (`catUid` changed) followed by one `type = 42` — reconstructable only heuristically, if at all |

### 8.5 Binding a category to an operation **[FACT]**

`params.catUid` on `type = 10`, mutable afterwards via `catUid` on `type = 11`
(`mok-obf-3_0_2.deobf.js:601-603`). Not validated against the category table at any point (§3.2).
Fully absent from this bot's model.

**[UNKNOWN]** whether a transaction may be *uncategorised* — i.e. whether `catUid` can be `0` or the
key omitted. It is present on all 22 transaction rows in `example.sqlite`, and the vendor's create
path reads it without a guard, but the sample is small.

---

## 9. Accounts and operation types

### 9.1 The account model **[FACT]**

The vendor's `DDAccount` has exactly **three** fields (`mok-obf-3_0_2.deobf.js:88-92`) plus one
assigned dynamically:

| Field | Source | Notes |
|---|---|---|
| `uid` | `params.uid` | own namespace, `0` reserved |
| `name` | `params.name` | mandatory at creation |
| `money` | `params.money` | **live running balance**, mutated by every event that touches the account |
| `currencyUid` | `params.currency` | assigned at `:251`, not declared in the constructor |

Plus, held on the DB object rather than the account: **`accountsOrder`** — an array of uids giving
display order, maintained by `type = 20` (push) / `22` (splice out) / `23` (move).

**Notably absent from the vendor model:** no account *type* (cash / card / savings), no institution,
no IBAN, no archived flag, no icon, no colour, **no note**, and — critically — **no creation date**
(§7.3).

This bot's `models/Account.kt:6-15` adds four synthetic fields that are **not in the dump**:
`note` (see §2.2 caveat), `initBalance`, `initDate`, `initNum` — the latter three are derived during
replay (`StatisticsProvider.kt:311-321`).

### 9.2 Complete operation-type enumeration **[FACT]**

Stored values are the integers in `Chronicle.type`. Vendor constants at
`mok-obf-3_0_2.deobf.js:855-880`; this bot's enum at `models/OperationType.kt:11-41`.

| Code | Vendor constant | Meaning | Vendor v3 handles | This bot parses | Affects balances |
|---:|---|---|:---:|:---:|:---:|
| `1` | `CHRONICLE_VERSION` | log format version | ✅ | ❌ dropped | — |
| `10` | `CREATE_OPERATION` | new income/expense transaction | ✅ | ✅ | ✅ |
| `11` | `UPDATE_OPERATION` | edit transaction | ✅ | ✅ | gated |
| `12` | `DELETE_OPERATION` | delete transaction | ✅ | ✅ | gated |
| `20` | `CREATE_ACCOUNT` | new account | ✅ | ✅ | ✅ (opening balance) |
| `21` | `UPDATE_ACCOUNT` | edit account | ✅ | ✅ | ✅ if `money` present |
| `22` | `DELETE_ACCOUNT` | delete account | ✅ | ✅ | indirect (orphaning) |
| `23` | `ACCOUNTS_ORDER` | reorder accounts | ✅ | ❌ dropped | ❌ |
| `30` | `CREATE_GROUP` | new category group | ✅ | ❌ dropped | ❌ |
| `31` | `UPDATE_GROUP` | rename group | ✅ | ❌ dropped | ❌ |
| `32` | `DELETE_GROUP` | delete group | ✅ | ❌ dropped | ❌ |
| `33` | `MOVE_GROUP` | **legacy — no handler in v3** | ⚠️ no | ❌ dropped | ❌ |
| `40` | `CREATE_CATEGORY` | new category | ✅ | ❌ dropped | ❌ |
| `41` | `UPDATE_CATEGORY` | rename/re-icon/re-colour | ✅ | ❌ dropped | ❌ |
| `42` | `DELETE_CATEGORY` | delete category **+ its transactions** | ✅ | ❌ dropped | ⚠️ **YES, gated on `moneyBack`** |
| `43` | `MOVE_CATEGORY` | **legacy — no handler in v3** | ⚠️ no | ❌ dropped | ❌ |
| `44` | `CATEGORIES_LIST` | **declared, no handler anywhere** | ⚠️ no | ⚠️ **THROWS** | — |
| `45` | `MOVE_ITEM` | re-parent/reorder a category **or** group | ✅ | ❌ dropped | ❌ |
| `50` | `CREATE_TRANSFER` | new inter-account transfer | ✅ | ✅ | ✅ |
| `51` | `UPDATE_TRANSFER` | edit transfer | ✅ | ✅ | ✅ always |
| `52` | `DELETE_TRANSFER` | delete transfer | ✅ | ✅ | ✅ always |
| `90` | `ADD_FILE` | attach file to a transaction | ✅ | ⚠️ **THROWS** | ❌ |
| `92` | `REMOVE_FILE` | detach file | ✅ | ⚠️ **THROWS** | ❌ |
| `93` | `MOVE_FILE` | reorder attachments | ✅ | ⚠️ **THROWS** | ❌ |
| `100` | `DELETED` | tombstone; explicit no-op | ✅ (no-op) | ❌ dropped | ❌ |
| `110` | `ACCOUNT_MONEY_VERIFICATION` | absolute balance correction | ✅ | ✅ | ✅ (absolute set) |

Codes **91**, and everything outside this set, are unallocated. Observed in `example.sqlite`:
`{1, 10, 20, 21, 30, 40}` only — a **very** narrow sample that exercises none of the deletion,
transfer, verification, or file paths.

### 9.3 How transfers are represented **[FACT]**

**One record, not two.** A single `type = 50` row holds both legs:

```json
{"uid": N, "accFrom": A, "accTo": B, "money": X, "moneyTo": Y, "date": "...", "note": "..."}
```

- `accFrom` is debited `money`; `accTo` is credited `valueOfMoneyTo()` = `moneyTo` if present and
  `>= 0`, else `money` (`mok-obf-3_0_2.deobf.js:129-135`, `669-701`; `models/Transfer.kt:15`).
- The two sides are linked by **being in the same record**. There is no matching key, no
  "counterpart uid", no pairing heuristic needed.
- Transfers live in their **own uid namespace**, separate from transactions
  (`db.transfers` vs `db.operations`).
- ⚠️ **A transfer has no category** — it is invisible to any income/expense categorisation.
- ⚠️ **A transfer is not two transactions.** It never appears in `db.operations`. Any new system
  that normalises transfers into paired ledger entries is doing a transformation the source does
  not do.
- **Orphaned legs:** after `type = 22` deletes one side, that side's uid becomes `0` and the
  transfer survives with one live leg (§4.3). A transfer with `accFrom = 0` still credits `accTo`.

---

## 10. Quirks, traps and edge cases

### 10.1 ⚠️ The file is 95% unvacuumed free pages **[FACT]**

`example.sqlite`: `page_count = 123`, **`freelist_count = 117`**, `auto_vacuum = 0`. 503,808 bytes
on disk for 50 rows of data.

The export is a raw copy of a live Android database with no `VACUUM`. **Free pages retain the bytes
of previously deleted rows** — i.e. transactions the user deleted, or (given §10.2) thousands of
rows removed to produce this sample. Anything reading these dumps is handling more data than
`SELECT` returns.

**Implications:** never treat "row deleted from `Chronicle`" as "data gone"; size the pipeline for
files far larger than their row count suggests; and if the new system stores or forwards raw dump
files, that residue travels with them.

### 10.2 ⚠️ `sqlite_sequence` contradicts the data — dumps get rewritten **[FACT]**

`sqlite_sequence.seq = 5448` but `max(uid) = 50`, with uid **1…50 contiguous, no gaps**. Under
`AUTOINCREMENT`, deleting rows leaves `seq` at the high-water mark but *cannot* renumber the
survivors. Contiguous 1…50 with `seq = 5448` therefore means **the rows were re-inserted with fresh
uids into a table that had already issued 5448** — the file was rebuilt, not merely pruned.

**Consequence:** `Chronicle.uid` is stable *within* a continuous history but **not stable across
exports**. Do not key anything durable on it, and do not assume two dumps from the same user share a
uid space. Cross-check `seq` vs `max(uid)` on ingest as a rewrite detector.

### 10.3 ⚠️ Meaning depends on a sibling field, in five places **[FACT]**

| Field | Governed by | Effect if the governor is absent/false |
|---|---|---|
| `money` on `type = 11` | `moneyBack` | field changes, **balance does not** |
| `accUid` on `type = 11` | `moneyBack2` | field changes, **balances do not** |
| `money` on `type = 10` | `opType` | sign flips entirely |
| `moneyTo` on `type = 50/51` | its own presence + `>= 0` | falls back to `money` |
| all transactions of a category on `type = 42` | `moneyBack` | deleted silently vs. reverted first |

`moneyBack` / `moneyBack2` are stored as **`0`/`1` integers**, read as `it == 1`
(`MokOperations.kt:169-170`). **[INFERRED]** they encode the user's answer to a UI confirmation
prompt — which means **the dump records user intent, not just data**, and two logs with identical
field values can imply different balances.

### 10.4 ⚠️ Unknown enum codes are fatal, not skipped **[FACT]**

Three hard-failure paths in the current reader, all reached by *valid* dumps:

1. **`OperationType.from(code)` throws** `IllegalArgumentException` on any unlisted code
   (`models/OperationType.kt:49`), called eagerly for **every row** during load
   (`MokOperationsLoaderImpl.kt:33`). Codes **`44`, `90`, `92`, `93`** are valid in the format and
   missing from the enum → **any dump with a single file attachment fails to load.**
2. **`Currency.from(code)` throws** on anything outside {0, 840, 978, 643, 941, 51}
   (`models/Currency.kt:16`). The format allows **171** codes. A JPY account kills the report.
3. **`getAccount` throws** `IllegalStateException` when an event references a missing account
   (`StatisticsProvider.kt:419`) — the exact dangling-reference case the vendor client treats as
   `"possibly not error"` (§3.2).

The vendor's contrasting stance: `default: console.log("Unknown action type…")` and carry on
(`:227-229`). **[INFERRED]** forward-compatibility requires the vendor's stance; a new
implementation should log-and-skip unknown `type` codes and unknown currencies, and reserve hard
failure for structural corruption. (Note that this bot's *replay* is deliberately fail-fast by
design, with the catch at the handler boundary, `MokDumpFileMessageHandler.kt:74` — that is a
sound choice; the problem is that *loading* is fail-fast too.)

### 10.5 ⚠️ `moneyTo` is reset to `-1` when omitted from an update **[FACT]**

The single exception to "absent key = unchanged" (§2.2). In `UPDATE_TRANSFER`:

```js
if ("moneyTo" in params) { t.moneyTo = Number(params.moneyTo); }
else                     { t.moneyTo = -1; }        // ← explicit reset
```

(`mok-obf-3_0_2.deobf.js:727-731`). So editing only a transfer's *note* silently **discards its
cross-currency target amount**, collapsing it to `moneyTo == money`. This bot reproduces the
behaviour faithfully — `transfer.moneyTo = updateTransfer.moneyTo` unconditionally, outside the
`?.let` pattern used for every other field (`StatisticsProvider.kt:445`) — and pins it with a
regression test (`StatisticsProviderTest.kt:135`).

**[INFERRED]** almost certainly a vendor bug rather than intent, but it is *the format's behaviour*
and real dumps carry its consequences. Reproduce it, or your balances will diverge from what the
user sees in the app.

### 10.6 ⚠️ Half the entity types have no timestamp **[FACT]**

Covered in §7.3. The practical consequence: **you cannot date an account's creation.** Any
"balance history" must invent a start date. This bot inherits the previous dated event's timestamp
+ 1 ns (`StatisticsProvider.kt:502`), which makes account creation order-dependent on unrelated
transactions.

### 10.7 ⚠️ Category deletion moves money — and this bot ignores it **[FACT]**

`type = 42` with `moneyBack` truthy reverts **every transaction in the category** on its account,
then deletes them (`mok-obf-3_0_2.deobf.js:400-424`). This bot drops `type = 42` entirely
(`StatisticsProvider.kt:45`), so a dump containing one produces **silently wrong balances** — the
transactions stay applied forever.

Related: `type = 32` (delete group) **does not cascade to its categories** (`:467-490`) — they remain
in `db.categories` pointing at a `parentGroupUid` that no longer resolves. Expect dangling
categories in real data.

### 10.8 ⚠️ Two competing balance models exist in this bot **[FACT]**

`buildStatisticsSnapshot` maintains a *live* balance on each `Account` **and** a separate
`stateByDate: TreeMap<LocalDate, HashMap<Int, Double>>` timeline, reconciling them after every step
by diffing before/after (`StatisticsProvider.kt:75-209`, `addBalanceChangesForward` at `:294-304`).
The retroactive-relocation logic (§4.5) then patches the timeline *behind* the live balances.

The commit history — `9c11e24 Correct file parsing & statistic calculation` → `0f67539 Fixed
periodical balance evaluation` → `2300006 Some changes - wrong calculation` → `1d7af93 Revert` →
`e814d02 Almost correct daily results` → `2f83eeb Finally correct result` — plus
`test-scenario.md` (a hand-worked back-dating scenario with expected daily balances) records how
hard this was to get right. **[INFERRED]** the difficulty is intrinsic to single-pass replay of a
back-datable log, not to this implementation. Treat `test-scenario.md` and the 22 tests in
`StatisticsProviderTest.kt` as a **portable conformance suite** for the new system.

### 10.9 Historical format changes **[FACT / INFERRED]**

| Signal | Reading |
|---|---|
| `type = 1` version `3`; `LOADER_VERSION = 3`; the check is `LOADER_VERSION < version` only (`:161-167`) | **[FACT]** versions 1 and 2 exist and remain readable |
| `33`, `43` declared but unhandled in v3; `45 MOVE_ITEM` handles both cases via `isGroup` | **[INFERRED]** 33 + 43 were consolidated into 45 at some version bump; legacy logs may still contain them |
| `44 CATEGORIES_LIST` declared, never handled | **[INFERRED]** planned, never shipped |
| File ops declared with `const` while everything else uses `var` (`:878-880`, `:905`) | **[INFERRED]** `90/92/93` and `targetUid` were added later than the rest |
| Two icon naming schemes coexist within one dump (bare `item*.png` vs `/Pack/Name.png`) | **[FACT]** an icon-library migration happened; **both forms remain live** |
| `color` present on only some categories | **[FACT]** added after the fact; default `-1` = none (`:99`) |
| `note` omitted rather than written empty (`:3596`) | **[FACT]** sparse-key convention, not a later addition |

### 10.10 Smaller traps **[FACT]**

- **`uid = 0` is universally rejected** by the vendor at the top of every handler. Never a valid
  entity id; it doubles as the orphaned-transfer-leg sentinel.
- **Mixed JSON numeric types** in `money`: `100` and `100.5` in the same field. Any decoder must
  accept both — a strict integer or strict decimal decoder will fail.
- **`params` may be unparseable**: the vendor guards it (`:149-152`); this bot does not
  (`StatisticsProvider.kt:29` would throw).
- **`Chronicle.uid` is 64-bit; this bot reads it as `Int`.** Fine today, silently wrong past 2³¹.
- **`Currency.from(json.get("currency")?.asInt() ?: 0)`** (`MokOperations.kt:34`) conflates "key
  absent" with "currency 0". Both mean unspecified in practice, but they are distinct facts.
- **`applyUpdateAccount` overwrites `initBalance` whenever the current balance happens to be
  `0.0`** (`StatisticsProvider.kt:334-336`) — including a legitimately zeroed account mid-history.
  Guarded by a test (`StatisticsProviderTest.kt:413`) but semantically fragile.
- **Vendor typo:** `DDTransfer` initialises `this.fromAccoutUid` (`:126`, missing `n`) while every
  other site uses `fromAccountUid`. Harmless in JS (the correct property is created on assignment at
  `:675`), but a warning that the vendor's own field names are not authoritative — **the dump's JSON
  keys are.**
- **`android_metadata` and `sqlite_sequence` will confuse schema-diffing tools.** Neither is
  MoneyOK's.
- **No `NOT NULL`, no `CHECK`, no index other than the implicit rowid.** A `SELECT * FROM Chronicle`
  returns rows in rowid order in practice, but **that is not guaranteed by SQL** — this bot relies
  on it in `buildNumToDateMap` (§7.3, defect 1). Always `ORDER BY uid` explicitly.

---

## 11. Open questions — require inspecting real dumps

Ordered by how much they would change a design.

### Blocking / high impact

1. **`type = 110` payload.** Does `ACCOUNT_MONEY_VERIFICATION` actually carry `date`? The vendor
   client never reads it; only this bot's hand-written test fixtures assert it. If it does not, then
   **balance corrections are undatable** and the entire historical-balance reconstruction changes
   shape. → `SELECT params FROM Chronicle WHERE type = 110 LIMIT 20`
2. **`type = 100 DELETED` payload.** Is it a tombstone for a specific `Chronicle.uid`? Does it name
   a target? Does it appear at all in real logs? Without this, "the log is append-only" has an
   unexamined escape hatch.
3. **Can a transaction be uncategorised?** Is `catUid` ever `0`, or ever absent from a `type = 10`
   payload? Determines whether the category FK is nullable.
4. **Can `catUid` be updated to a deleted category, or reference a category created later in the
   log?** The vendor never validates it. Determines whether categories can be resolved in one pass.
5. **Do accounts really have a `note`?** Not in `example.sqlite`; not in the vendor's `DDAccount`.
   If not, drop the field. → `SELECT params FROM Chronicle WHERE type IN (20,21) AND params LIKE '%note%'`

### Structural

6. **Does `type = 1` appear more than once?** A mid-log version bump would mean the payload grammar
   changes partway through a single file.
7. **Do `type = 33` / `43` (legacy move) actually occur** in older dumps, and what are their
   payloads? The v3 client silently drops them, so a faithful reimplementation may be *more* correct
   than the vendor.
8. **What is the real maximum category-tree depth?** Can `MOVE_ITEM` nest a group inside a
   non-root group in practice (§8.1)?
9. **Do transaction `opType` and its category's `opType` ever disagree?** (§8.3)
10. **Realistic row-count and `money` precision distributions.** How many rows in a 5-year install?
    Are there values with more than 2 decimal places (which the vendor would round on read)? Are
    there negative `money` values on `type = 10`?

### Environmental

11. **Does the iOS build emit `android_metadata`?** And does either platform stamp anything else —
    a `dbUid`, a device id — into the file?
12. **What produces the export?** A `VACUUM`-less file copy (§10.1) suggests a plain file share.
    Confirm whether the app ever produces a compacted or re-numbered export (which would explain
    §10.2 and would mean `uid` renumbering is *normal*, not an artifact of this sample).
13. **Is the `.mok` extension the same SQLite file, or a wrapper?** This bot accepts `mok`, `db`,
    `sqlite`, `sqlite3` interchangeably and opens all of them with the SQLite JDBC driver
    (`MokDumpFileMessageHandler.kt:137`, `MokOperationsLoaderImpl.kt:15`) — so it *assumes* they are
    identical. No `.mok` file was available here to confirm. If `.mok` is encrypted or compressed,
    that assumption is wrong.
14. **Multi-device sync artifacts.** Given `badge`'s apparent role as a de-dup token, can a synced
    log contain **duplicate `badge` values, out-of-order `uid`s, or two events with the same
    `params.uid` for the same entity kind**? This is the most likely source of real-world data that
    violates everything above.

---

## Appendix — evidence index

| Claim area | Primary citations |
|---|---|
| Physical schema | `example.sqlite` `sqlite_master`; pragmas `page_count`/`freelist_count`/`encoding` |
| Table read + columns consumed | `services/impl/MokOperationsLoaderImpl.kt:14-49` |
| Event type enum | `models/OperationType.kt:11-50`; `mok-obf-3_0_2.deobf.js:855-880` |
| Payload key names | `mok-obf-3_0_2.deobf.js:881-905` |
| Payload → typed model | `models/MokOperations.kt` (whole file) |
| Reference replay semantics | `mok-obf-3_0_2.deobf.js:138-853` (`DDChronicleToDbConverter`) |
| In-memory entity shapes | `mok-obf-3_0_2.deobf.js:72-136` |
| This bot's replay | `services/impl/StatisticsProvider.kt:54-224` |
| Balance-gate flags | `mok-obf-3_0_2.deobf.js:566-621`; `StatisticsProvider.kt:358-404` |
| Transfer `moneyTo` sentinel | `mok-obf-3_0_2.deobf.js:129-135`, `:727-731`; `models/Transfer.kt:11-15` |
| Account deletion / orphaning | `mok-obf-3_0_2.deobf.js:276-308`; `StatisticsProvider.kt:526-550` |
| Category model | `mok-obf-3_0_2.deobf.js:93-111`, `:341-424`, `:491-536` |
| Date format (write / read) | `mok-obf-3_0_2.deobf.js:3567-3582` / `:623-652`; `helpers/CommonHelper.kt:21-24` |
| Undated-event workaround | `StatisticsProvider.kt:490-507` |
| Back-dating / relocation | `StatisticsProvider.kt:66, 170-208, 260-270`; `test-scenario.md` |
| Money rounding | `mok-obf-3_0_2.deobf.js:154-157` |
| Currency table | `mok-obf-3_0_2.deobf.js:907-937` lookup, `:938`ff 171 entries; `models/Currency.kt` |
| No FX anywhere | absence of `exchange`/`rate`/`convert` in `mok-obf-3_0_2.deobf.js`; `CurrenciesStatistics.kt:20-32` |
| `badge` generation | `mok-obf-3_0_2.deobf.js:3545` |
| `uid` allocation | `mok-obf-3_0_2.deobf.js:3537-3548`, `:3474-3485`, `:549-551` |
| Conformance fixtures | `src/test/kotlin/**/StatisticsProviderTest.kt` (22 tests) |
