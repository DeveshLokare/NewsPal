import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:newspal/app/app.dart';
import 'package:newspal/app/theme.dart';

import 'support/fakes.dart';

void main() {
  late Fakes fakes;

  Future<void> pumpApp(WidgetTester tester) async {
    tester.view.physicalSize = const Size(1080, 2400);
    tester.view.devicePixelRatio = 3;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(ProviderScope(overrides: fakes.overrides, child: const NewsPalApp()));
    await tester.pumpAndSettle();
  }

  setUp(() => fakes = Fakes());

  testWidgets('signed-out user lands on login', (tester) async {
    await pumpApp(tester);
    expect(find.text('Welcome back'), findsOneWidget);
  });

  testWidgets('login validates input before calling the server', (tester) async {
    await pumpApp(tester);
    await tester.tap(find.text('Log in'));
    await tester.pumpAndSettle();
    expect(find.text('Enter your email'), findsOneWidget);
    expect(find.text('Enter your password'), findsOneWidget);
  });

  testWidgets('wrong password shows a friendly error', (tester) async {
    fakes.auth.users['a@example.com'] = 'password123';
    await pumpApp(tester);
    await tester.enterText(find.widgetWithText(TextFormField, 'Email'), 'a@example.com');
    await tester.enterText(find.widgetWithText(TextFormField, 'Password'), 'nope-nope');
    await tester.tap(find.text('Log in'));
    await tester.pumpAndSettle();
    expect(find.text('Wrong email or password.'), findsOneWidget);
  });

  testWidgets('register → pick categories → feed', (tester) async {
    await pumpApp(tester);
    await tester.tap(find.text('Create account'));
    await tester.pumpAndSettle();

    await tester.enterText(find.widgetWithText(TextFormField, 'Email'), 'new@example.com');
    await tester.enterText(find.widgetWithText(TextFormField, 'Password'), 'password123');
    await tester.enterText(find.widgetWithText(TextFormField, 'Confirm password'), 'password123');
    await tester.tap(find.widgetWithText(FilledButton, 'Create account'));
    await tester.pumpAndSettle();

    expect(find.text('What do you want to read?'), findsOneWidget);
    final continueButton = find.widgetWithText(FilledButton, 'Pick at least one');
    expect(tester.widget<FilledButton>(continueButton).onPressed, isNull);

    await tester.tap(find.byKey(const ValueKey('category-sports')));
    await tester.tap(find.byKey(const ValueKey('category-technology')));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Continue (2)'));
    await tester.pumpAndSettle();

    expect(fakes.categories.saved.single, ['technology', 'sports'], reason: 'saved in catalog order');
    expect(find.text('No news yet'), findsOneWidget);
  });

  testWidgets('duplicate email is shown on the email field', (tester) async {
    fakes.auth.users['taken@example.com'] = 'password123';
    await pumpApp(tester);
    await tester.tap(find.text('Create account'));
    await tester.pumpAndSettle();
    await tester.enterText(find.widgetWithText(TextFormField, 'Email'), 'taken@example.com');
    await tester.enterText(find.widgetWithText(TextFormField, 'Password'), 'password123');
    await tester.enterText(find.widgetWithText(TextFormField, 'Confirm password'), 'password123');
    await tester.tap(find.widgetWithText(FilledButton, 'Create account'));
    await tester.pumpAndSettle();
    expect(find.text('This email is already registered. Log in instead?'), findsOneWidget);
  });

  testWidgets('restored session with categories opens the feed; logout returns to login', (tester) async {
    fakes.storeSession();
    fakes.categories.followed = ['business'];
    await pumpApp(tester);
    expect(find.text('No news yet'), findsOneWidget);

    await tester.tap(find.text('Settings'));
    await tester.pumpAndSettle();
    expect(find.text('me@example.com'), findsOneWidget);
    await tester.tap(find.text('Log out'));
    await tester.pumpAndSettle();
    expect(find.text('Welcome back'), findsOneWidget);
    expect(fakes.store.session, isNull);
  });

  testWidgets('editing categories from settings saves and goes back', (tester) async {
    // Dark mode is the default look and where white-on-lime showed up.
    tester.platformDispatcher.platformBrightnessTestValue = Brightness.dark;
    addTearDown(tester.platformDispatcher.clearPlatformBrightnessTestValue);
    fakes.storeSession();
    fakes.categories.followed = ['business'];
    await pumpApp(tester);
    await tester.tap(find.text('Settings'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Your categories'));
    await tester.pumpAndSettle();

    final business = tester.widget<FilterChip>(find.byKey(const ValueKey('category-business')));
    expect(business.selected, isTrue);
    // Text on lime must be charcoal, never white (SPEC §9.1a).
    expect(business.labelStyle?.color, Brand.charcoal);
    final sports = tester.widget<FilterChip>(find.byKey(const ValueKey('category-sports')));
    expect(sports.labelStyle?.color, darkScheme.onSurface);
    await tester.tap(find.byKey(const ValueKey('category-sports')));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Save'));
    await tester.pumpAndSettle();

    expect(fakes.categories.saved.single, ['business', 'sports']);
    expect(find.text('Categories updated'), findsOneWidget);
    expect(find.text('Signed in as'), findsOneWidget);
  });
}
