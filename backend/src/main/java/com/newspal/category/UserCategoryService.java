package com.newspal.category;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.newspal.common.ApiException;
import com.newspal.common.ErrorCodes;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserCategoryService {

	private final CategoryRepository categories;

	public UserCategoryService(CategoryRepository categories) {
		this.categories = categories;
	}

	@Transactional(readOnly = true)
	public List<String> followedSlugs(long userId) {
		return this.categories.findFollowedBy(userId).stream().map(Category::slug).toList();
	}

	@Transactional
	public void replace(long userId, List<String> slugs) {
		if (slugs == null || slugs.isEmpty()) {
			throw invalid("Select at least one category");
		}
		Set<String> unique = new LinkedHashSet<>(slugs);
		List<Short> ids = this.categories.findIdsBySlugs(unique);
		if (ids.size() != unique.size()) {
			throw invalid("Unknown category");
		}
		this.categories.replaceUserCategories(userId, ids);
	}

	private static ApiException invalid(String message) {
		return new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_FAILED, message);
	}

}
