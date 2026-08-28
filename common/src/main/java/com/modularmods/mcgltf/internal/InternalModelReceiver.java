package com.modularmods.mcgltf.internal;

import java.util.List;

import de.javagl.jgltf.model.GltfModel;
import net.minecraft.resources.Identifier;

/** Internal receiver hook that can inspect parsed glTF before model sharing. */
public interface InternalModelReceiver {

	Identifier getModelLocation();

	default void onReceiveSharedModel(com.modularmods.mcgltf.RenderedGltfModel renderedModel) {
	}

	default boolean isReceiveSharedModel(GltfModel gltfModel, List<Runnable> gltfRenderDatas) {
		return true;
	}
}
