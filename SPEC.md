# NewsPal — Personalized News App Specification

> Status: v0.2.1 (released) · updated 2026-11-01 · Hobby project
> App name: **NewsPal** (final). App version `0.2.1+3`.
> v0.2 replaced webz.io with Free News API, added a 2-hourly job and daily cleanup, password reset with a security question, search and a logout confirmation. Its planning document, [SPEC-v2.md](SPEC-v2.md), is folded in here and kept only because code comments cite its sections. v0.2.1 fixed bugs found after that release; see [SPEC-v2.1.md](SPEC-v2.1.md).

---

## 1. Overview

NewsPal is a mobile app that shows each user the latest news in the categories they choose, lets them search recent news, and lets them archive articles they want to keep.

### 1.1 Goals
- **G1:** A user picks one or more news categories.
- **G2:** The user sees a feed of the latest news from those categories, refreshed about every 2 hours.
- **G3:** The user can archive (bookmark) articles and view or remove them later.
- **G4:** The app stays within free tiers: Free News API (5,000 requests/day), Render free hosting and Neon free Postgres. Nothing is paid.
- **G5:** A user who forgot their password can reset it without help.
- **G6:** A user can search the last 7 days of news by keyword.

### 1.2 Out of scope
Push notifications, custom topics, social sharing, read/unread tracking, password reset **by email** (needs a domain, §7.7), multiple languages (English only), and an admin UI.

### 1.3 Stack
| Layer | Technology |
|---|---|
| Mobile | Flutter (stable), Dart 3, Riverpod, dio, go_router, cached_network_image, flutter_secure_storage, url_launcher / webview_flutter |
| Backend | Java 21, Spring Boot 4.1: WebMVC, Data JPA + JdbcClient, Security + OAuth2 Resource Server (JWT via Nimbus), Validation, Actuator; Flyway; Caffeine |
| Database | PostgreSQL 16 on **Neon** (free plan, serverless, no expiry); full-text search with `tsvector` |
| News source | **Free News API** (freenewsapi.io), behind a `NewsProvider` abstraction |
| Hosting | Backend: Render Docker Web Service (free). Database: Neon (free). Scheduler: GitHub Actions |

### 1.4 External constraints (verify before changing, as they change)
| Constraint | Value (as of 2026-10) | Impact |
|---|---|---|
| Free News API quota | **5,000 requests/day**, **2 requests/second**, single free plan | Daily call budget (§4.2); calls spaced ≥ 550 ms apart |
| Free News API `/news` | 10 items per page; returns only `uuid`, `title`, `published_at`, `publisher` | Every new article costs **one extra `/details` call** for its URL, image and summary |
| Free News API search | `in_title` only | Not used; search runs on our DB (§8) |
| Free News API headers | `X-RateLimit-Remaining-Day`; `X-RateLimit-Reset-Day` is an ISO timestamp, a rolling 24 h from the first call of the day | A guard on top of our own count (§4.2) |
| Free News API history | Articles go back 60+ days (checked 2026-10-24) | Older news covers 30 days (§5) |
| Free News API terms | The docs don't forbid storing articles | We store title, summary, thumbnail and publisher for 7 days and show the publisher on every card |
| Render free web service | Sleeps after 15 min without traffic; about 1 min to wake; in-process timers don't run while asleep | An **external trigger** wakes it every 2 h (§4.4); the app shows a "waking up" banner |
| Render free filesystem | Erased on spin-down | Keep all state in Postgres |
| Render free outbound SMTP | Blocked | Email would need an HTTP API and a domain (§7.7) |
| Neon free storage | 0.5 GB per project | ≤ ~16 MB of articles plus users and archives |
| Neon free compute | 100 CU-hours / project / month; scales to zero after 5 min idle | The pool lets it sleep (§9.5); waking every 2 h costs ~15 CU-hours/month |
| GitHub Actions schedules | Can start 5–30 min late; paused on public repos with no commits for 60 days | Re-enable from the Actions tab if paused |

---

## 2. User stories & acceptance criteria

| ID | Story | Acceptance criteria |
|---|---|---|
| US-1 | As a visitor I can register with email, password and a security question. | Email is valid and unique; password ≥ 8 chars; a question from the fixed list, shown in full (long ones wrap), and an answer with ≥ 3 letters or digits. Success returns a JWT and the user lands on the category picker. Duplicate email → "Email already registered". |
| US-2 | As a user I can log in and stay logged in. | Valid credentials return a JWT stored in secure storage. The app opens straight to the Feed while the token is valid. An expired or invalid token sends the user to Login. |
| US-3 | As a user I can log out, after confirming. | "Log out" opens "Log out?" / "You'll need to log in again to see your feed." with **Cancel** and **Log out**. Cancel, back or tapping outside does nothing. Log out deletes the token and opens Login. |
| US-4 | As a new user I pick my categories. | At least 1 category must be selected to continue; the choice is saved on the server. |
| US-5 | As a user I can change my categories later. | Settings → Categories shows the current selection; saving updates the feed. |
| US-6 | As a user I see the latest news from my categories. | Feed shows articles from selected categories, newest first, each with image, title, source, relative time and category label. Every category gets new articles about every 2 h. Opening the app shows the newest stored articles without a pull, from cold or from the background; stories that arrive during a refresh appear by themselves, or behind a "New stories" button when scrolled down. Pull-to-refresh reloads. Infinite scroll loads more. |
| US-7 | As a user I can filter the feed to one category. | Chips at the top: "All" plus each selected category. |
| US-8 | As a user I can read an article. | Tapping a card opens the article inside the app (in-app webview). Links to a different site open in the phone's default browser, and the user stays on the article in the app. See §9.3. |
| US-9 | As a user I can load older news. | After the stored articles run out, the feed shows a "Load older news" button for a single category, which fetches older articles (up to ~30 days back). If the quota is used up, it shows "Older news unavailable right now". |
| US-10 | As a user I can archive an article. | A bookmark icon on the card and the article page toggles archive. It stays filled after a refresh. Archiving the same URL twice has no extra effect. |
| US-11 | As a user I can view my archive. | The Archive tab lists archived articles, newest-archived first, even after the original has been deleted from the feed. |
| US-12 | As a user I can remove an archived article. | Swipe-to-delete or tap the bookmark again; an undo snackbar appears for 4 s. |
| US-13 | As a user who forgot my password I can reset it. | Login → "Forgot password?" (email carried over) → the account's question (a question is shown for every email, registered or not) → answer + new password twice → signed in, "Password updated". A wrong answer shows "That answer doesn't match. N attempts left." |
| US-14 | Guessing is limited. | 5 wrong answers lock resets for that account for 24 h: "Too many attempts. Try again tomorrow." Answers ignore case, extra spaces and punctuation. |
| US-15 | As a user I can set or change my security question. | Settings → Security question, with the current password. Questions show in full (long ones wrap). Accounts without one (from v0.1) see a dismissible Feed banner leading there. |
| US-16 | As a user, after a reset, my other devices are signed out. | Any token issued before the reset gets `401` and that device goes to Login. |
| US-17 | As a user I can search news. | A search icon in the Feed app bar opens Search. Typing ≥ 2 chars shows matching articles (title or summary) from all categories, newest first, as feed cards (bookmark works). There's no category filter. "No results for "…"" when nothing matches. |

