# NewsPal backend

Spring Boot 4.1 / Java 21 API for NewsPal. See `../SPEC.md` for the full design.

## Run locally

Requires Java 21 and Docker Desktop (running).

```bash
docker compose up -d
```

PowerShell:

```powershell
$env:JWT_SECRET = "change-me-to-a-long-random-string-32-bytes-plus"
.\mvnw.cmd spring-boot:run
```

The API listens on http://localhost:8080. Flyway creates the tables on startup.

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

## Endpoints so far (M1)

- `POST /api/auth/register` `{email, password}` → `201 {token, expiresAt, user}`
- `POST /api/auth/login` `{email, password}` → `200 {token, expiresAt, user}`
- `GET /api/me` (Bearer token) → `200 {id, email}`
- `GET /actuator/health` → `200 {"status":"UP"}`
