import 'package:flutter_secure_storage/flutter_secure_storage.dart';

/// A stored session: the JWT and when it expires.
class StoredSession {
  const StoredSession({required this.token, required this.expiresAt, required this.email});

  final String token;
  final DateTime expiresAt;
  final String email;

  bool get isExpired => !DateTime.now().toUtc().isBefore(expiresAt);
}

/// Persists the session in the platform keychain/keystore.
abstract interface class TokenStore {
  Future<StoredSession?> read();
  Future<void> write(StoredSession session);
  Future<void> clear();
}

class SecureTokenStore implements TokenStore {
  SecureTokenStore([FlutterSecureStorage? storage]) : _storage = storage ?? const FlutterSecureStorage();

  final FlutterSecureStorage _storage;

  static const _tokenKey = 'newspal.token';
  static const _expiresKey = 'newspal.expiresAt';
  static const _emailKey = 'newspal.email';

  @override
  Future<StoredSession?> read() async {
    final values = await _storage.readAll();
    final token = values[_tokenKey];
    final expires = DateTime.tryParse(values[_expiresKey] ?? '');
    if (token == null || expires == null) return null;
    return StoredSession(token: token, expiresAt: expires.toUtc(), email: values[_emailKey] ?? '');
  }

  @override
  Future<void> write(StoredSession session) async {
    await _storage.write(key: _tokenKey, value: session.token);
    await _storage.write(key: _expiresKey, value: session.expiresAt.toUtc().toIso8601String());
    await _storage.write(key: _emailKey, value: session.email);
  }

  @override
  Future<void> clear() async {
    await _storage.delete(key: _tokenKey);
    await _storage.delete(key: _expiresKey);
    await _storage.delete(key: _emailKey);
  }
}

/// In-memory store for tests.
class MemoryTokenStore implements TokenStore {
  StoredSession? session;

  @override
  Future<StoredSession?> read() async => session;

  @override
  Future<void> write(StoredSession value) async => session = value;

  @override
  Future<void> clear() async => session = null;
}
