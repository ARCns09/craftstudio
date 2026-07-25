package dev.arcn.craftstudio.platform.task;

public final class OperationCancelledException extends RuntimeException {
	public OperationCancelledException() {
		super("The operation was cancelled.");
	}
}
