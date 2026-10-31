import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:newspal/app/app.dart';
import 'package:newspal/core/api_error.dart';
import 'package:newspal/features/feed/article.dart';
import 'package:newspal/features/feed/feed_controller.dart';
import 'package:newspal/features/feed/feed_page.dart';

import 'support/fakes.dart';

void main() {
  late Fakes fakes;

  setUp(() {
    fakes = Fakes()
      ..storeSession()
      ..categories.followed = ['technology', 'sports'];
  });

  Future<void> pumpApp(WidgetTester tester) async {
    tester.view.physicalSize = const Size(1080, 2400);
    tester.view.devicePixelRatio = 3;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(ProviderScope(overrides: fakes.overrides, child: const NewsPalApp()));
    await tester.pumpAndSettle();
  }

  testWidgets('shows chips for followed categories and labelled cards in All', (tester) async {
    fakes.feed.pages['|'] = FeedPageData(
      items: [article('1', category: 'technology'), article('2', category: 'sports')],
      nextCursor: null,
      lastRefreshedAt: DateTime.now().toUtc().subtract(const Duration(hours: 2)),
    );
    await pumpApp(tester);

    expect(find.byKey(const ValueKey('chip-all')), findsOneWidget);
    expect(find.byKey(const ValueKey('chip-technology')), findsOneWidget);
    expect(find.byKey(const ValueKey('chip-sports')), findsOneWidget);
    expect(find.byKey(const ValueKey('chip-business')), findsNothing);
    expect(find.text('Story 1'), findsOneWidget);
    expect(find.text('Story 2'), findsOneWidget);
    expect(find.text('Sports'), findsNWidgets(2), reason: 'chip + card label');
    expect(find.text('Updated 2h ago'), findsOneWidget);
    expect(find.text('Pick a category above to load older news.'), findsOneWidget);
  });

  group('fresh news without a pull', () {
    int reads() => fakes.feed.calls.where((c) => c == 'feed |').length;

    void reopen(WidgetTester tester) {
      for (final state in [
        AppLifecycleState.inactive,
        AppLifecycleState.hidden,
        AppLifecycleState.paused,
        AppLifecycleState.hidden,
        AppLifecycleState.inactive,
        AppLifecycleState.resumed,
      ]) {
        tester.binding.handleAppLifecycleStateChanged(state);
      }
    }

    testWidgets('new stories appear when the app is reopened', (tester) async {
      fakes.feed.pages['|'] = FeedPageData(items: [article('1')], nextCursor: null);
      await pumpApp(tester);
      final container = ProviderScope.containerOf(tester.element(find.byType(FeedList)));
      final later = DateTime.now().add(FeedController.reloadGap);
      container.read(feedControllerProvider(null).notifier).now = () => later;
      fakes.feed.pages['|'] = FeedPageData(items: [article('2'), article('1')], nextCursor: null);

      reopen(tester);
      await tester.pumpAndSettle();
      expect(find.text('Story 2'), findsOneWidget);
    });

    testWidgets('scrolled down, a button brings them in', (tester) async {
      fakes.feed.pages['|'] = FeedPageData(items: [for (var i = 0; i < 12; i++) article('$i')], nextCursor: null);
      await pumpApp(tester);
      await tester.drag(find.byType(ListView).last, const Offset(0, -600));
      await tester.pumpAndSettle();
      final container = ProviderScope.containerOf(tester.element(find.byType(FeedList)));
      fakes.feed.pages['|'] =
          FeedPageData(items: [article('new'), for (var i = 0; i < 12; i++) article('$i')], nextCursor: null);

      await container.read(feedControllerProvider(null).notifier).silentRefresh();
      await tester.pumpAndSettle();
      expect(find.text('Story new'), findsNothing);

      await tester.tap(find.byKey(const ValueKey('new-stories')));
      await tester.pumpAndSettle();
      expect(find.text('Story new'), findsOneWidget);
      expect(find.byKey(const ValueKey('new-stories')), findsNothing);
    });

    testWidgets('a quick app switch sends no request', (tester) async {
      fakes.feed.pages['|'] = FeedPageData(items: [article('1')], nextCursor: null);
      await pumpApp(tester);
      final before = reads();
      reopen(tester);
      await tester.pumpAndSettle();
      expect(reads(), before);
    });
  });

  testWidgets('a chip filters the feed', (tester) async {
    fakes.feed.pages['|'] = FeedPageData(items: [article('1'), article('2', category: 'sports')], nextCursor: null);
    fakes.feed.pages['sports|'] = FeedPageData(items: [article('2', category: 'sports')], nextCursor: null);
    await pumpApp(tester);

    await tester.tap(find.byKey(const ValueKey('chip-sports')));
    await tester.pumpAndSettle();
    expect(find.text('Story 1'), findsNothing);
    expect(find.text('Story 2'), findsOneWidget);
    expect(fakes.feed.calls, contains('feed sports|'));
  });

  testWidgets('scrolling to the end loads the next page', (tester) async {
    fakes.feed.pages['|'] = FeedPageData(items: [article('1')], nextCursor: 'c2');
    fakes.feed.pages['|c2'] = FeedPageData(items: [article('2')], nextCursor: null);
    await pumpApp(tester);
    expect(find.text('Story 2'), findsOneWidget);
    expect(fakes.feed.calls, ['feed |', 'feed |c2']);
  });

  testWidgets('older news for a category', (tester) async {
    fakes.feed.pages['technology|'] = FeedPageData(items: [article('1')], nextCursor: null);
    fakes.feed.olderPages['technology|'] = FeedPageData(items: [article('7', hoursAgo: 240)], nextCursor: null);
    await pumpApp(tester);
    await tester.tap(find.byKey(const ValueKey('chip-technology')));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Load older news'));
    await tester.pumpAndSettle();
    expect(find.text('Story 7'), findsOneWidget);
    expect(find.text("You're all caught up."), findsOneWidget);
  });

  testWidgets('older news unavailable shows a calm notice', (tester) async {
    fakes.feed.olderError = ApiError(code: 'QUOTA_EXHAUSTED', message: 'x', status: 429);
    await pumpApp(tester);
    await tester.tap(find.byKey(const ValueKey('chip-sports')));
    await tester.pumpAndSettle();
    expect(find.text('No news yet'), findsOneWidget);

    await tester.tap(find.text('Load older news'));
    await tester.pumpAndSettle();
    expect(find.textContaining('Older news is unavailable right now'), findsOneWidget);
  });

  testWidgets('tapping a card opens the article', (tester) async {
    fakes.feed.pages['|'] = FeedPageData(items: [article('1', source: 'bbc.com')], nextCursor: null);
    await pumpApp(tester);
    await tester.tap(find.text('Story 1'));
    await tester.pumpAndSettle();
    expect(find.text('Read on bbc.com'), findsOneWidget);
    expect(find.text('Summary of story 1'), findsOneWidget);

    await tester.pageBack();
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('chip-all')), findsOneWidget);
  });
}
