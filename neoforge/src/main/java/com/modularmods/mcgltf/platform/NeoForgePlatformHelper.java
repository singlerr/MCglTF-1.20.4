package com.modularmods.mcgltf.platform;

import java.util.function.BooleanSupplier;

import com.modularmods.mcgltf.Constants;
import com.modularmods.mcgltf.platform.services.IPlatformHelper;

import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;

public final class NeoForgePlatformHelper implements IPlatformHelper {

	@Override
	public String getPlatformName() {
		return "NeoForge";
	}

	@Override
	public boolean isModLoaded(String modId) {
		return ModList.get().isLoaded(modId);
	}

	@Override
	public boolean isDevelopmentEnvironment() {
		return !FMLLoader.getCurrent().isProduction();
	}

	@Override
	public void registerClientResourceReloadListener(ResourceReloadBridge bridge) {
		// Registered from MCglTFNeoForge via RegisterClientReloadListenersEvent.
	}

	@Override
	public BooleanSupplier createShaderModProbe() {
		if (!isModLoaded("oculus") && !isModLoaded("iris")) {
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
			Constants.LOG.debug("Could not initialize the shader-state probe on NeoForge", exception);
			return () -> false;
		}
	}
}
