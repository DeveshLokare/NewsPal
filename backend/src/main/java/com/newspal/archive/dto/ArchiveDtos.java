package com.newspal.archive.dto;

import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request and response bodies for {@code /api/archive} (SPEC §7.5).
 */
public final class ArchiveDtos {

	private ArchiveDtos() {
	}

	/**
	 * The article to archive. The app sends the full article (older-news items have no
	 * id); over-long optional fields are trimmed rather than rejected.
	 */
	public record ArchiveRequest(@NotBlank @Size(max = 2048) String url, @NotBlank @Size(max = 1000) String title,
			String summary, @Size(max = 2048) String imageUrl, String source, Instant publishedAt,
			@Size(max = 60) String category) {
	}

	public record ArchiveItem(long id, String url, String title, String summary, String imageUrl, String source,
			Instant publishedAt, String category, Instant archivedAt) {
	}

	public record ArchivePage(List<ArchiveItem> items, String nextCursor) {
	}

}
