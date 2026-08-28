package com.modularmods.mcgltf;

import java.util.Objects;

import com.modularmods.mcgltf.api.GltfModelHandle;
import com.modularmods.mcgltf.api.GltfRenderable;
import com.modularmods.mcgltf.internal.InternalModelReceiver;

import net.minecraft.resources.Identifier;

final class GltfModelHandleImpl implements GltfModelHandle {

	private final MCglTFImpl owner;
	private final Identifier location;
	private volatile RenderedGltfModel model;
	private InternalModelReceiver registration;
	private boolean closed;

	GltfModelHandleImpl(MCglTFImpl owner, Identifier location) {
		this.owner = Objects.requireNonNull(owner, "owner");
		this.location = Objects.requireNonNull(location, "location");
	}

	synchronized void attach(InternalModelReceiver registration) {
		this.registration = Objects.requireNonNull(registration, "registration");
	}

	synchronized void update(RenderedGltfModel model) {
		if (!closed) {
			this.model = Objects.requireNonNull(model, "model");
		}
	}

	synchronized void clear() {
		model = null;
	}

	@Override
	public Identifier location() {
		return location;
	}

	@Override
	public boolean isReady() {
		return model != null;
	}

	@Override
	public GltfRenderable renderable() {
		RenderedGltfModel current = model;
		if (current == null) {
			throw new IllegalStateException("glTF model is not loaded: " + location);
		}
		return current;
	}

	@Override
	public synchronized void close() {
		if (closed) {
			return;
		}
		closed = true;
		model = null;
		owner.removeInternalModelReceiver(registration);
	}
}
