package com.newspal.category;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class CategoryController {

	private final CategoryRepository categories;

	private final UserCategoryService userCategories;

	public CategoryController(CategoryRepository categories, UserCategoryService userCategories) {
		this.categories = categories;
		this.userCategories = userCategories;
	}

	@GetMapping("/categories")
	public List<CategoryDto> all() {
		return this.categories.findAll().stream().map((c) -> new CategoryDto(c.slug(), c.name())).toList();
	}

	@GetMapping("/me/categories")
	public List<String> mine(@AuthenticationPrincipal Jwt jwt) {
		return this.userCategories.followedSlugs(userId(jwt));
	}

	@PutMapping("/me/categories")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void replaceMine(@AuthenticationPrincipal Jwt jwt, @RequestBody List<String> slugs) {
		this.userCategories.replace(userId(jwt), slugs);
	}

	private static long userId(Jwt jwt) {
		return Long.parseLong(jwt.getSubject());
	}

	public record CategoryDto(String slug, String name) {
	}

}
