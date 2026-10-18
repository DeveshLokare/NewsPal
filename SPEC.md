# NewsPal — Personalized News App Specification

> Status: Draft v1 · 2026-10-17 · Hobby project
> App name: **NewsPal** (final).

---

## 1. Overview

NewsPal is a mobile app that shows each user the latest news in the categories they choose and lets them archive articles they want to keep.

### 1.1 Goals
- **G1:** A user picks one or more news categories.
- **G2:** The user sees a feed of the latest news from those categories.
- **G3:** The user can archive (bookmark) articles and view or remove them later.
- **G4:** The app stays within free tiers: webz.io News API Lite and Render free hosting.

### 1.2 Out of scope (MVP)
Push notifications, free-text keyword search, custom topics, social sharing, read/unread tracking, password reset by email, multiple languages (English only), and an admin UI.

### 1.3 Stack
| Layer | Technology |
|---|---|
| Mobile | Flutter (stable), Dart 3, Riverpod, dio, go_router, cached_network_image, flutter_secure_storage, url_launcher / webview_flutter |
| Backend | Java 21, Spring Boot 4.1: WebMVC, Data JPA, Security + OAuth2 Resource Server (JWT via Nimbus), Validation, Actuator; Flyway; Caffeine |
| Database | PostgreSQL on **Neon** (free plan, serverless, no expiry) |
| News source | webz.io News API Lite, used through a `NewsProvider` abstraction |
| Hosting | Backend: Render Docker Web Service (free). Database: Neon (free) |

### 1.4 External constraints (verify before building, as they change)
| Constraint | Value (as of 2026-10) | Impact |
|---|---|---|
| webz.io `/api/news` pricing | ~0.005 credit per post (0.06 per 10-post call); credit refills monthly (~5.00 assumed) | ~83 calls/month; drives the refresh interval (§4.2) |
| webz.io rate limit | HTTP 429 for back-to-back calls | Calls are spaced ≥ 2 s apart (`min-interval`) |
| webz.io history | ~30 days back (`allowNewsHistory` needed beyond that) | Older news is limited to 30 days (§5) |
| Render free web service | Sleeps after 15 min without traffic; about 1 min to wake | No reliable cron, so refresh is lazy (§4.3); slow first request |
| Render free filesystem | Erased on spin-down | Keep all state in Postgres |
| Neon free storage | 0.5 GB per project | Our usage is ~1.5 MB of articles plus users and archives, so this is not a concern |
| Neon free compute | 100 CU-hours / project / month; scales to zero after 5 min idle | The DB sleeps when the app is idle, so the connection pool must let it sleep (§9.5) |
| Neon cold start | The first query after sleeping takes a moment longer while the compute resumes | Hidden behind Render's own cold start |

---

## 2. User stories & acceptance criteria

| ID | Story | Acceptance criteria |
|---|---|---|
| US-1 | As a visitor I can register with email and password. | Email is valid and unique; password ≥ 8 chars; success returns a JWT and the user lands on the category picker. Duplicate email → "Email already registered". |
| US-2 | As a user I can log in and stay logged in. | Valid credentials return a JWT stored in secure storage. The app opens straight to the Feed while the token is valid. An expired or invalid token sends the user to Login. |
| US-3 | As a user I can log out. | Token is deleted locally and the app returns to Login. |
| US-4 | As a new user I pick my categories. | At least 1 category must be selected to continue; the choice is saved on the server. |
| US-5 | As a user I can change my categories later. | Settings → Categories shows the current selection; saving updates the feed. |
| US-6 | As a user I see the latest news from my categories. | Feed shows articles from selected categories, newest first, each with image, title, source, relative time and category label. Pull-to-refresh reloads. Infinite scroll loads more. |
| US-7 | As a user I can filter the feed to one category. | Chips at the top: "All" plus each selected category. |
| US-8 | As a user I can read an article. | Tapping a card opens the article inside the app (in-app webview). Tapping any link inside the article that leads to a different site opens it in the phone's default browser, and the user stays on the article in the app. See §9.3. |
| US-9 | As a user I can load older news. | After the stored articles run out, the feed shows a "Load older news" button, which fetches older articles (up to ~30 days back). If the quota is used up, it shows "Older news unavailable right now". |
| US-10 | As a user I can archive an article. | A bookmark icon on the card and the article page toggles archive. It stays filled after a refresh. Archiving the same URL twice has no extra effect. |
| US-11 | As a user I can view my archive. | The Archive tab lists archived articles, newest-archived first, even after the original has been deleted from the feed. |
| US-12 | As a user I can remove an archived article. | Swipe-to-delete or tap the bookmark again; an undo snackbar appears for 4 s. |

