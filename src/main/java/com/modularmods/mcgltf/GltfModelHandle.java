package com.modularmods.mcgltf;

import java.util.Objects;

import net.minecraft.resources.Identifier;

/** A reload-aware model registration returned by {@link MCglTF#registerModel(Identifier)}. */
public final class GltfModelHandle implements AutoCloseable {

	private final MCglTF owner;
	private final Identifier location;
	private volatile RenderedGltfModel model;
	private IGltfModelReceiver registration;
	private boolean closed;

	GltfModelHandle(MCglTF owner, Identifier location) {
		this.owner = Objects.requireNonNull(owner, "owner");
		this.location = Objects.requireNonNull(location, "location");
	}

	synchronized void attach(IGltfModelReceiver registration) {
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

	public Identifier location() {
		return location;
	}

	public boolean isReady() {
		return model != null;
	}

	public RenderedGltfModel get() {
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
		owner.removeGltfModelReceiver(registration);
	}
}