---

## 3. Categories

### 3.1 Fixed category list
Categories are rows in the `categories` table, seeded by Flyway. The app always loads them from the API and never hardcodes them. `provider_query` holds the **Free News API topic** (all 8 exist in `GET /v1/topics`; `V5__freenews_topics.sql`).

| slug | name | provider_query (topic) |
|---|---|---|
| technology | Technology | `technology` |
| business | Business | `business` |
| sports | Sports | `sports` |
| science | Science | `science` |
| health | Health | `health` |
| entertainment | Entertainment | `entertainment` |
| world | World | `world` |
| politics | Politics | `politics` |

### 3.2 Rules
- Each article belongs to **one** category: the one it was fetched for.
- If a story is fetched again under another category, the existing row is kept (`url_hash` and `provider_id` are unique).
- Adding a category means a new migration `V{n}__add_category_x.sql`. No app release is needed.

---

## 4. News ingestion & retention

### 4.1 Hot path / cold path
```
                 ┌─────────────── hot path (≤ 7 days) ───────────────────┐
Flutter ──JWT──▶ Spring Boot ──▶ PostgreSQL (articles, search index)       │
                     │  ▲                                                  │
GitHub Actions ──▶   │  └── job pass every ~2 h (all categories) ──▶ Free News API
 (every 2 h)         │                                                     │
                     └─ cold path (> 7 days, not stored) ──Caffeine──▶ Free News API
```

### 4.2 Call budget, pacing and guards
Every HTTP call to Free News API (list or details) counts against a bucket in `api_usage`, per **UTC day**, before it is made (atomic SQL, §6.2).

| Bucket | Requests / day | Worst case it covers |
|---|---|---|
| `ingestion` | 3,600 | 12 runs × 8 categories × (≤ 3 list pages + ≤ 30 details) = 3,168 |
| `older` | 1,000 | ~90 "Load older news" pages (1 list + ≤ 10 details each) |
| Margin | 400 | Retries, manual tests, and our UTC-midnight reset vs. the provider's rolling reset |

- **Pacing:** one limiter keeps calls ≥ 550 ms apart across all threads (2 req/s limit).
- **429:** `Too Many Requests` with `retry_after_ms` ≤ 5 s → wait and retry **once**. A quota-exceeded message → the provider pauses itself until `X-RateLimit-Reset-Day` (or the next UTC midnight if missing or implausible).
- **Header guard:** `X-RateLimit-Remaining-Day` < 100 → pause until the reset.
- **401/403** (bad, expired or disabled key): log once at ERROR and disable until restart.
- An empty `FREENEWS_API_KEY` disables fetching without spending quota.

### 4.3 One refresh of a category (`IngestionService`)
1. Claim it: `UPDATE categories SET last_fetched_at = now() WHERE id = ? AND (last_fetched_at IS NULL OR last_fetched_at < now() - refresh-ttl)`. 0 rows → another thread has it, or it's fresh; skip. `refresh-ttl` = 1 h 50 m, just under the job interval.
2. `since` = newest stored `published_at` for the category minus 5 min (none if empty).
3. List `GET /news?topic=…&language=en&order_by=recent&published_after=…`, paging with `cursor`, up to 3 pages. Stop early when a page brings nothing new (refs whose `provider_id` is already stored are dropped).
4. For each new ref, oldest first: `GET /details?uuid=…`, clean (§4.5), insert (`ON CONFLICT DO NOTHING` on `url_hash` or `provider_id`). A failed details call skips only that article; running out of quota stops the batch.
5. A failing **first** list page restores `last_fetched_at` so the next pass retries. A later page failing keeps what was listed.

Details mapping: `original_url` → `url` (**skipped if missing or not http(s)**), `incipit` → `summary` (else the start of `body`), `thumbnail` → `imageUrl` (http(s) only), `publisher` → `source`, `published_at` → `publishedAt`. `body` is not stored.

### 4.4 Job passes and triggers (`JobRunner`)
A **pass** = the daily cleanup if it's due (§4.6), then a refresh of **every** stale category, followed or not (search covers all of them). Only one pass runs at a time (in-memory flag); category claims make overlaps with the feed's lazy refresh harmless.