---

## 3. Categories

### 3.1 Fixed category list
Categories are rows in the `categories` table, seeded by a Flyway migration. The app always loads them from the API and never hardcodes them.

| slug | name | provider_query (webz.io `q`; tune during M2) |
|---|---|---|
| technology | Technology | `category:"Science and Technology" language:english` |
| business | Business | `category:"Economy, Business and Finance" language:english` |
| sports | Sports | `category:Sports language:english` |
| science | Science | `(category:"Science and Technology" AND (research OR study OR space)) language:english` |
| health | Health | `category:Health language:english` |
| entertainment | Entertainment | `category:"Arts, Culture and Entertainment" language:english` |
| world | World | `category:"Conflict, War and Peace" OR category:"Human Interest" language:english` |
| politics | Politics | `category:Politics language:english` |

> The query strings are a starting point based on webz.io's IPTC-style categories. Check each one in the webz.io query builder during M2 and adjust. Changing a query is a SQL update, not a code change.

### 3.2 Rules
- Each article belongs to **one** category: the one it was fetched for.
- If a URL is fetched again under another category, the existing row is kept and the new one is ignored (`url_hash` is unique).
- Adding a category means a new migration `V{n}__add_category_x.sql`. No app release is needed.

---

## 4. News ingestion & retention

### 4.1 Hot path / cold path
```
                 ┌─────────────── hot path (≤ 7 days) ───────────────┐
Flutter ──JWT──▶ Spring Boot ──▶ PostgreSQL (articles)                │
                     │  ▲                                             │
                     │  └── lazy refresh when category is stale ──▶ webz.io
                     │                                                │
                     └─ cold path (> 7 days, not stored) ──Caffeine──▶ webz.io
```

### 4.2 Quota budget
> **Revised 2026-10-18 after testing the live API.** The account uses `/api/news`, which is billed from a credit balance that refills monthly, not a fixed call count. Measured: **0.06 credit for a 10-post call, 0.11 for 20 posts**, so it is roughly 0.005 per post. Assuming a monthly credit of **~5.00**, that's about **83 calls of 10 posts per month**.

| Bucket | Calls / month | Notes |
|---|---|---|
| Ingestion (hot path) | 68 | 8 categories refreshed every 3.5 days ≈ 69 calls |
| Older news (cold path) | 8 | Shared by all users; cached |
| Safety margin | ~7 | Retries, manual tests |

So **`refresh-ttl` = 84 h (3.5 days)** and **`retention` = 7 days**. Retention must be longer than the refresh interval, or a category's feed would empty out between refreshes. Each category gains about 10 articles every 3.5 days.

**Balance guard:** every `/api/news` response includes `balance`. When it falls below `newspal.webz.min-balance` (0.30), the provider reports itself disabled for the rest of the calendar month. This holds even if the monthly credit estimate above is wrong.

**To get fresher news:** follow fewer categories, raise the monthly credit (`refresh-ttl` and the quotas are plain config), or switch to another provider behind `NewsProvider`.

Usage is counted in the `api_usage` table, so the counts survive Render restarts. Before **every** webz.io call, the backend checks the bucket's monthly limit and skips the call when the limit is reached.

