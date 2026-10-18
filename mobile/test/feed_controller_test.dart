import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:newspal/core/api_error.dart';
import 'package:newspal/features/feed/article.dart';
import 'package:newspal/features/feed/feed_controller.dart';

import 'support/fakes.dart';

void main() {
  late Fakes fakes;
  late ProviderContainer container;

  setUp(() {
    fakes = Fakes();
    container = ProviderContainer(overrides: fakes.overrides);
  });

  tearDown(() => container.dispose());

  FeedController controller(String? category) => container.read(feedControllerProvider(category).notifier);
  Future<FeedState> load(String? category) => container.read(feedControllerProvider(category).future);
  FeedState current(String? category) => container.read(feedControllerProvider(category)).value!;
  List<String> urls(String? category) => current(category).items.map((a) => a.url).toList();

  test('first page, then the next page via the cursor', () async {
    fakes.feed.pages['|'] = FeedPageData(items: [article('1'), article('2')], nextCursor: 'c2');
    fakes.feed.pages['|c2'] = FeedPageData(items: [article('3')], nextCursor: null);

    expect((await load(null)).phase, FeedPhase.stored);
    await controller(null).loadMore();
    expect(urls(null), ['https://example.com/1', 'https://example.com/2', 'https://example.com/3']);
    expect(current(null).phase, FeedPhase.storedDone);
  });

  test('loadMore does nothing once stored articles run out', () async {
    fakes.feed.pages['|'] = FeedPageData(items: [article('1')], nextCursor: null);
    await load(null);
    await controller(null).loadMore();
    expect(fakes.feed.calls, ['feed |']);
  });

  test('"All" never loads older news', () async {
    await load(null);
    expect(controller(null).canLoadOlder, isFalse);
    await controller(null).loadOlder();
    expect(fakes.feed.calls.where((c) => c.startsWith('older')), isEmpty);
  });

  test('older news appends after stored articles and pages on, skipping duplicates', () async {
    fakes.feed.pages['sports|'] = FeedPageData(items: [article('1', category: 'sports')], nextCursor: null);
    fakes.feed.olderPages['sports|'] =
        FeedPageData(items: [article('1', category: 'sports'), article('9', category: 'sports')], nextCursor: 'o2');
    fakes.feed.olderPages['sports|o2'] = FeedPageData(items: [article('10', category: 'sports')], nextCursor: null);

    await load('sports');
    await controller('sports').loadOlder();
    expect(current('sports').phase, FeedPhase.older);
    expect(urls('sports'), ['https://example.com/1', 'https://example.com/9']);

    await controller('sports').loadMore();
    expect(urls('sports').last, 'https://example.com/10');
    expect(current('sports').phase, FeedPhase.done);
  });

  test('quota errors become a friendly notice and stop paging', () async {
    fakes.feed.olderError = ApiError(code: 'QUOTA_EXHAUSTED', message: 'x', status: 429);
    await load('sports');
    await controller('sports').loadOlder();
    final state = current('sports');
    expect(state.phase, FeedPhase.done);
    expect(state.notice, contains('unavailable'));
    expect(state.loadingMore, isFalse);
  });

  test('other failures keep the list and offer a retry', () async {
    fakes.feed.pages['sports|'] = FeedPageData(items: [article('1', category: 'sports')], nextCursor: null);
    fakes.feed.olderError = ApiError(code: 'PROVIDER_ERROR', message: "Couldn't load older news right now", status: 502);
    await load('sports');
    await controller('sports').loadOlder();
    final state = current('sports');
    expect(state.items, hasLength(1));
    expect(state.phase, FeedPhase.storedDone, reason: 'retry calls loadOlder again');
    expect(state.loadMoreError, "Couldn't load older news right now");

    fakes.feed.olderError = null;
    await controller('sports').loadOlder();
    expect(current('sports').loadMoreError, isNull);
  });

  test('refresh reloads the first page', () async {
    fakes.feed.pages['|'] = FeedPageData(items: [article('1')], nextCursor: null);
    await load(null);
    fakes.feed.pages['|'] = FeedPageData(items: [article('2'), article('1')], nextCursor: null);
    await controller(null).refresh();
    expect(urls(null).first, 'https://example.com/2');
  });
}
