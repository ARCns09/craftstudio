package dev.arcn.craftstudio.reload;

import dev.arcn.craftstudio.resource.domain.ResourcePath;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record ProjectFileChangeBatch(Map<Path, ReloadClassification> changes) {
	public ProjectFileChangeBatch {
		Objects.requireNonNull(changes, "changes");
		LinkedHashMap<Path, ReloadClassification> safeChanges = new LinkedHashMap<>();
		changes.forEach((path, classification) -> {
			Path relative = Objects.requireNonNull(path, "path").normalize();
			if (relative.isAbsolute() || relative.startsWith("..")) {
				throw new IllegalArgumentException("Changed path must remain inside the pack root.");
			}
			safeChanges.put(relative, Objects.requireNonNull(classification, "classification"));
		});
		changes = Map.copyOf(safeChanges);
	}

	public boolean requiresBroadPreviewInvalidation() {
		return changes.containsValue(ReloadClassification.ATLAS);
	}

	public Optional<ResourcePath> resourcePath(Path relativePath) {
		Path normalized = Objects.requireNonNull(relativePath, "relativePath").normalize();
		String packPath = normalized.toString().replace('\\', '/');
		try {
			return Optional.of(ResourcePath.fromPackPath(packPath));
		} catch (IllegalArgumentException exception) {
			return Optional.empty();
		}
	}
}
