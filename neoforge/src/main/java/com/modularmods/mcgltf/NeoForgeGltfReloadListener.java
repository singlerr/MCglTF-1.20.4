package com.modularmods.mcgltf;

import java.util.List;
import java.util.Map;

import com.modularmods.mcgltf.internal.InternalModelReceiver;
import com.modularmods.mcgltf.platform.ResourceReloadBridge;

import de.javagl.jgltf.model.GltfModel;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import org.apache.commons.lang3.tuple.MutablePair;

final class NeoForgeGltfReloadListener
	extends SimplePreparableReloadListener<Map<Identifier, MutablePair<GltfModel, List<InternalModelReceiver>>>> {

	private final ResourceReloadBridge bridge;

	NeoForgeGltfReloadListener(ResourceReloadBridge bridge) {
		this.bridge = bridge;
	}

	@Override
	protected Map<Identifier, MutablePair<GltfModel, List<InternalModelReceiver>>> prepare(ResourceManager manager,
		ProfilerFiller profiler) {
		return bridge.prepare(manager);
	}

	@Override
	protected void apply(Map<Identifier, MutablePair<GltfModel, List<InternalModelReceiver>>> preparations,
		ResourceManager manager, ProfilerFiller profiler) {
		bridge.apply(preparations);
	}
}
