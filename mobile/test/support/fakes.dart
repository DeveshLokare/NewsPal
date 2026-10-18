import 'package:flutter_riverpod/misc.dart' show Override;
import 'package:newspal/core/api_client.dart';
import 'package:newspal/core/api_error.dart';
import 'package:newspal/core/token_store.dart';
import 'package:newspal/features/archive/archive_repository.dart';
import 'package:newspal/features/auth/auth_repository.dart';
import 'package:newspal/features/categories/category_repository.dart';
import 'package:newspal/features/feed/article.dart';
import 'package:newspal/features/feed/article_page.dart';
import 'package:newspal/features/feed/feed_repository.dart';

ApiError networkError() => ApiError(code: ApiError.network, message: 'offline');

ApiError unauthorized() => ApiError(code: 'UNAUTHORIZED', message: 'Authentication required', status: 401);

class FakeAuthRepository implements AuthRepository {
  /// email → password
  final Map<String, String> users = {};
  ApiError? meError;
  String meEmail = 'me@example.com';
  int meCalls = 0;

  AuthResult _result(String email) =>
      AuthResult(token: 'token-$email', expiresAt: DateTime.now().toUtc().add(const Duration(days: 7)), email: email);

  @override
  Future<AuthResult> register(String email, String password) async {
    if (users.containsKey(email)) {
      throw ApiError(code: 'EMAIL_TAKEN', message: 'Email already registered', status: 409);
    }
    users[email] = password;
    return _result(email);
  }

  @override
  Future<AuthResult> login(String email, String password) async {
    if (users[email] != password) {
      throw ApiError(code: 'BAD_CREDENTIALS', message: 'Invalid email or password', status: 401);
    }
    return _result(email);
  }

  @override
  Future<String> me() async {
    meCalls++;
    if (meError != null) throw meError!;
    return meEmail;
  }
}

class FakeCategoryRepository implements CategoryRepository {
  List<Category> catalog = const [
    Category(slug: 'technology', name: 'Technology'),
    Category(slug: 'business', name: 'Business'),
    Category(slug: 'sports', name: 'Sports'),
  ];
  List<String> followed = [];
  List<List<String>> saved = [];

  @override
  Future<List<Category>> all() async => catalog;

  @override
  Future<List<String>> mine() async => List.of(followed);

  @override
  Future<void> saveMine(List<String> slugs) async {
    saved.add(slugs);
    followed = List.of(slugs);
  }
}

Article article(String id,
        {String? category = 'technology', int hoursAgo = 1, String? source = 'example.com', bool archived = false}) =>
    Article(
      id: int.tryParse(id),
      url: 'https://example.com/$id',
      title: 'Story $id',
      summary: 'Summary of story $id',
      imageUrl: null,
      source: source,
      publishedAt: DateTime.now().toUtc().subtract(Duration(hours: hoursAgo)),
      category: category,
      archived: archived,
    );

/// Serves scripted pages. Keys are `category|cursor` (empty strings for null).
class FakeFeedRepository implements FeedRepository {
  final Map<String, FeedPageData> pages = {};
  final Map<String, FeedPageData> olderPages = {};
  ApiError? olderError;
  final List<String> calls = [];

  static String key(String? category, String? cursor) => '${category ?? ''}|${cursor ?? ''}';

  @override
  Future<FeedPageData> feed({String? category, String? cursor, int size = 20}) async {
    calls.add('feed ${key(category, cursor)}');
    return pages[key(category, cursor)] ?? const FeedPageData(items: [], nextCursor: null);
  }

  @override
  Future<FeedPageData> older({required String category, String? cursor}) async {
    calls.add('older ${key(category, cursor)}');
    if (olderError != null) throw olderError!;
    return olderPages[key(category, cursor)] ?? const FeedPageData(items: [], nextCursor: null);
  }
}

/// In-memory archive keyed by URL, newest first.
class FakeArchiveRepository implements ArchiveRepository {
  final List<ArchiveItem> items = [];
  int _nextId = 100;
  ApiError? error;
  int pageSize = 20;
  final List<String> calls = [];

  ArchiveItem seed(String id, {String? category = 'technology'}) {
    final item = ArchiveItem(
      id: _nextId++,
      url: 'https://example.com/$id',
      title: 'Saved $id',
      summary: 'Summary of saved $id',
      imageUrl: null,
      source: 'example.com',
      publishedAt: DateTime.now().toUtc().subtract(const Duration(days: 2)),
      category: category,
      archivedAt: DateTime.now().toUtc(),
    );
    items.add(item);
    return item;
  }

  @override
  Future<ArchivePageData> list({String? cursor, int size = 20}) async {
    calls.add('list ${cursor ?? ''}');
    if (error != null) throw error!;
    final start = cursor == null ? 0 : int.parse(cursor);
    final end = (start + pageSize).clamp(0, items.length);
    return ArchivePageData(items: items.sublist(start, end), nextCursor: end < items.length ? '$end' : null);
  }

  @override
  Future<ArchiveItem> add(Article article) async {
    calls.add('add ${article.url}');
    if (error != null) throw error!;
    final existing = items.where((i) => i.url == article.url);
    if (existing.isNotEmpty) return existing.first;
    final item = ArchiveItem(
      id: _nextId++,
      url: article.url,
      title: article.title,
      summary: article.summary,
      imageUrl: article.imageUrl,
      source: article.source,
      publishedAt: article.publishedAt,
      category: article.category,
      archivedAt: DateTime.now().toUtc(),
    );
    items.insert(0, item);
    return item;
  }

  @override
  Future<void> remove(int id) async {
    calls.add('remove $id');
    if (error != null) throw error!;
    items.removeWhere((i) => i.id == id);
  }

  @override
  Future<void> removeByUrl(String url) async {
    calls.add('removeByUrl $url');
    if (error != null) throw error!;
    items.removeWhere((i) => i.url == url);
  }
}

/// Overrides every network-facing provider with in-memory fakes.
class Fakes {
  final store = MemoryTokenStore();
  final auth = FakeAuthRepository();
  final categories = FakeCategoryRepository();
  final feed = FakeFeedRepository();
  final archive = FakeArchiveRepository();

  List<Override> get overrides => [
        tokenStoreProvider.overrideWithValue(store),
        authRepositoryProvider.overrideWithValue(auth),
        categoryRepositoryProvider.overrideWithValue(categories),
        feedRepositoryProvider.overrideWithValue(feed),
        archiveRepositoryProvider.overrideWithValue(archive),
        // No platform WebView in tests; the reading card is used instead.
        inAppWebViewProvider.overrideWithValue(false),
      ];

  void storeSession({bool expired = false, String email = 'me@example.com'}) {
    final expiresAt = DateTime.now().toUtc().add(expired ? const Duration(hours: -1) : const Duration(days: 1));
    store.session = StoredSession(token: 'stored-token', expiresAt: expiresAt, email: email);
  }
}
