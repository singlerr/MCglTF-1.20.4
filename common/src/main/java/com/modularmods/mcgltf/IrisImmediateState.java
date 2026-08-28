package com.modularmods.mcgltf;

import java.lang.reflect.Field;

import com.modularmods.mcgltf.platform.Services;

final class IrisImmediateState {

	private static final Field SKIP_EXTENSION = resolveSkipExtension();

	private IrisImmediateState() {
	}

	static Boolean skipExtensionGet() {
		if (SKIP_EXTENSION == null) {
			return null;
		}
		try {
			return (Boolean) SKIP_EXTENSION.get(null);
		} catch (ReflectiveOperationException exception) {
			return null;
		}
	}

	static void skipExtensionSet(Boolean value) {
		if (SKIP_EXTENSION == null || value == null) {
			return;
		}
		try {
			SKIP_EXTENSION.set(null, value);
		} catch (ReflectiveOperationException exception) {
			Constants.LOG.debug("Could not restore Iris ImmediateState.skipExtension", exception);
		}
	}

	private static Field resolveSkipExtension() {
		if (!Services.PLATFORM.isModLoaded("iris")) {
			return null;
		}
		try {
			Class<?> immediateState = Class.forName("net.irisshaders.iris.vertices.ImmediateState");
			Field field = immediateState.getField("skipExtension");
			field.setAccessible(true);
			return field;
		} catch (ReflectiveOperationException exception) {
			Constants.LOG.debug("Iris ImmediateState is unavailable", exception);
			return null;
		}
	}
}
