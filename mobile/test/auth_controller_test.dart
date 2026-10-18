import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:newspal/core/api_client.dart';
import 'package:newspal/core/api_error.dart';
import 'package:newspal/features/auth/auth_controller.dart';

import 'support/fakes.dart';

void main() {
  late Fakes fakes;
  late ProviderContainer container;

  setUp(() {
    fakes = Fakes();
    container = ProviderContainer(overrides: fakes.overrides);
  });

  tearDown(() => container.dispose());

  Future<AuthState> restore() => container.read(authControllerProvider.future);

  group('restoring a session', () {
    test('no stored session is signed out', () async {
      expect(await restore(), isA<SignedOut>());
      expect(fakes.auth.meCalls, 0);
    });

    test('expired session is cleared without calling the server', () async {
      fakes.storeSession(expired: true);
      expect(await restore(), isA<SignedOut>());
      expect(fakes.store.session, isNull);
      expect(fakes.auth.meCalls, 0);
    });

    test('valid session checks the token and categories', () async {
      fakes.storeSession();
      fakes.categories.followed = ['sports'];
      final state = await restore();
      expect(state, isA<SignedIn>().having((s) => s.hasCategories, 'hasCategories', true));
      expect(container.read(sessionTokenProvider), 'stored-token');
    });

    test('user without categories still needs onboarding', () async {
      fakes.storeSession();
      expect(await restore(), isA<SignedIn>().having((s) => s.hasCategories, 'hasCategories', false));
    });

    test('rejected token signs out and forgets the session', () async {
      fakes.storeSession();
      fakes.auth.meError = unauthorized();
      expect(await restore(), isA<SignedOut>());
      expect(fakes.store.session, isNull);
      expect(container.read(sessionTokenProvider), isNull);
    });

    test('offline keeps the unexpired session', () async {
      fakes.storeSession(email: 'offline@example.com');
      fakes.auth.meError = networkError();
      expect(await restore(),
          isA<SignedIn>().having((s) => s.email, 'email', 'offline@example.com'));
    });
  });

  group('signing in and out', () {
    test('register stores the session and asks for onboarding', () async {
      await restore();
      await container.read(authControllerProvider.notifier).register('new@example.com', 'password123');
      final state = container.read(authControllerProvider).value;
      expect(state, isA<SignedIn>().having((s) => s.hasCategories, 'hasCategories', false));
      expect(fakes.store.session?.token, 'token-new@example.com');
      expect(container.read(sessionTokenProvider), 'token-new@example.com');
    });

    test('failed login throws and stays signed out', () async {
      await restore();
      await expectLater(
        container.read(authControllerProvider.notifier).signIn('nobody@example.com', 'x'),
        throwsA(isA<ApiError>().having((e) => e.code, 'code', 'BAD_CREDENTIALS')),
      );
      expect(container.read(authControllerProvider).value, isA<SignedOut>());
      expect(fakes.store.session, isNull);
    });

    test('login of a user with categories skips onboarding', () async {
      fakes.auth.users['a@example.com'] = 'password123';
      fakes.categories.followed = ['technology'];
      await restore();
      await container.read(authControllerProvider.notifier).signIn('a@example.com', 'password123');
      expect(container.read(authControllerProvider).value,
          isA<SignedIn>().having((s) => s.hasCategories, 'hasCategories', true));
    });

    test('categoriesSaved finishes onboarding', () async {
      await restore();
      final controller = container.read(authControllerProvider.notifier);
      await controller.register('b@example.com', 'password123');
      controller.categoriesSaved();
      expect(container.read(authControllerProvider).value,
          isA<SignedIn>().having((s) => s.hasCategories, 'hasCategories', true));
    });

    test('signOut clears everything', () async {
      fakes.storeSession();
      await restore();
      await container.read(authControllerProvider.notifier).signOut();
      expect(container.read(authControllerProvider).value, isA<SignedOut>());
      expect(fakes.store.session, isNull);
      expect(container.read(sessionTokenProvider), isNull);
    });
  });
}
