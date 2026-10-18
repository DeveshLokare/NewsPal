import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api_client.dart';
import '../../core/api_error.dart';
import '../feed/article.dart';

/// An archived article (SPEC §7.5): a full copy, kept after the feed drops it.
class ArchiveItem {
  const ArchiveItem({
    required this.id,
    required this.url,
    required this.title,
    required this.summary,
    required this.imageUrl,
    required this.source,
    required this.publishedAt,
    required this.category,
    required this.archivedAt,
  });

  final int id;
  final String url;
  final String title;
  final String? summary;
  final String? imageUrl;
  final String? source;
  final DateTime? publishedAt;
  final String? category;
  final DateTime archivedAt;

  factory ArchiveItem.fromJson(Map<String, dynamic> json) => ArchiveItem(
        id: (json['id'] as num).toInt(),
        url: json['url'] as String,
        title: json['title'] as String,
        summary: json['summary'] as String?,
        imageUrl: json['imageUrl'] as String?,
        source: json['source'] as String?,
        publishedAt: json['publishedAt'] == null ? null : DateTime.parse(json['publishedAt'] as String).toUtc(),
        category: json['category'] as String?,
        archivedAt: DateTime.parse(json['archivedAt'] as String).toUtc(),
      );

  /// For reusing the feed's card and article screen.
  Article toArticle() => Article(
        id: null,
        url: url,
        title: title,
        summary: summary,
        imageUrl: imageUrl,
        source: source,
        publishedAt: publishedAt ?? archivedAt,
        category: category,
        archived: true,
      );
}

class ArchivePageData {
  const ArchivePageData({required this.items, required this.nextCursor});

  final List<ArchiveItem> items;
  final String? nextCursor;
}

final archiveRepositoryProvider =
    Provider<ArchiveRepository>((ref) => DioArchiveRepository(ref.watch(dioProvider)));

abstract interface class ArchiveRepository {
  Future<ArchivePageData> list({String? cursor, int size = 20});

  /// Archives [article]; returns the stored copy (idempotent by URL).
  Future<ArchiveItem> add(Article article);

  Future<void> remove(int id);

  /// Un-archives by URL; succeeds even if it wasn't archived.
  Future<void> removeByUrl(String url);
}

class DioArchiveRepository implements ArchiveRepository {
  DioArchiveRepository(this._dio);

  final Dio _dio;

  @override
  Future<ArchivePageData> list({String? cursor, int size = 20}) => guardApi(() async {
        final response = await _dio.get<Map<String, dynamic>>('/api/archive', queryParameters: {
          'cursor': ?cursor,
          'size': size,
        });
        final data = response.data!;
        return ArchivePageData(
          items: (data['items'] as List<dynamic>)
              .map((e) => ArchiveItem.fromJson(e as Map<String, dynamic>))
              .toList(),
          nextCursor: data['nextCursor'] as String?,
        );
      });

  @override
  Future<ArchiveItem> add(Article article) => guardApi(() async {
        final response = await _dio.post<Map<String, dynamic>>('/api/archive', data: {
          'url': article.url,
          'title': article.title,
          'summary': article.summary,
          'imageUrl': article.imageUrl,
          'source': article.source,
          'publishedAt': article.publishedAt.toUtc().toIso8601String(),
          'category': article.category,
        });
        return ArchiveItem.fromJson(response.data!);
      });

  @override
  Future<void> remove(int id) => guardApi(() async {
        await _dio.delete<void>('/api/archive/$id');
      });

  @override
  Future<void> removeByUrl(String url) => guardApi(() async {
        await _dio.delete<void>('/api/archive', queryParameters: {'url': url});
      });
}
