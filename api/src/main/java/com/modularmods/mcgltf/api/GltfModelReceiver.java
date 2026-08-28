package com.modularmods.mcgltf.api;

import net.minecraft.resources.Identifier;

/**
 * Advanced hook for sharing one prepared {@link GltfRenderable} across multiple consumers of the
 * same asset. Most dependent mods should use {@link MCglTFApi#registerModel(Identifier)} instead.
 */
public interface GltfModelReceiver {

	Identifier modelLocation();

	default void onModelReady(GltfRenderable renderable) {
	}

	default boolean acceptsSharedModel() {
		return true;
	}
}
