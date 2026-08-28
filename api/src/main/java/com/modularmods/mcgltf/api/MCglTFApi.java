package com.modularmods.mcgltf.api;

import java.util.Objects;

import net.minecraft.resources.Identifier;

/** Public entry point registered by the loader-specific MCglTF bootstrap. */
public abstract class MCglTFApi {

	private static MCglTFApi instance;

	public static MCglTFApi get() {
		MCglTFApi current = instance;
		if (current == null) {
			throw new IllegalStateException("MCglTF has not been initialized yet");
		}
		return current;
	}

	protected static void register(MCglTFApi api) {
		instance = Objects.requireNonNull(api, "api");
	}

	public abstract GltfModelHandle registerModel(Identifier location);

	public abstract boolean isShaderModActive();
}
