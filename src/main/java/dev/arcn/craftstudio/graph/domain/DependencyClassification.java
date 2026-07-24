package dev.arcn.craftstudio.graph.domain;

public enum DependencyClassification {
	REQUIRED_ROOT,
	REQUIRED_TRANSITIVE,
	OPTIONAL,
	SHARED_VANILLA,
	GENERATED,
	MISSING,
	UNSUPPORTED_SPECIAL_CASE
}