### 4.3 Lazy refresh (`IngestionService`)
Triggered by:
1. `GET /api/feed` (first page only): for each category the user follows where `last_fetched_at < now − refresh-ttl`, start an **async** refresh. The request does not wait for it, and the current DB contents are returned right away.
2. `@Scheduled(fixedDelay = 1h)`: refreshes stale categories that have ≥ 1 follower. It only runs while the instance is awake.
3. App startup (`ApplicationReadyEvent`): the same pass as (2).

Refreshing one category:
1. Take the per-category lock: `UPDATE categories SET last_fetched_at = now() WHERE id = ? AND (last_fetched_at IS NULL OR last_fetched_at < now() - ttl)`. If 0 rows change, another thread already has it, so skip.
2. Check and increment the `ingestion` bucket in `api_usage`.
3. Call `NewsProvider.fetchLatest(category, since = newest published_at in DB)`. Use webz.io `ts` to get only new posts.
4. Map each post to an `Article`: summary trimmed to 500 chars, `url_hash = sha256(normalized url)`. Normalizing means lowercase host, no `utm_*` parameters, no trailing slash.
5. `INSERT … ON CONFLICT (url_hash) DO NOTHING`.
6. On provider failure: log it and set `last_fetched_at` back to its old value so the next request tries again. The failed call still counts against the quota.

### 4.4 Retention (`CleanupService`)
Runs at startup and `@Scheduled(cron = "0 15 * * * *")`:
```sql
DELETE FROM articles WHERE published_at < now() - interval '7 days';  -- newspal.news.retention
DELETE FROM articles a USING (
  SELECT id, row_number() OVER (PARTITION BY category_id ORDER BY published_at DESC) rn
  FROM articles) r
WHERE a.id = r.id AND r.rn > 200;
```
Archived articles live in a separate table and are **never** affected.

**Storage estimate:** 8 categories × ~20 articles (7 days ÷ 3.5 days × 10) × ~2 KB ≈ **0.3 MB**. The 200-per-category cap only matters if `refresh-ttl` is lowered.

### 4.5 Configuration (`application.yml`, overridable by env)
```yaml
newspal:
  news:
    refresh-ttl: PT84H
    retention: P7D
    max-per-category: 200
    summary-max-chars: 500
    quota:
      ingestion-monthly: 68
      older-monthly: 8
    older:
      cache-ttl: PT1H
      per-user-daily: 10
  jwt:
    secret: ${JWT_SECRET}
    ttl: P7D
  webz:
    base-url: https://api.webz.io
    endpoint: /newsApiLite   # configurable; webz.io docs are inconsistent about the Lite path
    token: ${WEBZ_TOKEN:}    # empty = fetching disabled
    timeout: PT10S
```

---

## 5. Older news (cold path)

- `GET /api/feed/older?category={slug}&cursor={opaque}`
  - `category` is required and must be one the user follows (`400` otherwise).
  - Without `cursor`: the backend asks webz.io for posts in that category published **before** `now − retention` (rounded down to the hour, via `published:<ms` in `q`), going back at most `older.max-age` (30 days, via `ts`).
  - With `cursor`: continues where the previous page ended.
  - **Cursors are server-side (changed in M3).** webz.io's `next` path contains the API token, so it never leaves the server. The app gets a random id that maps to it in memory for `older.cache-ttl` (1 h). An unknown or expired id, or an id used with a different category, returns `400 BAD_CURSOR`, and the app starts again from the first page.
