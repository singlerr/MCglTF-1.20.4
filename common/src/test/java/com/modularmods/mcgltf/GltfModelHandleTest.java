package com.modularmods.mcgltf;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.modularmods.mcgltf.api.GltfModelHandle;
import com.modularmods.mcgltf.api.MCglTFApi;

import net.minecraft.resources.Identifier;

class GltfModelHandleTest {

	@BeforeEach
	void registerApi() {
		MCglTFImpl.ensureRegisteredForTests();
	}

	@Test
	void tracksReloadedModelAndRejectsUseBeforeReady() {
		GltfModelHandle handle = MCglTFImpl.getInstance().registerModel(Identifier.parse("mcgltf:test"));
		assertFalse(handle.isReady());
		assertThrows(IllegalStateException.class, handle::renderable);

		RenderedGltfModel model = new RenderedGltfModel(java.util.List.of(), new EmptyGltfModel());
		((GltfModelHandleImpl) handle).update(model);
		assertTrue(handle.isReady());
		assertSame(model, handle.renderable());
		((GltfModelHandleImpl) handle).clear();
		assertFalse(handle.isReady());
	}

	@Test
	void closeIsIdempotentAndBlocksLateReloadDelivery() {
		Identifier location = Identifier.parse("mcgltf:test_close");
		GltfModelHandle handle = MCglTFImpl.getInstance().registerModel(location);
		handle.close();
		handle.close();
		((GltfModelHandleImpl) handle).update(new RenderedGltfModel(java.util.List.of(), new EmptyGltfModel()));
		assertFalse(handle.isReady());
	}

	private static final class EmptyGltfModel extends de.javagl.jgltf.model.impl.DefaultGltfModel {
	}
}
