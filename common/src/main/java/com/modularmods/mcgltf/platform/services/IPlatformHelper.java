package com.modularmods.mcgltf.platform.services;

import java.util.function.BooleanSupplier;

import com.modularmods.mcgltf.platform.ResourceReloadBridge;

public interface IPlatformHelper {

	String getPlatformName();

	boolean isModLoaded(String modId);

	boolean isDevelopmentEnvironment();

	default String getEnvironmentName() {
		return isDevelopmentEnvironment() ? "development" : "production";
	}

	void registerClientResourceReloadListener(ResourceReloadBridge bridge);

	BooleanSupplier createShaderModProbe();
}