- Results are **not stored**. Each page is cached in Caffeine for 1 h, keyed by (category, hour) for first pages and by cursor id after that, so everyone asking within the hour shares one call. Cache hits don't count against the quota or the per-user limit. The `archived` flag is worked out per user on every response.
- Limits: per user `older.per-user-daily` (10) cache-missing lookups per UTC day, kept in memory (a restart resetting it is acceptable for a hobby app). Global: the `older` bucket in `api_usage` (8/month on the current credit).
- When a limit is hit → `429` with code `OLDER_RATE_LIMITED` (per user) or `QUOTA_EXHAUSTED` (global quota, provider disabled, or webz.io balance too low). A webz.io failure → `502 PROVIDER_ERROR`; failures are not cached.
- Posts with no text are dropped, the same as for stored articles.
- **Live check (2026-10-18):** the `published:<ms` filter works. With `sort=crawled`, webz.io paged forward from `ts`, so the first page showed stories from about 30 days back. Older-news calls therefore use **`sort=published&order=desc`**. Verified live: the first page starts right at the cutoff and goes newest first. Ingestion keeps `sort=crawled`, so `ts` returns only newly crawled posts.
- Older articles have no DB `id`, so archiving them sends the full article details (§6.6).
- "All" view: older news is loaded **one category at a time**. The UI asks the user to choose a category chip first, which avoids spending N calls per tap.

---

## 6. Data model

### 6.1 Tables (Flyway `V1__init.sql`)
```sql
CREATE TABLE users (
  id            BIGSERIAL PRIMARY KEY,
  email         VARCHAR(254) NOT NULL UNIQUE,
  password_hash VARCHAR(100) NOT NULL,
  created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE categories (
  id              SMALLSERIAL PRIMARY KEY,
  slug            VARCHAR(40)  NOT NULL UNIQUE,
  name            VARCHAR(60)  NOT NULL,
  provider_query  TEXT         NOT NULL,
  sort_order      SMALLINT     NOT NULL DEFAULT 0,
  last_fetched_at TIMESTAMPTZ
);

CREATE TABLE user_categories (
  user_id     BIGINT   NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  category_id SMALLINT NOT NULL REFERENCES categories(id) ON DELETE CASCADE,
  PRIMARY KEY (user_id, category_id)
);

CREATE TABLE articles (
  id           BIGSERIAL PRIMARY KEY,
  url_hash     CHAR(64)     NOT NULL UNIQUE,
  url          TEXT         NOT NULL,
  title        TEXT         NOT NULL,
  summary      VARCHAR(500),
  image_url    TEXT,
  source       VARCHAR(200),
  published_at TIMESTAMPTZ  NOT NULL,
  category_id  SMALLINT     NOT NULL REFERENCES categories(id),
  fetched_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_articles_cat_pub ON articles (category_id, published_at DESC, id DESC);
CREATE INDEX idx_articles_pub     ON articles (published_at DESC, id DESC);

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
  category      VARCHAR(60),   -- slug; renamed from category_name in V4
  archived_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
  UNIQUE (user_id, url_hash)
);
CREATE INDEX idx_archive_user ON archived_articles (user_id, archived_at DESC);

CREATE TABLE api_usage (
  month   CHAR(7)     NOT NULL,          -- '2026-10'
  bucket  VARCHAR(20) NOT NULL,          -- 'ingestion' | 'older'
  calls   INT         NOT NULL DEFAULT 0,
  PRIMARY KEY (month, bucket)
);
```
`V2__seed_categories.sql` inserts the rows from §3.1.

### 6.2 Quota check (atomic)
```sql
INSERT INTO api_usage (month, bucket, calls) VALUES (:m, :b, 1)
ON CONFLICT (month, bucket) DO UPDATE SET calls = api_usage.calls + 1
WHERE api_usage.calls < :limit
RETURNING calls;
```
No row returned → over the limit → skip the call.

---

## 7. REST API

Base path `/api`. JSON only. All endpoints except `/auth/**`, `GET /categories` and `/actuator/health` need `Authorization: Bearer <jwt>`.

### 7.1 Error format
```json
{ "code": "VALIDATION_FAILED", "message": "Request is invalid",
  "fieldErrors": [{ "field": "email", "message": "must be a valid email" }] }
```
| HTTP | code |
|---|---|
| 400 | `VALIDATION_FAILED`, `BAD_CURSOR` |
| 401 | `UNAUTHORIZED`, `BAD_CREDENTIALS` |
| 404 | `NOT_FOUND` |
| 409 | `EMAIL_TAKEN` |
| 429 | `OLDER_RATE_LIMITED`, `QUOTA_EXHAUSTED` |
| 502 | `PROVIDER_ERROR` |

