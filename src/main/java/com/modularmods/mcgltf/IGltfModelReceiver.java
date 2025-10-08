package com.modularmods.mcgltf;

import de.javagl.jgltf.model.GltfModel;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

public interface IGltfModelReceiver {

    ResourceLocation getModelLocation();

    default void onReceiveSharedModel(RenderedGltfModel renderedModel) {
    }

    default boolean isReceiveSharedModel(GltfModel gltfModel, List<Runnable> gltfRenderDatas) {
        return true;
    }
}
