package dev.arcn.craftstudio.export.domain;

public class ExportException extends Exception {
	public ExportException(String message) {
		super(message);
	}

	public ExportException(String message, Throwable cause) {
		super(message, cause);
	}
}
