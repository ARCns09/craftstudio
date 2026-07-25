package dev.arcn.craftstudio.validation.domain;

import java.util.List;
import java.util.Objects;

public record ValidationIssue(
	ValidationSeverity severity,
	String code,
	String summary,
	String packPath,
	String jsonPath,
	List<String> dependencyChain,
	String suggestedRepair
) {
	public ValidationIssue {
		severity = Objects.requireNonNull(severity, "severity");
		code = requireText(code, "code");
		summary = requireText(summary, "summary");
		packPath = Objects.requireNonNull(packPath, "packPath");
		jsonPath = Objects.requireNonNull(jsonPath, "jsonPath");
		dependencyChain = List.copyOf(Objects.requireNonNull(dependencyChain, "dependencyChain"));
		suggestedRepair = Objects.requireNonNull(suggestedRepair, "suggestedRepair");
	}

	public static ValidationIssue passed(String code, String summary) {
		return new ValidationIssue(
			ValidationSeverity.PASSED,
			code,
			summary,
			"",
			"",
			List.of(),
			""
		);
	}

	private static String requireText(String value, String name) {
		String result = Objects.requireNonNull(value, name).strip();
		if (result.isEmpty()) {
			throw new IllegalArgumentException(name + " cannot be blank.");
		}
		return result;
	}
}
