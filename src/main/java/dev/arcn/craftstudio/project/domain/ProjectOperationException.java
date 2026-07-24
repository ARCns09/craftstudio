package dev.arcn.craftstudio.project.domain;

public final class ProjectOperationException extends Exception {
	public ProjectOperationException(String message) {
		super(message);
	}

	public ProjectOperationException(String message, Throwable cause) {
		super(message, cause);
	}
}