| Trigger | When | Notes |
|---|---|---|
| **GitHub Actions** (`.github/workflows/jobs.yml`) | Every 2 h (`0 */2 * * *` UTC), plus a manual "Run workflow" | `POST /api/internal/jobs/run` with `X-Job-Token`, `curl --retry 3 --max-time 120`; this also wakes Render. Fails the run on anything but `202`, with a hint for `404` (token mismatch) |
| `ApplicationReadyEvent` | Every boot | So the wake-up itself runs a pass even if the request gave up |
| `@Scheduled(cron = "0 5 */2 * * *", zone = "UTC")` | While awake | A no-op when a trigger just ran |
| Feed, first page | Per request | Async refresh of the user's stale categories (lazy fallback) |

`POST /api/internal/jobs/run`: public in `SecurityConfig` but requires `X-Job-Token` = `JOB_TOKEN`, compared in constant time. Missing/wrong token, or no token configured → `404`. Returns `202 {"started": true}` at once and runs on a background thread, or `{"started": false}` if a pass is already running.

**Free-tier cost:** Render awake ~3 h/day for these (within 750 instance-hours/month); Neon ~15 CU-hours/month; ≤ 3,600 Free News API calls/day.

### 4.5 Cleaning articles (`ArticleCleaner`, `SummaryCleaner`)
Applied to stored and older-news articles alike:
- HTML entities in titles and summaries are decoded (`&rsquo;` → `’`); whitespace is collapsed; the summary is trimmed to 500 chars at a word boundary; non-http(s) images are dropped.
- Scraped page clutter at the **start** of summaries is removed: navigation and glued menus ("Skip to contentAllNewsSport…"), audio/"listen" notices, datelines ("Published 08:00 24 Oct 2026 GMT+1"), relative times, photo credits, some bylines, and a repeated title. It's heuristic and errs on keeping text; on the 236 articles of the first live run it improved 49 summaries and removed no real text.
- A summary that is **only** a notice (cookie wall, paywall pitch) becomes `null`; the article is kept.
- An article with no text at all (videos, listings) is dropped.

### 4.6 Retention (`CleanupService`)
Runs at most once per 23 h (claimed in `job_runs`, §6.1), at the start of a pass, plus a 03:30 UTC check while awake:
```sql
DELETE FROM articles WHERE published_at < now() - interval '7 days';  -- newspal.news.retention
DELETE FROM articles a USING (
  SELECT id, row_number() OVER (PARTITION BY category_id ORDER BY published_at DESC, id DESC) rn
  FROM articles) r
WHERE a.id = r.id AND r.rn > 1000;                                    -- newspal.news.max-per-category
```
Archived articles live in a separate table and are **never** affected. Search reads `articles`, so it covers the same 7 days.

**Storage estimate:** 8 categories × ≤ 1,000 rows × ~2 KB ≈ **16 MB** at most.

### 4.7 Configuration (`application.yml`, overridable by env)
```yaml
newspal:
  news:
    refresh-ttl: PT1H50M
    retention: P7D
    max-per-category: 1000
    summary-max-chars: 500
    max-list-pages: 3
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
    country:                        # empty = English news from all countries
    timeout: PT10S
    min-interval: PT0.55S
    min-remaining: 100
  reset:
    max-attempts: 5
    lockout: P1D
    hourly-per-ip: 20
    answer-min-chars: 3
  jobs:
    token: ${JOB_TOKEN:}            # empty = trigger endpoint disabled
    cleanup-every: PT23H
  jwt:
    secret: ${JWT_SECRET}
    ttl: P7D
```

---

## 5. Older news (cold path)

- `GET /api/feed/older?category={slug}&cursor={opaque}`
  - `category` is required and must be one the user follows (`400` otherwise).
  - Without `cursor`: articles published **before** `now − retention` (rounded down to the hour) and not before `now − 30 days` (`published_before` / `published_after`), newest first.
  - With `cursor`: continues where the previous page ended. Cursors handed to the app are random ids mapped server-side to Free News API's `next_cursor` for `older.cache-ttl` (1 h). An unknown or expired id, or one used with another category, returns `400 BAD_CURSOR` and the app starts that list again.
- A page costs 1 list call + 1 details call per article. After the list call, the page's details are reserved from the `older` bucket in one go; if they don't fit → `429 QUOTA_EXHAUSTED` without calling details, so a page is never served half-fetched.
- Results are **not stored**. Each page is cached in Caffeine for 1 h, keyed by (category, hour) for first pages and by cursor id after that, so everyone asking within the hour shares the calls. Cache hits don't count against quotas. The `archived` flag is worked out per user on every response.
- Limits: per user 10 cache-missing lookups per UTC day (in memory) → `429 OLDER_RATE_LIMITED`; the global `older` bucket, a paused provider or no key → `429 QUOTA_EXHAUSTED`. A provider failure → `502 PROVIDER_ERROR`; failures are not cached.
- Older articles have no DB `id`, so archiving them sends the full article details (§7.5).
- "All" view: older news is loaded one category at a time; the UI asks the user to pick a chip first.
- A page takes about 6 s (11 calls spaced 0.55 s apart).

---

## 6. Data model

