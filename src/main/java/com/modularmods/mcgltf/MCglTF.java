package com.modularmods.mcgltf;

import java.io.BufferedInputStream;
import java.io.IOException;
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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import de.javagl.jgltf.model.GltfModel;
import de.javagl.jgltf.model.io.Buffers;
import de.javagl.jgltf.model.io.GltfModelReader;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.reloader.SimpleReloadListener;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.PreparableReloadListener.SharedState;
import net.minecraft.server.packs.resources.ResourceManager;

public final class MCglTF implements ClientModInitializer {

	public static final String MODID = "mcgltf";
	public static final String RESOURCE_LOCATION = "resourceLocation";
	public static final Logger logger = LogManager.getLogger(MODID);

	private static final MCglTF INSTANCE = new MCglTF();

	private final Map<Identifier, Supplier<ByteBuffer>> loadedBufferResources = new HashMap<>();
	private final Map<Identifier, Supplier<ByteBuffer>> loadedImageResources = new HashMap<>();
	private final List<IGltfModelReceiver> gltfModelReceivers = new ArrayList<>();
	private final List<Runnable> gltfRenderData = new ArrayList<>();
	private BooleanSupplier shaderModActive = () -> false;
	private boolean initialized;

	@Override
	public void onInitializeClient() {
		INSTANCE.initializeClient();
	}

	private synchronized void initializeClient() {
		if (initialized) {
			return;
		}
		initialized = true;
		MToonRenderTypes.bootstrap();
		shaderModActive = createShaderModProbe();
		ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(
			Identifier.fromNamespaceAndPath(MODID, "gltf_reload_listener"),
			new SimpleReloadListener<Map<Identifier, MutablePair<GltfModel, List<IGltfModelReceiver>>>>() {
				@Override
				protected Map<Identifier, MutablePair<GltfModel, List<IGltfModelReceiver>>> prepare(SharedState state) {
					return prepareModels(state.resourceManager());
				}

				@Override
				protected void apply(Map<Identifier, MutablePair<GltfModel, List<IGltfModelReceiver>>> models,
					SharedState state) {
					applyModels(models);
				}
			});
	}

	private Map<Identifier, MutablePair<GltfModel, List<IGltfModelReceiver>>> prepareModels(ResourceManager resourceManager) {
		Map<Identifier, MutablePair<GltfModel, List<IGltfModelReceiver>>> lookup = new HashMap<>();
		synchronized (this) {
			for (IGltfModelReceiver receiver : gltfModelReceivers) {
				lookup.computeIfAbsent(receiver.getModelLocation(), ignored -> MutablePair.of(null, new ArrayList<>()))
					.getRight().add(receiver);
			}
		}

		lookup.entrySet().parallelStream().forEach(entry -> {
			try (BufferedInputStream input = new BufferedInputStream(resourceManager.getResource(entry.getKey()).orElseThrow().open())) {
				entry.getValue().setLeft(new GltfModelReader().readWithoutReferences(input));
			} catch (Exception exception) {
				logger.error("Failed to load glTF model {}", entry.getKey(), exception);
			}
		});
		return lookup;
	}

	private void applyModels(Map<Identifier, MutablePair<GltfModel, List<IGltfModelReceiver>>> lookup) {
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

	private void processReceivers(Identifier location, GltfModel gltfModel, List<IGltfModelReceiver> receivers) {
		Iterator<IGltfModelReceiver> iterator = receivers.iterator();
		while (iterator.hasNext()) {
			IGltfModelReceiver receiver = iterator.next();
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
				logger.error("Failed to prepare glTF model {}", location, exception);
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
						} catch (IOException exception) {
							throw new IllegalStateException("Could not load resource " + key, exception);
						}
					}
					return data.duplicate();
				}
			});
		}
		return supplier.get();
	}

	public synchronized void addGltfModelReceiver(IGltfModelReceiver receiver) {
		gltfModelReceivers.add(receiver);
	}

	public GltfModelHandle registerModel(Identifier location) {
		GltfModelHandle handle = new GltfModelHandle(this, Objects.requireNonNull(location, "location"));
		HandleReceiver receiver = new HandleReceiver(location, handle);
		handle.attach(receiver);
		addGltfModelReceiver(receiver);
		return handle;
	}

	public synchronized boolean removeGltfModelReceiver(IGltfModelReceiver receiver) {
		return gltfModelReceivers.remove(receiver);
	}

	public boolean isShaderModActive() {
		return shaderModActive.getAsBoolean();
	}

	private static BooleanSupplier createShaderModProbe() {
		if (!FabricLoader.getInstance().isModLoaded("iris")) {
			return () -> false;
		}
		return () -> {
			try {
				Class<?> irisApi = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
				Object api = irisApi.getMethod("getInstance").invoke(null);
				return (boolean) irisApi.getMethod("isShaderPackInUse").invoke(api);
			} catch (ReflectiveOperationException exception) {
				logger.debug("Could not query Iris shader state", exception);
				return false;
			}
		};
	}

	public static MCglTF getInstance() {
		return INSTANCE;
	}

	private record HandleReceiver(Identifier location, GltfModelHandle handle) implements IGltfModelReceiver {
		@Override
		public Identifier getModelLocation() {
			return location;
		}

		@Override
		public void onReceiveSharedModel(RenderedGltfModel renderedModel) {
			handle.update(renderedModel);
		}
	}
}
