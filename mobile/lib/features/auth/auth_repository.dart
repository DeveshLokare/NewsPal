import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api_client.dart';
import '../../core/api_error.dart';

class AuthResult {
  const AuthResult({
    required this.token,
    required this.expiresAt,
    required this.email,
    this.hasSecurityQuestion = true,
  });

  final String token;
  final DateTime expiresAt;
  final String email;
  final bool hasSecurityQuestion;

  factory AuthResult.fromJson(Map<String, dynamic> json) {
    final user = json['user'] as Map<String, dynamic>;
    return AuthResult(
      token: json['token'] as String,
      expiresAt: DateTime.parse(json['expiresAt'] as String).toUtc(),
      email: user['email'] as String,
      // Older backends don't send it; don't nag users then.
      hasSecurityQuestion: user['hasSecurityQuestion'] as bool? ?? true,
    );
  }
}

/// The signed-in user, from `GET /api/me`.
class Me {
  const Me({required this.email, required this.hasSecurityQuestion});

  final String email;
  final bool hasSecurityQuestion;
}

/// One of the fixed security questions (SPEC-v2 §7.3).
class SecurityQuestion {
  const SecurityQuestion({required this.id, required this.text});

  final int id;
  final String text;
}

final authRepositoryProvider = Provider<AuthRepository>((ref) => DioAuthRepository(ref.watch(dioProvider)));

/// The questions offered at registration and in Settings. Loaded once.
final securityQuestionsProvider = FutureProvider<List<SecurityQuestion>>(
  (ref) => ref.watch(authRepositoryProvider).securityQuestions(),
);

abstract interface class AuthRepository {
  Future<AuthResult> register(String email, String password, {int? questionId, String? answer});
  Future<AuthResult> login(String email, String password);

  /// The signed-in user; throws [ApiError] with status 401 if the token is invalid.
  Future<Me> me();

  Future<List<SecurityQuestion>> securityQuestions();

  /// Sets or changes the user's question; `WRONG_PASSWORD` if [currentPassword] is wrong.
  Future<void> setSecurityQuestion({required String currentPassword, required int questionId, required String answer});

  /// The question to answer to reset [email]'s password.
  Future<SecurityQuestion> resetQuestion(String email);

  /// Resets the password and signs in; `WRONG_ANSWER` (with attempts left) or `RESET_LOCKED`.
  Future<AuthResult> resetPassword({required String email, required String answer, required String newPassword});
}

class DioAuthRepository implements AuthRepository {
  DioAuthRepository(this._dio);

  final Dio _dio;

  @override
  Future<AuthResult> register(String email, String password, {int? questionId, String? answer}) =>
      guardApi(() async {
        final response = await _dio.post<Map<String, dynamic>>('/api/auth/register', data: {
          'email': email,
          'password': password,
          'questionId': ?questionId,
          'answer': ?answer,
        });
        return AuthResult.fromJson(response.data!);
      });

  @override
  Future<AuthResult> login(String email, String password) => guardApi(() async {
        final response = await _dio.post<Map<String, dynamic>>('/api/auth/login',
            data: {'email': email, 'password': password});
        return AuthResult.fromJson(response.data!);
      });

  @override
  Future<Me> me() => guardApi(() async {
        final response = await _dio.get<Map<String, dynamic>>('/api/me');
        final data = response.data!;
        return Me(
          email: data['email'] as String,
          hasSecurityQuestion: data['hasSecurityQuestion'] as bool? ?? true,
        );
      });

  @override
  Future<List<SecurityQuestion>> securityQuestions() => guardApi(() async {
        final response = await _dio.get<List<dynamic>>('/api/auth/security-questions');
        return response.data!
            .cast<Map<String, dynamic>>()
            .map((q) => SecurityQuestion(id: (q['id'] as num).toInt(), text: q['text'] as String))
            .toList();
      });

  @override
  Future<void> setSecurityQuestion({required String currentPassword, required int questionId, required String answer}) =>
      guardApi(() async {
        await _dio.put<void>('/api/me/security-question',
            data: {'currentPassword': currentPassword, 'questionId': questionId, 'answer': answer});
      });

  @override
  Future<SecurityQuestion> resetQuestion(String email) => guardApi(() async {
        final response = await _dio.post<Map<String, dynamic>>('/api/auth/password/question', data: {'email': email});
        final data = response.data!;
        return SecurityQuestion(id: (data['questionId'] as num).toInt(), text: data['text'] as String);
      });

  @override
  Future<AuthResult> resetPassword({required String email, required String answer, required String newPassword}) =>
      guardApi(() async {
        final response = await _dio.post<Map<String, dynamic>>('/api/auth/password/reset',
            data: {'email': email, 'answer': answer, 'newPassword': newPassword});
        return AuthResult.fromJson(response.data!);
      });
}