### 6.1 Tables (Flyway `V1`…`V9`)
```sql
CREATE TABLE users (
  id                    BIGSERIAL PRIMARY KEY,
  email                 VARCHAR(254) NOT NULL UNIQUE,
  password_hash         VARCHAR(100) NOT NULL,
  created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
  security_question_id  SMALLINT REFERENCES security_questions(id),  -- V8; NULL for v0.1 accounts
  security_answer_hash  VARCHAR(100),                                 -- BCrypt of the normalised answer
  reset_failed_attempts SMALLINT NOT NULL DEFAULT 0,
  reset_locked_until    TIMESTAMPTZ,
  password_changed_at   TIMESTAMPTZ                                   -- tokens carrying an older value are rejected
);

CREATE TABLE security_questions (              -- V8, 8 rows seeded
  id          SMALLSERIAL  PRIMARY KEY,
  text        VARCHAR(120) NOT NULL UNIQUE,
  sort_order  SMALLINT     NOT NULL DEFAULT 0
);

CREATE TABLE categories (
  id              SMALLSERIAL PRIMARY KEY,
  slug            VARCHAR(40)  NOT NULL UNIQUE,
  name            VARCHAR(60)  NOT NULL,
  provider_query  TEXT         NOT NULL,     -- Free News API topic
  sort_order      SMALLINT     NOT NULL DEFAULT 0,
  last_fetched_at TIMESTAMPTZ
);

CREATE TABLE user_categories (
  user_id     BIGINT   NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  category_id SMALLINT NOT NULL REFERENCES categories(id) ON DELETE CASCADE,
  PRIMARY KEY (user_id, category_id)
);

CREATE TABLE articles (
  id            BIGSERIAL PRIMARY KEY,
  url_hash      CHAR(64)     NOT NULL UNIQUE,
  url           TEXT         NOT NULL,
  title         TEXT         NOT NULL,
  summary       VARCHAR(500),
  image_url     TEXT,
  source        VARCHAR(200),
  published_at  TIMESTAMPTZ  NOT NULL,
  category_id   SMALLINT     NOT NULL REFERENCES categories(id),
  fetched_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
  provider_id   VARCHAR(64),                 -- V6; unique when not null
  search_vector tsvector GENERATED ALWAYS AS (   -- V9
    setweight(to_tsvector('english', coalesce(title, '')), 'A') ||
    setweight(to_tsvector('english', coalesce(summary, '')), 'B')) STORED
);
-- indexes: (category_id, published_at DESC, id DESC), (published_at DESC, id DESC),
--          unique (provider_id) WHERE provider_id IS NOT NULL, GIN (search_vector)

CREATE TABLE archived_articles (
  id            BIGSERIAL PRIMARY KEY,
  user_id       BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  url_hash      CHAR(64)     NOT NULL,
  url           TEXT         NOT NULL,
  title         TEXT         NOT NULL,
  summary       VARCHAR(500),
  image_url     TEXT,
  source        VARCHAR(200),
  published_at  TIMESTAMPTZ,
  category      VARCHAR(60),               -- slug (V4)
  archived_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
  UNIQUE (user_id, url_hash)
);

CREATE TABLE api_usage (
  period  VARCHAR(10) NOT NULL,          -- UTC day, '2026-10-24' (V6; was month)
  bucket  VARCHAR(20) NOT NULL,          -- 'ingestion' | 'older'
  calls   INT         NOT NULL DEFAULT 0,
  PRIMARY KEY (period, bucket)
);

CREATE TABLE job_runs (                  -- V7
  name         VARCHAR(40) PRIMARY KEY,  -- 'cleanup'
  last_run_at  TIMESTAMPTZ NOT NULL
);
```
Migrations: V1 tables, V2 category seed, V3 webz.io query tuning (historical), V4 archive slug, V5 topics, V6 provider ids + daily quota, V7 job runs, V8 security questions, V9 search index.

### 6.2 Atomic checks
```sql
-- Quota: take n calls, all or none
INSERT INTO api_usage (period, bucket, calls) VALUES (:period, :bucket, :n)
ON CONFLICT (period, bucket) DO UPDATE SET calls = api_usage.calls + :n
WHERE api_usage.calls + :n <= :limit
RETURNING calls;

-- Daily job claim
INSERT INTO job_runs (name, last_run_at) VALUES (:name, :now)
ON CONFLICT (name) DO UPDATE SET last_run_at = :now
WHERE job_runs.last_run_at < :dueBefore
RETURNING name;
```
No row returned → over the limit / not due → skip.

---

## 7. REST API

Base path `/api`. JSON only. Public: `POST /auth/register`, `POST /auth/login`, `GET /auth/security-questions`, `POST /auth/password/question`, `POST /auth/password/reset`, `GET /categories`, `GET /actuator/health`, and `POST /internal/jobs/run` (own token). Everything else needs `Authorization: Bearer <jwt>`.

### 7.1 Error format
```json
{ "code": "VALIDATION_FAILED", "message": "Request is invalid",
  "fieldErrors": [{ "field": "email", "message": "must be a valid email" }] }
```
Some errors add fields at the top level (`attemptsLeft`, `retryAfterSeconds`); those with `retryAfterSeconds` also send a `Retry-After` header.

| HTTP | code |
|---|---|
| 400 | `VALIDATION_FAILED`, `BAD_CURSOR`, `WRONG_ANSWER` (+`attemptsLeft`), `WRONG_PASSWORD` |
| 401 | `UNAUTHORIZED`, `BAD_CREDENTIALS` |
| 404 | `NOT_FOUND` |
| 409 | `EMAIL_TAKEN` |
| 429 | `OLDER_RATE_LIMITED`, `QUOTA_EXHAUSTED`, `RESET_LOCKED`, `RESET_RATE_LIMITED`, `SEARCH_RATE_LIMITED` |
| 502 | `PROVIDER_ERROR` |

### 7.2 Auth
| Method & path | Body | Response |
|---|---|---|
| `POST /auth/register` | `{email, password, questionId?, answer?}` | `201 {token, expiresAt, user:{id, email, hasSecurityQuestion}}`. Question and answer go together; **optional on the API** so v0.1 apps can still register; the v0.2 app always sends them |
| `POST /auth/login` | `{email, password}` | `200 {token, expiresAt, user}`; also clears wrong reset answers |
| `GET /me` | — | `200 {id, email, hasSecurityQuestion}`; used by the splash screen and the Feed banner |
| `GET /auth/security-questions` | — | `200 [{id, text}]` |
| `PUT /me/security-question` | `{currentPassword, questionId, answer}` | `204`; `400 WRONG_PASSWORD` (not 401: the app signs out on any 401 from a signed-in call) |
| `POST /auth/password/question` | `{email}` | `200 {questionId, text}`, always (a stable decoy for unknown emails and accounts without a question) |
| `POST /auth/password/reset` | `{email, answer, newPassword}` | `200 {token, expiresAt, user}`; `400 WRONG_ANSWER {attemptsLeft}`; `429 RESET_LOCKED {retryAfterSeconds}` |

