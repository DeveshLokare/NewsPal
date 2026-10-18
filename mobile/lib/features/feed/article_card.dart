import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/material.dart';

import '../../app/theme.dart';
import '../../core/time_format.dart';
import 'article.dart';

/// A feed item: image, source · time, optional category label, title, summary.
class ArticleCard extends StatelessWidget {
  const ArticleCard({super.key, required this.article, required this.onTap, this.categoryName});

  final Article article;
  final VoidCallback onTap;

  /// Shown as a small label in the "All" view; null hides it.
  final String? categoryName;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final meta = [
      if (article.source != null && article.source!.isNotEmpty) article.source!,
      timeAgo(article.publishedAt),
    ].join(' · ');
    return Card(
      margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
      color: scheme.surfaceContainer,
      elevation: 0,
      clipBehavior: Clip.antiAlias,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16), side: BorderSide(color: scheme.outline)),
      child: InkWell(
        onTap: onTap,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            if (article.imageUrl != null) _ArticleImage(url: article.imageUrl!),
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 14, 16, 16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      if (categoryName != null) ...[
                        _CategoryLabel(categoryName!),
                        const SizedBox(width: 8),
                      ],
                      Expanded(
                        child: Text(
                          meta,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: theme.textTheme.labelMedium?.copyWith(color: scheme.onSurfaceVariant),
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 8),
                  Text(
                    article.title,
                    maxLines: 3,
                    overflow: TextOverflow.ellipsis,
                    style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w700, height: 1.3),
                  ),
                  if (article.summary != null) ...[
                    const SizedBox(height: 6),
                    Text(
                      article.summary!,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: theme.textTheme.bodyMedium?.copyWith(color: scheme.onSurfaceVariant, height: 1.4),
                    ),
                  ],
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _ArticleImage extends StatelessWidget {
  const _ArticleImage({required this.url});

  final String url;

  @override
  Widget build(BuildContext context) {
    final placeholder = ColoredBox(color: Theme.of(context).colorScheme.surfaceContainerHighest);
    return AspectRatio(
      aspectRatio: 16 / 9,
      child: CachedNetworkImage(
        imageUrl: url,
        fit: BoxFit.cover,
        placeholder: (_, _) => placeholder,
        // Broken images are common in news feeds; show a quiet block, not an error icon.
        errorWidget: (_, _, _) => placeholder,
      ),
    );
  }
}

class _CategoryLabel extends StatelessWidget {
  const _CategoryLabel(this.name);

  final String name;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(color: Brand.lime, borderRadius: BorderRadius.circular(6)),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
        child: Text(
          name,
          style: const TextStyle(color: Brand.charcoal, fontSize: 11, fontWeight: FontWeight.w700),
        ),
      ),
    );
  }
}
