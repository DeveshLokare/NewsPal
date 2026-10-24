# NewsPal v0.2 — Release Specification

> Status: Draft · 2026-10-24 · Changes on top of [SPEC.md](SPEC.md) (v1). Sections not mentioned here are unchanged.
> Once v0.2 ships, fold this document into SPEC.md and delete it.

---

## 1. Summary

| # | Feature | Area | Section |
|---|---|---|---|
| F1 | Confirm before logging out | Mobile | §3 |
| F2 | Replace webz.io with **Free News API** (freenewsapi.io) | Backend | §4 |
| F3 | Fetch every category **every 2 hours** into the DB | Backend | §5 |
| F4 | **Daily** job deletes articles older than 7 days | Backend | §6 |
| F5 | Forgot password with a **security question** | Backend + Mobile | §7 |
| F6 | Search news (stored articles only) | Backend + Mobile | §8 |

App version: `0.1.0+1` → **`0.2.0+2`**.

### 1.1 Updated goals
- **G4 (revised):** stay within free tiers: Free News API (5,000 req/day), Render free and Neon free. v0.2 has no paid items.
- **G5 (new):** a user who forgot their password can reset it without help.
- **G6 (new):** a user can search the last 7 days of news by keyword.

### 1.2 Removed from "out of scope"
Free-text keyword search and password reset (via a security question) are now **in scope**. Password reset **by email** stays out of scope until there's a domain (§7.7).

### 1.3 External constraints (new / changed; verify before building)
| Constraint | Value (as of 2026-10) | Impact |
|---|---|---|
| Free News API quota | **5,000 requests/day**, **2 requests/second**, single free plan | Daily budget in §4.4; calls spaced ≥ 550 ms apart |
| Free News API `/news` | 10 items per page; returns only `uuid`, `title`, `published_at`, `publisher` | Every new article needs **one extra `/details` call** to get its URL, image and summary |
| Free News API `/news` search | `in_title` only (`q`, `in_body` removed) | We don't use provider search; search runs on our DB (§8) |
| Free News API quota headers | `X-RateLimit-Remaining-Day`, `X-RateLimit-Reset-Day` | Used as a guard in addition to our own count (§4.4) |
| Free News API terms | The docs don't forbid storing articles | **Decided:** we store title, summary, thumbnail and publisher for 7 days and show the publisher on every card |
| Render free web service | Sleeps after 15 min idle; in-process `@Scheduled` jobs don't run while it sleeps | A 2-hour job needs an **external trigger** to wake it (§5.2) |
| Email reset (deferred) | Render free blocks SMTP; a deliverable sender needs a domain (~US$10/year) | v0.2 uses a security question instead (§7) |

---

## 2. User stories

| ID | Story | Acceptance criteria |
|---|---|---|
| US-3 (revised) | As a user I can log out, after confirming. | Tapping "Log out" opens a dialog: title "Log out?", body "You'll need to log in again to see your feed.", buttons **Cancel** and **Log out**. Cancel, back or tapping outside the dialog does nothing. Log out deletes the token and opens Login. |
| US-1 (revised) | As a visitor I register with a security question. | Registration also asks for a security question (from a fixed list) and an answer of ≥ 3 chars. |
| US-13 | As a user who forgot my password I can reset it. | Login shows "Forgot password?". Enter an email → see a security question (a question is shown for every email, registered or not) → enter the answer and a new password (≥ 8 chars, entered twice) → signed in and taken to the Feed. A wrong answer shows "That answer doesn't match", with the attempts left. |
| US-14 | Guessing is limited. | 5 wrong answers lock resets for that account for 24 h: "Too many attempts. Try again tomorrow." Answers ignore case, extra spaces and punctuation. |
| US-15 | As an existing (v1) user I can add a security question. | Until one is set, the Feed shows a dismissible banner linking to Settings → Security question. Saving needs the current password. The question can be changed there later. |
| US-16 | As a user, after a reset, my other devices are signed out. | Any JWT issued before the reset gets `401` and that device goes to Login. |
| US-17 | As a user I can search news. | A search icon in the Feed app bar opens Search. Typing ≥ 2 chars shows matching articles (title or summary), newest first, as the same cards as the feed (bookmark toggle works). Optional category chips narrow results. A note says "Searching news from the last 7 days". No match → "No results for "…"". |
| US-6 (revised) | Feed freshness. | Every category gets new articles about every 2 h while the backend can be woken up (§5). |