### 7.2 Auth
| Method & path | Body | Response |
|---|---|---|
| `POST /auth/register` | `{email, password}` | `201 {token, expiresAt, user:{id,email}}` |
| `POST /auth/login` | `{email, password}` | `200 {token, expiresAt, user:{id,email}}` |
| `GET /me` | — (JWT required) | `200 {id, email}`; used by the splash screen to check a stored token |

JWT: HS256, claims `sub` = user id and `exp` = 7 days. There are no refresh tokens in the MVP; the user logs in again after expiry.

### 7.3 Categories
| Method & path | Response |
|---|---|
| `GET /categories` | `200 [{slug, name}]`, ordered by `sort_order` |
| `GET /me/categories` | `200 ["technology","sports"]` |
| `PUT /me/categories` | body `["technology","sports"]` (≥ 1, known slugs) → `204` |

### 7.4 Feed
`GET /feed?category={slug?}&cursor={opaque?}&size={1..50, default 20}`
```json
{
  "items": [{
    "id": 123, "url": "...", "title": "...", "summary": "...",
    "imageUrl": "...", "source": "bbc.com", "publishedAt": "2026-10-17T08:00:00Z",
    "category": "technology", "archived": false
  }],
  "nextCursor": "MjAyNi0xMC0xN1QwODowMDowMFp8MTIz",
  "lastRefreshedAt": "2026-10-17T06:00:00Z"
}
```
- **Without `category`:** articles from all of the user's categories.
- **With `category`:** must be one the user follows, otherwise `400`.
- **Cursor:** base64 of `publishedAt|id` (keyset pagination: `WHERE (published_at, id) < (:p, :i)`).
- **`nextCursor: null`:** there are no more stored articles, so the app shows "Load older news".
- **`archived`:** found with a `LEFT JOIN archived_articles ON url_hash AND user_id`.

`GET /feed/older?category={slug}&cursor={opaque?}` returns the same shape, with `id: null` and `lastRefreshedAt` omitted. See §5.

### 7.5 Archive
| Method & path | Body | Response |
|---|---|---|
| `GET /archive?cursor=&size=` | | `200 {items:[{id,url,title,summary,imageUrl,source,publishedAt,category,archivedAt}], nextCursor}` |
| `POST /archive` | `{url, title, summary?, imageUrl?, source?, publishedAt?, category?}` | `201 {item}`, or `200 {item}` if it was already archived (idempotent) |

Archive items: `{id, url, title, summary, imageUrl, source, publishedAt, category, archivedAt}`. `category` is a slug, as in feed items, and is `null` if unknown. Summaries are whitespace-flattened and trimmed to 500 chars, and a non-http(s) `imageUrl` is dropped. Deleting another user's item returns `404`, the same as a missing one.
| `DELETE /archive/{id}` | | `204`; `404` if the item isn't the user's |

The server recomputes `url_hash` and never trusts a client hash. Field lengths are validated, and `url` must be http(s).

### 7.6 Ops
`GET /actuator/health` returns `{status:"UP"}` and is used by the Render health check. All other actuator endpoints are disabled.

---

## 8. Backend design

### 8.1 Package layout (`com.newspal`)
```
NewspalApplication.java
config/        SecurityConfig, JwtProperties, NewsProperties, CacheConfig, AsyncConfig
common/        ApiError, GlobalExceptionHandler, CursorCodec, UrlUtils (normalize + sha256)
auth/          AuthController, AuthService, JwtService, dto/   (token validation: Spring Security resource server, see SecurityConfig)
user/          User, UserRepository
category/      Category, CategoryRepository, CategoryController, UserCategoryService
feed/          FeedController, FeedService, OlderNewsService, dto/ArticleDto, FeedPage
article/       Article, ArticleRepository
archive/       ArchivedArticle, ArchiveRepository, ArchiveController, ArchiveService
ingestion/     IngestionService, IngestionScheduler, CleanupService, QuotaService
provider/      NewsProvider, ProviderArticle, ProviderPage, webz/WebzNewsProvider, webz/WebzResponse
```

