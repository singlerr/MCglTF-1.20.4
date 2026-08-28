package com.modularmods.mcgltf.platform;

import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import com.modularmods.mcgltf.Constants;
import com.modularmods.mcgltf.internal.InternalModelReceiver;
import com.modularmods.mcgltf.platform.services.IPlatformHelper;

import de.javagl.jgltf.model.GltfModel;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.reloader.SimpleReloadListener;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.PreparableReloadListener.SharedState;

import org.apache.commons.lang3.tuple.MutablePair;

public final class FabricPlatformHelper implements IPlatformHelper {

	@Override
	public String getPlatformName() {
		return "Fabric";
	}

	@Override
	public boolean isModLoaded(String modId) {
		return FabricLoader.getInstance().isModLoaded(modId);
	}

	@Override
	public boolean isDevelopmentEnvironment() {
		return FabricLoader.getInstance().isDevelopmentEnvironment();
	}

	@Override
	public void registerClientResourceReloadListener(ResourceReloadBridge bridge) {
		ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(
			Identifier.fromNamespaceAndPath(Constants.MOD_ID, "gltf_reload_listener"),
			new SimpleReloadListener<Map<Identifier, MutablePair<GltfModel, List<InternalModelReceiver>>>>() {
				@Override
				protected Map<Identifier, MutablePair<GltfModel, List<InternalModelReceiver>>> prepare(
					SharedState state) {
					return bridge.prepare(state.resourceManager());
				}

				@Override
				protected void apply(
					Map<Identifier, MutablePair<GltfModel, List<InternalModelReceiver>>> models,
					SharedState state) {
					bridge.apply(models);
				}
			});
	}

	@Override
	public BooleanSupplier createShaderModProbe() {
		if (!isModLoaded("iris")) {
			return () -> false;
		}
		try {
			Class<?> irisApi = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
			Object api = irisApi.getMethod("getInstance").invoke(null);
			var query = irisApi.getMethod("isShaderPackInUse");
			return () -> {
				try {
					return (boolean) query.invoke(api);
				} catch (ReflectiveOperationException exception) {
					return false;
				}
			};
		} catch (ReflectiveOperationException exception) {
			Constants.LOG.debug("Could not initialize the Iris shader-state probe", exception);
			return () -> false;
		}
	}
}
