import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api_error.dart';
import 'auth_controller.dart';
import 'auth_repository.dart';

/// Where the forgot-password flow is (SPEC-v2 §7.6). Kept in memory, never in
/// the URL.
class PasswordResetState {
  const PasswordResetState({required this.email, this.question});

  final String email;

  /// Set once the server has given the account's question.
  final SecurityQuestion? question;
}

final passwordResetControllerProvider =
    NotifierProvider<PasswordResetController, PasswordResetState?>(PasswordResetController.new);

class PasswordResetController extends Notifier<PasswordResetState?> {
  AuthRepository get _auth => ref.read(authRepositoryProvider);

  @override
  PasswordResetState? build() => null;

  /// Starts the flow, pre-filled with whatever was typed on the login screen.
  void start(String email) => state = PasswordResetState(email: email.trim());

  /// Looks up the question for [email]; throws [ApiError].
  Future<void> findQuestion(String email) async {
    final normalized = email.trim();
    final question = await _auth.resetQuestion(normalized);
    state = PasswordResetState(email: normalized, question: question);
  }

  /// Resets the password and signs in; throws [ApiError] (`WRONG_ANSWER`,
  /// `RESET_LOCKED`, `RESET_RATE_LIMITED`, …) and keeps the flow going on failure.
  Future<void> reset({required String answer, required String newPassword}) async {
    final current = state;
    if (current == null) throw StateError('No reset in progress');
    final result = await _auth.resetPassword(email: current.email, answer: answer, newPassword: newPassword);
    await ref.read(authControllerProvider.notifier).signInWith(result);
    state = null;
  }

  void clear() => state = null;
}

/// Friendly text for reset failures.
String resetErrorMessage(ApiError e) => switch (e.code) {
      'WRONG_ANSWER' => switch (e.attemptsLeft) {
          1 => "That answer doesn't match. 1 attempt left.",
          final int left => "That answer doesn't match. $left attempts left.",
          null => "That answer doesn't match.",
        },
      'RESET_LOCKED' => 'Too many attempts. Try again tomorrow.',
      'RESET_RATE_LIMITED' => 'Too many attempts from this network. Try again in an hour.',
      _ => e.message,
    };
