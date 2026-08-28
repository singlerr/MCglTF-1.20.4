package com.modularmods.mcgltf;

import java.io.BufferedInputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.logging.log4j.Logger;

import com.modularmods.mcgltf.api.GltfModelHandle;
import com.modularmods.mcgltf.api.GltfModelReceiver;
import com.modularmods.mcgltf.api.MCglTFApi;
import com.modularmods.mcgltf.internal.InternalModelReceiver;
import com.modularmods.mcgltf.platform.ResourceReloadBridge;
import com.modularmods.mcgltf.platform.Services;

import de.javagl.jgltf.model.GltfModel;
import de.javagl.jgltf.model.io.Buffers;
import de.javagl.jgltf.model.io.GltfModelReader;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

public final class MCglTFImpl extends MCglTFApi implements ResourceReloadBridge {

	private static final MCglTFImpl INSTANCE = new MCglTFImpl();

	private final Map<Identifier, Supplier<ByteBuffer>> loadedBufferResources = new HashMap<>();
	private final Map<Identifier, Supplier<ByteBuffer>> loadedImageResources = new HashMap<>();
	private final List<InternalModelReceiver> gltfModelReceivers = new ArrayList<>();
	private final List<Runnable> gltfRenderData = new ArrayList<>();
	private BooleanSupplier shaderModActive = () -> false;
	private boolean initialized;

	private MCglTFImpl() {
	}

	public static MCglTFImpl getInstance() {
		return INSTANCE;
	}

	static void ensureRegisteredForTests() {
		try {
			MCglTFApi.get();
		} catch (IllegalStateException ignored) {
			register(INSTANCE);
		}
	}

	public void initializeClient() {
		synchronized (this) {
			if (initialized) {
				return;
			}
			initialized = true;
		}
		MCglTFApi.register(this);
		MToonRenderTypes.bootstrap();
		shaderModActive = Services.PLATFORM.createShaderModProbe();
		Services.PLATFORM.registerClientResourceReloadListener(this);
	}

	@Override
	public Map<Identifier, MutablePair<GltfModel, List<InternalModelReceiver>>> prepare(ResourceManager resourceManager) {
		Map<Identifier, MutablePair<GltfModel, List<InternalModelReceiver>>> lookup = new HashMap<>();
		synchronized (this) {
			for (InternalModelReceiver receiver : gltfModelReceivers) {
				lookup.computeIfAbsent(receiver.getModelLocation(), ignored -> MutablePair.of(null, new ArrayList<>()))
					.getRight().add(receiver);
			}
		}

		lookup.entrySet().parallelStream().forEach(entry -> {
			try (BufferedInputStream input = new BufferedInputStream(
				resourceManager.getResource(entry.getKey()).orElseThrow().open())) {
				entry.getValue().setLeft(new GltfModelReader().readWithoutReferences(input));
			} catch (Exception exception) {
				logger().error("Failed to load glTF model {}", entry.getKey(), exception);
			}
		});
		return lookup;
	}

	@Override
	public void apply(Map<Identifier, MutablePair<GltfModel, List<InternalModelReceiver>>> lookup) {
		List<HandleReceiver> handles;
		synchronized (this) {
			handles = gltfModelReceivers.stream()
				.filter(HandleReceiver.class::isInstance)
				.map(HandleReceiver.class::cast)
				.toList();
		}
		handles.forEach(receiver -> receiver.handle.clear());
		gltfRenderData.forEach(Runnable::run);
		gltfRenderData.clear();
		loadedBufferResources.clear();
		loadedImageResources.clear();
		lookup.forEach((location, receivers) -> {
			GltfModel gltfModel = receivers.getLeft();
			if (gltfModel == null) {
				return;
			}
			processReceivers(location, gltfModel, receivers.getRight());
		});
	}

	private void processReceivers(Identifier location, GltfModel gltfModel, List<InternalModelReceiver> receivers) {
		Iterator<InternalModelReceiver> iterator = receivers.iterator();
		while (iterator.hasNext()) {
			InternalModelReceiver receiver = iterator.next();
			if (!receiver.isReceiveSharedModel(gltfModel, gltfRenderData)) {
				continue;
			}

			try {
				RenderedGltfModel renderedModel = new RenderedGltfModel(gltfRenderData, gltfModel);
				receiver.onReceiveSharedModel(renderedModel);
				while (iterator.hasNext()) {
					receiver = iterator.next();
					if (receiver.isReceiveSharedModel(gltfModel, gltfRenderData)) {
						receiver.onReceiveSharedModel(renderedModel);
					}
				}
			} catch (RuntimeException exception) {
				logger().error("Failed to prepare glTF model {}", location, exception);
			}
			return;
		}
	}

	public ByteBuffer getBufferResource(Identifier location) {
		return getResource(loadedBufferResources, location);
	}

	public ByteBuffer getImageResource(Identifier location) {
		return getResource(loadedImageResources, location);
	}

	private ByteBuffer getResource(Map<Identifier, Supplier<ByteBuffer>> resources, Identifier location) {
		Supplier<ByteBuffer> supplier;
		synchronized (resources) {
			supplier = resources.computeIfAbsent(location, key -> new Supplier<>() {
				private ByteBuffer data;

				@Override
				public synchronized ByteBuffer get() {
					if (data == null) {
						try (BufferedInputStream input = new BufferedInputStream(
							Minecraft.getInstance().getResourceManager().getResource(key).orElseThrow().open())) {
							data = Buffers.create(IOUtils.toByteArray(input));
						} catch (Exception exception) {
							throw new IllegalStateException("Could not load resource " + key, exception);
						}
					}
					return data.duplicate();
				}
			});
		}
		return supplier.get();
	}

	public synchronized void addInternalModelReceiver(InternalModelReceiver receiver) {
		gltfModelReceivers.add(receiver);
	}

	@Override
	public GltfModelHandle registerModel(Identifier location) {
		GltfModelHandleImpl handle = new GltfModelHandleImpl(this, Objects.requireNonNull(location, "location"));
		HandleReceiver receiver = new HandleReceiver(location, handle);
		handle.attach(receiver);
		addInternalModelReceiver(receiver);
		return handle;
	}

	public synchronized void registerModelReceiver(GltfModelReceiver receiver) {
		addInternalModelReceiver(new ApiModelReceiverAdapter(receiver));
	}

	public synchronized boolean removeInternalModelReceiver(InternalModelReceiver receiver) {
		return gltfModelReceivers.remove(receiver);
	}

	@Override
	public boolean isShaderModActive() {
		return shaderModActive.getAsBoolean();
	}

	private static Logger logger() {
		return Constants.LOG;
	}

	private record HandleReceiver(Identifier location, GltfModelHandleImpl handle) implements InternalModelReceiver {
		@Override
		public Identifier getModelLocation() {
			return location;
		}

		@Override
		public void onReceiveSharedModel(RenderedGltfModel renderedModel) {
			handle.update(renderedModel);
		}
	}

	private record ApiModelReceiverAdapter(GltfModelReceiver receiver) implements InternalModelReceiver {
		@Override
		public Identifier getModelLocation() {
			return receiver.modelLocation();
		}

		@Override
		public boolean isReceiveSharedModel(GltfModel gltfModel, List<Runnable> gltfRenderDatas) {
			return receiver.acceptsSharedModel();
		}

		@Override
		public void onReceiveSharedModel(RenderedGltfModel renderedModel) {
			receiver.onModelReady(renderedModel);
		}
	}
}
