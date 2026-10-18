import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../features/auth/auth_controller.dart';
import 'env.dart';
import 'token_store.dart';

final tokenStoreProvider = Provider<TokenStore>((ref) => SecureTokenStore());

/// The bearer token for API calls; set by [AuthController] on sign-in/out.
final sessionTokenProvider = NotifierProvider<SessionToken, String?>(SessionToken.new);

class SessionToken extends Notifier<String?> {
  @override
  String? build() => null;

  void set(String? token) => state = token;
}

/// Number of requests that have been waiting more than 3 s (non-zero = show the
/// "waking up the server" banner). On Render's free tier the first request after
/// idling can take up to a minute (SPEC §9.3).
final serverWakingProvider = NotifierProvider<ServerWaking, int>(ServerWaking.new);

class ServerWaking extends Notifier<int> {
  @override
  int build() => 0;

  void slowStarted() => state = state + 1;

  void slowFinished() => state = state > 0 ? state - 1 : 0;
}

final dioProvider = Provider<Dio>((ref) {
  final dio = Dio(BaseOptions(
    baseUrl: apiBaseUrl,
    // Long enough to outlast a Render cold start.
    connectTimeout: const Duration(seconds: 70),
    receiveTimeout: const Duration(seconds: 70),
    contentType: Headers.jsonContentType,
  ));
  dio.interceptors.add(_AppInterceptor(ref));
  return dio;
});

class _AppInterceptor extends Interceptor {
  _AppInterceptor(this._ref);

  final Ref _ref;

  static const _slowAfter = Duration(seconds: 3);
  static const _timerKey = 'newspal.slowTimer';
  static const _slowKey = 'newspal.wasSlow';

  @override
  void onRequest(RequestOptions options, RequestInterceptorHandler handler) {
    final token = _ref.read(sessionTokenProvider);
    if (token != null) {
      options.headers['Authorization'] = 'Bearer $token';
    }
    options.extra[_timerKey] = Timer(_slowAfter, () {
      options.extra[_slowKey] = true;
      _ref.read(serverWakingProvider.notifier).slowStarted();
    });
    handler.next(options);
  }

  @override
  void onResponse(Response<dynamic> response, ResponseInterceptorHandler handler) {
    _finish(response.requestOptions);
    handler.next(response);
  }

  @override
  void onError(DioException err, ErrorInterceptorHandler handler) {
    _finish(err.requestOptions);
    final isAuthCall = err.requestOptions.path.startsWith('/api/auth/');
    if (err.response?.statusCode == 401 && !isAuthCall) {
      // Token expired or revoked: sign out; the router sends the user to login.
      unawaited(_ref.read(authControllerProvider.notifier).signOut());
    }
    handler.next(err);
  }

  void _finish(RequestOptions options) {
    (options.extra[_timerKey] as Timer?)?.cancel();
    if (options.extra[_slowKey] == true) {
      _ref.read(serverWakingProvider.notifier).slowFinished();
    }
  }
}
