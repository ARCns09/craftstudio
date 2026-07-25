package dev.arcn.craftstudio.platform.filesystem;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.UUID;

public final class AtomicFileWriter {
	public void writeUtf8(Path target, String content) throws IOException {
		writeBytes(
			target,
			Objects.requireNonNull(content, "content").getBytes(StandardCharsets.UTF_8)
		);
	}

	public void writeBytes(Path target, byte[] content) throws IOException {
		Path normalizedTarget = Objects.requireNonNull(target, "target").toAbsolutePath().normalize();
		Path parent = normalizedTarget.getParent();
		if (parent == null) {
			throw new IOException("The target file must have a parent directory.");
		}
		if (Files.isSymbolicLink(normalizedTarget)) {
			throw new IOException("Refusing to replace a symbolic link: " + normalizedTarget);
		}

		Files.createDirectories(parent);
		Path temporaryFile = parent.resolve(
			"." + normalizedTarget.getFileName() + ".craftstudio-" + UUID.randomUUID() + ".tmp"
		);
		byte[] bytes = Objects.requireNonNull(content, "content").clone();

		try {
			try (FileChannel channel = FileChannel.open(
				temporaryFile,
				StandardOpenOption.CREATE_NEW,
				StandardOpenOption.WRITE
			)) {
				ByteBuffer buffer = ByteBuffer.wrap(bytes);
				while (buffer.hasRemaining()) {
					channel.write(buffer);
				}
				channel.force(true);
			}

			try {
				Files.move(
					temporaryFile,
					normalizedTarget,
					StandardCopyOption.ATOMIC_MOVE,
					StandardCopyOption.REPLACE_EXISTING
				);
			} catch (AtomicMoveNotSupportedException exception) {
				Files.move(temporaryFile, normalizedTarget, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temporaryFile);
		}
	}
}