---

## 3. F1 — Logout confirmation (mobile only)

- `features/settings/settings_page.dart`: the "Log out" tile's `onTap` calls `showDialog<bool>` with an `AlertDialog`. `signOut()` runs only when the result is `true`.
- Buttons: `TextButton('Cancel')` → `pop(false)`. `FilledButton('Log out')` → `pop(true)`, using `colorScheme.error` with `onError` text to match the destructive tile.
- The dialog is a small private widget (`_LogoutDialog`), so a widget test can find it.
- No backend change.
- **Tests (widget):** tap Log out → dialog shown; Cancel → still signed in; Log out → `signOut` called once.

---

## 4. F2 — Free News API provider (backend only)

### 4.1 What changes
- **Remove** `provider/webz/*`, `WebzProperties`, `scripts/check-webz-queries.ps1`, the `newspal.webz.*` config, the `WEBZ_TOKEN` env var and the webz tests.
- **Add** `provider/freenews/FreeNewsProvider`, `FreeNewsProperties`, `FreeNewsResponseParser`, plus tests with fixture JSON taken from the attached OpenAPI examples.
- The `NewsProvider` interface stays, so `IngestionService`, `OlderNewsService` and the fake provider in tests barely change.

### 4.2 API usage
Base URL `https://api.freenewsapi.io/v1`, header `x-api-key: ${FREENEWS_API_KEY}`. The key is never logged and never sent to clients. Use `RestClient` with a 10 s timeout.

| Purpose | Call |
|---|---|
| New articles in a category | `GET /news?topic={topic}&language=en&order_by=recent&published_after={since}` then `&cursor={next_cursor}` for more pages |
| Older news (cold path, §4.6) | `GET /news?topic={topic}&language=en&order_by=recent&published_before={cutoff}&published_after={notBefore}` + `cursor` |
| Article fields | `GET /details?uuid={uuid}`, once per article we don't have yet |

`offset` is never used, only `cursor` (the docs recommend it, and offset is capped at 10,000).

### 4.3 Category → topic mapping
`categories.provider_query` now holds the Free News API **topic**. All 8 exist in `/topics`:

| slug | provider_query (topic) |
|---|---|
| technology | `technology` |
| business | `business` |
| sports | `sports` |
| science | `science` |
| health | `health` |
| entertainment | `entertainment` |
| world | `world` |
| politics | `politics` |

Migration `V5__freenews_topics.sql` updates the 8 rows. Changing a topic later is still a SQL update.

