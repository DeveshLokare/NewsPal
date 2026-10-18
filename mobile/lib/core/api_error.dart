import 'package:dio/dio.dart';

/// The backend's error shape (SPEC §7.1), or a client-side network failure.
class ApiError implements Exception {
  ApiError({required this.code, required this.message, this.status, this.fieldErrors = const {}});

  /// Couldn't reach the server (offline, DNS, timeout).
  static const network = 'NETWORK';

  final String code;
  final String message;
  final int? status;

  /// field name → message, from validation errors.
  final Map<String, String> fieldErrors;

  factory ApiError.fromDio(DioException e) {
    final response = e.response;
    if (response == null) {
      return ApiError(
        code: network,
        message: "Can't reach NewsPal right now. Check your connection and try again.",
      );
    }
    final data = response.data;
    if (data is Map && data['code'] is String) {
      final fields = <String, String>{};
      final raw = data['fieldErrors'];
      if (raw is List) {
        for (final item in raw) {
          if (item is Map && item['field'] is String) {
            fields.putIfAbsent(item['field'] as String, () => '${item['message']}');
          }
        }
      }
      return ApiError(
        code: data['code'] as String,
        message: (data['message'] as String?) ?? 'Something went wrong',
        status: response.statusCode,
        fieldErrors: fields,
      );
    }
    return ApiError(code: 'HTTP_${response.statusCode}', message: 'Something went wrong', status: response.statusCode);
  }

  @override
  String toString() => 'ApiError($code, $message)';
}

/// Runs [call], converting dio failures into [ApiError].
Future<T> guardApi<T>(Future<T> Function() call) async {
  try {
    return await call();
  } on DioException catch (e) {
    throw ApiError.fromDio(e);
  }
}
