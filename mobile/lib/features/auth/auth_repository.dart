import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api_client.dart';
import '../../core/api_error.dart';

class AuthResult {
  const AuthResult({required this.token, required this.expiresAt, required this.email});

  final String token;
  final DateTime expiresAt;
  final String email;

  factory AuthResult.fromJson(Map<String, dynamic> json) => AuthResult(
        token: json['token'] as String,
        expiresAt: DateTime.parse(json['expiresAt'] as String).toUtc(),
        email: (json['user'] as Map<String, dynamic>)['email'] as String,
      );
}

final authRepositoryProvider = Provider<AuthRepository>((ref) => DioAuthRepository(ref.watch(dioProvider)));

abstract interface class AuthRepository {
  Future<AuthResult> register(String email, String password);
  Future<AuthResult> login(String email, String password);

  /// The signed-in user's email; throws [ApiError] with status 401 if the token is invalid.
  Future<String> me();
}

class DioAuthRepository implements AuthRepository {
  DioAuthRepository(this._dio);

  final Dio _dio;

  @override
  Future<AuthResult> register(String email, String password) => guardApi(() async {
        final response = await _dio.post<Map<String, dynamic>>('/api/auth/register',
            data: {'email': email, 'password': password});
        return AuthResult.fromJson(response.data!);
      });

  @override
  Future<AuthResult> login(String email, String password) => guardApi(() async {
        final response = await _dio.post<Map<String, dynamic>>('/api/auth/login',
            data: {'email': email, 'password': password});
        return AuthResult.fromJson(response.data!);
      });

  @override
  Future<String> me() => guardApi(() async {
        final response = await _dio.get<Map<String, dynamic>>('/api/me');
        return response.data!['email'] as String;
      });
}
