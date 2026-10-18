import 'package:flutter_riverpod/misc.dart' show Override;
import 'package:newspal/core/api_client.dart';
import 'package:newspal/core/api_error.dart';
import 'package:newspal/core/token_store.dart';
import 'package:newspal/features/auth/auth_repository.dart';
import 'package:newspal/features/categories/category_repository.dart';

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

/// Overrides every network-facing provider with in-memory fakes.
class Fakes {
  final store = MemoryTokenStore();
  final auth = FakeAuthRepository();
  final categories = FakeCategoryRepository();

  List<Override> get overrides => [
        tokenStoreProvider.overrideWithValue(store),
        authRepositoryProvider.overrideWithValue(auth),
        categoryRepositoryProvider.overrideWithValue(categories),
      ];

  void storeSession({bool expired = false, String email = 'me@example.com'}) {
    final expiresAt = DateTime.now().toUtc().add(expired ? const Duration(hours: -1) : const Duration(days: 1));
    store.session = StoredSession(token: 'stored-token', expiresAt: expiresAt, email: email);
  }
}
