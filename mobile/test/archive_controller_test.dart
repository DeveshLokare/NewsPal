import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:newspal/core/api_error.dart';
import 'package:newspal/features/archive/archive_controller.dart';
import 'package:newspal/features/archive/bookmarks.dart';

import 'support/fakes.dart';

void main() {
  late Fakes fakes;
  late ProviderContainer container;

  setUp(() {
    fakes = Fakes();
    container = ProviderContainer(overrides: fakes.overrides);
  });

  tearDown(() => container.dispose());

  Bookmarks bookmarks() => container.read(bookmarksProvider.notifier);
  ArchiveController archive() => container.read(archiveControllerProvider.notifier);
  Future<ArchiveState> loadArchive() => container.read(archiveControllerProvider.future);
  List<String> titles() => container.read(archiveControllerProvider).value!.items.map((i) => i.title).toList();
  ApiError failure() => ApiError(code: 'HTTP_500', message: 'boom', status: 500);

  group('bookmark toggle', () {
    test('archives, then un-archives by URL', () async {
      final story = article('1');
      expect(bookmarks().isArchived(story), isFalse);

      await bookmarks().toggle(story);
      expect(bookmarks().isArchived(story), isTrue);
      expect(fakes.archive.items.single.url, story.url);

      await bookmarks().toggle(story);
      expect(bookmarks().isArchived(story), isFalse);
      expect(fakes.archive.calls.last, 'removeByUrl ${story.url}');
    });

    test('respects the archived flag the feed item came with', () async {
      final saved = article('2', archived: true);
      expect(bookmarks().isArchived(saved), isTrue);
      await bookmarks().toggle(saved);
      expect(fakes.archive.calls, ['removeByUrl ${saved.url}']);
    });

    test('failure reverts and rethrows', () async {
      fakes.archive.error = failure();
      final story = article('3');
      await expectLater(bookmarks().toggle(story), throwsA(isA<ApiError>()));
      expect(bookmarks().isArchived(story), isFalse);
    });

    test('a toggle refreshes the archive list', () async {
      await loadArchive();
      await bookmarks().toggle(article('4'));
      expect((await loadArchive()).items.single.title, 'Story 4');
    });
  });

  group('archive list', () {
    test('pages through all items', () async {
      for (var i = 0; i < 5; i++) {
        fakes.archive.seed('$i');
      }
      fakes.archive.pageSize = 2;
      await loadArchive();
      await archive().loadMore();
      await archive().loadMore();
      expect(titles(), ['Saved 0', 'Saved 1', 'Saved 2', 'Saved 3', 'Saved 4']);
      expect(container.read(archiveControllerProvider).value!.cursor, isNull);
    });

    test('remove then undo restores the item at the same position', () async {
      fakes.archive
        ..seed('a')
        ..seed('b')
        ..seed('c');
      await loadArchive();
      final b = container.read(archiveControllerProvider).value!.items[1];

      final index = await archive().remove(b);
      expect(index, 1);
      expect(titles(), ['Saved a', 'Saved c']);
      expect(container.read(bookmarksProvider)[b.url], isFalse);

      await archive().undoRemove(b, index);
      expect(titles(), ['Saved a', 'Saved b', 'Saved c']);
      expect(container.read(bookmarksProvider)[b.url], isTrue);
      expect(fakes.archive.items.any((i) => i.url == b.url), isTrue, reason: 'archived again on the server');
    });

    test('failed remove puts the item back', () async {
      fakes.archive.seed('a');
      await loadArchive();
      final a = container.read(archiveControllerProvider).value!.items.single;
      fakes.archive.error = failure();
      await expectLater(archive().remove(a), throwsA(isA<ApiError>()));
      expect(titles(), ['Saved a']);
    });
  });
}