### 8.2 `NewsProvider` interface
```java
public interface NewsProvider {
    boolean isEnabled();                                   // false when WEBZ_TOKEN is empty → no calls, no quota
    List<ProviderArticle> fetchLatest(String query, Instant since);          // M2
    ProviderPage fetchOlder(String query, Instant before, Instant notBefore, String cursor); // M3
}
public record ProviderArticle(String url, String title, String summary, String imageUrl,
                              String source, Instant publishedAt) {}
public record ProviderPage(List<ProviderArticle> items, String nextCursor) {}
```
`WebzNewsProvider` uses Spring `RestClient` with a 10 s timeout. It maps the webz.io `posts[]` fields (`url`, `title`, `text`, `thread.main_image`, `thread.site`, `published`). It does not retry on its own; the next lazy refresh acts as the retry.

### 8.3 Security
- BCrypt password hashing (strength 10).
- Stateless sessions. CSRF is disabled, because the API is JWT-only. CORS is not needed for a mobile client.
- The JWT secret is ≥ 32 random bytes from the `JWT_SECRET` env var. The app fails to start if it's missing.
- Login failures return a generic `BAD_CREDENTIALS`, so attackers can't tell which emails are registered.
- The webz.io token is never logged and never sent to the client.

### 8.4 Async
`@EnableAsync` with a small `ThreadPoolTaskExecutor` (core 2, max 4, queue 20) named `ingestionExecutor`. Lazy refreshes run on this pool.

---

## 9. Mobile app design

### 9.1 Structure (`lib/`)
```
main.dart
app/            router.dart (go_router + auth redirect), theme.dart
core/           api_client.dart (dio + interceptors), api_error.dart, secure_storage.dart, env.dart
features/
  auth/         data/auth_repository.dart, providers.dart, ui/login_page.dart, ui/register_page.dart
  categories/   data/category_repository.dart, providers.dart, ui/category_picker_page.dart
  feed/         data/feed_repository.dart, providers.dart (AsyncNotifier w/ pagination),
                ui/feed_page.dart, ui/article_card.dart, ui/article_page.dart
  archive/      data/archive_repository.dart, providers.dart, ui/archive_page.dart
  settings/     ui/settings_page.dart
models/         article.dart, category.dart, user.dart (freezed + json_serializable)
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
- **Never use lime as text or as thin icons on a light background.** It is unreadable on white. In light mode, lime is only a fill (buttons, selected chips, the bookmark badge), with charcoal content on top.
- Text on lime is always charcoal `#23262F`. Charcoal on lime contrasts well, and so does lime on charcoal.
- Links inside the app (e.g. "Load older news") use lime in dark mode and charcoal with a lime underline in light mode.
- Defined once in `app/theme.dart`, via `ColorScheme(...)` with these exact values (not `fromSeed`, which would shift the brand colors).

**Logo (chosen 2026-10-18: "folded page"):** a lime newspaper sheet with a darker-lime folded corner (`#7FB81C`) and three charcoal headline lines, next to the wordmark "News" + "Pal". "Pal" is lime on dark surfaces and `onSurfaceVariant` grey on light ones. It's drawn in code (`widgets/brand_wordmark.dart`: `BrandIcon`, `BrandWordmark`), so it stays sharp at any size. Launcher icons (Android adaptive on charcoal, iOS) and the web favicon/PWA icons are generated from the same painter: `tool/brand_icons_test.dart` renders `assets/branding/*.png`, then `flutter_launcher_icons` produces every size. The app's display name is "NewsPal" on every platform.

### 9.2 Navigation
```
/splash → (token?) → /feed            : else → /login
/login ↔ /register → /onboarding/categories (if user has none) → /feed
Bottom nav: Feed | Archive | Settings
/article (extra: Article)
/settings/categories
```

