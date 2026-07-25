package dev.arcn.craftstudio.export.domain;

import dev.arcn.craftstudio.validation.domain.ValidationReport;
import java.util.Objects;

public final class ExportBlockedException extends ExportException {
	private final ValidationReport validation;

	public ExportBlockedException(ValidationReport validation) {
		super(
			"Export is blocked by "
				+ Objects.requireNonNull(validation, "validation")
					.count(dev.arcn.craftstudio.validation.domain.ValidationSeverity.ERROR)
				+ " validation error(s)."
		);
		this.validation = validation;
	}

	public ValidationReport validation() {
		return validation;
	}
}
