# NewsPal backend

Spring Boot 4.1 / Java 21 API for NewsPal. See `../SPEC.md` for the full design.

## Run locally

Requires Java 21 and Docker Desktop (running).

```bash
docker compose up -d
```

Then start the API with the `dev` profile (a local JWT secret, and CORS for the Flutter web app on `localhost`):

```powershell
.\run-dev.cmd
```

The API listens on http://localhost:8080. Flyway creates the tables on startup. Without the dev profile, set `JWT_SECRET` (at least 32 bytes) yourself.

Local test account (created during M4 testing, only in your local Docker database): `dev-tester@newspal.test` / `localdev-pass-2026`.

## Tests

```bash
./mvnw test
```

The tests start a throwaway Postgres 16 in Docker (Testcontainers), so Docker must be running.

## Environment variables

| Variable | Purpose | Local default |
|---|---|---|
| `SPRING_DATASOURCE_URL` | JDBC URL (Neon in production, `?sslmode=require`) | `jdbc:postgresql://localhost:5432/newspal` |
| `SPRING_DATASOURCE_USERNAME` | DB user | `newspal` |
| `SPRING_DATASOURCE_PASSWORD` | DB password | `newspal` |
| `JWT_SECRET` | HS256 signing secret, at least 32 bytes | none, required |
| `FREENEWS_API_KEY` | Free News API key. If empty, no news is fetched and no quota is used | empty |
| `JOB_TOKEN` | Shared secret for `POST /api/internal/jobs/run`. If empty, that endpoint answers 404 | empty |

Other news settings (`refresh-ttl`, `retention`, daily quotas, language/country) are under `newspal.news` and `newspal.freenews` in `application.yml`.

## Endpoints so far

- `POST /api/auth/register` `{email, password, questionId?, answer?}` → `201 {token, expiresAt, user}` (question and answer go together; optional until the app sends them)
- `POST /api/auth/login` `{email, password}` → `200 {token, expiresAt, user}`
- `GET /api/auth/security-questions` → `200 [{id, text}]`
- `POST /api/auth/password/question` `{email}` → `200 {questionId, text}` (a stable decoy for unknown emails)
- `POST /api/auth/password/reset` `{email, answer, newPassword}` → `200 {token, expiresAt, user}`; `400 WRONG_ANSWER {attemptsLeft}`; `429 RESET_LOCKED {retryAfterSeconds}` after 5 wrong answers (24 h). Signs out the account's older tokens
- `GET /api/me` (Bearer token) → `200 {id, email, hasSecurityQuestion}`
- `PUT /api/me/security-question` `{currentPassword, questionId, answer}` → `204`; `400 WRONG_PASSWORD`
- `GET /api/categories` (public) → `200 [{slug, name}]`
- `GET /api/me/categories` → `200 ["technology", ...]`
- `PUT /api/me/categories` `["technology","sports"]` → `204`
- `GET /api/feed?category=&cursor=&size=` → `200 {items, nextCursor, lastRefreshedAt}` (stored articles, newest first)
- `GET /api/feed/older?category=&cursor=` → `200 {items, nextCursor}` (live from Free News API, cached 1 h, not stored)
- `GET /api/archive?cursor=&size=` → `200 {items, nextCursor}`
- `POST /api/archive` `{url, title, summary?, imageUrl?, source?, publishedAt?, category?}` → `201` new / `200` existing
- `DELETE /api/archive/{id}` → `204`
- `GET /actuator/health` → `200 {"status":"UP"}`

## Background jobs

- A job pass runs the daily cleanup if it's due (at most once per 23 h, tracked in `job_runs`), then refreshes every stale category, followed or not (stale = not fetched for `refresh-ttl`, 1 h 50 m). A refresh lists up to 3 pages of new articles for the category's topic, then makes one `/details` call per article not stored yet. Only one pass runs at a time.
- A pass runs on startup, every 2 hours at :05 UTC while the service is awake, and when `POST /api/internal/jobs/run` is called with the `X-Job-Token` header (`JOB_TOKEN`). A GitHub Actions workflow calls it every 2 hours, which also wakes the service on Render (DEPLOY.md §6). The endpoint returns `202 {"started": true}`, or `false` if a pass is already running, and `404` for a missing or wrong token.
- Cleanup deletes articles older than 7 days and keeps at most 1000 per category. A 03:30 UTC timer also checks whether it's due.
- Every provider call is counted per UTC day in the `api_usage` table (3,600 for refreshes, 1,000 for older news; the API allows 5,000/day). Fetching also pauses until the daily reset once Free News API reports fewer than 100 requests left, or says the quota is used up.
