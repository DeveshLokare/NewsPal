# NewsPal

A hobby news app: pick the topics you care about, get the latest stories from them, and archive the ones you want to keep.

- **mobile/**: Flutter app (Android, iOS, web for development). See [mobile/README.md](mobile/README.md).
- **backend/**: Spring Boot 4 / Java 21 API with PostgreSQL. News comes from webz.io. See [backend/README.md](backend/README.md).
- **SPEC.md**: the full design: features, data model, API, budget and decisions.
- **DEPLOY.md**: how to deploy (Render for the API, Neon for the database) and install the app on a phone.

## Run it locally

```bash
cd backend && docker compose up -d && run-dev.cmd
```

```bash
cd mobile && run-web.cmd
```

Then open http://localhost:5005.
