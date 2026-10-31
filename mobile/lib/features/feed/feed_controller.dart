import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api_client.dart';
import '../../core/api_error.dart';
import 'article.dart';
import 'feed_repository.dart';

/// Where the list is in its paging (SPEC §4.1): first the stored articles, then
/// optionally live older news for a single category.
enum FeedPhase {
  /// Stored articles; [FeedState.cursor] continues them.
  stored,

  /// Stored articles are exhausted; older news can be requested.
  storedDone,

  /// Showing older news; [FeedState.cursor] continues it.
  older,

  /// Nothing more to load.
  done,
}

class FeedState {
  const FeedState({
    required this.items,
    required this.phase,
    this.cursor,
    this.loadingMore = false,
    this.loadMoreError,
    this.notice,
    this.lastRefreshedAt,
    this.pending,
  });

  final List<Article> items;
  final FeedPhase phase;
  final String? cursor;
  final bool loadingMore;

  /// A failed "load more"; the list stays and the footer offers a retry.
  final String? loadMoreError;

  /// Informational footer message, e.g. older news being unavailable.
  final String? notice;
  final DateTime? lastRefreshedAt;

  /// A newer first page, held back because the user has scrolled down.
  final FeedPageData? pending;

  FeedState copyWith({
    List<Article>? items,
    FeedPhase? phase,
    String? Function()? cursor,
    bool? loadingMore,
    String? Function()? loadMoreError,
    String? Function()? notice,
    DateTime? lastRefreshedAt,
    FeedPageData? Function()? pending,
  }) =>
      FeedState(
        items: items ?? this.items,
        phase: phase ?? this.phase,
        cursor: cursor != null ? cursor() : this.cursor,
        loadingMore: loadingMore ?? this.loadingMore,
        loadMoreError: loadMoreError != null ? loadMoreError() : this.loadMoreError,
        notice: notice != null ? notice() : this.notice,
        lastRefreshedAt: lastRefreshedAt ?? this.lastRefreshedAt,
        pending: pending != null ? pending() : this.pending,
      );
}

/// One paged feed per chip: `null` = all followed categories, else a slug.
final feedControllerProvider =
    AsyncNotifierProvider.family<FeedController, FeedState, String?>(FeedController.new);

class FeedController extends AsyncNotifier<FeedState> {
  FeedController(this.category);

  /// How often to look for new articles while the backend refreshes, and how many
  /// times: 2 minutes in all (SPEC-v2.1 §6.4).
  static const pollEvery = Duration(seconds: 10);
  static const maxPolls = 12;

  /// A list this fresh isn't reloaded on reopening the app or returning to its chip.
  static const reloadGap = Duration(seconds: 30);

  final String? category;

  /// Whether the list is scrolled to the top; set by the page.
  bool atTop = true;

  @visibleForTesting
  DateTime Function() now = DateTime.now;

  Timer? _poll;
  int _pollsLeft = 0;

  /// Polling was paused and should pick up again on the next reload.
  bool _pollPaused = false;
  DateTime? _loadedAt;

  /// Bumped on every full load; older silent answers are dropped.
  int _generation = 0;

  FeedRepository get _repo => ref.read(feedRepositoryProvider);

  /// Older news is fetched one category at a time, to save the API quota.
  bool get canLoadOlder => category != null;

  @override
  Future<FeedState> build() async {
    // Per user: start over when someone else signs in.
    ref.watch(sessionTokenProvider);
    ref.onDispose(_stopPolling);
    _generation++;
    final page = await _repo.feed(category: category);
    _loaded(page);
    return _firstPage(page);
  }

  FeedState _firstPage(FeedPageData page) => FeedState(
        items: page.items,
        phase: page.nextCursor != null ? FeedPhase.stored : FeedPhase.storedDone,
        cursor: page.nextCursor,
        lastRefreshedAt: page.lastRefreshedAt,
      );

  /// [polled]: the answer to a poll, which never starts polling again. That would
  /// outlive [maxPolls], or restart it after [pausePolling].
  void _loaded(FeedPageData page, {bool polled = false}) {
    _loadedAt = now();
    if (!page.refreshing) {
      _stopPolling();
      _pollPaused = false;
    } else if (!polled) {
      _pollPaused = false;
      if (_poll != null) return;
      _pollsLeft = maxPolls;
      _poll = Timer.periodic(pollEvery, (_) {
        if (--_pollsLeft <= 0) _stopPolling();
        unawaited(silentRefresh(polled: true));
      });
    }
  }