JWT: HS256; claims `iss` = `newspal`, `sub` = user id, `exp` = 7 days, and `pwc` = the account's `password_changed_at` in epoch millis when it has ever reset (§7.6). No refresh tokens; the user logs in again after expiry.

### 7.3 Categories
| Method & path | Response |
|---|---|
| `GET /categories` | `200 [{slug, name}]`, ordered by `sort_order` |
| `GET /me/categories` | `200 ["technology","sports"]` |
| `PUT /me/categories` | body `["technology","sports"]` (≥ 1, known slugs) → `204` |

### 7.4 Feed and search
`GET /feed?category={slug?}&cursor={opaque?}&size={1..50, default 20}`
```json
{
  "items": [{
    "id": 123, "url": "...", "title": "...", "summary": "...",
    "imageUrl": "...", "source": "The Guardian", "publishedAt": "2026-10-24T08:00:00Z",
    "category": "technology", "archived": false
  }],
  "nextCursor": "MjAyNi0xMC0xN1QwODowMDowMFp8MTIz",
  "lastRefreshedAt": "2026-10-24T06:00:00Z",
  "refreshing": false
}
```
- **Without `category`:** articles from all of the user's categories. **With `category`:** must be followed, otherwise `400`.
- **Cursor:** base64 of `publishedAt|id` (keyset: `WHERE (published_at, id) < (:p, :i)`). `nextCursor: null` → no more stored articles; the app offers "Load older news".
- **`archived`:** from a `LEFT JOIN archived_articles ON url_hash AND user_id`.
- **`refreshing`:** first page only: true while any selected category is queued or being refreshed, by the feed or a job pass. Tracked in memory, as there's one backend instance. Omitted from later pages, `/feed/older` and `/search`.

`GET /feed/older?category={slug}&cursor={opaque?}` returns the same shape with `id: null` and no `lastRefreshedAt` (§5).

`GET /search?q={text}&category={slug?}&cursor={opaque?}&size={1..50}` returns the same shape without `lastRefreshedAt`:
- `q`: trimmed, 2–100 chars. Parsed with `websearch_to_tsquery('english', …)`, so `"exact phrase"`, `-word` and `or` work and words are stemmed ("launches" ≈ "launch"). The last plain word also matches as a **prefix** (`to_tsquery('english', 'word:*')`), so results appear while typing ("tesl" → Tesla); not for quoted, negated or post-"or" words. Stop words alone ("the") → empty page.
- Covers **all** categories; `category` may be any known slug (`400` if unknown), followed or not. The app stopped sending it in v0.2.1; it stays for older versions.
- Newest first with the feed's keyset cursor. Never calls the provider.
- 60 searches per user per minute → `429 SEARCH_RATE_LIMITED`.

### 7.5 Archive
| Method & path | Body | Response |
|---|---|---|
| `GET /archive?cursor=&size=` | | `200 {items:[{id,url,title,summary,imageUrl,source,publishedAt,category,archivedAt}], nextCursor}` |
| `POST /archive` | `{url, title, summary?, imageUrl?, source?, publishedAt?, category?}` | `201 {item}`, or `200 {item}` if already archived (idempotent) |
| `DELETE /archive/{id}` | | `204`; `404` if the item isn't the user's |
| `DELETE /archive?url=` | | `204` (idempotent; matches the normalized URL). Used by the feed's bookmark toggle. `400` for a non-http(s) URL |

`category` is a slug, `null` if unknown. Summaries are whitespace-flattened and trimmed to 500 chars; a non-http(s) `imageUrl` is dropped. The server recomputes `url_hash` and never trusts a client hash.

