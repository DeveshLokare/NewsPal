import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:newspal/app/router.dart';
import 'package:newspal/features/auth/auth_controller.dart';

void main() {
  const loading = AsyncLoading<AuthState>();
  const signedOut = AsyncData<AuthState>(SignedOut());
  const needsOnboarding = AsyncData<AuthState>(SignedIn(email: 'a@b.co', hasCategories: false));
  const ready = AsyncData<AuthState>(SignedIn(email: 'a@b.co', hasCategories: true));

  test('while restoring, everything goes to splash', () {
    expect(authRedirect(loading, Routes.feed), Routes.splash);
    expect(authRedirect(loading, Routes.splash), isNull);
  });

  test('signed out users can only see login and register', () {
    expect(authRedirect(signedOut, Routes.splash), Routes.login);
    expect(authRedirect(signedOut, Routes.feed), Routes.login);
    expect(authRedirect(signedOut, Routes.login), isNull);
    expect(authRedirect(signedOut, Routes.register), isNull);
  });

  test('users without categories are held on onboarding', () {
    expect(authRedirect(needsOnboarding, Routes.feed), Routes.onboarding);
    expect(authRedirect(needsOnboarding, Routes.settings), Routes.onboarding);
    expect(authRedirect(needsOnboarding, Routes.onboarding), isNull);
  });

  test('ready users leave entry screens for the feed', () {
    for (final entry in [Routes.splash, Routes.login, Routes.register, Routes.onboarding]) {
      expect(authRedirect(ready, entry), Routes.feed, reason: entry);
    }
    expect(authRedirect(ready, Routes.archive), isNull);
    expect(authRedirect(ready, Routes.editCategories), isNull);
  });
}
