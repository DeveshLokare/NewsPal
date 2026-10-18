import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api_client.dart';
import '../../core/api_error.dart';
import 'archive_repository.dart';
import 'bookmarks.dart';

class ArchiveState {
  const ArchiveState({required this.items, this.cursor, this.loadingMore = false, this.loadMoreError});

  final List<ArchiveItem> items;

  /// Continues the list; null = everything loaded.
  final String? cursor;
  final bool loadingMore;
  final String? loadMoreError;

  ArchiveState copyWith({
    List<ArchiveItem>? items,
    String? Function()? cursor,
    bool? loadingMore,
    String? Function()? loadMoreError,
  }) =>
      ArchiveState(
        items: items ?? this.items,
        cursor: cursor != null ? cursor() : this.cursor,
        loadingMore: loadingMore ?? this.loadingMore,
        loadMoreError: loadMoreError != null ? loadMoreError() : this.loadMoreError,
      );
}

/// The archive tab (US-11, US-12): newest-archived first, paged.
final archiveControllerProvider = AsyncNotifierProvider<ArchiveController, ArchiveState>(ArchiveController.new);

class ArchiveController extends AsyncNotifier<ArchiveState> {
  ArchiveRepository get _repo => ref.read(archiveRepositoryProvider);

  @override
  Future<ArchiveState> build() async {
    ref.watch(sessionTokenProvider);
    final page = await _repo.list();
    return ArchiveState(items: page.items, cursor: page.nextCursor);
  }

  Future<void> refresh() async {
    ref.invalidateSelf();
    await future;
  }

  Future<void> loadMore() async {
    final current = state.value;
    if (current == null || current.loadingMore || current.cursor == null) return;
    state = AsyncData(current.copyWith(loadingMore: true, loadMoreError: () => null));
    try {
      final page = await _repo.list(cursor: current.cursor);
      final seen = {for (final i in current.items) i.id};
      state = AsyncData(current.copyWith(
        items: [...current.items, ...page.items.where((i) => seen.add(i.id))],
        cursor: () => page.nextCursor,
        loadingMore: false,
        loadMoreError: () => null,
      ));
    } on ApiError catch (e) {
      state = AsyncData((state.value ?? current).copyWith(loadingMore: false, loadMoreError: () => e.message));
    }
  }

  /// Removes [item] right away (swipe or bookmark tap). Returns its position so
  /// the UI can offer [undoRemove]. On failure the item is put back and
  /// [ApiError] is thrown.
  Future<int> remove(ArchiveItem item) async {
    final current = state.value;
    if (current == null) return -1;
    final index = current.items.indexWhere((i) => i.id == item.id);
    if (index < 0) return -1;
    _replaceItems([...current.items]..removeAt(index));
    ref.read(bookmarksProvider.notifier).set(item.url, false);
    try {
      await _repo.remove(item.id);
    } on ApiError {
      _insert(item, index);
      ref.read(bookmarksProvider.notifier).set(item.url, true);
      rethrow;
    }
    return index;
  }

  /// "Undo" from the snackbar: archives the article again at its old position.
  /// It gets a new id on the server; the stored copy replaces the old one.
  Future<void> undoRemove(ArchiveItem item, int index) async {
    _insert(item, index);
    ref.read(bookmarksProvider.notifier).set(item.url, true);
    try {
      final restored = await _repo.add(item.toArticle());
      final items = state.value?.items ?? const <ArchiveItem>[];
      _replaceItems([for (final i in items) i.id == item.id ? restored : i]);
    } on ApiError {
      final items = state.value?.items ?? const <ArchiveItem>[];
      _replaceItems(items.where((i) => i.id != item.id).toList());
      ref.read(bookmarksProvider.notifier).set(item.url, false);
      rethrow;
    }
  }

  void _insert(ArchiveItem item, int index) {
    final items = [...?state.value?.items];
    if (items.any((i) => i.id == item.id)) return;
    items.insert(index.clamp(0, items.length), item);
    _replaceItems(items);
  }

  void _replaceItems(List<ArchiveItem> items) {
    final current = state.value;
    if (current != null) state = AsyncData(current.copyWith(items: items));
  }
}
