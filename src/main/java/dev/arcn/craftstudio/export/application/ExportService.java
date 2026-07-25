package dev.arcn.craftstudio.export.application;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import dev.arcn.craftstudio.export.domain.ExistingOutputException;
import dev.arcn.craftstudio.export.domain.ExistingOutputPolicy;
import dev.arcn.craftstudio.export.domain.ExportBlockedException;
import dev.arcn.craftstudio.export.domain.ExportException;
import dev.arcn.craftstudio.export.domain.ExportRequest;
import dev.arcn.craftstudio.export.domain.ExportResult;
import dev.arcn.craftstudio.export.domain.ExportType;
import dev.arcn.craftstudio.platform.filesystem.AtomicFileWriter;
import dev.arcn.craftstudio.platform.task.OperationCancellation;
import dev.arcn.craftstudio.project.domain.CraftStudioProject;
import dev.arcn.craftstudio.validation.application.ValidationService;
import dev.arcn.craftstudio.validation.domain.ValidationReport;
import dev.arcn.craftstudio.validation.domain.ValidationSeverity;
import dev.arcn.craftstudio.version.TargetVersionManifest;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import java.util.stream.Stream;

public final class ExportService {
	private final TargetVersionManifest target;
	private final ValidationService validationService;
	private final AtomicFileWriter atomicFileWriter;
	private final Clock clock;
	private final Gson gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

	public ExportService(
		TargetVersionManifest target,
		ValidationService validationService,
		AtomicFileWriter atomicFileWriter,
		Clock clock
	) {
		this.target = Objects.requireNonNull(target, "target");
		this.validationService = Objects.requireNonNull(validationService, "validationService");
		this.atomicFileWriter = Objects.requireNonNull(atomicFileWriter, "atomicFileWriter");
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	public Path targetPath(ExportRequest request) {
		Objects.requireNonNull(request, "request");
		String fileName = request.type() == ExportType.ZIP
			? request.exportName() + ".zip"
			: request.exportName();
		Path targetPath = request.destinationRoot().resolve(fileName).normalize();
		if (!targetPath.getParent().equals(request.destinationRoot())) {
			throw new IllegalArgumentException("Export target escaped its destination folder.");
		}
		return targetPath;
	}

	public ExportResult export(
		CraftStudioProject project,
		ExportRequest request,
		OperationCancellation cancellation
	) throws ExportException {
		Objects.requireNonNull(project, "project");
		Objects.requireNonNull(request, "request");
		Objects.requireNonNull(cancellation, "cancellation").throwIfCancelled();
		ValidationReport sourceValidation = validationService.validateProject(project, cancellation);
		if (!sourceValidation.canExport()) {
			throw new ExportBlockedException(sourceValidation);
		}

		Path output = targetPath(request);
		validateDestination(project, request.destinationRoot(), output);
		Path stagingRoot = project.root()
			.resolve(".craftstudio/staging/export-" + UUID.randomUUID())
			.toAbsolutePath()
			.normalize();
		Path stagedPack = stagingRoot.resolve("pack");
		Path publishTemporary = request.destinationRoot().resolve(
			"." + output.getFileName() + ".craftstudio-" + UUID.randomUUID() + ".tmp"
		).normalize();
		BackupSnapshot backup = null;
		boolean published = false;
		try {
			Files.createDirectories(stagedPack);
			copyDirectory(project.packRoot(), stagedPack, cancellation);
			ValidationReport stagedValidation = validationService.validateStagedPack(
				project.metadata().projectId(),
				stagedPack,
				cancellation
			);
			if (!stagedValidation.canExport()) {
				throw new ExportBlockedException(stagedValidation);
			}

			Files.createDirectories(request.destinationRoot());
			rejectSymbolicParents(request.destinationRoot());
			if (request.type() == ExportType.ZIP) {
				writeZip(stagedPack, publishTemporary, cancellation);
				verifyZip(publishTemporary);
			} else {
				Files.createDirectory(publishTemporary);
				copyDirectory(stagedPack, publishTemporary, cancellation);
				verifyFolder(publishTemporary);
			}
			cancellation.throwIfCancelled();

			if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
				if (request.existingOutputPolicy() == ExistingOutputPolicy.CANCEL) {
					throw new ExistingOutputException(output);
				}
				backup = backupExistingOutput(project, output);
				deleteExplicitOutput(output);
			}
			try {
				publish(publishTemporary, output);
				published = true;
			} catch (IOException exception) {
				restoreBackupIfNeeded(output, backup);
				throw exception;
			}
			if (request.type() == ExportType.ZIP) {
				verifyZip(output);
			} else {
				verifyFolder(output);
			}

			int fileCount = request.type() == ExportType.ZIP
				? countZipFiles(output)
				: countRegularFiles(output);
			String hash = request.type() == ExportType.ZIP
				? sha256File(output)
				: sha256Directory(output);
			Path reportPath = writeReport(
				project,
				request,
				output,
				fileCount,
				hash,
				sourceValidation,
				backup
			);
			return new ExportResult(
				output,
				reportPath,
				fileCount,
				hash,
				sourceValidation,
				Optional.ofNullable(backup).map(BackupSnapshot::root)
			);
		} catch (ExportException exception) {
			throw exception;
		} catch (IOException | RuntimeException exception) {
			if (!published) {
				restoreBackupIfNeeded(output, backup);
			}
			throw new ExportException("Could not export the resource pack safely.", exception);
		} finally {
			deleteTaskOwned(stagingRoot);
			deleteTaskOwned(publishTemporary);
		}
	}

