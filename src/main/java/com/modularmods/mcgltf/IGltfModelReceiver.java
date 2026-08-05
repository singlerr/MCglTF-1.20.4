package com.modularmods.mcgltf;

import java.util.List;

import de.javagl.jgltf.model.GltfModel;
import net.minecraft.resources.Identifier;

public interface IGltfModelReceiver {

	Identifier getModelLocation();

	default void onReceiveSharedModel(RenderedGltfModel renderedModel) {}

	default boolean isReceiveSharedModel(GltfModel gltfModel, List<Runnable> gltfRenderDatas) {
		return true;
	}
}
