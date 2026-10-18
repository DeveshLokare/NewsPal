import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api_client.dart';
import '../../core/api_error.dart';
import 'article.dart';

final feedRepositoryProvider = Provider<FeedRepository>((ref) => DioFeedRepository(ref.watch(dioProvider)));

abstract interface class FeedRepository {
  /// Stored articles, newest first. [category] null = all followed categories.
  Future<FeedPageData> feed({String? category, String? cursor, int size = 20});

  /// Live older news for one category (SPEC §5).
  Future<FeedPageData> older({required String category, String? cursor});
}

class DioFeedRepository implements FeedRepository {
  DioFeedRepository(this._dio);

  final Dio _dio;

  @override
  Future<FeedPageData> feed({String? category, String? cursor, int size = 20}) => guardApi(() async {
        final response = await _dio.get<Map<String, dynamic>>('/api/feed', queryParameters: {
          'category': ?category,
          'cursor': ?cursor,
          'size': size,
        });
        return FeedPageData.fromJson(response.data!);
      });

  @override
  Future<FeedPageData> older({required String category, String? cursor}) => guardApi(() async {
        final response = await _dio.get<Map<String, dynamic>>('/api/feed/older', queryParameters: {
          'category': category,
          'cursor': ?cursor,
        });
        return FeedPageData.fromJson(response.data!);
      });
}