  void _stopPolling() {
    _poll?.cancel();
    _poll = null;
  }

  /// Stops polling while the list isn't on screen; [reloadIfDue] resumes it.
  void pausePolling() {
    if (_poll == null) return;
    _stopPolling();
    _pollPaused = true;
  }

  /// The app was reopened or this list's chip was selected again.
  Future<void> reloadIfDue() async {
    final loadedAt = _loadedAt;
    if (_pollPaused || loadedAt == null || now().difference(loadedAt) >= reloadGap) {
      await silentRefresh();
    }
  }

  /// Reloads the first page without a spinner. New stories replace the list at the
  /// top; further down they wait in [FeedState.pending], so nothing jumps. A failure
  /// changes nothing.
  Future<void> silentRefresh({bool polled = false}) async {
    if (state.value == null) return;
    final generation = _generation;
    final FeedPageData page;
    try {
      page = await _repo.feed(category: category);
    } on ApiError {
      if (generation == _generation) _stopPolling();
      return;
    }
    final latest = state.value;
    if (generation != _generation || latest == null || latest.loadingMore) return;
    _loaded(page, polled: polled);
    final known = {for (final a in latest.items) a.url};
    final hasNew = page.items.any((a) => !known.contains(a.url));
    if (hasNew && atTop) {
      state = AsyncData(_firstPage(page));
    } else {
      state = AsyncData(latest.copyWith(
        lastRefreshedAt: page.lastRefreshedAt,
        pending: hasNew ? () => page : null,
      ));
    }
  }

  /// "New stories": shows what [silentRefresh] held back.
  void showPending() {
    final pending = state.value?.pending;
    if (pending != null) state = AsyncData(_firstPage(pending));
  }

  /// Pull-to-refresh: reload the first page (which also nudges the backend to
  /// refresh stale categories).
  Future<void> refresh() async {
    ref.invalidateSelf();
    await future;
  }

  /// Infinite scroll: the next page of whatever phase we're in.
  Future<void> loadMore() async {
    final current = state.value;
    if (current == null || current.loadingMore) return;
    switch (current.phase) {
      case FeedPhase.stored:
        await _append(current, () => _repo.feed(category: category, cursor: current.cursor), FeedPhase.stored,
            FeedPhase.storedDone);
      case FeedPhase.older:
        await _append(current, () => _repo.older(category: category!, cursor: current.cursor), FeedPhase.older,
            FeedPhase.done);
      case FeedPhase.storedDone:
      case FeedPhase.done:
        return;
    }
  }

  /// "Load older news" (US-9).
  Future<void> loadOlder() async {
    final current = state.value;
    if (current == null || current.loadingMore || !canLoadOlder) return;
    if (current.phase != FeedPhase.storedDone) return;
    await _append(current, () => _repo.older(category: category!), FeedPhase.older, FeedPhase.done);
  }

  Future<void> _append(FeedState current, Future<FeedPageData> Function() fetch, FeedPhase more, FeedPhase end) async {
    state = AsyncData(current.copyWith(loadingMore: true, loadMoreError: () => null));
    try {
      final page = await fetch();
      final seen = {for (final a in current.items) a.url};
      final fresh = page.items.where((a) => seen.add(a.url)).toList();
      state = AsyncData(current.copyWith(
        items: [...current.items, ...fresh],
        phase: page.nextCursor != null ? more : end,
        cursor: () => page.nextCursor,
        loadingMore: false,
        // `current` may still carry the error from an earlier attempt.
        loadMoreError: () => null,
      ));
    } on ApiError catch (e) {
      final latest = state.value ?? current;
      switch (e.code) {
        case 'QUOTA_EXHAUSTED':
          state = AsyncData(latest.copyWith(
            loadingMore: false,
            phase: FeedPhase.done,
            notice: () => 'Older news is unavailable right now. Check back later.',
          ));
        case 'OLDER_RATE_LIMITED':
          state = AsyncData(latest.copyWith(
            loadingMore: false,
            phase: FeedPhase.done,
            notice: () => "You've loaded a lot of older news today. More tomorrow.",
          ));
        case 'BAD_CURSOR':
          // Cursors for older news expire after an hour; start that list again.
          state = AsyncData(latest.copyWith(
            loadingMore: false,
            phase: latest.phase == FeedPhase.older ? FeedPhase.storedDone : latest.phase,
            cursor: () => null,
            loadMoreError: () => 'That list expired. Try again.',
          ));
        default:
          state = AsyncData(latest.copyWith(loadingMore: false, loadMoreError: () => e.message));
      }
    }
  }
}
