package com.modularmods.mcgltf.gametest;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.modularmods.mcgltf.Constants;
import com.modularmods.mcgltf.MCglTFImpl;
import com.modularmods.mcgltf.RenderedGltfModel;
import com.modularmods.mcgltf.api.GltfModelHandle;
import com.modularmods.mcgltf.internal.InternalModelReceiver;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import de.javagl.jgltf.model.GltfModel;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;

public final class MCglTFGameTestClient implements ClientModInitializer {

	public enum RenderMode {
		OFF,
		WORLD_CENTERED,
		WORLD_ENTITY,
		WORLD_BENCHMARK,
		HAND
	}

	public record BenchmarkStats(int instances, long frames, long instanceSubmissions, long submittedVertices,
		long submitNanos, long maxSubmitNanos, long elapsedNanos) {
	}

	private static final Map<String, RenderedGltfModel> MODELS = new ConcurrentHashMap<>();
	private static final Map<String, Long> PREPARE_NANOS = new ConcurrentHashMap<>();
	private static GltfModelHandle simpleHandle;
	private static volatile RenderMode renderMode = RenderMode.OFF;
	private static volatile String selectedModel = "wakgood";
	private static volatile long handSubmissions;
	private static volatile long worldSubmissions;
	private static volatile int benchmarkInstances;
	private static volatile int benchmarkTargetFrames;
	private static volatile boolean benchmarkMeasuring;
	private static long benchmarkFrames;
	private static long benchmarkInstanceSubmissions;
	private static long benchmarkSubmittedVertices;
	private static long benchmarkSubmitNanos;
	private static long benchmarkMaxSubmitNanos;
	private static long benchmarkStartedNanos;
	private static long benchmarkElapsedNanos;

	@Override
	public void onInitializeClient() {
		registerModel("wakgood", Identifier.fromNamespaceAndPath("mcgltf-gametest", "models/transformed_wakgood.vrm"));
		registerModel("jingburger", Identifier.fromNamespaceAndPath("mcgltf-gametest", "models/transformed_jingburger.vrm"));
		simpleHandle = MCglTFImpl.getInstance().registerModel(
			Identifier.fromNamespaceAndPath("mcgltf-gametest", "models/transformed_wakgood.vrm"));

		LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
			if (renderMode != RenderMode.WORLD_CENTERED && renderMode != RenderMode.WORLD_ENTITY
				&& renderMode != RenderMode.WORLD_BENCHMARK) {
				return;
			}
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft.player == null || context.levelState().cameraRenderState.pos == null) {
				return;
			}
			if (renderMode == RenderMode.WORLD_BENCHMARK) {
				submitBenchmark(context, minecraft);
				return;
			}

			RenderedGltfModel model = MODELS.get(selectedModel);
			if (model == null) {
				return;
			}

