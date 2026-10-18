# NewsPal mobile app

Flutter app for NewsPal. See `../SPEC.md` §9 for the design, and §9.1a for the theme.

## Setup

Flutter (stable) is installed at `C:\src\flutter`. Either add `C:\src\flutter\bin` to your PATH or call it by full path, as below.

```powershell
C:\src\flutter\bin\flutter.bat pub get
```

## Run against the local backend

1. Start the backend (see `../backend/README.md`): `docker compose up -d`, then `run-dev.cmd` in `backend/`.
2. Start the app:
   - **Browser (no Android SDK needed):** `run-web.cmd`, then open http://localhost:5005. The backend's `dev` profile allows this origin (CORS).
   - **Android emulator:** `C:\src\flutter\bin\flutter.bat run`. The app reaches your PC at `10.0.2.2:8080`. This needs Android Studio and an emulator.
   - **Another backend:** add `--dart-define=API_BASE_URL=https://your-backend.onrender.com`.

## Tests

```powershell
C:\src\flutter\bin\flutter.bat analyze
C:\src\flutter\bin\flutter.bat test
```

Tests use in-memory fakes (`test/support/fakes.dart`), so no backend is needed.

## Structure

```
lib/
  main.dart
  app/        app.dart (MaterialApp + "waking server" banner), router.dart (go_router + auth redirects),
              theme.dart (brand colors), home_shell.dart (bottom nav), splash_page.dart
  core/       api_client.dart (dio + auth header + 401 sign-out), api_error.dart, token_store.dart, env.dart
  features/
    auth/        auth_controller.dart (session state), auth_repository.dart, login/register pages
    categories/  category_repository.dart, category_picker_page.dart (onboarding + settings)
    feed/        placeholder until M5
    archive/     placeholder until M6
    settings/    settings_page.dart
  widgets/    brand_wordmark.dart, coming_soon.dart
```
