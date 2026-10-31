import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:newspal/app/app.dart';
import 'package:newspal/features/auth/auth_repository.dart';

import 'support/fakes.dart';

/// Logout confirmation, forgot password and the security question (SPEC-v2 §3, §7).
void main() {
  late Fakes fakes;

  setUp(() => fakes = Fakes());

  Future<void> pumpApp(WidgetTester tester) async {
    tester.view.physicalSize = const Size(1080, 2400);
    tester.view.devicePixelRatio = 3;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(ProviderScope(overrides: fakes.overrides, child: const NewsPalApp()));
    await tester.pumpAndSettle();
  }

  Future<void> openSettings(WidgetTester tester) async {
    await tester.tap(find.text('Settings'));
    await tester.pumpAndSettle();
  }

  group('logging out', () {
    setUp(() {
      fakes.storeSession();
      fakes.categories.followed = ['business'];
    });

    testWidgets('Cancel keeps the user signed in', (tester) async {
      await pumpApp(tester);
      await openSettings(tester);
      await tester.tap(find.text('Log out'));
      await tester.pumpAndSettle();
      expect(find.text("You'll need to log in again to see your feed."), findsOneWidget);
      await tester.tap(find.text('Cancel'));
      await tester.pumpAndSettle();
      expect(find.text('Signed in as'), findsOneWidget);
      expect(fakes.store.session, isNotNull);
    });

    testWidgets('tapping outside the dialog keeps the user signed in', (tester) async {
      await pumpApp(tester);
      await openSettings(tester);
      await tester.tap(find.text('Log out'));
      await tester.pumpAndSettle();
      await tester.tapAt(const Offset(10, 10));
      await tester.pumpAndSettle();
      expect(find.text('Log out?'), findsNothing);
      expect(fakes.store.session, isNotNull);
    });
  });

  group('forgot password', () {
    setUp(() {
      fakes.auth.users['r@example.com'] = 'old-password';
      fakes.auth.questions['r@example.com'] = (1, 'Mr. Whiskers');
      fakes.categories.followed = ['business'];
    });

    Future<void> startReset(WidgetTester tester) async {
      await pumpApp(tester);
      await tester.enterText(find.widgetWithText(TextFormField, 'Email'), 'r@example.com');
      await tester.tap(find.text('Forgot password?'));
      await tester.pumpAndSettle();
      expect(find.text('Forgot your password?'), findsOneWidget);
      expect(find.widgetWithText(TextFormField, 'r@example.com'), findsOneWidget, reason: 'email carried over');
      await tester.tap(find.widgetWithText(FilledButton, 'Continue'));
      await tester.pumpAndSettle();
    }

    Future<void> submitAnswer(WidgetTester tester, String answer) async {
      await tester.enterText(find.widgetWithText(TextFormField, 'Answer'), answer);
      await tester.enterText(find.widgetWithText(TextFormField, 'New password'), 'new-password');
      await tester.enterText(find.widgetWithText(TextFormField, 'Confirm new password'), 'new-password');
      await tester.tap(find.widgetWithText(FilledButton, 'Reset password'));
      await tester.pumpAndSettle();
    }

    testWidgets('the right answer sets a new password and signs in', (tester) async {
      await startReset(tester);
      expect(find.text('What was the name of your first pet?'), findsOneWidget);

      await submitAnswer(tester, 'Rex');
      expect(find.text("That answer doesn't match. 4 attempts left."), findsOneWidget);

      await submitAnswer(tester, '  mr whiskers ');
      expect(find.text('Password updated'), findsOneWidget);
      expect(find.text('No news yet'), findsOneWidget, reason: 'on the feed');
      expect(fakes.auth.users['r@example.com'], 'new-password');
      expect(fakes.store.session?.token, 'token-r@example.com');
    });

    testWidgets('too many wrong answers lock the form', (tester) async {
      fakes.auth.attemptsLeft['r@example.com'] = 1;
      await startReset(tester);
      await submitAnswer(tester, 'Rex');
      expect(find.text('Too many attempts. Try again tomorrow.'), findsOneWidget);
      final button = tester.widget<FilledButton>(find.widgetWithText(FilledButton, 'Reset password'));
      expect(button.onPressed, isNull);
    });

    testWidgets('new passwords must match', (tester) async {
      await startReset(tester);
      await tester.enterText(find.widgetWithText(TextFormField, 'Answer'), 'Mr. Whiskers');
      await tester.enterText(find.widgetWithText(TextFormField, 'New password'), 'new-password');
      await tester.enterText(find.widgetWithText(TextFormField, 'Confirm new password'), 'other-password');
      await tester.tap(find.widgetWithText(FilledButton, 'Reset password'));
      await tester.pumpAndSettle();
      expect(find.text("Passwords don't match"), findsOneWidget);
      expect(fakes.auth.calls, isNot(contains('resetPassword r@example.com')));
    });

    testWidgets('back to log in leaves the flow', (tester) async {
      await startReset(tester);
      await tester.tap(find.text('Back to log in'));
      await tester.pumpAndSettle();
      expect(find.text('Welcome back'), findsOneWidget);
    });
  });

  group('security question', () {
    setUp(() {
      fakes.storeSession();
      fakes.categories.followed = ['business'];
      fakes.auth.users['me@example.com'] = 'password123';
    });

    testWidgets('long questions wrap instead of being cropped', (tester) async {
      const long = 'What is the name of the street you lived on as a child, and in which town was it?';
      fakes.auth.offered = const [SecurityQuestion(id: 1, text: long)];
      fakes.auth.meHasQuestion = false;
      await pumpApp(tester);
      tester.view.physicalSize = const Size(960, 2400);
      await tester.tap(find.text('Set it'));
      await tester.pumpAndSettle();

      void expectWholeAndWrapped(Finder text) {
        expect(tester.renderObject<RenderParagraph>(text).didExceedMaxLines, isFalse);
        expect(tester.getSize(text).height, greaterThan(30), reason: 'should take more than one line');
      }

      await tester.tap(find.byKey(const ValueKey('security-question')));
      await tester.pumpAndSettle();
      expectWholeAndWrapped(find.text(long).last);

      await tester.tap(find.text(long).last);
      await tester.pumpAndSettle();
      expectWholeAndWrapped(find.text(long).first);
    });

    testWidgets('accounts without one see a banner that leads to Settings', (tester) async {
      fakes.auth.meHasQuestion = false;
      await pumpApp(tester);
      expect(find.byKey(const ValueKey('security-banner')), findsOneWidget);

      await tester.tap(find.text('Set it'));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('security-question')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('What was your favourite subject at school?').last);
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const ValueKey('security-answer')), 'History');

      await tester.enterText(find.widgetWithText(TextFormField, 'Current password'), 'wrong-one');
      await tester.tap(find.widgetWithText(FilledButton, 'Save'));
      await tester.pumpAndSettle();
      expect(find.text("That password isn't right"), findsOneWidget);
      expect(fakes.store.session, isNotNull, reason: 'a wrong password must not sign out');

      await tester.enterText(find.widgetWithText(TextFormField, 'Current password'), 'password123');
      await tester.tap(find.widgetWithText(FilledButton, 'Save'));
      await tester.pumpAndSettle();
      expect(find.text('Security question saved'), findsOneWidget);
      expect(fakes.auth.questions['me@example.com'], (2, 'History'));

      await tester.tap(find.text('Feed'));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('security-banner')), findsNothing);
    });

    testWidgets('Not now hides the banner', (tester) async {
      fakes.auth.meHasQuestion = false;
      await pumpApp(tester);
      await tester.tap(find.text('Not now'));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('security-banner')), findsNothing);
    });

    testWidgets('accounts with one see no banner', (tester) async {
      await pumpApp(tester);
      expect(find.byKey(const ValueKey('security-banner')), findsNothing);
    });
  });
}
