package com.modularmods.mcgltf.platform;

import java.util.ServiceLoader;

import com.modularmods.mcgltf.Constants;
import com.modularmods.mcgltf.platform.services.IPlatformHelper;

public final class Services {

	public static final IPlatformHelper PLATFORM = load(IPlatformHelper.class);

	private Services() {
	}

	private static <T> T load(Class<T> clazz) {
		T loadedService = ServiceLoader.load(clazz, Services.class.getClassLoader())
			.findFirst()
			.orElseThrow(() -> new NullPointerException("Failed to load service for " + clazz.getName()));
		Constants.LOG.debug("Loaded {} for service {}", loadedService, clazz);
		return loadedService;
	}
}
