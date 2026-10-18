import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../features/archive/archive_page.dart';
import '../features/auth/auth_controller.dart';
import '../features/auth/login_page.dart';
import '../features/auth/register_page.dart';
import '../features/categories/category_picker_page.dart';
import '../features/feed/feed_page.dart';
import '../features/settings/settings_page.dart';
import 'home_shell.dart';
import 'splash_page.dart';

abstract final class Routes {
  static const splash = '/splash';
  static const login = '/login';
  static const register = '/register';
  static const onboarding = '/onboarding';
  static const feed = '/feed';
  static const archive = '/archive';
  static const settings = '/settings';
  static const editCategories = '/settings/categories';
}

/// Where the auth state says the user should be, or null to stay (SPEC §9.2).
@visibleForTesting
String? authRedirect(AsyncValue<AuthState> auth, String location) {
  final value = auth.value;
  if (value == null) {
    // Still restoring the session (or failed before any value).
    return location == Routes.splash ? null : Routes.splash;
  }
  const publicRoutes = {Routes.login, Routes.register};
  switch (value) {
    case SignedOut():
      return publicRoutes.contains(location) ? null : Routes.login;
    case SignedIn(hasCategories: false):
      return location == Routes.onboarding ? null : Routes.onboarding;
    case SignedIn():
      const entryRoutes = {Routes.splash, Routes.login, Routes.register, Routes.onboarding};
      return entryRoutes.contains(location) ? Routes.feed : null;
  }
}

final routerProvider = Provider<GoRouter>((ref) {
  // Re-run redirects whenever the auth state changes.
  final refresh = ValueNotifier<int>(0);
  ref.listen(authControllerProvider, (_, _) => refresh.value++);
  ref.onDispose(refresh.dispose);

  return GoRouter(
    initialLocation: Routes.splash,
    refreshListenable: refresh,
    redirect: (context, state) => authRedirect(ref.read(authControllerProvider), state.matchedLocation),
    routes: [
      GoRoute(path: Routes.splash, builder: (_, _) => const SplashPage()),
      GoRoute(path: Routes.login, builder: (_, _) => const LoginPage()),
      GoRoute(path: Routes.register, builder: (_, _) => const RegisterPage()),
      GoRoute(
        path: Routes.onboarding,
        builder: (_, _) => const CategoryPickerPage(mode: PickerMode.onboarding),
      ),
      StatefulShellRoute.indexedStack(
        builder: (context, state, shell) => HomeShell(shell: shell),
        branches: [
          StatefulShellBranch(routes: [
            GoRoute(path: Routes.feed, builder: (_, _) => const FeedPage()),
          ]),
          StatefulShellBranch(routes: [
            GoRoute(path: Routes.archive, builder: (_, _) => const ArchivePage()),
          ]),
          StatefulShellBranch(routes: [
            GoRoute(
              path: Routes.settings,
              builder: (_, _) => const SettingsPage(),
              routes: [
                GoRoute(
                  path: 'categories',
                  builder: (_, _) => const CategoryPickerPage(mode: PickerMode.edit),
                ),
              ],
            ),
          ]),
        ],
      ),
    ],
  );
});
