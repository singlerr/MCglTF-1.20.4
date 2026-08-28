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
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

/** NeoForge client harness mirroring the Fabric GameTest submit path. */
public final class MCglTFNeoForgeGameTestClient {

	public enum RenderMode {
		OFF,
		WORLD_CENTERED,
		WORLD_ENTITY,
		WORLD_BENCHMARK
	}

	public record BenchmarkStats(int instances, long frames, long instanceSubmissions, long submittedVertices,
		long submitNanos, long maxSubmitNanos, long elapsedNanos) {
	}

	private static final Map<String, RenderedGltfModel> MODELS = new ConcurrentHashMap<>();
	private static final Map<String, Long> PREPARE_NANOS = new ConcurrentHashMap<>();
	private static GltfModelHandle simpleHandle;
	private static volatile RenderMode renderMode = RenderMode.OFF;
	private static volatile String selectedModel = "wakgood";
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

	private MCglTFNeoForgeGameTestClient() {
	}

	public static void initializeClient() {
		registerModel("wakgood", Identifier.fromNamespaceAndPath("mcgltf-gametest", "models/transformed_wakgood.vrm"));
		registerModel("jingburger", Identifier.fromNamespaceAndPath("mcgltf-gametest", "models/transformed_jingburger.vrm"));
		simpleHandle = MCglTFImpl.getInstance().registerModel(
			Identifier.fromNamespaceAndPath("mcgltf-gametest", "models/transformed_wakgood.vrm"));
	}

	public static void onSubmitCustomGeometry(SubmitCustomGeometryEvent event) {
		if (renderMode == RenderMode.OFF) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null || event.getLevelRenderState().cameraRenderState.pos == null) {
			return;
		}
		if (renderMode == RenderMode.WORLD_BENCHMARK) {
			submitBenchmark(event, minecraft);
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
		submitWorldModel(event, minecraft, model, target, 1.0F);
		worldSubmissions++;
	}

	private static void submitBenchmark(SubmitCustomGeometryEvent event, Minecraft minecraft) {
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
			submitWorldModel(event, minecraft, model, target, 0.55F);
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

	private static void submitWorldModel(SubmitCustomGeometryEvent event, Minecraft minecraft,
		RenderedGltfModel model, Vec3 target, float scale) {
		Vec3 camera = event.getLevelRenderState().cameraRenderState.pos;
		PoseStack poseStack = event.getPoseStack();
		SubmitNodeCollector collector = event.getSubmitNodeCollector();
		poseStack.pushPose();
		poseStack.translate(target.x - camera.x, target.y - camera.y, target.z - camera.z);
		poseStack.scale(scale, scale, scale);
		poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
		BlockPos lightPosition = BlockPos.containing(target);
		int packedLight = LightCoordsUtil.pack(
			minecraft.level.getBrightness(LightLayer.BLOCK, lightPosition),
			minecraft.level.getBrightness(LightLayer.SKY, lightPosition));
		model.submit(0, poseStack, collector, packedLight, OverlayTexture.NO_OVERLAY);
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

	public static void show(String model, RenderMode mode) {
		selectedModel = model;
		worldSubmissions = 0L;
		renderMode = mode;
	}

	public static void hide() {
		renderMode = RenderMode.OFF;
		benchmarkMeasuring = false;
	}

	public static long submissionCount() {
		return worldSubmissions;
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

	public static void logSmokeResult() {
		BenchmarkStats stats = benchmarkStats();
		Constants.LOG.info(
			"NeoForge GameTest smoke: ready={}, submissions={}, benchmarkFrames={}, vertices={}, avgSubmitMs={}",
			ready(), submissionCount(), stats.frames(), stats.submittedVertices(),
			stats.frames() == 0L ? 0.0D : stats.submitNanos() / 1_000_000.0D / stats.frames());
	}
}
