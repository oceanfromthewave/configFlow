package dev.configflow.api.repository;

import dev.configflow.application.repository.RepositoryService;
import dev.configflow.domain.repository.Repository;
import dev.configflow.domain.repository.RepositoryId;
import dev.configflow.domain.vcs.model.VcsType;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Repository management API: list registered repositories, register a local working copy
 * (VCS auto-detected) and mark one as opened. Thin controller — all logic lives in the
 * application {@link RepositoryService}; this layer only maps to and from DTOs.
 */
@RestController
@RequestMapping("/api/v1/repositories")
public class RepositoryController {

	/** POST body for registering a local working copy. */
	public record RegisterRequest(String localPath) {}

	/** Repository as seen by the client (domain types flattened to JSON-friendly forms). */
	public record RepositoryResponse(
			String id,
			String name,
			String localPath,
			String remoteUrl,
			VcsType vcsType,
			String groupName,
			boolean favorite,
			Instant createdAt,
			Instant lastOpenedAt) {

		static RepositoryResponse from(Repository repo) {
			return new RepositoryResponse(
					repo.id().asString(),
					repo.name(),
					repo.localPath().toString(),
					repo.remoteUrl(),
					repo.vcsType(),
					repo.groupName(),
					repo.favorite(),
					repo.createdAt(),
					repo.lastOpenedAt());
		}
	}

	private final RepositoryService repositoryService;

	public RepositoryController(RepositoryService repositoryService) {
		this.repositoryService = repositoryService;
	}

	@GetMapping
	public List<RepositoryResponse> list() {
		return repositoryService.list().stream().map(RepositoryResponse::from).toList();
	}

	@PostMapping
	public RepositoryResponse register(@RequestBody RegisterRequest request) {
		if (request == null || request.localPath() == null || request.localPath().isBlank()) {
			throw new IllegalArgumentException("Request body must contain a 'localPath' field");
		}
		Repository registered = repositoryService.register(Path.of(request.localPath()));
		return RepositoryResponse.from(registered);
	}

	@PostMapping("/{id}/open")
	public RepositoryResponse open(@PathVariable String id) {
		return RepositoryResponse.from(repositoryService.open(RepositoryId.of(id)));
	}
}