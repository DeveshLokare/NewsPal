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
| `WEBZ_TOKEN` | webz.io API token. If empty, no news is fetched and no quota is used | empty |

Other news settings (`refresh-ttl`, `retention`, quotas, webz.io endpoint) are under `newspal.news` and `newspal.webz` in `application.yml`.

## Endpoints so far

- `POST /api/auth/register` `{email, password}` → `201 {token, expiresAt, user}`
- `POST /api/auth/login` `{email, password}` → `200 {token, expiresAt, user}`
- `GET /api/me` (Bearer token) → `200 {id, email}`
- `GET /api/categories` (public) → `200 [{slug, name}]`
- `GET /api/me/categories` → `200 ["technology", ...]`
- `PUT /api/me/categories` `["technology","sports"]` → `204`
- `GET /api/feed?category=&cursor=&size=` → `200 {items, nextCursor, lastRefreshedAt}` (stored articles, newest first)
- `GET /api/feed/older?category=&cursor=` → `200 {items, nextCursor}` (live from webz.io, cached 1 h, not stored)
- `GET /api/archive?cursor=&size=` → `200 {items, nextCursor}`
- `POST /api/archive` `{url, title, summary?, imageUrl?, source?, publishedAt?, category?}` → `201` new / `200` existing
- `DELETE /api/archive/{id}` → `204`
- `GET /actuator/health` → `200 {"status":"UP"}`

## Background jobs

- On startup, then every hour: refresh stale categories that someone follows (at most one webz.io call per category per `refresh-ttl`).
- Every hour at :15 (UTC): delete articles older than 7 days and keep at most 200 per category.
- The monthly call count is stored in the `api_usage` table. Fetching also pauses for the month once webz.io reports a balance below 0.30.

## Checking category queries against webz.io

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\check-webz-queries.ps1 [-Only sports] [-Size 10]
```

The script asks for the token without echoing it, makes one call per category (each costs credit), and writes a summary to `target/webz-check.json`.
