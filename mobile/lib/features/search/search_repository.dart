import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api_client.dart';
import '../../core/api_error.dart';
import '../feed/article.dart';

final searchRepositoryProvider = Provider<SearchRepository>((ref) => DioSearchRepository(ref.watch(dioProvider)));

abstract interface class SearchRepository {
  /// Stored news from the last 7 days, every category, newest first (SPEC-v2 §8).
  Future<FeedPageData> search({required String query, String? category, String? cursor, int size = 20});
}

class DioSearchRepository implements SearchRepository {
  DioSearchRepository(this._dio);

  final Dio _dio;

  @override
  Future<FeedPageData> search({required String query, String? category, String? cursor, int size = 20}) =>
      guardApi(() async {
        final response = await _dio.get<Map<String, dynamic>>('/api/search', queryParameters: {
          'q': query,
          'category': ?category,
          'cursor': ?cursor,
          'size': size,
        });
        return FeedPageData.fromJson(response.data!);
      });
}
