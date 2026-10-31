import 'dart:async';

import 'package:fake_async/fake_async.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:newspal/app/app.dart';
import 'package:newspal/core/api_error.dart';
import 'package:newspal/features/feed/article.dart';
import 'package:newspal/features/search/search_controller.dart';
import 'package:newspal/features/search/search_repository.dart';

import 'support/fakes.dart';

/// A repository whose answers the test releases one by one.
class _SlowSearch implements SearchRepository {
  final List<(String, Completer<FeedPageData>)> pending = [];

  @override
  Future<FeedPageData> search({required String query, String? cursor, int size = 20}) {
    final completer = Completer<FeedPageData>();
    pending.add((query, completer));
    return completer.future;
  }
}

void main() {
  group('NewsSearchController', () {
    late Fakes fakes;
    late ProviderContainer container;

    setUp(() {
      fakes = Fakes();
      container = ProviderContainer(overrides: fakes.overrides);
    });

    tearDown(() => container.dispose());

    NewsSearchController controller() => container.read(searchControllerProvider.notifier);
    SearchState state() => container.read(searchControllerProvider);

    test('waits for a pause in typing before searching', () {
      fakeAsync((async) {
        container.read(searchControllerProvider);
        controller().queryChanged('te');
        controller().queryChanged('tes');
        controller().queryChanged('tesla');
        async.elapse(const Duration(milliseconds: 399));
        expect(fakes.search.calls, isEmpty);
        async.elapse(const Duration(milliseconds: 1));
        async.flushMicrotasks();
        expect(fakes.search.calls, ['tesla|']);
        expect(state().searched, isTrue);
      });
    });

    test('too-short text clears results without searching', () {
      fakeAsync((async) {
        fakes.search.pages['tesla|'] = FeedPageData(items: [article('1')], nextCursor: null);
        controller().queryChanged('tesla');
        async.elapse(NewsSearchController.debounce);
        async.flushMicrotasks();
        expect(state().items, hasLength(1));
        controller().queryChanged('t');
        async.elapse(NewsSearchController.debounce);
        expect(state().items, isEmpty);
        expect(state().searched, isFalse);
        expect(fakes.search.calls, ['tesla|']);
      });
    });

    test('only the newest answer is shown', () async {
      final slow = _SlowSearch();
      fakes.searchOverride = slow;
      final c = ProviderContainer(overrides: fakes.overrides);
      addTearDown(c.dispose);
      final notifier = c.read(searchControllerProvider.notifier);
      notifier.submit('apple');
      notifier.submit('samsung');
      slow.pending[1].$2.complete(FeedPageData(items: [article('2')], nextCursor: null));
      await Future<void>.delayed(Duration.zero);
      slow.pending[0].$2.complete(FeedPageData(items: [article('1')], nextCursor: null));
      await Future<void>.delayed(Duration.zero);
      expect(c.read(searchControllerProvider).items.single.url, 'https://example.com/2');
    });

    test('remembers submitted searches, newest first, up to 5', () {
      for (final q in ['one', 'two', 'three', 'four', 'five', 'six', 'TWO']) {
        controller().submit(q);
      }
      expect(state().recent, ['TWO', 'six', 'five', 'four', 'three']);
    });

    test('searching too fast is a calm notice that keeps results', () async {
      fakes.search.pages['tesla|'] = FeedPageData(items: [article('1')], nextCursor: null);
      controller().submit('tesla');
      await Future<void>.delayed(Duration.zero);
      fakes.search.error = ApiError(code: 'SEARCH_RATE_LIMITED', message: 'slow down', status: 429);
      controller().submit('tesla');
      await Future<void>.delayed(Duration.zero);
      expect(state().items, hasLength(1));
      expect(state().notice, contains('searching very fast'));
      expect(state().error, isNull);
    });
  });

  group('SearchPage', () {
    late Fakes fakes;

    setUp(() {
      fakes = Fakes()
        ..storeSession()
        ..categories.followed = ['technology'];
    });

    Future<void> pumpApp(WidgetTester tester) async {
      tester.view.physicalSize = const Size(1080, 2400);
      tester.view.devicePixelRatio = 3;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(ProviderScope(overrides: fakes.overrides, child: const NewsPalApp()));
      await tester.pumpAndSettle();
    }

    Future<void> type(WidgetTester tester, String text) async {
      await tester.enterText(find.byKey(const ValueKey('search-field')), text);
      await tester.pump(NewsSearchController.debounce);
      await tester.pumpAndSettle();
    }

    testWidgets('finds stories in any category, with no category filter', (tester) async {
      // Business isn't followed (only technology is), but search covers it.
      fakes.search.pages['launch|'] = FeedPageData(
        items: [article('1', category: 'technology'), article('2', category: 'business')],
        nextCursor: null,
      );
      await pumpApp(tester);
      await tester.tap(find.byKey(const ValueKey('open-search')));
      await tester.pumpAndSettle();
      expect(find.text('Search news from the last 7 days, across every category.'), findsOneWidget);

      await type(tester, 'launch');
      expect(find.text('Story 1'), findsOneWidget);
      expect(find.text('Story 2'), findsOneWidget);
      expect(find.byType(ChoiceChip), findsNothing);
    });

    testWidgets('says when nothing matches', (tester) async {
      await pumpApp(tester);
      await tester.tap(find.byKey(const ValueKey('open-search')));
      await tester.pumpAndSettle();
      await type(tester, 'zebra');
      expect(find.text('No results for "zebra"'), findsOneWidget);
    });

    testWidgets('a failed search offers to try again', (tester) async {
      fakes.search.error = networkError();
      await pumpApp(tester);
      await tester.tap(find.byKey(const ValueKey('open-search')));
      await tester.pumpAndSettle();
      await type(tester, 'tesla');
      expect(find.text('offline'), findsOneWidget);
      fakes.search.error = null;
      fakes.search.pages['tesla|'] = FeedPageData(items: [article('1')], nextCursor: null);
      await tester.tap(find.text('Try again'));
      await tester.pumpAndSettle();
      expect(find.text('Story 1'), findsOneWidget);
    });
  });
}
