package dev.arcn.craftstudio.validation.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record ValidationReport(
	String projectId,
	Instant generatedAt,
	int fileCount,
	List<ValidationIssue> issues
) {
	public ValidationReport {
		projectId = Objects.requireNonNull(projectId, "projectId");
		generatedAt = Objects.requireNonNull(generatedAt, "generatedAt");
		if (fileCount < 0) {
			throw new IllegalArgumentException("fileCount cannot be negative.");
		}
		issues = List.copyOf(Objects.requireNonNull(issues, "issues"));
	}

	public long count(ValidationSeverity severity) {
		return issues.stream().filter(issue -> issue.severity() == severity).count();
	}

	public boolean canExport() {
		return count(ValidationSeverity.ERROR) == 0;
	}
}
