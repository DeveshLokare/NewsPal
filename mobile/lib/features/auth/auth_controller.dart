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
  const SignedIn({required this.email, required this.hasCategories, this.hasSecurityQuestion = true});

  final String email;

  /// False until the user has picked at least one category (onboarding).
  final bool hasCategories;

  /// False for accounts from before security questions; the feed then suggests
  /// setting one (SPEC-v2 §7.6).
  final bool hasSecurityQuestion;

  SignedIn copyWith({bool? hasCategories, bool? hasSecurityQuestion}) => SignedIn(
        email: email,
        hasCategories: hasCategories ?? this.hasCategories,
        hasSecurityQuestion: hasSecurityQuestion ?? this.hasSecurityQuestion,
      );
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
      final me = await _auth.me();
      final followed = await _categories.mine();
      return SignedIn(email: me.email, hasCategories: followed.isNotEmpty, hasSecurityQuestion: me.hasSecurityQuestion);
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
    await signInWith(await _auth.login(email, password));
  }

  /// Signs in with a token the server already issued, e.g. after a password reset.
  Future<void> signInWith(AuthResult result) async {
    await _remember(result);
    final followed = await _categories.mine();
    state = AsyncData(SignedIn(
      email: result.email,
      hasCategories: followed.isNotEmpty,
      hasSecurityQuestion: result.hasSecurityQuestion,
    ));
  }

  Future<void> register(String email, String password, {int? questionId, String? answer}) async {
    final result = await _auth.register(email, password, questionId: questionId, answer: answer);
    await _remember(result);
    state = AsyncData(SignedIn(
      email: result.email,
      hasCategories: false,
      hasSecurityQuestion: result.hasSecurityQuestion,
    ));
  }

  /// Called after the user saves at least one category.
  void categoriesSaved() {
    final current = state.value;
    if (current is SignedIn && !current.hasCategories) {
      state = AsyncData(current.copyWith(hasCategories: true));
    }
  }

  /// Called after the user saves a security question.
  void securityQuestionSaved() {
    final current = state.value;
    if (current is SignedIn && !current.hasSecurityQuestion) {
      state = AsyncData(current.copyWith(hasSecurityQuestion: true));
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
