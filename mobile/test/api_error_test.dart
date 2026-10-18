import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:newspal/core/api_error.dart';

void main() {
  final request = RequestOptions(path: '/api/auth/register');

  test('parses the backend error shape with field errors', () {
    final error = ApiError.fromDio(DioException(
      requestOptions: request,
      response: Response(requestOptions: request, statusCode: 400, data: {
        'code': 'VALIDATION_FAILED',
        'message': 'Request is invalid',
        'fieldErrors': [
          {'field': 'email', 'message': 'must be a well-formed email address'},
          {'field': 'password', 'message': 'size must be between 8 and 72'},
        ],
      }),
    ));
    expect(error.code, 'VALIDATION_FAILED');
    expect(error.status, 400);
    expect(error.fieldErrors.keys, ['email', 'password']);
  });

  test('no response means a network error', () {
    final error = ApiError.fromDio(DioException.connectionError(requestOptions: request, reason: 'refused'));
    expect(error.code, ApiError.network);
    expect(error.status, isNull);
  });

  test('unexpected bodies get a generic message', () {
    final error = ApiError.fromDio(DioException(
      requestOptions: request,
      response: Response(requestOptions: request, statusCode: 502, data: '<html>Bad gateway</html>'),
    ));
    expect(error.code, 'HTTP_502');
  });
}
