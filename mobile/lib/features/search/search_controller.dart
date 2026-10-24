import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api_client.dart';
import '../../core/api_error.dart';
import '../feed/article.dart';
import 'search_repository.dart';

/// What the search screen shows (SPEC-v2 §8.2).
class SearchState {
  const SearchState({
    this.query = '',
    this.category,
    this.items = const [],
    this.cursor,
    this.loading = false,
    this.loadingMore = false,
    this.error,
    this.notice,
    this.searched = false,
    this.recent = const [],
  });

  /// The text as typed.
  final String query;

  /// Category chip; null = all.
  final String? category;
  final List<Article> items;
  final String? cursor;
  final bool loading;
  final bool loadingMore;

  /// A failure worth a "Try again".
  final String? error;

  /// A calm message, e.g. searching too fast.
  final String? notice;

  /// Whether [items] are results for [query] (false while typing a too-short query).
  final bool searched;

  /// Up to 5 recent searches, this session only: ones submitted or whose result
  /// was opened, not every pause while typing.
  final List<String> recent;

  bool get hasMore => cursor != null;

  SearchState copyWith({
    String? query,
    String? Function()? category,
    List<Article>? items,
    String? Function()? cursor,
    bool? loading,
    bool? loadingMore,
    String? Function()? error,
    String? Function()? notice,
    bool? searched,
    List<String>? recent,
  }) =>
      SearchState(
        query: query ?? this.query,
        category: category != null ? category() : this.category,
        items: items ?? this.items,
        cursor: cursor != null ? cursor() : this.cursor,
        loading: loading ?? this.loading,
        loadingMore: loadingMore ?? this.loadingMore,
        error: error != null ? error() : this.error,
        notice: notice != null ? notice() : this.notice,
        searched: searched ?? this.searched,
        recent: recent ?? this.recent,
      );
}

final searchControllerProvider = NotifierProvider<NewsSearchController, SearchState>(NewsSearchController.new);

/// Searches as the user types: waits [debounce] after the last keystroke, and
/// only the newest request's answer is shown.
class NewsSearchController extends Notifier<SearchState> {
  static const debounce = Duration(milliseconds: 400);
  static const minChars = 2;

  Timer? _timer;

  /// Bumped for every new search; answers to older ones are dropped.
  int _generation = 0;

  SearchRepository get _repo => ref.read(searchRepositoryProvider);

  @override
  SearchState build() {
    // Per user: start over when someone else signs in.
    ref.watch(sessionTokenProvider);
    ref.onDispose(() => _timer?.cancel());
    return const SearchState();
  }

  /// The text changed: search after a pause, or clear if it's too short.
  void queryChanged(String text) {
    _timer?.cancel();
    state = state.copyWith(query: text);
    if (text.trim().length < minChars) {
      _generation++;
      state = state.copyWith(
          items: const [], cursor: () => null, loading: false, error: () => null, notice: () => null, searched: false);
      return;
    }
    _timer = Timer(debounce, _search);
  }

  /// Search now (keyboard "search" button or a recent search).
  void submit([String? text]) {
    _timer?.cancel();
    if (text != null) state = state.copyWith(query: text);
    if (state.query.trim().length < minChars) return;
    remember();
    _search();
  }

  /// Adds the current query to [SearchState.recent].
  void remember() {
    final query = state.query.trim();
    if (query.length < minChars) return;
    final rest = state.recent.where((r) => r.toLowerCase() != query.toLowerCase());
    state = state.copyWith(recent: [query, ...rest].take(5).toList());
  }

  void selectCategory(String? slug) {
    if (slug == state.category) return;
    state = state.copyWith(category: () => slug);
    if (state.query.trim().length >= minChars) {
      _timer?.cancel();
      _search();
    }
  }

  Future<void> loadMore() async {
    final current = state;
    if (!current.hasMore || current.loading || current.loadingMore) return;
    final generation = _generation;
    state = current.copyWith(loadingMore: true, error: () => null);
    try {
      final page = await _repo.search(query: current.query.trim(), category: current.category, cursor: current.cursor);
      if (generation != _generation) return;
      final seen = {for (final a in state.items) a.url};
      state = state.copyWith(
        items: [...state.items, ...page.items.where((a) => seen.add(a.url))],
        cursor: () => page.nextCursor,
        loadingMore: false,
      );
    } on ApiError catch (e) {
      if (generation != _generation) return;
      state = state.copyWith(loadingMore: false, error: () => _message(e));
    }
  }

  Future<void> _search() async {
    final generation = ++_generation;
    final query = state.query.trim();
    state = state.copyWith(loading: true, error: () => null, notice: () => null);
    try {
      final page = await _repo.search(query: query, category: state.category);
      if (generation != _generation) return;
      state = state.copyWith(
        items: page.items,
        cursor: () => page.nextCursor,
        loading: false,
        searched: true,
      );
    } on ApiError catch (e) {
      if (generation != _generation) return;
      final calm = e.code == 'SEARCH_RATE_LIMITED';
      state = state.copyWith(
        loading: false,
        searched: true,
        items: calm ? state.items : const [],
        cursor: calm ? null : () => null,
        error: calm ? null : () => _message(e),
        notice: calm ? () => "You're searching very fast. Wait a moment and try again." : null,
      );
    }
  }

  static String _message(ApiError e) => e.message;
}
