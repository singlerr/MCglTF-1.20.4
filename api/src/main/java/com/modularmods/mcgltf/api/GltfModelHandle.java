package com.modularmods.mcgltf.api;

import net.minecraft.resources.Identifier;

/** Reload-aware model registration returned by {@link MCglTFApi#registerModel(Identifier)}. */
public interface GltfModelHandle extends AutoCloseable {

	Identifier location();

	boolean isReady();

	GltfRenderable renderable();

	@Override
	void close();
}
