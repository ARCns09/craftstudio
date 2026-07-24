package dev.arcn.craftstudio.catalog.infrastructure.minecraft;

import dev.arcn.craftstudio.catalog.domain.AssetKind;
import dev.arcn.craftstudio.catalog.domain.CatalogAssetSeed;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

public final class MinecraftVanillaCatalogAdapter {
	public List<CatalogAssetSeed> snapshot() {
		List<CatalogAssetSeed> seeds = new ArrayList<>(
			Registries.BLOCK.getIds().size() + Registries.ITEM.getIds().size()
		);
		Registries.BLOCK.getIds().stream()
			.sorted(Comparator.comparing(Identifier::toString))
			.forEach(identifier -> seeds.add(blockSeed(identifier, Registries.BLOCK.get(identifier))));
		Registries.ITEM.getIds().stream()
			.sorted(Comparator.comparing(Identifier::toString))
			.forEach(identifier -> seeds.add(itemSeed(identifier, Registries.ITEM.get(identifier))));
		return List.copyOf(seeds);
	}

	private CatalogAssetSeed blockSeed(Identifier identifier, Block block) {
		String translationKey = block.getTranslationKey();
		return new CatalogAssetSeed(
			AssetKind.BLOCK,
			identifier.toString(),
			Text.translatable(translationKey).getString(),
			identifier.getNamespace(),
			identifier.getPath(),
			translationKey
		);
	}

	private CatalogAssetSeed itemSeed(Identifier identifier, Item item) {
		String translationKey = item.getTranslationKey();
		return new CatalogAssetSeed(
			AssetKind.ITEM,
			identifier.toString(),
			Text.translatable(translationKey).getString(),
			identifier.getNamespace(),
			identifier.getPath(),
			translationKey
		);
	}
}
