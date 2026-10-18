import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api_client.dart';
import '../../core/api_error.dart';

class Category {
  const Category({required this.slug, required this.name});

  final String slug;
  final String name;

  factory Category.fromJson(Map<String, dynamic> json) =>
      Category(slug: json['slug'] as String, name: json['name'] as String);
}

final categoryRepositoryProvider =
    Provider<CategoryRepository>((ref) => DioCategoryRepository(ref.watch(dioProvider)));

/// The full catalog, in display order. Loaded once and reused.
final allCategoriesProvider = FutureProvider<List<Category>>(
  (ref) => ref.watch(categoryRepositoryProvider).all(),
);

/// The user's categories with display names, in catalog order (feed chips).
/// Invalidate after the user changes their categories.
final followedCategoriesProvider = FutureProvider<List<Category>>((ref) async {
  // Per user: reload when someone else signs in.
  ref.watch(sessionTokenProvider);
  final catalog = await ref.watch(allCategoriesProvider.future);
  final mine = (await ref.watch(categoryRepositoryProvider).mine()).toSet();
  return catalog.where((c) => mine.contains(c.slug)).toList();
});

abstract interface class CategoryRepository {
  Future<List<Category>> all();
  Future<List<String>> mine();
  Future<void> saveMine(List<String> slugs);
}

class DioCategoryRepository implements CategoryRepository {
  DioCategoryRepository(this._dio);

  final Dio _dio;

  @override
  Future<List<Category>> all() => guardApi(() async {
        final response = await _dio.get<List<dynamic>>('/api/categories');
        return response.data!.map((e) => Category.fromJson(e as Map<String, dynamic>)).toList();
      });

  @override
  Future<List<String>> mine() => guardApi(() async {
        final response = await _dio.get<List<dynamic>>('/api/me/categories');
        return response.data!.cast<String>();
      });

  @override
  Future<void> saveMine(List<String> slugs) => guardApi(() async {
        await _dio.put<void>('/api/me/categories', data: slugs);
      });
}