### 7.6 Password reset rules
- **Answers** are normalised (lowercase, punctuation and symbols removed, whitespace collapsed: "  Mr. Whiskers " = "mr whiskers") and stored only as BCrypt (strength 10). Never logged or returned.
- **Lockout:** each wrong answer counts (atomic SQL); the 5th locks resets for that account for 24 h (`RESET_LOCKED`, even for the right answer). A successful reset or a normal login clears the count.
- **Per IP:** 20 requests per hour across `/password/question` and `/password/reset` (in memory, client IP via `forward-headers-strategy`) → `RESET_RATE_LIMITED`.
- **No enumeration:** unknown emails and accounts without a question get a decoy question, `HMAC-SHA256(email, server key) mod N`, the same every time; their resets always fail after a dummy BCrypt check, with an in-memory decoy lockout, so responses and timing match a real account.
- **Signing out other devices:** a reset stores the new hash and `password_changed_at` (millisecond precision). The JWT decoder has a validator that rejects tokens whose `pwc` claim is missing or older than the stored value (cached per user for 1 min; a reset clears its entry). `iat` alone isn't enough: it has whole-second precision, so a token from the same second as the reset would survive it.
- **Changing the question** needs the current password, so a borrowed unlocked phone can't plant an answer.
- **Why a security question, not email (decided 2026-10-24):** email needs a verified domain (~US$10/year; even Resend's free plan requires one) and Render's free tier blocks SMTP. A security question is weaker (answers can be guessed or looked up, and forgotten); the rules above limit the damage.

### 7.7 Later: email OTP
With a domain, add an emailed code as the main method and keep the question as a fallback: an `EmailSender` over an HTTP API (e.g. Resend, ~3,000 emails/month free, SPF/DKIM/DMARC set up). The `pwc` sign-out logic is reused unchanged.

### 7.8 Ops
`GET /actuator/health` returns `{status:"UP"}` for the Render health check (it doesn't touch the DB, so Neon can sleep). All other actuator endpoints are disabled. `POST /internal/jobs/run` is §4.4.

---

## 8. Backend design

### 8.1 Package layout (`com.newspal`)
```
NewspalApplication.java
config/     SecurityConfig, JwtProperties, NewsProperties, ResetProperties, JobProperties, IngestionConfig, ClockConfig
common/     ApiError, ApiException (+details), GlobalExceptionHandler, ErrorCodes, CursorCodec, UrlUtils, CurrentUser
auth/       AuthController, AuthService, JwtService, PasswordResetController, PasswordResetService,
            PasswordChangeValidator, SecurityAnswers, SecurityQuestions, UserSecurity, dto/
user/       User, UserRepository
category/   Category, CategoryRepository, CategoryController, UserCategoryService
feed/       FeedController, FeedService, FeedRepository, OlderNewsService, dto/
search/     SearchController, SearchService, SearchRepository, SearchQuery
article/    ArticleStore, ArticleCleaner, SummaryCleaner
archive/    ArchiveController, ArchiveService, ArchiveRepository, dto/
ingestion/  IngestionService, JobRunner, JobRuns, JobController, IngestionScheduler, CleanupService, QuotaService
provider/   NewsProvider, ProviderRef, ProviderRefPage, ProviderArticle, ProviderException,
            freenews/FreeNewsProvider, FreeNewsProperties, FreeNewsResponseParser
```

### 8.2 `NewsProvider` interface
Each method is exactly **one** provider call, so every call can be counted against the quota; callers do the paging.
```java
public interface NewsProvider {
    boolean isEnabled();   // key present and not paused
    ProviderRefPage listLatest(String topic, Instant since, String cursor);
    ProviderRefPage listOlder(String topic, Instant before, Instant notBefore, String cursor);
    Optional<ProviderArticle> details(String providerId);   // empty if gone (404) or no usable URL
}
public record ProviderRef(String providerId, String title, Instant publishedAt, String publisher) {}
public record ProviderRefPage(List<ProviderRef> items, String nextCursor) {}
public record ProviderArticle(String providerId, String url, String title, String summary,
                              String imageUrl, String source, Instant publishedAt) {}
```
`FreeNewsProvider` uses `RestClient` (10 s timeouts) with the `x-api-key` header; error messages never contain the URL or key. Only cursor pagination is used (offset is capped at 10,000 by the API).

### 8.3 Security
- BCrypt (strength 10) for passwords and security answers.
- Stateless sessions; CSRF off (JWT only). CORS only when `newspal.cors.allowed-origin-patterns` is set (the `dev` profile, for Flutter web).
- The JWT secret is ≥ 32 bytes from `JWT_SECRET`; the app fails to start without it.
- Login failures return a generic `BAD_CREDENTIALS`; reset flows don't reveal registered emails (§7.6).
- The Free News API key and `JOB_TOKEN` are never logged or sent to clients.

### 8.4 Background work
- `ingestionExecutor` (core 2, max 4, queue 20) runs the feed's lazy refreshes; a full queue skips a refresh rather than failing a request, and that category stops counting as refreshing.
- Job passes run on their own (virtual) thread, so a full pool can't drop one and leave it marked running.

---

## 9. Mobile app design

### 9.1 Structure (`lib/`)
```
main.dart
app/        app.dart (MaterialApp + "waking server" banner), router.dart (go_router + auth redirects),
            theme.dart, home_shell.dart (Feed | Archive | Settings), splash_page.dart
core/       api_client.dart (dio, auth header, 401 → sign out), api_error.dart (+details), token_store.dart,
            env.dart, link_rules.dart, time_format.dart
features/
  auth/        auth_controller.dart, auth_repository.dart, login_page.dart, register_page.dart, auth_form.dart,
               security_question_fields.dart, security_question_banner.dart,
               password_reset_controller.dart, forgot_password_pages.dart
  categories/  category_repository.dart, category_picker_page.dart
  feed/        article.dart, feed_repository.dart, feed_controller.dart, feed_page.dart, article_card.dart, article_page.dart
  search/      search_repository.dart, search_controller.dart (NewsSearchController), search_page.dart
  archive/     archive_repository.dart, archive_controller.dart, archive_page.dart, bookmarks.dart
  settings/    settings_page.dart (+ LogoutDialog), security_question_page.dart
widgets/       brand_wordmark.dart, coming_soon.dart
```

### 9.1a Theme (brand colors)
Brand colors: **lime `#B6FF2E`** (accent) and **charcoal `#23262F`** (base). The app is **dark-first**, with charcoal as the background and lime as the accent. A light theme follows the system setting.

| Role (Material 3 `ColorScheme`) | Dark (default) | Light |
|---|---|---|
| `primary` (buttons, selected chip, bookmark-on, progress) | `#B6FF2E` | `#B6FF2E` |
| `onPrimary` (text/icons on lime) | `#23262F` | `#23262F` |
| `surface` / scaffold background | `#23262F` | `#F7F8F4` |
| `surfaceContainer` (cards, bottom bar) | `#2E323C` | `#FFFFFF` |
| `onSurface` (body text) | `#F2F4F7` | `#23262F` |
| `onSurfaceVariant` (source, time, hints) | `#A3A9B6` | `#5B6170` |
| `outline` (dividers, unselected chip border) | `#3A3F4B` | `#D9DCE3` |
| `error` | `#FF6B6B` | `#C62828` |

Rules:
- **Never use lime as text or as thin icons on a light background.** In light mode, lime is only a fill, with charcoal content on top.
- Text on lime is always charcoal `#23262F`.
- Filled buttons are full width in forms (theme `minimumSize`); dialogs override it (the logout dialog).
- Defined once in `app/theme.dart`, via `ColorScheme(...)` with these exact values (not `fromSeed`).

**Logo ("folded page"):** a lime newspaper sheet with a darker-lime folded corner (`#7FB81C`) and three charcoal headline lines, next to the wordmark "News" + "Pal". Drawn in code (`widgets/brand_wordmark.dart`); launcher icons are generated from the same painter (`tool/brand_icons_test.dart` → `assets/branding/*.png` → `flutter_launcher_icons`).

### 9.2 Navigation
```
/splash → (token?) → /feed            : else → /login
/login ↔ /register → /onboarding (if user has no categories) → /feed
/login → /forgot-password → /forgot-password/answer → (signed in) /feed
Bottom nav: Feed | Archive | Settings
/article (extra: Article), /search          full screen, above the bottom nav
/settings/categories, /settings/security-question
```
Signed-out users can only reach login, register and the two forgot-password routes; signed-in users are sent from those to the Feed.

### 9.3 Behaviour
- **dio interceptors:** one adds the `Bearer` token. A `401` on any non-`/api/auth/` call signs out, and the router goes to Login. Timeout is 70 s, to outlast a Render cold start.
- **Cold start UX:** a request taking more than 3 s shows "Waking up the server… this can take up to a minute."
- **Feed:** each chip has its own paged list (`feedControllerProvider(category)`). Stored pages first; at the end, "Load older news" switches that list to the older-news endpoint (single category only). `QUOTA_EXHAUSTED` and `OLDER_RATE_LIMITED` become calm notices; other failures keep the list and offer "Try again". Lists reset when the signed-in user changes.
- **Fresh feed without a pull:** `silentRefresh` reloads the first page with no spinner, on `AppLifecycleListener.onResume` and when returning to a chip, unless that list loaded under 30 s ago. While `refreshing` is true it polls every 10 s, at most 12 times, stopping on an error or when the list leaves the screen. New stories replace the list near the top (under 100 px); further down they wait in `FeedState.pending` behind a "New stories" button. Failures change nothing.
- **Security question banner:** shown on the Feed when `GET /me` (or the login/reset response) says `hasSecurityQuestion: false`. **Not now** hides it until the next launch; **Set it** opens Settings → Security question.
- **Register:** the question dropdown is loaded from the API (with a retry if it fails), and long questions wrap in the list and once selected (also in Settings); the answer needs ≥ 3 letters or digits and stays visible while typing.
- **Settings → Security question:** question, answer and current password; `WRONG_PASSWORD` shows on the password field and doesn't sign out.
- **Forgot password:** the email typed on Login is carried over; the email and question live in `passwordResetControllerProvider`, never in URLs; opening the answer screen without step 1 goes back to it. `WRONG_ANSWER` shows the attempts left; `RESET_LOCKED` / `RESET_RATE_LIMITED` show a message and disable the button; success signs in via `AuthController.signInWith` with a "Password updated" snackbar.
- **Logout:** `LogoutDialog` (`Cancel` / `Log out`); only `true` signs out.
- **Search:** debounced 400 ms after typing stops, at ≥ 2 chars or on submit; a generation counter drops answers to older queries. Results reuse `ArticleCard` (bookmark via `bookmarksProvider`) with infinite scroll. States: initial hint and recent searches, loading bar, "No results for "…"", error with "Try again", `SEARCH_RATE_LIMITED` as a calm notice that keeps results. Recent searches: the last 5 **submitted or opened**, in memory only.
- **Archive toggle:** optimistic; a shared per-URL override (`bookmarksProvider`) keeps the feed, search, the article screen and the archive in step. Taps while a request is in flight are ignored.
- **Archive list:** newest-archived first, paged. Swipe left or tap the filled bookmark to remove (`DELETE /archive/{id}`); a 4 s "Undo" re-archives it at the same position.
- **Empty states:** "No news yet" (feed), "Nothing archived yet" (archive).
- **Article page (`webview_flutter` + `url_launcher`):** loads the article URL with JavaScript, a progress bar and a bookmark action. Same-site links (same registrable domain, `core/link_rules.dart`) stay in the webview; other sites and non-http(s) links open externally. Redirects during the first load stay in the webview. A load failure offers "Retry" and "Open in browser". Android back goes back in the webview's history first. **On web** (development only) a reading card with "Read on <source>" is shown instead, since news sites refuse iframes.
- **API base URL:** `--dart-define=API_BASE_URL=...`.

### 9.4 Deployment (Render)
**Backend Dockerfile** (multi-stage):
```dockerfile
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN ./mvnw -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/target/newspal-*.jar app.jar
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC"
EXPOSE 8080
ENTRYPOINT ["sh","-c","java $JAVA_OPTS -Dserver.port=${PORT:-8080} -jar app.jar"]
```
- **Render Web Service** (`render.yaml` blueprint): Docker, Free, Singapore (same region as Neon), health check `/actuator/health`, auto-deploy on changes under `backend/`.
- **Env vars:** `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, `JWT_SECRET` (generated), `FREENEWS_API_KEY`, `JOB_TOKEN`.
- **GitHub Actions:** repository secret `JOB_TOKEN` (same value as Render) and variable `API_BASE_URL`, for `.github/workflows/jobs.yml` (DEPLOY.md §6).
- **Local dev:** `docker-compose.yml` with `postgres:16`, the backend with the `dev` profile (`run-dev.cmd`: local JWT secret, CORS for `http://localhost:*`). The app runs in the browser (`mobile/run-web.cmd`, http://localhost:5005) or on an emulator against `http://10.0.2.2:8080`. The same Flyway migrations run locally and on Neon.
- **Android release:** `flutter build apk --release --dart-define=API_BASE_URL=https://newspal-api.onrender.com`, signed with the debug key (fine for sideloading; the Play Store would need a real key).

### 9.5 Database on Neon
**Setup**
1. Create a Neon project (free plan) named `newspal`, Postgres 16, in the region nearest the Render service.
2. Use the default branch and a database `newspal`.
3. Use the **direct** (non-pooled) connection string, converted to JDBC; Hikari pools connections and Flyway works best on a direct connection.
   ```
   SPRING_DATASOURCE_URL=jdbc:postgresql://<endpoint>.<region>.aws.neon.tech/newspal?sslmode=require
   SPRING_DATASOURCE_USERNAME=<neon role>
   SPRING_DATASOURCE_PASSWORD=<neon password>
   ```
4. Flyway creates and migrates the tables on startup.

**Let Neon sleep**
```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 5
      minimum-idle: 0          # don't hold idle connections open
      idle-timeout: 60000
      max-lifetime: 240000     # < Neon's 5 min suspend window
      keepalive-time: 0
      connection-timeout: 15000 # allow time for Neon to wake up
management:
  health:
    db:
      enabled: false           # Render's health check must not wake the DB
```
The DB wakes for job passes (every 2 h, a few minutes each) and while people use the app.

**Backups (optional)**
```bash
pg_dump --no-owner -Fc -t users -t user_categories -t archived_articles -t security_questions "$NEON_URL" > newspal-backup.dump
```
`articles` refills automatically within a day.

---

## 10. Testing

| Layer | What |
|---|---|
| Backend unit | `UrlUtils`, `CursorCodec`, `JwtService`, `ArticleCleaner` (entities), `SummaryCleaner` (45 real-world cases incl. text that must stay), `FreeNewsResponseParser` and `FreeNewsProvider` (encoded params, 404, 429 retry-once, quota pause, low-remaining pause, rejected key, reset-time formats; MockRestServiceServer), `SecurityAnswers`, `SearchQuery` |
| Backend integration (Testcontainers Postgres + fake provider) | Ingestion (call counting, provider-id skip, paging limits, failures, quota mid-refresh), cleanup and `job_runs` window, job trigger (404s, one pass at a time), feed and older news (server-side cursors, shared cache, reserved details quota), archive, auth, password reset (full flow, lockout, decoys, IP limit, old tokens rejected incl. same second), search (stemming, prefix, phrase/exclusion/or, category, stop words, paging, archived flag, rate limit) |
| Flutter unit | Auth controller, feed and archive controllers, search controller (debounce, stale answers, recent searches, calm notice), link rules, time formatting, API errors |
| Flutter widget | App flow (register with question, onboarding, logout with confirm), account flows (logout cancel/outside tap, forgot password incl. lockout and mismatch, security question banner and Settings page), feed and archive pages, search page |
| Manual | Live run against Free News API (all categories fill, call count matches the API's); browser click-through of the v0.2 screens against the local backend; GitHub Actions run returning `202`; app on a phone against Render |

Totals at v0.2.1: 199 backend tests, 90 Flutter tests.

---

## 11. Milestones

| # | Deliverable | Status |
|---|---|---|
| M1–M7 | v0.1: backend, ingestion, feed/older/archive APIs, Flutter auth/feed/archive, Neon + Render deploy | Done |
| M8 | Free News API provider, daily per-call quota, summary cleanup | Done (PR #1) |
| M9 | Job pass every 2 h, GitHub Actions trigger, daily cleanup | Done (PR #2) |
| M10 | Security questions and password reset (backend) | Done (PR #3) |
| M11 | Search (backend) | Done (PR #4) |
| M12 | App: logout dialog, security question, forgot password, search | Done (PR #5) |
| M13 | Release 0.2.0+2: docs, APK, tag `v0.2.0` | Done (PR #6) |
| M14 | v0.2.1: deploy the missing backend versions, workflow check | Done |
| M15 | Remove Search chips, wrap security questions | Done (PR #7) |
| M16–M17 | Latest stored news without a pull | Done (PR #7) |
| M18 | Release 0.2.1+3: docs, APK, tag `v0.2.1` | This release |

---

## 12. Traceability

| Story | Endpoint(s) | Screen |
|---|---|---|
| US-1 | `GET /auth/security-questions`, `POST /auth/register` | Register |
| US-2 | `POST /auth/login`, `GET /me` | Login, Splash |
| US-3 | — (local) | Settings (logout dialog) |
| US-4 | `GET /categories`, `PUT /me/categories` | Category picker |
| US-5 | `GET /me/categories`, `PUT /me/categories` | Settings → Categories |
| US-6 | `GET /feed`; job trigger `POST /internal/jobs/run` | Feed |
| US-7 | `GET /feed?category=` | Feed (chips) |
| US-8 | — (opens URL) | Article |
| US-9 | `GET /feed/older` | Feed |
| US-10 | `POST /archive`, `DELETE /archive?url=` | Feed card, Search, Article |
| US-11 | `GET /archive` | Archive |
| US-12 | `DELETE /archive/{id}`, `POST /archive` (undo) | Archive |
| US-13 | `POST /auth/password/question`, `POST /auth/password/reset` | Forgot password (2 screens) |
| US-14 | `POST /auth/password/reset` (`WRONG_ANSWER`, `RESET_LOCKED`) | Forgot password |
| US-15 | `PUT /me/security-question`, `GET /me` | Settings → Security question, Feed banner |
| US-16 | all JWT endpoints (`pwc` check) | any → Login |
| US-17 | `GET /search` | Search |

---

## 13. Decisions and open questions
1. **App name:** NewsPal.
2. **Article opening:** in the app; other sites in the default browser (§9.3).
3. **News source:** Free News API (2026-10-24), replacing webz.io, whose credit allowed only ~83 calls/month.
4. **Storing articles:** allowed (the docs don't forbid it); 7 days, publisher shown.
5. **Language/country:** English, all countries.
6. **Job trigger:** GitHub Actions every 2 h.
7. **Password reset:** security question; email OTP once there's a domain (§7.7).
8. **Search scope:** all categories, last 7 days.
9. **Database host:** Neon free plan.

Open:
- After switching accounts, the Feed chips can briefly show the previous account's categories until they reload (`followedCategoriesProvider` keeps its old value while refreshing). Small, not yet fixed.
- When the installed base has v0.2, the API could require a security question at registration.