### 4.4 Interface changes
```java
public interface NewsProvider {
    boolean isEnabled();   // false when FREENEWS_API_KEY is empty
    /** One page of article refs newer than since (null = no lower bound). Each method is exactly one call, so every call can be counted against the quota. */
    ProviderRefPage listLatest(String topic, Instant since, String cursor);
    /** One page of refs in [notBefore, before). cursor null = first page. */
    ProviderRefPage listOlder(String topic, Instant before, Instant notBefore, String cursor);
    /** Full article, or empty if it has no usable URL. */
    Optional<ProviderArticle> details(String providerId);
}
public record ProviderRef(String providerId, String title, Instant publishedAt, String publisher) {}
public record ProviderRefPage(List<ProviderRef> items, String nextCursor) {}
public record ProviderArticle(String providerId, String url, String title, String summary,
                              String imageUrl, String source, Instant publishedAt) {}
```
Mapping from `/details`: `original_url` → `url` (**skip the article if it's null or not http(s)**), `incipit` → `summary` (falls back to the first 500 chars of `body`; cleaned by `ArticleCleaner`; skip if both are empty), `thumbnail` → `imageUrl` (http(s) only), `publisher` → `source`, `published_at` → `publishedAt`. `body` is **not stored**, to keep rows small and avoid republishing full text.

### 4.5 Call budget, pacing and guards
The quota is counted per **UTC day**, not per month.

| Bucket | Requests / day | Worst case it covers |
|---|---|---|
| `ingestion` | 3,600 | 12 runs × 8 categories × (≤ 3 list pages + ≤ 30 details) = 3,168 |
| `older` | 1,000 | ~90 "Load older news" taps (1 list + ≤ 10 details each) |
| Margin | 400 | Retries, manual tests, clock skew vs. provider reset time |

- **Every HTTP call** (list or details) takes one unit from its bucket via `QuotaService.tryAcquire`, the same atomic SQL as v1 (§6.2 of SPEC.md) keyed by day.
- **Pacing:** a shared limiter keeps calls ≥ `min-interval` (550 ms) apart across all threads (2 req/s limit).
- **429 handling:** on `Too Many Requests` with `retry_after_ms` ≤ 5 s, wait and retry **once**. On `Daily quota exceeded`, the provider disables itself until `X-RateLimit-Reset-Day` (or the next UTC midnight if the header is missing).
- **Header guard:** if `X-RateLimit-Remaining-Day` < `min-remaining` (100), disable until the reset, like v1's webz balance guard. **Observed 2026-10-24:** `X-RateLimit-Reset-Day` is an ISO timestamp (e.g. `2026-10-25T10:07:57.000Z`), a rolling 24 h from the first call of the day, not UTC midnight. Our own count still resets at UTC midnight, which is what the 400-call margin is for.
- **401/403** (bad, expired or disabled key): log once at ERROR and disable until restart.

### 4.6 Older news (cold path)
Kept, with the same endpoint, caching, cursor ids, per-user daily limit and error codes as SPEC.md §5. Changes:
- The provider cursor is Free News API's `next_cursor` (no credentials in it, but it still stays server-side).
- Each page costs 1 list call + up to 10 details calls. A page is only served once all its details are fetched or skipped. After the list call, the page's details calls are reserved from the `older` bucket in one go; if they don't fit, return `429 QUOTA_EXHAUSTED` without calling details.
- `older.max-age` stays at 30 days. **Verified 2026-10-24:** Free News API returns technology articles from 60+ days back.

### 4.7 Data model changes (`V6__freenews_articles.sql`)
```sql
ALTER TABLE articles ADD COLUMN provider_id VARCHAR(64);
CREATE UNIQUE INDEX uq_articles_provider_id ON articles (provider_id) WHERE provider_id IS NOT NULL;

-- Quota per day instead of per month: '2026-10-24'
ALTER TABLE api_usage RENAME COLUMN month TO period;
ALTER TABLE api_usage ALTER COLUMN period TYPE VARCHAR(10);
```
- Dedupe stays on `url_hash` (`ON CONFLICT DO NOTHING`). `provider_id` lets ingestion skip `/details` calls for refs it has already stored: `SELECT provider_id FROM articles WHERE provider_id IN (...)`.
- Leftover webz rows (`provider_id IS NULL`) expire through normal 7-day retention. No backfill needed.

### 4.8 Configuration
```yaml
newspal:
  news:
    refresh-ttl: PT1H50M      # just under the 2 h job interval, so every run finds categories stale
    retention: P7D
    max-per-category: 1000    # was 200; ~10–120 new/day/category × 7 days
    summary-max-chars: 500
    max-list-pages: 3         # per category per run
    quota:
      ingestion-daily: 3600
      older-daily: 1000
    older:
      cache-ttl: PT1H
      per-user-daily: 10
      max-age: P30D
  freenews:
    base-url: https://api.freenewsapi.io/v1
    api-key: ${FREENEWS_API_KEY:}   # empty = fetching disabled
    language: en
    country:                        # empty = English news from all countries (decided 2026-10-24)
    timeout: PT10S
    min-interval: PT0.55S
    min-remaining: 100
```
**Storage estimate:** 8 categories × ≤ 1,000 rows × ~2 KB ≈ **16 MB** max, well under Neon's 0.5 GB.

---

## 5. F3 — Ingestion job every 2 hours (backend only)

### 5.1 One run
`IngestionJob.runAll()` (replaces `refreshAllStaleFollowed`):
1. For **every** category (followed or not, because search covers all of them), one at a time:
   1. Claim it: the same conditional `UPDATE categories SET last_fetched_at …` as v1, with `refresh-ttl` = 1 h 50 m. 0 rows → another run has it; skip.
   2. `since` = newest `published_at` stored for the category minus 5 min overlap (or now − 2 h if empty).
   3. `listLatest(topic, since, max-list-pages)`. Stop paging early once a page holds only refs we already have.
   4. Drop refs whose `provider_id` is already stored.
   5. For each remaining ref, oldest first: `details()`, map, insert. A failed details call is logged and skipped; the rest of the batch continues.
   6. Any list failure restores `last_fetched_at` to its old value (as in v1) so the next run retries.
2. Log a single line per run: categories refreshed, refs seen, articles inserted, calls used.

Categories run sequentially (pacing makes parallel runs pointless). The feed's async lazy refresh (SPEC.md §4.3 trigger 1) **stays** as a fallback. It's the same code path with the same claim, so it can't double-fetch.

### 5.2 Triggers
Render free sleeps, so an in-process timer alone doesn't run every 2 h. Three triggers share the claim, so overlaps are harmless:

| Trigger | When | Notes |
|---|---|---|
| **External cron → wake-up** | Every 2 h (`0 */2 * * *` UTC) | A GitHub Actions workflow (`.github/workflows/jobs.yml`) calls `POST /api/internal/jobs/run` with `X-Job-Token: ${JOB_TOKEN}`, using `curl --retry 3 --max-time 120` to wait out a cold start. The run happens even if the request times out, because startup (next row) runs the job too. |
| `ApplicationReadyEvent` | Every boot | Runs ingestion + cleanup-if-due (§6) on `ingestionExecutor`. Already exists in v1. |
| `@Scheduled(cron = "0 5 */2 * * *", zone = "UTC")` | While awake | Covers the case where the instance was already awake. |

`POST /api/internal/jobs/run`:
- Public in `SecurityConfig` (no JWT), but requires `X-Job-Token`, compared in constant time against `newspal.jobs.token` (`JOB_TOKEN`). Missing or wrong → `404` (doesn't reveal the endpoint). Empty `JOB_TOKEN` → endpoint disabled (404).
- Returns `202 {"started": true}` immediately and runs on `ingestionExecutor`. If a run is already in progress (an in-memory `AtomicBoolean`), it returns `202 {"started": false}`.

**Why GitHub Actions (decided 2026-10-24):** it's free, versioned in the repo, and the secret lives in repo secrets. Caveats: scheduled runs can start 5–30 min late, and GitHub pauses schedules on public repos with no commits for 60 days (re-enable from the Actions tab). The workflow sends nothing secret except `JOB_TOKEN`, from repo secrets.

**Free-tier cost of waking every 2 h:** Render ~12 × ~16 min ≈ 3.2 h awake/day (within 750 instance-hours/month). Neon ~12 × ~10 min × 0.25 CU ≈ 15 CU-hours/month (limit 100).

---

## 6. F4 — Daily cleanup (backend only)

- **What:** `DELETE FROM articles WHERE published_at < now() - retention` (7 days), then the per-category cap (`max-per-category` = 1000). This is the v1 SQL with new limits. Archived articles are never touched.
- **When:** at most once per 24 h, tracked in a new table so it survives sleeps and restarts:
  ```sql
  -- V7__job_runs.sql
  CREATE TABLE job_runs (
    name         VARCHAR(40) PRIMARY KEY,   -- 'cleanup'
    last_run_at  TIMESTAMPTZ NOT NULL
  );
  ```
  Claim: `INSERT … ON CONFLICT (name) DO UPDATE SET last_run_at = :now WHERE job_runs.last_run_at < :now - interval '23 hours' RETURNING name`. No row → it already ran today; skip.
- **Triggers:** checked at the start of every ingestion trigger (§5.2), plus `@Scheduled(cron = "0 30 3 * * *", zone = "UTC")`. The v1 hourly cron is **removed**.
- Search reads the same table, so the 7-day window applies to search results too.

---

## 7. F5 — Forgot password with a security question (backend + mobile)

> **Decided 2026-10-24:** a security question for now, instead of an emailed OTP, so v0.2 needs no domain or email provider. This is weaker than email: answers can be guessed or looked up, and people forget them. The rules in §7.4 limit the damage. Email OTP is planned for later (§7.7).

### 7.1 Flow
```
Register ── email, password, question + answer ──▶ (answer stored as a BCrypt hash)

Login ──"Forgot password?"──▶ Enter email ──POST /auth/password/question──▶ the account's question
      ──▶ Answer + new password ×2 ──POST /auth/password/reset──▶ AuthResponse (signed in) ──▶ Feed / Onboarding
```

### 7.2 Endpoints
| Method & path | Auth | Body | Response |
|---|---|---|---|
| `GET /api/auth/security-questions` | public | — | `200 [{id, text}]`, ordered by `sort_order` |
| `POST /api/auth/register` (changed) | public | `{email, password, questionId?, answer?}` | As v1, plus `user.hasSecurityQuestion`. `questionId` and `answer` go together: `questionId` must exist, and `answer` is 3–100 chars after normalising. **Optional until M12 ships** (decided 2026-10-24), so the app already installed can still register; the new app always sends them |
| `POST /api/auth/password/question` | public | `{email}` | `200 {questionId, text}`. **Always 200**, for unknown emails and accounts without a question too (§7.4). `429 RESET_RATE_LIMITED` if over the IP limit |
| `POST /api/auth/password/reset` | public | `{email, answer, newPassword}` (8–72 chars) | `200 {token, expiresAt, user}` (same as login). `400 WRONG_ANSWER {attemptsLeft}`. `429 RESET_LOCKED {retryAfterSeconds}` |
| `GET /api/me` (changed) | JWT | — | `200 {id, email, hasSecurityQuestion}` |
| `PUT /api/me/security-question` | JWT | `{currentPassword, questionId, answer}` | `204`. `400 WRONG_PASSWORD` if the password is wrong. Not `401`: the app signs out on any `401` from a signed-in call |

### 7.3 Data model (`V8__security_questions.sql`)
```sql
CREATE TABLE security_questions (
  id          SMALLSERIAL PRIMARY KEY,
  text        VARCHAR(120) NOT NULL UNIQUE,
  sort_order  SMALLINT     NOT NULL DEFAULT 0
);
INSERT INTO security_questions (text, sort_order) VALUES
  ('What was the name of your first pet?', 1),
  ('What was the first concert or live show you went to?', 2),
  ('What is the name of the street you lived on as a child?', 3),
  ('What was the name of your childhood best friend?', 4),
  ('What was the make and model of your first car or bike?', 5),
  ('What is the title of the first book you loved?', 6),
  ('In what city or town did your parents meet?', 7),
  ('What was your favourite subject at school?', 8);

ALTER TABLE users
  ADD COLUMN security_question_id SMALLINT REFERENCES security_questions(id),
  ADD COLUMN security_answer_hash VARCHAR(100),           -- BCrypt of the normalised answer
  ADD COLUMN reset_failed_attempts SMALLINT NOT NULL DEFAULT 0,
  ADD COLUMN reset_locked_until   TIMESTAMPTZ,
  ADD COLUMN password_changed_at  TIMESTAMPTZ;
```
- The question list is data, like categories: the app loads it from the API, and changing it is a migration. Questions whose answers are commonly public (mother's maiden name, birthplace, school name) are left out on purpose.
- Existing v1 users get `NULL` question columns. They set one after updating the app (§7.6).

### 7.4 Rules
- **Normalising answers:** trim, lowercase (`Locale.ROOT`), collapse runs of whitespace into one space, and drop punctuation, so "  Mr. Whiskers " matches "mr whiskers". Then BCrypt (strength 10), the same as passwords. Answers are never logged or returned.
- **Lockout per account:** each wrong answer increments `reset_failed_attempts`. At **5** the account is locked for **24 h** (`reset_locked_until`) and the counter resets. While locked, `/reset` returns `429 RESET_LOCKED` without checking the answer. A successful reset or a normal password login clears the counter.
- **IP limit:** 20 requests per hour per IP across `/password/question` and `/password/reset` (in-memory Caffeine, using the client IP via `forward-headers-strategy`). Over the limit → `429 RESET_RATE_LIMITED`.
- **No enumeration:** for an unknown email, or an account with no question set, `/password/question` returns a **decoy**: the question with index `HMAC-SHA256(email, server key) mod N`. The same email always gets the same decoy, so asking twice gives nothing away. `/reset` for those emails always fails with `WRONG_ANSWER` after a dummy BCrypt check, with a decoy lockout counter in memory so it looks and times like a real account. Login already does the same with `dummyHash`.
- **Sign out other devices (US-16):** `/reset` sets the new password hash and `password_changed_at` (millisecond precision) in one statement. Every token carries the account's `password_changed_at` as a `pwc` claim (epoch millis; absent if never reset). A JWT validator added to `SecurityConfig`'s decoder rejects a token whose `pwc` is missing or older than the stored value, with a per-user Caffeine cache (1 min) to avoid a DB hit per request; a reset clears its user's entry. Comparing `pwc` rather than `iat` matters because `iat` has whole-second precision, so a token issued in the same second as the reset would otherwise survive it. The token returned by `/reset` carries the new value, so it stays valid.
- **Changing the question** (`PUT /me/security-question`) needs the current password, so a borrowed unlocked phone can't be used to plant an answer.
- **New error codes:** `WRONG_ANSWER` (400, body adds `attemptsLeft`), `WRONG_PASSWORD` (400), `RESET_LOCKED` (429, body adds `retryAfterSeconds` and a `Retry-After` header), `RESET_RATE_LIMITED` (429, same).
- **Config:**
  ```yaml
  newspal:
    reset:
      max-attempts: 5
      lockout: P1D
      hourly-per-ip: 20
      answer-min-chars: 3
  ```

### 7.5 Backend code
- `auth/`: `PasswordResetController`, `PasswordResetService`, `SecurityAnswers` (normalise + hash + match), `SecurityQuestionRepository`, `dto/PasswordResetDtos`.
- `RegisterRequest` gains `@NotNull Short questionId` and `@NotBlank @Size(max = 100) String answer`.
- `SecurityConfig`: permit `/api/auth/security-questions` and `/api/auth/password/**`, and add the `password_changed_at` token validator.

### 7.6 Mobile
- **Register page:** a "Security question" dropdown (loaded from `GET /auth/security-questions`) and an "Answer" field, with the helper text "Used to reset your password. Pick something only you know." The answer is ≥ 3 chars and stays visible while typing, so typos are caught.
- **Login page:** a "Forgot password?" `TextButton`, right-aligned under the password field. It pre-fills the email if one was typed.
- Routes (public, added to `authRedirect`'s `publicRoutes`): `/forgot-password` and `/forgot-password/answer`. The email and question live in a `passwordResetControllerProvider` (Riverpod Notifier), not in URLs. Leaving the flow clears it.
  - **Email screen:** email field (`AuthValidators.email`) → "Continue".
  - **Answer screen:** shows the question, then an answer field, new password + confirm (must match, ≥ 8 chars, show/hide toggle) → "Reset password". `WRONG_ANSWER` shows "That answer doesn't match" plus the attempts left. `RESET_LOCKED` shows "Too many attempts. Try again tomorrow." Success → `AuthController.signInWithResult(...)`, then the router goes to Feed or Onboarding, with the snackbar "Password updated".
- **Existing users without a question:** after sign-in, when `GET /me` says `hasSecurityQuestion: false`, the Feed shows a dismissible banner: "Set a security question so you can reset your password" → `/settings/security-question`. Dismissing hides it for the rest of the app session; it comes back on the next launch until a question is set.
- **Settings:** a new "Security question" tile → `/settings/security-question`: question dropdown, answer and current password → "Save".
- `AuthRepository` gains `securityQuestions()`, `resetQuestion(email)`, `resetPassword(email, answer, newPassword)` and `setSecurityQuestion(currentPassword, questionId, answer)`. `register(...)` takes the question and answer.

### 7.7 Later: email OTP
Once there's a domain, add email OTP as the main method and keep the security question as a fallback. That needs a domain + Resend (free plan ~3,000 emails/month; SPF/DKIM/DMARC records) and an `EmailSender` interface. Render's free tier blocks SMTP, so it has to be an HTTP API. The `password_changed_at` sign-out logic above is reused unchanged.

---

## 8. F6 — Search (backend + mobile)

### 8.1 Backend
**Index (`V9__article_search.sql`):**
```sql
ALTER TABLE articles ADD COLUMN search_vector tsvector GENERATED ALWAYS AS (
  setweight(to_tsvector('english', coalesce(title, '')), 'A') ||
  setweight(to_tsvector('english', coalesce(summary, '')), 'B')) STORED;
CREATE INDEX idx_articles_search ON articles USING GIN (search_vector);
```
(`Article` keeps it unmapped, or `insertable=false, updatable=false`, so `ddl-auto: validate` still passes.)

**Endpoint:** `GET /api/search?q={text}&category={slug?}&cursor={opaque?}&size={1..50, default 20}` (JWT required)
- `q`: trimmed, 2–100 chars, else `400 VALIDATION_FAILED`. Parsed with `websearch_to_tsquery('english', :q)`, so users can type `"exact phrase"`, `-word` and `or`. A query that reduces to nothing (only stop-words, e.g. "the") returns an empty page, not an error.
- **Scope (decided 2026-10-24):** all stored articles (all 8 categories, last 7 days), not just followed categories. `category`, if given, must be a known slug (`400` otherwise). It doesn't need to be followed.
- **Order:** newest first, `(published_at, id) DESC`, using the existing `CursorCodec` keyset cursor. Relevance only filters, it doesn't sort: news readers expect recency, and keyset paging stays simple.
- **Response:** the `FeedPage` shape (`items` with the `archived` flag, `nextCursor`; `lastRefreshedAt` omitted). Items render with the existing `ArticleCard`.
- **Abuse guard:** 60 searches/min per user (in-memory). Over that → `429 SEARCH_RATE_LIMITED`.
- No provider calls, so search never spends API quota.

New package `search/`: `SearchController`, `SearchService` (`JdbcClient` query with `search_vector @@ query`, the archive `LEFT JOIN` from `FeedRepository`).

### 8.2 Mobile
- **Entry:** a search `IconButton` in the Feed `AppBar` → `/search` (full-screen route above the bottom nav, like `/article`).
- **Search page:** an autofocused `TextField` in the app bar, with a clear button. Requests are **debounced 400 ms** and fire at ≥ 2 chars or on submit. Stale responses are dropped (only the latest query's result is shown).
- Category chips under the field: "All" + **all** categories from `GET /categories`.
- Infinite scroll with `nextCursor`. The bookmark toggle uses the shared `bookmarksProvider` so state matches the feed.
- States: initial ("Search news from the last 7 days"), loading, empty ("No results for "q""), error with "Try again", and `SEARCH_RATE_LIMITED` as a calm notice.
- Recent searches: the last 5 kept in memory for the session only (no storage).
- New files: `features/search/search_repository.dart`, `search_controller.dart`, `search_page.dart`.

---

## 9. API changes at a glance

| Method & path | Auth | New / changed |
|---|---|---|
| `GET /api/auth/security-questions` | public | New |
| `POST /api/auth/register` | public | Changed: + `questionId`, `answer` |
| `POST /api/auth/password/question` | public | New |
| `POST /api/auth/password/reset` | public | New |
| `GET /api/me` | JWT | Changed: + `hasSecurityQuestion` |
| `PUT /api/me/security-question` | JWT | New |
| `GET /api/search` | JWT | New |
| `POST /api/internal/jobs/run` | `X-Job-Token` | New |
| all JWT endpoints | JWT | Tokens issued before a password reset → `401` |

Error-code table additions: `400 WRONG_ANSWER, WRONG_PASSWORD` · `429 RESET_LOCKED, RESET_RATE_LIMITED, SEARCH_RATE_LIMITED`.

## 10. Migrations
| File | Purpose |
|---|---|
| `V5__freenews_topics.sql` | `provider_query` → topic names |
| `V6__freenews_articles.sql` | `articles.provider_id`; `api_usage.month` → `period` (daily) |
| `V7__job_runs.sql` | Daily-cleanup bookkeeping |
| `V8__security_questions.sql` | `security_questions` (seeded), question/answer/lockout columns and `password_changed_at` on `users` |
| `V9__article_search.sql` | Generated `search_vector` + GIN index |

All are additive or renames; v1 data (users, follows, archives) is kept.

## 11. Configuration & deployment
- **Render env vars:** add `FREENEWS_API_KEY` and `JOB_TOKEN` (`sync: false` in `render.yaml`: the same value must also go into GitHub, so it's generated once and pasted into both). Remove `WEBZ_TOKEN`.
- **GitHub repo settings:** secret `JOB_TOKEN` (same value as Render) and variable `API_BASE_URL` (not secret) for `.github/workflows/jobs.yml`. The workflow also has `workflow_dispatch`, for manual runs.
- **DEPLOY.md:** replace the webz.io section with Free News API signup and the GitHub Actions schedule. Update the architecture diagram.
- **README / SPEC.md:** after release, merge this document into SPEC.md (§1.4, §3.1, §4, §6, §7, §8.2, §9.3, §10–§12).

## 12. Testing
| Layer | What |
|---|---|
| Backend unit | `FreeNewsResponseParser` (list + details fixtures from the OpenAPI examples; null `original_url` skipped; incipit/body fallback); pacing limiter; 429 retry-once + quota-header disable; `SecurityAnswers` normalising (case, spaces, punctuation); decoy question is stable per email |
| Backend web | `/auth/password/*` validation; `/password/question` returns 200 for unknown emails; `/search` validation (length, unknown category, stop-word query → empty page); `/internal/jobs/run` 404 without/with wrong token, 202 with token |
| Backend integration (Testcontainers + fake provider) | Ingestion run inserts new articles, skips known `provider_id`s without calling details, restores claim on list failure, respects the daily bucket. Cleanup runs once per 23 h via `job_runs` and keeps archives. Full reset flow: register with question → question → wrong answer ×5 → `RESET_LOCKED` (also for the right answer) → clock +24 h → right answer resets → old JWT `401`, new JWT works. Unknown email: same responses as a real account. `PUT /me/security-question` needs the current password. v1 user without a question can't reset and `GET /me` says so. Search matches title and summary, honours category filter and cursor, sets `archived` |
| Flutter unit | `PasswordResetController` state machine; search controller debounce and stale-response dropping; repositories with mocked dio |
| Flutter widget | Logout dialog (cancel / confirm); register question dropdown + answer validation; Forgot password link; answer screen wrong-answer and locked messages; new-password mismatch; security-question banner shows only when `hasSecurityQuestion` is false; search empty and results states |
| Manual | Let Render sleep, trigger the workflow by hand, and confirm new rows appear and the Actions log shows `202`. Reset the password on the phone with a security question, then check another signed-in device goes to Login. Watch `X-RateLimit-Remaining-Day` in the logs for the first day |

## 13. Milestones
| # | Deliverable | Done when |
|---|---|---|
| M8 | Free News API provider + daily quota (§4) | Live smoke test fills all 8 categories locally; webz code removed; tests green |
| M9 | 2-hour job, external trigger, daily cleanup (§5, §6) | Workflow wakes the deployed service and new articles land every ~2 h for a day; cleanup ran exactly once that day |
| M10 | Security questions + password reset backend (§7.1–7.5) | Integration flow test passes, including lockout, decoys and signing out old tokens |
| M11 | Search backend (§8.1) | `/search` integration tests pass on Postgres 16 |
| M12 | Mobile: logout dialog, security question (register, settings, banner), forgot password, search (§3, §7.6, §8.2) | All widget tests pass; full flows work on the phone against Render |
| M13 | Release 0.2.0+2 | DEPLOY.md and SPEC.md updated; APK built; tagged `v0.2.0` |

M8 → M9 depend on each other. M10 and M11 are independent of M8/M9 and of each other. M12 needs M10 + M11.

## 14. Decisions (2026-10-24)
1. **Storing articles:** allowed. The docs don't forbid it. We keep title, summary, thumbnail and publisher for 7 days and show the publisher name.
2. **Language/country:** English only (`language=en`), from all countries (no `country` filter).
3. **Password reset:** a security question only (§7), so v0.2 needs no domain or email provider. Resend's free plan (3,000 emails/month) was considered, but it can only email users once a domain is verified, and a domain costs ~US$10/year. Email OTP is deferred until there's a domain (§7.7).
4. **Job trigger:** GitHub Actions (§5.2).
5. **Search scope:** all categories (§8.1).

## 15. Open questions
None right now.
