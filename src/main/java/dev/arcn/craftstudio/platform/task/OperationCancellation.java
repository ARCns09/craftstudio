package dev.arcn.craftstudio.platform.task;

import java.util.concurrent.atomic.AtomicBoolean;

public final class OperationCancellation {
	private final AtomicBoolean cancelled = new AtomicBoolean();

	public void cancel() {
		cancelled.set(true);
	}

	public boolean isCancelled() {
		return cancelled.get();
	}

	public void throwIfCancelled() {
		if (isCancelled()) {
			throw new OperationCancelledException();
		}
	}
}
