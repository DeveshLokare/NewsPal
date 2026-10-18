import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:newspal/app/app.dart';
import 'package:newspal/features/feed/article.dart';

import 'support/fakes.dart';

void main() {
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

  Future<void> openArchiveTab(WidgetTester tester) async {
    await tester.tap(find.text('Archive'));
    await tester.pumpAndSettle();
  }

  IconData bookmarkIcon(WidgetTester tester, String url) =>
      tester.widget<Icon>(find.descendant(of: find.byKey(ValueKey('bookmark-$url')), matching: find.byType(Icon))).icon!;

  testWidgets('bookmarking from the feed shows up in the archive', (tester) async {
    fakes.feed.pages['|'] = FeedPageData(items: [article('1')], nextCursor: null);
    await pumpApp(tester);
    const url = 'https://example.com/1';
    expect(bookmarkIcon(tester, url), Icons.bookmark_border);

    await tester.tap(find.byKey(const ValueKey('bookmark-$url')));
    await tester.pumpAndSettle();
    expect(bookmarkIcon(tester, url), Icons.bookmark);
    expect(fakes.archive.items.single.url, url);

    await openArchiveTab(tester);
    expect(find.text('Story 1'), findsOneWidget);
  });

  testWidgets('a failed bookmark reverts and explains', (tester) async {
    fakes.feed.pages['|'] = FeedPageData(items: [article('1')], nextCursor: null);
    fakes.archive.error = networkError();
    await pumpApp(tester);
    await tester.tap(find.byKey(const ValueKey('bookmark-https://example.com/1')));
    await tester.pumpAndSettle();
    expect(bookmarkIcon(tester, 'https://example.com/1'), Icons.bookmark_border);
    expect(find.text("Couldn't update your archive. Try again."), findsOneWidget);
  });

  testWidgets('empty archive invites saving stories', (tester) async {
    await pumpApp(tester);
    await openArchiveTab(tester);
    expect(find.text('Nothing archived yet'), findsOneWidget);
  });

  testWidgets('swipe removes, undo brings it back', (tester) async {
    fakes.archive
      ..seed('a')
      ..seed('b');
    await pumpApp(tester);
    await openArchiveTab(tester);
    expect(find.text('Saved a'), findsOneWidget);

    await tester.drag(find.text('Saved a'), const Offset(-600, 0));
    await tester.pumpAndSettle();
    expect(find.text('Saved a'), findsNothing);
    expect(find.text('Removed from archive'), findsOneWidget);
    expect(fakes.archive.items.map((i) => i.title), ['Saved b']);

    await tester.tap(find.text('Undo'));
    await tester.pumpAndSettle();
    expect(find.text('Saved a'), findsOneWidget);
    expect(fakes.archive.items.map((i) => i.title), containsAll(['Saved a', 'Saved b']));
  });

  testWidgets('tapping the filled bookmark in the archive also removes with undo', (tester) async {
    final a = fakes.archive.seed('a');
    await pumpApp(tester);
    await openArchiveTab(tester);
    await tester.tap(find.byKey(ValueKey('unarchive-${a.id}')));
    await tester.pumpAndSettle();
    expect(find.text('Saved a'), findsNothing);
    expect(find.text('Undo'), findsOneWidget);
  });

  testWidgets('un-archiving in the archive updates the feed bookmark', (tester) async {
    fakes.feed.pages['|'] = FeedPageData(items: [article('a', archived: true)], nextCursor: null);
    fakes.archive.seed('a');
    await pumpApp(tester);
    expect(bookmarkIcon(tester, 'https://example.com/a'), Icons.bookmark);

    await openArchiveTab(tester);
    await tester.drag(find.text('Saved a'), const Offset(-600, 0));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Feed'));
    await tester.pumpAndSettle();
    expect(bookmarkIcon(tester, 'https://example.com/a'), Icons.bookmark_border);
  });
}
