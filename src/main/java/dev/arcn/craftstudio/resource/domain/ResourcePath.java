package dev.arcn.craftstudio.resource.domain;

import java.util.Objects;
import java.util.regex.Pattern;

public record ResourcePath(String namespace, String path) implements Comparable<ResourcePath> {
	private static final Pattern NAMESPACE_PATTERN = Pattern.compile("[a-z0-9_.-]+");
	private static final Pattern PATH_PATTERN = Pattern.compile("[a-z0-9/._-]+");
	private static final String ASSETS_PREFIX = "assets/";

	public ResourcePath {
		namespace = validateNamespace(namespace);
		path = validatePath(path, false);
	}

	public static ResourcePath fromPackPath(String packPath) {
		String value = requireUnchanged(packPath, "packPath");
		if (!value.startsWith(ASSETS_PREFIX)) {
			throw new IllegalArgumentException("Resource pack path must begin with assets/.");
		}
		int namespaceEnd = value.indexOf('/', ASSETS_PREFIX.length());
		if (namespaceEnd < 0) {
			throw new IllegalArgumentException("Resource pack path must include a namespace and asset path.");
		}
		return new ResourcePath(
			value.substring(ASSETS_PREFIX.length(), namespaceEnd),
			value.substring(namespaceEnd + 1)
		);
	}

	public static String validateNamespace(String namespace) {
		String value = requireUnchanged(namespace, "namespace");
		if (!NAMESPACE_PATTERN.matcher(value).matches()) {
			throw new IllegalArgumentException("Invalid resource namespace: " + value);
		}
		return value;
	}

	public static String normalizePrefix(String prefix) {
		String value = requireUnchanged(prefix, "prefix");
		if (value.isEmpty()) {
			return value;
		}
		String normalized = value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
		return validatePath(normalized, true);
	}

	public String packPath() {
		return ASSETS_PREFIX + namespace + "/" + path;
	}

	@Override
	public int compareTo(ResourcePath other) {
		int namespaceComparison = namespace.compareTo(other.namespace);
		return namespaceComparison != 0 ? namespaceComparison : path.compareTo(other.path);
	}

	private static String validatePath(String path, boolean prefix) {
		String value = requireUnchanged(path, prefix ? "prefix" : "path");
		if (value.isEmpty()) {
			throw new IllegalArgumentException((prefix ? "Resource prefix" : "Resource path") + " cannot be empty.");
		}
		if (!PATH_PATTERN.matcher(value).matches()
			|| value.startsWith("/")
			|| value.endsWith("/")
			|| value.contains("//")) {
			throw new IllegalArgumentException("Invalid relative resource path: " + value);
		}
		for (String segment : value.split("/")) {
			if (segment.equals(".") || segment.equals("..")) {
				throw new IllegalArgumentException("Resource paths cannot contain dot traversal segments.");
			}
		}
		return value;
	}

	private static String requireUnchanged(String value, String name) {
		String result = Objects.requireNonNull(value, name);
		if (result.indexOf('\0') >= 0) {
			throw new IllegalArgumentException(name + " cannot contain null bytes.");
		}
		if (!result.equals(result.strip())) {
			throw new IllegalArgumentException(name + " cannot contain leading or trailing whitespace.");
		}
		if (result.indexOf('\\') >= 0) {
			throw new IllegalArgumentException(name + " must use forward slashes.");
		}
		return result;
	}
}
