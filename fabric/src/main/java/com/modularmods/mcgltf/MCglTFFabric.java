package com.modularmods.mcgltf;

import com.modularmods.mcgltf.platform.ResourceReloadBridge;

import net.fabricmc.api.ClientModInitializer;

public final class MCglTFFabric implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		MCglTFImpl.getInstance().initializeClient();
	}
}
