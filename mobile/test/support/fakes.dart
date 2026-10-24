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
import 'package:newspal/features/search/search_repository.dart';

ApiError networkError() => ApiError(code: ApiError.network, message: 'offline');

ApiError unauthorized() => ApiError(code: 'UNAUTHORIZED', message: 'Authentication required', status: 401);

class FakeAuthRepository implements AuthRepository {
  /// email → password
  final Map<String, String> users = {};

  /// email → (question id, answer); answers compare like the server's.
  final Map<String, (int, String)> questions = {};
  ApiError? meError;
  String meEmail = 'me@example.com';
  bool meHasQuestion = true;
  int meCalls = 0;

  /// Wrong answers left before a reset locks, per email.
  final Map<String, int> attemptsLeft = {};
  ApiError? resetQuestionError;
  final List<String> calls = [];

  static const catalog = [
    SecurityQuestion(id: 1, text: 'What was the name of your first pet?'),
    SecurityQuestion(id: 2, text: 'What was your favourite subject at school?'),
  ];

  AuthResult _result(String email) => AuthResult(
        token: 'token-$email',
        expiresAt: DateTime.now().toUtc().add(const Duration(days: 7)),
        email: email,
        hasSecurityQuestion: questions.containsKey(email),
      );

  static String _normalize(String answer) =>
      answer.toLowerCase().replaceAll(RegExp(r'[\p{P}\p{S}]', unicode: true), '').replaceAll(RegExp(r'\s+'), ' ').trim();

  @override
  Future<AuthResult> register(String email, String password, {int? questionId, String? answer}) async {
    calls.add('register $email ${questionId ?? '-'}');
    if (users.containsKey(email)) {
      throw ApiError(code: 'EMAIL_TAKEN', message: 'Email already registered', status: 409);
    }
    users[email] = password;
    if (questionId != null && answer != null) questions[email] = (questionId, answer);
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
  Future<Me> me() async {
    meCalls++;
    if (meError != null) throw meError!;
    return Me(email: meEmail, hasSecurityQuestion: meHasQuestion);
  }

  @override
  Future<List<SecurityQuestion>> securityQuestions() async => catalog;

  @override
  Future<void> setSecurityQuestion({required String currentPassword, required int questionId, required String answer}) async {
    calls.add('setSecurityQuestion $questionId');
    if (users[meEmail] != currentPassword) {
      throw ApiError(code: 'WRONG_PASSWORD', message: "Your password isn't right", status: 400);
    }
    questions[meEmail] = (questionId, answer);
    meHasQuestion = true;
  }

  @override
  Future<SecurityQuestion> resetQuestion(String email) async {
    calls.add('resetQuestion $email');
    if (resetQuestionError != null) throw resetQuestionError!;
    final id = questions[email]?.$1 ?? 2;
    return catalog.firstWhere((q) => q.id == id);
  }

  @override
  Future<AuthResult> resetPassword({required String email, required String answer, required String newPassword}) async {
    calls.add('resetPassword $email');
    final left = attemptsLeft[email] ?? 5;
    if (left <= 0) {
      throw ApiError(code: 'RESET_LOCKED', message: 'Too many attempts', status: 429, details: {'retryAfterSeconds': 86400});
    }
    final stored = questions[email];
    if (stored == null || _normalize(stored.$2) != _normalize(answer)) {
      attemptsLeft[email] = left - 1;
      if (left - 1 == 0) {
        throw ApiError(code: 'RESET_LOCKED', message: 'Too many attempts', status: 429, details: {'retryAfterSeconds': 86400});
      }
      throw ApiError(code: 'WRONG_ANSWER', message: "That answer doesn't match", status: 400, details: {'attemptsLeft': left - 1});
    }
    users[email] = newPassword;
    return _result(email);
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

/// Serves scripted search pages. Keys are `query|category|cursor` (empty strings for null).
class FakeSearchRepository implements SearchRepository {
  final Map<String, FeedPageData> pages = {};
  final List<String> calls = [];
  ApiError? error;

  static String key(String query, String? category, String? cursor) => '$query|${category ?? ''}|${cursor ?? ''}';

  @override
  Future<FeedPageData> search({required String query, String? category, String? cursor, int size = 20}) async {
    calls.add(key(query, category, cursor));
    if (error != null) throw error!;
    return pages[key(query, category, cursor)] ?? const FeedPageData(items: [], nextCursor: null);
  }
}

/// Overrides every network-facing provider with in-memory fakes.
class Fakes {
  final store = MemoryTokenStore();
  final auth = FakeAuthRepository();
  final categories = FakeCategoryRepository();
  final feed = FakeFeedRepository();
  final archive = FakeArchiveRepository();
  final search = FakeSearchRepository();

  /// Replaces [search] in [overrides] when a test needs other behaviour.
  SearchRepository? searchOverride;

  List<Override> get overrides => [
        tokenStoreProvider.overrideWithValue(store),
        authRepositoryProvider.overrideWithValue(auth),
        categoryRepositoryProvider.overrideWithValue(categories),
        feedRepositoryProvider.overrideWithValue(feed),
        archiveRepositoryProvider.overrideWithValue(archive),
        searchRepositoryProvider.overrideWithValue(searchOverride ?? search),
        // No platform WebView in tests; the reading card is used instead.
        inAppWebViewProvider.overrideWithValue(false),
      ];

  void storeSession({bool expired = false, String email = 'me@example.com'}) {
    final expiresAt = DateTime.now().toUtc().add(expired ? const Duration(hours: -1) : const Duration(days: 1));
    store.session = StoredSession(token: 'stored-token', expiresAt: expiresAt, email: email);
  }
}
