package com.modularmods.mcgltf;

import com.modularmods.mcgltf.platform.ResourceReloadBridge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.minecraft.resources.Identifier;

@Mod(Constants.MOD_ID)
public final class MCglTFNeoForge {

	public MCglTFNeoForge(IEventBus modEventBus) {
		if (FMLEnvironment.getDist().isClient()) {
			modEventBus.addListener(this::registerReloadListeners);
			MCglTFImpl.getInstance().initializeClient();
		}
	}

	private void registerReloadListeners(AddClientReloadListenersEvent event) {
		ResourceReloadBridge bridge = MCglTFImpl.getInstance();
		event.addListener(
			Identifier.fromNamespaceAndPath(Constants.MOD_ID, "gltf_reload_listener"),
			new NeoForgeGltfReloadListener(bridge));
	}
}