			Vec3 target;
			if (renderMode == RenderMode.WORLD_ENTITY) {
				target = new Vec3(0.0D, 100.0D, 4.0D);
			} else {
				target = minecraft.player.getEyePosition()
					.add(minecraft.player.getViewVector(1.0F).scale(4.0D))
					.add(1.8D, -1.6D, 0.0D);
			}
			submitWorldModel(context, minecraft, model, target, 1.0F);
			worldSubmissions++;
		});
	}

	private static void submitBenchmark(LevelRenderContext context, Minecraft minecraft) {
		boolean measuring = benchmarkMeasuring;
		long started = measuring ? System.nanoTime() : 0L;
		if (measuring && benchmarkFrames == 0L) {
			benchmarkStartedNanos = started;
		}
		double span = Math.min(8.4D, benchmarkInstances * 1.5D);
		for (int index = 0; index < benchmarkInstances; index++) {
			RenderedGltfModel model = MODELS.get((index & 1) == 0 ? "wakgood" : "jingburger");
			if (model == null) {
				continue;
			}
			double x = benchmarkInstances == 1 ? 0.0D : span * index / (benchmarkInstances - 1) - span / 2.0D;
			Vec3 target = new Vec3(x, 100.0D, 8.5D);
			submitWorldModel(context, minecraft, model, target, 0.55F);
			worldSubmissions++;
			if (measuring) {
				benchmarkInstanceSubmissions++;
				benchmarkSubmittedVertices += model.renderedGltfScenes.getFirst().getSubmittedVertexCount();
			}
		}
		if (measuring) {
			long finished = System.nanoTime();
			long submitNanos = finished - started;
			benchmarkSubmitNanos += submitNanos;
			benchmarkMaxSubmitNanos = Math.max(benchmarkMaxSubmitNanos, submitNanos);
			benchmarkFrames++;
			if (benchmarkFrames >= benchmarkTargetFrames) {
				benchmarkElapsedNanos = finished - benchmarkStartedNanos;
				benchmarkMeasuring = false;
			}
		}
	}

	private static void submitWorldModel(LevelRenderContext context, Minecraft minecraft,
		RenderedGltfModel model, Vec3 target, float scale) {
		Vec3 camera = context.levelState().cameraRenderState.pos;
		PoseStack poseStack = context.poseStack();
		poseStack.pushPose();
		poseStack.translate(target.x - camera.x, target.y - camera.y, target.z - camera.z);
		poseStack.scale(scale, scale, scale);
		poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
		BlockPos lightPosition = BlockPos.containing(target);
		int packedLight = LightCoordsUtil.pack(
			minecraft.level.getBrightness(LightLayer.BLOCK, lightPosition),
			minecraft.level.getBrightness(LightLayer.SKY, lightPosition));
		model.submit(0, poseStack, context.submitNodeCollector(), packedLight, OverlayTexture.NO_OVERLAY);
		poseStack.popPose();
	}

	private static void registerModel(String name, Identifier location) {
		MCglTFImpl.getInstance().addInternalModelReceiver(new InternalModelReceiver() {
			private long started;

			@Override
			public Identifier getModelLocation() {
				return location;
			}

			@Override
			public boolean isReceiveSharedModel(GltfModel gltfModel, java.util.List<Runnable> cleanup) {
				started = System.nanoTime();
				return true;
			}

			@Override
			public void onReceiveSharedModel(RenderedGltfModel renderedModel) {
				MODELS.put(name, renderedModel);
				PREPARE_NANOS.put(name, System.nanoTime() - started);
			}
		});
	}

	public static boolean ready() {
		return MODELS.size() == 2 && simpleHandle != null && simpleHandle.isReady();
	}

	public static RenderedGltfModel simpleModel() {
		return simpleHandle == null || !simpleHandle.isReady() ? null
			: (RenderedGltfModel) simpleHandle.renderable();
	}

	public static RenderedGltfModel model(String name) {
		return MODELS.get(name);
	}

	public static long prepareNanos(String name) {
		return PREPARE_NANOS.getOrDefault(name, Long.MAX_VALUE);
	}

	public static void show(String model, RenderMode mode) {
		selectedModel = model;
		if (mode == RenderMode.HAND) {
			handSubmissions = 0L;
		} else {
			worldSubmissions = 0L;
		}
		renderMode = mode;
	}

	public static void hide() {
		renderMode = RenderMode.OFF;
		benchmarkMeasuring = false;
	}

	public static long submissionCount(RenderMode mode) {
		return mode == RenderMode.HAND ? handSubmissions : worldSubmissions;
	}

	public static void showBenchmark(int instances) {
		benchmarkInstances = instances;
		worldSubmissions = 0L;
		benchmarkMeasuring = false;
		renderMode = RenderMode.WORLD_BENCHMARK;
	}

	public static void startBenchmark(int instances, int targetFrames) {
		benchmarkInstances = instances;
		benchmarkTargetFrames = targetFrames;
		benchmarkFrames = 0L;
		benchmarkInstanceSubmissions = 0L;
		benchmarkSubmittedVertices = 0L;
		benchmarkSubmitNanos = 0L;
		benchmarkMaxSubmitNanos = 0L;
		benchmarkStartedNanos = 0L;
		benchmarkElapsedNanos = 0L;
		worldSubmissions = 0L;
		benchmarkMeasuring = true;
		renderMode = RenderMode.WORLD_BENCHMARK;
	}

	public static boolean benchmarkComplete() {
		return !benchmarkMeasuring && benchmarkFrames >= benchmarkTargetFrames;
	}

	public static BenchmarkStats benchmarkStats() {
		return new BenchmarkStats(benchmarkInstances, benchmarkFrames, benchmarkInstanceSubmissions,
			benchmarkSubmittedVertices, benchmarkSubmitNanos, benchmarkMaxSubmitNanos, benchmarkElapsedNanos);
	}

	public static void submitHand(PoseStack poseStack, SubmitNodeCollector collector, int packedLight) {
		if (renderMode != RenderMode.HAND) {
			return;
		}
		RenderedGltfModel model = MODELS.get(selectedModel);
		if (model == null) {
			return;
		}
		poseStack.pushPose();
		poseStack.translate(0.0F, -1.35F, -2.2F);
		poseStack.scale(0.55F, 0.55F, 0.55F);
		poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
		model.submit(0, poseStack, collector, packedLight, OverlayTexture.NO_OVERLAY);
		handSubmissions++;
		poseStack.popPose();
	}
}