### 9.3 Behaviour
- **dio interceptors:** one adds the `Bearer` token. On `401`, a second interceptor clears the token and the router redirects to `/login`. Timeout is 70 s, so it lasts through a Render cold start.
- **Cold start UX:** if a request takes more than 3 s, a banner shows "Waking up the server… this can take up to a minute."
- **Feed provider:** holds `items`, `nextCursor`, `isLoadingMore` and `mode` (`stored` | `older`). When `nextCursor == null` in `stored` mode, it shows the "Load older news" button. In "All", that button asks the user to pick a category.
- **Archive toggle:** optimistic. The bookmark icon updates immediately, and is reverted with a snackbar on error. The feed and archive providers are invalidated after a change.
- **Empty states:** "No news yet, check back soon" (feed), "Nothing archived yet" (archive).
- **Article page (`webview_flutter` + `url_launcher`):**
  - Loads the article URL in a `WebViewController` with JavaScript enabled. A progress bar shows while it loads, plus a bookmark (archive) action in the app bar.
  - A `NavigationDelegate.onNavigationRequest` decides where each link opens:
    - **Same site** as the article (the registrable domain matches, so `www.bbc.com` and `bbc.com` count as the same): continue inside the webview. This keeps pages split across several screens, cookie banners and redirects working.
    - **Any other site, or a non-http(s) link** (`mailto:`, `tel:`, app links): return `NavigationDecision.prevent` and call `launchUrl(uri, mode: LaunchMode.externalApplication)`. The phone's default browser or app opens it, and the article stays open in NewsPal.
    - Redirects the page makes on its own while first loading (before the user taps anything) stay in the webview, so publishers that redirect to an AMP or regional domain still load.
  - If the page fails to load, an error view offers "Retry" and "Open in browser".
  - Android back button: go back in the webview's history if possible, otherwise close the article page.
- **API base URL:** set via `--dart-define=API_BASE_URL=...`.

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
- **Render Web Service:** runtime Docker, plan Free, health check path `/actuator/health`. Pick the Render region closest to the Neon region (e.g. both in Frankfurt, or both in US East) so each query has less distance to travel.
- **Env vars:** `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, `JWT_SECRET`, `WEBZ_TOKEN`.
- **Optional:** a `render.yaml` blueprint for the web service.
- **Local dev:** `docker-compose.yml` with `postgres:16`, plus the backend started with the `dev` profile (`run-dev.cmd`: local JWT secret, CORS for `http://localhost:*`). The Flutter app runs in the browser (`mobile/run-web.cmd`, http://localhost:5005) or on the Android emulator against `http://10.0.2.2:8080`. CORS is off unless `newspal.cors.allowed-origin-patterns` is set, and production doesn't set it. The same Flyway migrations run against local Postgres and Neon.

### 9.5 Database on Neon
**Setup**
1. Create a Neon project (free plan) named `newspal`, Postgres 16, in the region nearest your Render service.
2. Use the default `main` branch and create a database `newspal`.
3. Copy the **direct** (non-pooled) connection string and convert it to JDBC. Hikari already pools connections, and Flyway works best on a direct connection.
   ```
   SPRING_DATASOURCE_URL=jdbc:postgresql://<endpoint>.<region>.aws.neon.tech/newspal?sslmode=require
   SPRING_DATASOURCE_USERNAME=<neon role>
   SPRING_DATASOURCE_PASSWORD=<neon password>
   ```
4. Flyway creates the tables on the first backend start.

**Let Neon sleep (saves compute hours)**

Neon only scales to zero when nothing touches the database for 5 minutes. Pool keep-alives and DB health checks would keep it awake around the clock, so:
```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 5
      minimum-idle: 0          # don't hold idle connections open
      idle-timeout: 60000      # close idle connections after 1 min
      max-lifetime: 240000     # < Neon's 5 min suspend window
      keepalive-time: 0        # no keep-alive pings
      connection-timeout: 15000 # allow time for Neon to wake up
management:
  health:
    db:
      enabled: false           # Render's health check must not wake the DB
```
The hourly cleanup job and lazy refresh only run while the Render service is awake, which is when users are active. So the DB is mostly awake only while someone is using the app, well within 100 CU-hours/month.

