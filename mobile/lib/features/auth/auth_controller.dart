import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api_client.dart';
import '../../core/api_error.dart';
import '../../core/token_store.dart';
import '../categories/category_repository.dart';
import 'auth_repository.dart';

sealed class AuthState {
  const AuthState();
}

class SignedOut extends AuthState {
  const SignedOut();
}

class SignedIn extends AuthState {
  const SignedIn({required this.email, required this.hasCategories});

  final String email;

  /// False until the user has picked at least one category (onboarding).
  final bool hasCategories;
}

final authControllerProvider = AsyncNotifierProvider<AuthController, AuthState>(AuthController.new);

/// Owns the session. Sign-in methods throw [ApiError] for the UI to show and
/// leave the state unchanged on failure.
class AuthController extends AsyncNotifier<AuthState> {
  TokenStore get _store => ref.read(tokenStoreProvider);
  AuthRepository get _auth => ref.read(authRepositoryProvider);
  CategoryRepository get _categories => ref.read(categoryRepositoryProvider);

  @override
  Future<AuthState> build() async {
    final session = await _store.read();
    if (session == null || session.isExpired) {
      await _store.clear();
      return const SignedOut();
    }
    ref.read(sessionTokenProvider.notifier).set(session.token);
    try {
      final email = await _auth.me();
      final followed = await _categories.mine();
      return SignedIn(email: email, hasCategories: followed.isNotEmpty);
    } on ApiError catch (e) {
      if (e.status == 401) {
        await _forget();
        return const SignedOut();
      }
      // Offline or server error: trust the unexpired token and carry on.
      return SignedIn(email: session.email, hasCategories: true);
    }
  }

  Future<void> signIn(String email, String password) async {
    final result = await _auth.login(email, password);
    await _remember(result);
    final followed = await _categories.mine();
    state = AsyncData(SignedIn(email: result.email, hasCategories: followed.isNotEmpty));
  }

  Future<void> register(String email, String password) async {
    final result = await _auth.register(email, password);
    await _remember(result);
    state = AsyncData(SignedIn(email: result.email, hasCategories: false));
  }

  /// Called after the user saves at least one category.
  void categoriesSaved() {
    final current = state.value;
    if (current is SignedIn && !current.hasCategories) {
      state = AsyncData(SignedIn(email: current.email, hasCategories: true));
    }
  }

  Future<void> signOut() async {
    await _forget();
    state = const AsyncData(SignedOut());
  }

  Future<void> _remember(AuthResult result) async {
    await _store.write(StoredSession(token: result.token, expiresAt: result.expiresAt, email: result.email));
    ref.read(sessionTokenProvider.notifier).set(result.token);
  }

  Future<void> _forget() async {
    await _store.clear();
    ref.read(sessionTokenProvider.notifier).set(null);
  }
}
