package com.modularmods.mcgltf;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.resources.Identifier;

class GltfModelHandleTest {

	@Test
	void tracksReloadedModelAndRejectsUseBeforeReady() {
		GltfModelHandle handle = new GltfModelHandle(MCglTF.getInstance(), Identifier.parse("mcgltf:test"));
		assertFalse(handle.isReady());
		assertThrows(IllegalStateException.class, handle::get);

		RenderedGltfModel model = new RenderedGltfModel(java.util.List.of(), new EmptyGltfModel());
		handle.update(model);
		assertTrue(handle.isReady());
		assertSame(model, handle.get());
		handle.clear();
		assertFalse(handle.isReady());
	}

	@Test
	void closeIsIdempotentAndBlocksLateReloadDelivery() {
		Identifier location = Identifier.parse("mcgltf:test_close");
		GltfModelHandle handle = new GltfModelHandle(MCglTF.getInstance(), location);
		IGltfModelReceiver receiver = () -> location;
		handle.attach(receiver);
		MCglTF.getInstance().addGltfModelReceiver(receiver);

		handle.close();
		handle.close();
		handle.update(new RenderedGltfModel(java.util.List.of(), new EmptyGltfModel()));
		assertFalse(handle.isReady());
	}

	private static final class EmptyGltfModel extends de.javagl.jgltf.model.impl.DefaultGltfModel {
	}
}