**Backups (optional, belt-and-braces)**

Neon keeps a short point-in-time restore window on the free plan. For a hobby app, an occasional manual dump of the tables that matter is enough:
```bash
pg_dump --no-owner -Fc -t users -t user_categories -t archived_articles "$NEON_URL" > newspal-backup.dump
```
`articles` doesn't need backing up, because it is refilled automatically from webz.io.

**Switching later:** the backend only knows a JDBC URL. Moving to Supabase, Render Postgres or anything else means changing the three `SPRING_DATASOURCE_*` env vars.

---

## 10. Testing

| Layer | What |
|---|---|
| Backend unit | `UrlUtils` normalization/hashing, `CursorCodec`, `JwtService`, `QuotaService` limit logic, `WebzNewsProvider` mapping (fixture JSON) |
| Backend web | `@WebMvcTest` for each controller: validation, error codes, auth required |
| Backend integration | Testcontainers Postgres + `@SpringBootTest` with a fake `NewsProvider`: register → choose categories → feed triggers ingestion → archive → cleanup deletes old articles but keeps archived ones → quota stops calls at the limit |
| Flutter unit | Repositories with a mocked dio; feed notifier pagination and the switch to older mode; `shouldOpenExternally(articleUri, targetUri)` link rule (same site vs other site, `www.` prefix, `mailto:`/`tel:`) |
| Flutter widget | Login validation, category picker (≥ 1 rule), feed card archive toggle, empty states |
| Manual | Deploy to Render, let it sleep, reopen the app and confirm the cold-start banner and that the stale refresh happens |

---

## 11. Milestones

| # | Deliverable | Done when |
|---|---|---|
| M1 | Backend skeleton, Flyway, auth | Register/login work locally; tests pass |
| M2 | Categories, `NewsProvider`, ingestion, quota, cleanup | Category queries tuned against webz.io; articles appear in the DB; cleanup works |
| M3 | Feed, older-news and archive APIs | All §7 endpoints pass integration tests |
| M4 | Flutter: auth, router, onboarding | Can register and pick categories against the local backend |
| M5 | Flutter: feed, article view, older news | Infinite scroll, chips, webview, older-news button |
| M6 | Flutter: archive | Archive toggle, archive list, swipe-to-delete with undo |
| M7 | Neon DB + Render deployment | App on a phone talks to Render, Render talks to Neon; Neon dashboard shows the compute suspending when the app is idle |

---

## 12. Traceability

| Story | Endpoint(s) | Screen |
|---|---|---|
| US-1 | `POST /auth/register` | Register |
| US-2 | `POST /auth/login` | Login, Splash |
| US-3 | — (local) | Settings |
| US-4 | `GET /categories`, `PUT /me/categories` | Category picker |
| US-5 | `GET /me/categories`, `PUT /me/categories` | Settings → Categories |
| US-6 | `GET /feed` | Feed |
| US-7 | `GET /feed?category=` | Feed (chips) |
| US-8 | — (opens URL) | Article |
| US-9 | `GET /feed/older` | Feed |
| US-10 | `POST /archive`, `DELETE /archive/{id}` | Feed card, Article |
| US-11 | `GET /archive` | Archive |
| US-12 | `DELETE /archive/{id}`, `POST /archive` (undo) | Archive |

---

## 13. Open questions
1. ~~App name~~ → **Decided: NewsPal.** App icon still to be designed.
2. ~~Article opening~~ → **Decided:** articles open in the app; links to other sites open in the default browser (§9.3).
3. ~~News freshness~~ → **Decided:** news up to ~8 h old was accepted, but the real webz.io pricing (§4.2) forces `refresh-ttl` = `PT84H` (3.5 days) on the ~5.00 monthly credit.
4. ~~Database host~~ → **Decided: Neon free plan** (no expiry; §9.5).
