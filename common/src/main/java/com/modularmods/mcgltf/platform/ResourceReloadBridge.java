package com.modularmods.mcgltf.platform;

import java.util.Map;

import org.apache.commons.lang3.tuple.MutablePair;

import com.modularmods.mcgltf.internal.InternalModelReceiver;
import de.javagl.jgltf.model.GltfModel;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

public interface ResourceReloadBridge {

	Map<Identifier, MutablePair<GltfModel, java.util.List<InternalModelReceiver>>> prepare(ResourceManager resourceManager);

	void apply(Map<Identifier, MutablePair<GltfModel, java.util.List<InternalModelReceiver>>> models);
}