	private void validateDestination(
		CraftStudioProject project,
		Path destinationRoot,
		Path output
	) throws ExportException {
		Path packRoot = project.packRoot().toAbsolutePath().normalize();
		Path projectRoot = project.root().toAbsolutePath().normalize();
		if (destinationRoot.equals(packRoot)
			|| destinationRoot.startsWith(packRoot)
			|| output.equals(projectRoot)
			|| output.equals(packRoot)
			|| packRoot.startsWith(output)) {
			throw new ExportException("Export destination overlaps the source project pack.");
		}
		Path existing = destinationRoot;
		while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
			existing = existing.getParent();
		}
		if (existing == null || Files.isSymbolicLink(existing) || !Files.isDirectory(existing)) {
			throw new ExportException("Export destination has no safe existing parent directory.");
		}
		try {
			rejectSymbolicParents(existing);
		} catch (IOException exception) {
			throw new ExportException("Export destination passes through a symbolic link.", exception);
		}
	}

	private void copyDirectory(
		Path sourceRoot,
		Path destinationRoot,
		OperationCancellation cancellation
	) throws IOException {
		Path source = sourceRoot.toAbsolutePath().normalize();
		Path destination = destinationRoot.toAbsolutePath().normalize();
		if (Files.isSymbolicLink(source) || !Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Source pack folder is not a safe directory.");
		}
		List<Path> entries;
		try (Stream<Path> stream = Files.walk(source)) {
			entries = stream.sorted().toList();
		}
		for (Path entry : entries) {
			cancellation.throwIfCancelled();
			if (Files.isSymbolicLink(entry)) {
				throw new IOException("Export source contains a symbolic link: " + entry);
			}
			Path relative = source.relativize(entry);
			Path targetPath = resolveUnder(destination, relative);
			if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
				Files.createDirectories(targetPath);
			} else if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
				Files.createDirectories(targetPath.getParent());
				Files.copy(
					entry,
					targetPath,
					StandardCopyOption.REPLACE_EXISTING,
					StandardCopyOption.COPY_ATTRIBUTES
				);
			} else {
				throw new IOException("Export source contains an unsupported file type: " + entry);
			}
		}
	}

	private void writeZip(
		Path stagedPack,
		Path destination,
		OperationCancellation cancellation
	) throws IOException {
		List<Path> files;
		try (Stream<Path> stream = Files.walk(stagedPack)) {
			files = stream
				.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
				.sorted(Comparator.comparing(path -> zipEntryName(stagedPack, path)))
				.toList();
		}
		Files.createDirectories(destination.getParent());
		try (OutputStream fileOutput = Files.newOutputStream(
			destination,
			StandardOpenOption.CREATE_NEW,
			StandardOpenOption.WRITE
		);
			ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(fileOutput))) {
			for (Path file : files) {
				cancellation.throwIfCancelled();
				if (Files.isSymbolicLink(file)) {
					throw new IOException("ZIP source contains a symbolic link.");
				}
				String entryName = zipEntryName(stagedPack, file);
				ZipEntrySafety.requireSafe(entryName);
				zip.putNextEntry(new ZipEntry(entryName));
				try (InputStream input = new BufferedInputStream(Files.newInputStream(file))) {
					input.transferTo(zip);
				}
				zip.closeEntry();
			}
		}
	}

	private void verifyFolder(Path folder) throws IOException {
		if (Files.isSymbolicLink(folder)
			|| !Files.isRegularFile(folder.resolve("pack.mcmeta"), LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Exported folder does not contain pack.mcmeta at its root.");
		}
		try (Stream<Path> paths = Files.walk(folder)) {
			for (Path path : paths.toList()) {
				if (Files.isSymbolicLink(path)) {
					throw new IOException("Exported folder contains a symbolic link.");
				}
			}
		}
	}

	private void verifyZip(Path zipPath) throws IOException {
		boolean hasPackMetadata = false;
		try (ZipFile zip = new ZipFile(zipPath.toFile(), StandardCharsets.UTF_8)) {
			java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				ZipEntrySafety.requireSafe(entry.getName());
				if (entry.getName().equals("pack.mcmeta") && !entry.isDirectory()) {
					hasPackMetadata = true;
				}
			}
		}
		if (!hasPackMetadata) {
			throw new IOException("Exported ZIP does not contain pack.mcmeta at its root.");
		}
	}

	private BackupSnapshot backupExistingOutput(
		CraftStudioProject project,
		Path output
	) throws IOException {
		if (Files.isSymbolicLink(output)) {
			throw new IOException("Refusing to replace a symbolic output target.");
		}
		Path backupRoot = project.root()
			.resolve(".craftstudio/backups/exports")
			.resolve(clock.instant().toEpochMilli() + "-" + UUID.randomUUID())
			.toAbsolutePath()
			.normalize();
		Files.createDirectories(backupRoot);
		Path payload = backupRoot.resolve(output.getFileName().toString()).normalize();
		if (Files.isDirectory(output, LinkOption.NOFOLLOW_LINKS)) {
			String sourceHash = sha256Directory(output);
			Files.createDirectory(payload);
			copyDirectory(output, payload, new OperationCancellation());
			verifyFolderOrGeneralDirectory(payload);
			if (!sourceHash.equals(sha256Directory(payload))) {
				throw new IOException("Existing directory backup verification failed.");
			}
			return new BackupSnapshot(backupRoot, payload, true);
		}
		if (Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS)) {
			Files.copy(output, payload, StandardCopyOption.COPY_ATTRIBUTES);
			if (!sha256File(output).equals(sha256File(payload))) {
				throw new IOException("Existing output backup verification failed.");
			}
			return new BackupSnapshot(backupRoot, payload, false);
		}
		throw new IOException("Existing output is not a regular file or directory.");
	}

	private void verifyFolderOrGeneralDirectory(Path folder) throws IOException {
		if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(folder)) {
			throw new IOException("Directory backup verification failed.");
		}
	}

	private void restoreBackupIfNeeded(Path output, BackupSnapshot backup) {
		if (backup == null || Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		try {
			if (backup.directory()) {
				Files.createDirectory(output);
				copyDirectory(backup.payload(), output, new OperationCancellation());
			} else {
				Files.copy(backup.payload(), output);
			}
		} catch (Exception ignored) {
			// Preserve the original export failure and retain the complete backup.
		}
	}

	private void deleteExplicitOutput(Path output) throws IOException {
		if (Files.isSymbolicLink(output)) {
			throw new IOException("Refusing to delete a symbolic output target.");
		}
		if (Files.isDirectory(output, LinkOption.NOFOLLOW_LINKS)) {
			deleteTree(output);
		} else {
			Files.delete(output);
		}
	}

	private void publish(Path temporary, Path output) throws IOException {
		try {
			Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException exception) {
			Files.move(temporary, output);
		}
	}

	private Path writeReport(
		CraftStudioProject project,
		ExportRequest request,
		Path output,
		int fileCount,
		String hash,
		ValidationReport validation,
		BackupSnapshot backup
	) throws IOException {
		Path reportRoot = project.root().resolve(".craftstudio/export-reports").normalize();
		Path reportPath = reportRoot.resolve(
			clock.instant().toEpochMilli() + "-" + UUID.randomUUID() + ".json"
		).normalize();
		List<String> warnings = validation.issues().stream()
			.filter(issue -> issue.severity() == ValidationSeverity.WARNING)
			.map(issue -> issue.code() + ": " + issue.summary())
			.toList();
		ExportReportDocument report = new ExportReportDocument(
			target.minecraftVersion(),
			target.resourcePackFormat(),
			clock.instant().toString(),
			request.type().name().toLowerCase(java.util.Locale.ROOT),
			fileCount,
			project.metadata().selectedRoots().stream()
				.map(ProjectMetadataView::from)
				.toList(),
			new ValidationSummary(
				validation.count(ValidationSeverity.ERROR),
				validation.count(ValidationSeverity.WARNING),
				validation.count(ValidationSeverity.INFORMATION),
				validation.count(ValidationSeverity.PASSED)
			),
			output.toString(),
			hash,
			warnings,
			backup == null ? "" : backup.root().toString()
		);
		atomicFileWriter.writeUtf8(
			reportPath,
			gson.toJson(report) + System.lineSeparator()
		);
		return reportPath;
	}

	private int countRegularFiles(Path root) throws IOException {
		try (Stream<Path> files = Files.walk(root)) {
			return Math.toIntExact(files
				.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
				.count());
		}
	}

	private int countZipFiles(Path zipPath) throws IOException {
		try (ZipFile zip = new ZipFile(zipPath.toFile(), StandardCharsets.UTF_8)) {
			return Math.toIntExact(zip.stream().filter(entry -> !entry.isDirectory()).count());
		}
	}

	private String sha256File(Path file) throws IOException {
		MessageDigest digest = sha256Digest();
		try (InputStream input = new DigestInputStream(
			new BufferedInputStream(Files.newInputStream(file)),
			digest
		)) {
			input.transferTo(OutputStream.nullOutputStream());
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	private String sha256Directory(Path root) throws IOException {
		MessageDigest digest = sha256Digest();
		List<Path> files;
		try (Stream<Path> stream = Files.walk(root)) {
			files = stream
				.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
				.sorted(Comparator.comparing(path -> zipEntryName(root, path)))
				.toList();
		}
		for (Path file : files) {
			digest.update(zipEntryName(root, file).getBytes(StandardCharsets.UTF_8));
			digest.update((byte) 0);
			try (InputStream input = new DigestInputStream(
				new BufferedInputStream(Files.newInputStream(file)),
				digest
			)) {
				input.transferTo(OutputStream.nullOutputStream());
			}
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	private MessageDigest sha256Digest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable.", exception);
		}
	}

	private String zipEntryName(Path root, Path file) {
		return root.relativize(file).toString().replace('\\', '/');
	}

	private Path resolveUnder(Path root, Path relative) throws IOException {
		if (relative.isAbsolute() || relative.normalize().startsWith("..")) {
			throw new IOException("Export path escaped its operation root.");
		}
		Path normalizedRoot = root.toAbsolutePath().normalize();
		Path result = normalizedRoot.resolve(relative).normalize();
		if (!result.startsWith(normalizedRoot)) {
			throw new IOException("Export path escaped its operation root.");
		}
		return result;
	}

	private void rejectSymbolicParents(Path path) throws IOException {
		Path current = path.toAbsolutePath().normalize();
		List<Path> chain = new ArrayList<>();
		while (current != null) {
			chain.add(current);
			current = current.getParent();
		}
		for (Path candidate : chain.reversed()) {
			if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)
				&& Files.isSymbolicLink(candidate)) {
				throw new IOException("Export destination passes through a symbolic link.");
			}
		}
	}

	private void deleteTaskOwned(Path path) {
		if (path == null || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		try {
			if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
				&& !Files.isSymbolicLink(path)) {
				deleteTree(path);
			} else {
				Files.deleteIfExists(path);
			}
		} catch (IOException ignored) {
			// A later startup can recover a clearly named CraftStudio temporary.
		}
	}

	private void deleteTree(Path root) throws IOException {
		try (Stream<Path> paths = Files.walk(root)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				if (Files.isSymbolicLink(path)) {
					throw new IOException("Refusing to delete a directory containing a symbolic link.");
				}
				Files.deleteIfExists(path);
			}
		}
	}

	private record BackupSnapshot(Path root, Path payload, boolean directory) {
	}

	private record ProjectMetadataView(String type, String id, @SerializedName("selection_mode") String mode) {
		private static ProjectMetadataView from(
			dev.arcn.craftstudio.project.domain.ProjectMetadata.SelectedRoot root
		) {
			return new ProjectMetadataView(root.type(), root.id(), root.selectionMode());
		}
	}

	private record ValidationSummary(long errors, long warnings, long information, long passed) {
	}

	private record ExportReportDocument(
		@SerializedName("minecraft_version") String minecraftVersion,
		@SerializedName("resource_pack_format") int resourcePackFormat,
		String timestamp,
		@SerializedName("export_type") String exportType,
		@SerializedName("file_count") int fileCount,
		@SerializedName("root_assets") List<ProjectMetadataView> rootAssets,
		ValidationSummary validation,
		@SerializedName("output_path") String outputPath,
		String sha256,
		@SerializedName("warnings_accepted") List<String> warningsAccepted,
		@SerializedName("backup_path") String backupPath
	) {
	}
}
