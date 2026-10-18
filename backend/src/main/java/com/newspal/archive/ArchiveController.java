package com.newspal.archive;

import com.newspal.archive.dto.ArchiveDtos.ArchiveItem;
import com.newspal.archive.dto.ArchiveDtos.ArchivePage;
import com.newspal.archive.dto.ArchiveDtos.ArchiveRequest;
import com.newspal.common.CurrentUser;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/archive")
public class ArchiveController {

	private final ArchiveService archiveService;

	public ArchiveController(ArchiveService archiveService) {
		this.archiveService = archiveService;
	}

	@GetMapping
	public ArchivePage list(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) String cursor,
			@RequestParam(defaultValue = "20") int size) {
		return this.archiveService.page(CurrentUser.id(jwt), cursor, size);
	}

	/** 201 when newly archived, 200 with the existing item when already archived. */
	@PostMapping
	public ResponseEntity<ArchiveItem> archive(@AuthenticationPrincipal Jwt jwt,
			@Valid @RequestBody ArchiveRequest request) {
		ArchiveService.Result result = this.archiveService.archive(CurrentUser.id(jwt), request);
		return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.item());
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
		this.archiveService.delete(CurrentUser.id(jwt), id);
	}

}
