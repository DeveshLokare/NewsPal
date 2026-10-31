/// A feed item (SPEC §7.4). [id] is null for older-news items, which the
/// backend doesn't store.
class Article {
  const Article({
    required this.id,
    required this.url,
    required this.title,
    required this.summary,
    required this.imageUrl,
    required this.source,
    required this.publishedAt,
    required this.category,
    required this.archived,
  });

  final int? id;
  final String url;
  final String title;
  final String? summary;
  final String? imageUrl;
  final String? source;
  final DateTime publishedAt;
  final String? category;
  final bool archived;

  factory Article.fromJson(Map<String, dynamic> json) => Article(
        id: (json['id'] as num?)?.toInt(),
        url: json['url'] as String,
        title: json['title'] as String,
        summary: json['summary'] as String?,
        imageUrl: json['imageUrl'] as String?,
        source: json['source'] as String?,
        publishedAt: DateTime.parse(json['publishedAt'] as String).toUtc(),
        category: json['category'] as String?,
        archived: json['archived'] as bool? ?? false,
      );
}

class FeedPageData {
  const FeedPageData({required this.items, required this.nextCursor, this.lastRefreshedAt, this.refreshing = false});

  final List<Article> items;
  final String? nextCursor;
  final DateTime? lastRefreshedAt;

  /// New articles may still arrive: the backend is refreshing these categories.
  final bool refreshing;

  factory FeedPageData.fromJson(Map<String, dynamic> json) => FeedPageData(
        items: (json['items'] as List<dynamic>).map((e) => Article.fromJson(e as Map<String, dynamic>)).toList(),
        nextCursor: json['nextCursor'] as String?,
        lastRefreshedAt:
            json['lastRefreshedAt'] == null ? null : DateTime.parse(json['lastRefreshedAt'] as String).toUtc(),
        // Older backends don't send it.
        refreshing: json['refreshing'] as bool? ?? false,
      );
}
