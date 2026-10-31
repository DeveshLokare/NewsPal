# NewsPal v0.2.1 — Bug Fix Specification

> Status: **Historical** · the v0.2.1 plan (2026-11-01), written as changes on top of v0.2.0.
> v0.2.1 shipped and **[SPEC.md](SPEC.md) is the full, current spec**; read that first. Code comments cite this file's sections. Where the two differ, SPEC.md describes what was built.

---

## 1. Summary

| # | Bug | Cause | Fix | Area | Status | Section |
|---|---|---|---|---|---|---|
| B1 | Search shows no results for any keyword | Production backend was older than search (M11) | Deploy the current backend | Ops | **Fixed** 2026-11-01 | §3, §4 |
| B2 | Category chips show on the Search screen | Built that way (US-17) | Remove the chips | Mobile | **Fixed** (PR #7) | §5 |
| B3 | Latest news isn't shown when the app opens | The app doesn't re-read the database when reopened, or after a refresh finishes | Reload from the database automatically | Backend + Mobile | **Fixed** (PR #7) | §6 |
| B4 | Settings shows no security question | Production backend was older than security questions (M10) | Deploy the current backend | Ops | **Fixed** 2026-11-01 | §3, §4 |
| B5 | Long security questions are cropped | Dropdown text is cut to one line with an ellipsis | Wrap onto more lines | Mobile | **Fixed** (PR #7) | §5a |

App version: `0.2.0+2` → **`0.2.1+3`**.

B1 and B4 needed a deploy, not new code. It went out on 2026-11-01 and fixed both. "Forgot password" shared the same cause and works too.

---

## 2. Evidence for B1 and B4

Checked against `https://newspal-api.onrender.com` on 2026-11-01, without signing in. That URL is the `API_BASE_URL` repository variable and the one in the APK build command in DEPLOY.md.

| Request | Current code should return | Production returned | Meaning |
|---|---|---|---|
| `POST /api/internal/jobs/run`, no token | `404` | `404` | M9 is live |
| `GET /api/auth/security-questions` | `200` with 8 questions | `401` | M10 is **not** live |
| `POST /api/auth/password/question`, empty body | `400` | `401` | M10 is **not** live |

M10 made both of those routes public, so a `401` means production predates it. M11 (search) was merged after M10, and it is also missing unless it was deployed out of order, which the Render dashboard will confirm in §3.1. `/api/search` can't be told apart without signing in.

Signed in, a missing route returns `404`. The app then shows "Couldn't load the questions" in Settings, and an error instead of results in Search.

**After the deploy (2026-11-01):** the security-questions route returned `200` with all 8 questions, and the reset route returned `400` for an empty body. Search and the security question were confirmed working on a phone.

**Never established:** why M10 onwards didn't deploy on 2026-10-24. Nothing in the repo explains it: M10 and M11 add no required environment variables (`ResetProperties` has defaults). Render's logs from that day had expired, and the last successful deploy shown was M9. It may recur, which is why §3.4 matters.

---

## 3. Deploy (B1, B4)

The steps below were followed for the manual deploy on 2026-11-01. §3.1 is kept as the checklist if a deploy goes missing again.

### 3.1 If a deploy doesn't arrive
In the Render dashboard, open **newspal-api → Events**.

| What you see | Likely cause | Action |
|---|---|---|
| A deploy **failed** | Build error, or a boot failure such as a Flyway migration; Render keeps the last good version live | Read the log, fix the cause |
| **No deploy** after M9 | Auto-deploy is off, or the GitHub link is broken | Turn it on, or reconnect the repo |
| Deployed from a **different branch** | Service is pointed at something other than `main` | Point it at `main` |

### 3.2 Deploy
1. Back up the Neon database first (a Neon branch is enough). Any deploy with new migrations changes real data.
2. Deploy the latest commit of `main`.
3. Confirm the deploy log shows the migrations applied.

**Confirmed 2026-11-01:** Render deploys from `main` with Auto-Deploy on.

### 3.3 Accounts created while production was behind
A v0.2 app registering against the old backend had its question and answer ignored. No data needs fixing: once deployed, `/api/me` returns `hasSecurityQuestion: false` for these accounts, and the Feed banner asks them to set one.

### 3.4 Stop this happening again
Add a step to `.github/workflows/jobs.yml` that fails the run unless `GET /api/auth/security-questions` returns `200`. A silently stale deploy then shows up as a failed Action within 2 hours. A proper version check can replace it later.

---

## 4. Acceptance for B1 and B4

Run on a phone against production, with the existing `0.2.0+2` APK:

- [x] `GET /api/auth/security-questions` returns `200` with 8 questions.
- [x] Search returns results on a phone (reported working 2026-11-01).
- [x] The security question works in Settings (reported working 2026-11-01).
- [x] Search: a partial word ("tech") returns results, and nonsense ("zzqqxx") shows `No results for "zzqqxx"`.
- [x] Forgot password works end to end on a test account.
- [x] Login, Feed and Archive still work, and the existing session isn't signed out.

All checks passed on 2026-11-01.

---

## 5. B2 — Remove category chips from Search (mobile only)

**US-17 (revised):** Search covers every category, with no category filter.

- `features/search/search_page.dart`: remove the chip row and the `Brand` / theme imports it alone uses. Keep `allCategoriesProvider`, because cards still show the category name. Always pass `names[article.category]` as `categoryName`.
- `features/search/search_controller.dart`: remove `SearchState.category`, `selectCategory`, and the `category:` argument from both `_repo.search` calls.
- `features/search/search_repository.dart`: remove the `category` parameter.
- **Backend:** no change. `GET /search?category=` stays, so older app versions keep working.
- **Tests:** in `mobile/test/search_test.dart`, remove the chip test and update the fake's `search` signature. Add a widget test: no `search-chip-*` key on screen, and results from two categories each show their category name.

The Feed's chips are not affected.

---

## 5a. B5 — Show long security questions in full (mobile only)

**US-1 and US-15 (revised):** every security question is readable in full, in the list and once selected, at any supported phone width and text size.

Cause: `features/auth/security_question_fields.dart` uses `TextOverflow.ellipsis` at lines 43 and 45. The dropdown also has a fixed row height, so removing the ellipsis alone isn't enough. Registration and Settings share this widget, so both get the fix. The Forgot password screen shows the question as plain text and isn't affected.

- Remove `overflow: TextOverflow.ellipsis` from both `Text` widgets, so they wrap.
- On `DropdownButtonFormField`, set `itemHeight: null` so rows grow with their text, and `isDense: false` so the closed field can grow too. `isExpanded: true` stays: it gives the text a width to wrap at.
- Add a little vertical padding around each menu item, so wrapped questions don't run together.
- Confirm on a real phone: Flutter's dropdown handles multi-line selected values less predictably than menu rows.

**Tests** (widget, in `account_flows_test.dart`), at a 320 px wide screen with a large text scale:
- The open menu shows the full text of the longest question, with no overflow error.
- After selecting it, the closed field shows the full text and no overflow error.

---

## 6. B3 — Show the latest news on open

**Goal:** a user never has to pull to refresh to see the newest articles **already in our database**. How often we fetch from Free News API doesn't change: every 2 hours, as now.

### 6.1 What happens today
1. A cold start calls `GET /feed`, so it does load from the database. That response is then never read again.
2. Reopening the app from the background does nothing: the app has no lifecycle handling and keeps showing the list it already had.
3. Switching to a chip that was loaded earlier also shows that old list.
4. A cold start can itself bring in news, because waking the server starts a job pass and `GET /feed` starts a refresh of stale categories. Those articles reach the database after the response, so they stay hidden until a pull.

### 6.2 Required behaviour
**US-6 (revised):**
- Every time the app is opened, from cold or from the background, the Feed shows the newest articles in the database without a pull.
- If news arrives while the app is open because a refresh was already running, it appears without a pull, usually within about a minute.
- If the user has scrolled down, the list doesn't jump. A **"New stories"** pill appears; tapping it scrolls to the top and shows them.
- Failures stay silent: the current list stays and pull-to-refresh still works.

### 6.3 Backend

No new quota, no new settings, and no extra Free News API calls.

`IngestionService`: track categories that are queued or refreshing in an in-memory concurrent set. Add at queue time and remove in a `finally`. One backend instance runs, so memory is enough. Job passes use the set too.

**API:** `GET /feed` first page gains `"refreshing": true|false`, true when any selected category is in that set. Later pages and `/feed/older` omit it. Empty feeds return `false`. Add it as a nullable `Boolean` with `NON_NULL`, like `lastRefreshedAt`, so search responses don't change.

### 6.4 Mobile

`FeedPageData` and `FeedState` gain `refreshing`, defaulting to `false` when the field is absent.

`FeedController`:
- `silentRefresh()` fetches page 1 without entering a loading state. No new URLs: no change. New URLs and the list at the top: replace the list. New URLs and the user scrolled down: hold the page in `pending`, which shows the pill.
- **Polling:** while the latest response has `refreshing: true`, call `silentRefresh()` every **10 s**, for at most **2 minutes**. Stop when `refreshing` is false, on dispose, or when the app goes to the background.
- Any `ApiError` during a silent refresh is ignored, and polling stops.
- A `401` still signs out through the existing interceptor.

`FeedPage` / `FeedList`:
- Use `AppLifecycleListener`. On `resumed`, call `silentRefresh()` on the visible chip. Skip it if that list loaded under **30 s** ago, so quick app switches don't send needless requests.
- Apply the same rule when switching to a chip that already has a list.
- Give the list a `ScrollController`, and treat "at the top" as offset under 100 px.
- Show the pill over the list. Tapping it applies `pending` and scrolls to the top.

Accepted limits:
- News is only as fresh as the database, so a story can be about 2 hours old.
- On Render free, an open after 15 idle minutes takes about a minute to wake the server. The stored list can't load until then, and the existing "Waking up the server…" banner covers it. Keeping the server awake is out of scope.

### 6.5 Tests

**Backend** (`FeedApiTests`, `IngestionTests`):
- `refreshing` is true during a refresh and false after.
- The set is cleared when a refresh throws.
- A job pass also shows as `refreshing`.

**Flutter** (`feed_controller_test`, feed page test):
- Resuming reloads the visible list; resuming within 30 s doesn't.
- Switching to an older chip reloads it.
- Polling stops on `refreshing: false`, and at the 2-minute cap.
- New items replace the list at the top, and show the pill when scrolled down.
- Tapping the pill shows them.
- An error during polling leaves the list unchanged.
- A response without `refreshing` parses as `false`.

---

## 7. Docs and release

- **SPEC.md:** US-1, US-6, US-15, US-17, §7.4 feed and search, §9.3 Feed, Search and Register, §10 totals, §11 milestones. Mark this file Historical, as SPEC-v2.md is.
- **DEPLOY.md:** add a post-deploy check of `GET /api/auth/security-questions`, and a troubleshooting row for "the app works but new features 404".
- **Release:** bump to `0.2.1+3`, build the APK, tag `v0.2.1`.

Order matters: the backend must ship before the APK. The new app treats a missing `refreshing` as `false`, so it still works on an older backend, but it won't poll.

---

## 8. Milestones

| # | Deliverable | Depends on |
|---|---|---|
| M14 | Deploy the current backend and pass §4 (**done**); add the workflow check (§3.4) | — |
| M15 | B2: remove Search chips; B5: wrap security questions | — |
| M16 | B3 backend: `refreshing` | M14 |
| M17 | B3 app: reload on open, polling, "New stories" pill | M16 |
| M18 | Docs, APK, tag `v0.2.1` | M15, M17 |

---

## 9. Decisions

| # | Decision | Chosen | Rejected |
|---|---|---|---|
| 1 | New stories when scrolled down (§6.2) | "New stories" pill | Always jump to the top: simpler, but it moves what the user is reading |

## 10. Out of scope
- Keeping Render awake, or moving off its free tier.
- Push notifications for new stories.
- Fetching from Free News API more often, or when the app opens beyond the existing refresh of stale categories.
- Feed chip changes.
