package dev.arcn.craftstudio.graph.domain;

import java.util.List;
import java.util.Objects;

public record ResolutionIssue(
	ResolutionIssueSeverity severity,
	String code,
	String message,
	String packPath,
	String jsonPath,
	List<String> dependencyChain
) {
	public ResolutionIssue {
		severity = Objects.requireNonNull(severity, "severity");
		code = requireText(code, "code");
		message = requireText(message, "message");
		packPath = Objects.requireNonNull(packPath, "packPath");
		jsonPath = Objects.requireNonNull(jsonPath, "jsonPath");
		dependencyChain = List.copyOf(Objects.requireNonNull(dependencyChain, "dependencyChain"));
	}

	private static String requireText(String value, String name) {
		String result = Objects.requireNonNull(value, name).strip();
		if (result.isEmpty()) {
			throw new IllegalArgumentException(name + " cannot be blank.");
		}
		return result;
	}
}
