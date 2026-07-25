package dev.arcn.craftstudio.platform.process;

import com.google.gson.annotations.SerializedName;
import java.util.Objects;

public record EditorSettings(
	@SerializedName("preferred_image_editor") String preferredImageEditor
) {
	public static final EditorSettings DEFAULT = new EditorSettings("");

	public EditorSettings {
		preferredImageEditor = Objects.requireNonNull(
			preferredImageEditor,
			"preferredImageEditor"
		).strip();
	}
}
