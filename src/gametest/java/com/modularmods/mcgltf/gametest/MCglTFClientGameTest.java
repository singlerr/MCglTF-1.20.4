package com.modularmods.mcgltf.gametest;

import java.awt.image.BufferedImage;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import com.modularmods.mcgltf.MCglTF;
import com.modularmods.mcgltf.gametest.MCglTFGameTestClient.BenchmarkStats;
import com.modularmods.mcgltf.gametest.MCglTFGameTestClient.RenderMode;
import com.mojang.blaze3d.systems.RenderSystem;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.CameraType;
import net.minecraft.client.CloudStatus;
import net.minecraft.core.BlockPos;

public final class MCglTFClientGameTest implements FabricClientGameTest {
	private static final int BENCHMARK_FRAMES = 16;
	private static final int[] BENCHMARK_INSTANCE_COUNTS = { 1, 2, 4, 8 };

	@Override
	public void runTest(ClientGameTestContext context) {
		context.getInput().resizeWindow(1280, 720);
		context.waitFor(client -> MCglTFGameTestClient.ready(), 2400);
		assertModelPreparation();

		if (Boolean.getBoolean("mcgltf.gametest.requireIris")) {
			if (!FabricLoader.getInstance().isModLoaded("iris")) {
				throw new AssertionError("Iris runtime was required but is not loaded");
			}
			context.waitFor(client -> MCglTF.getInstance().isShaderModActive(), 1200);
			String backend = context.computeOnClient(client -> RenderSystem.getDevice().getDeviceInfo().backendName());
			if (!"OpenGL".equals(backend)) {
				throw new AssertionError("Iris visual regression must run on OpenGL, got " + backend);
			}
		}

		try (TestSingleplayerContext world = context.worldBuilder().setUseConsistentSettings(true).create()) {
			world.getServer().runCommand("time set noon");
			world.getServer().runCommand("weather clear");
			world.getServer().runCommand("fill -12 99 -8 12 99 12 minecraft:white_concrete");
			world.getServer().runCommand("summon minecraft:armor_stand 0 100 4 {NoGravity:1b,Invulnerable:1b}");
			world.getServer().runCommand("gamemode creative @a");
			world.getServer().runCommand("tp @a 0 100 0 0 0");
			world.getConnection().waitForChunksRender(true, 1200);
			context.runOnClient(client -> {
				client.options.cloudStatus().set(CloudStatus.OFF);
				client.options.bobView().set(false);
				client.options.setCameraType(CameraType.FIRST_PERSON);
			});
			context.getInput().lookAt(new BlockPos(0, 101, 4));
			context.waitTicks(10);

			Path firstPersonBaseline = screenshot(context, "vrm-first-person-baseline", null, RenderMode.OFF);
			Path firstPerson = screenshot(context, "vrm-first-person-wakgood", "wakgood", RenderMode.HAND);
			assertSubmitted(RenderMode.HAND);
			assertRegionChanged(firstPersonBaseline, firstPerson, Region.CENTER, 1.0D,
				"First-person hand VRM was not visible");
			context.getInput().pressKey(options -> options.keyToggleGui);

			context.runOnClient(client -> client.options.setCameraType(CameraType.THIRD_PERSON_BACK));
			Path thirdBackBaseline = screenshot(context, "vrm-third-back-baseline", null, RenderMode.OFF);
			Path thirdBack = screenshot(context, "vrm-third-back-wakgood", "wakgood", RenderMode.WORLD_CENTERED);
			assertSubmitted(RenderMode.WORLD_CENTERED);
			assertRegionChanged(thirdBackBaseline, thirdBack, Region.CENTER, 1.0D,
				"Third-person back VRM was not visible");

			context.runOnClient(client -> client.options.setCameraType(CameraType.FIRST_PERSON));
			context.getInput().lookAt(new BlockPos(0, 101, 4));
			Path entityBaseline = screenshot(context, "entity-gaze-before", null, RenderMode.OFF);
			Path entityDuring = screenshot(context, "entity-gaze-vrm", "jingburger", RenderMode.WORLD_ENTITY);
			assertSubmitted(RenderMode.WORLD_ENTITY);
			assertRegionChanged(entityBaseline, entityDuring, Region.CENTER, 1.0D,
				"Entity-positioned VRM was not visible");
			assertRegionStable(entityBaseline, entityDuring, Region.BORDER, 12.0D,
				"Looking at the rendered entity corrupted surrounding textures");
			Path entityAfter = screenshot(context, "entity-gaze-after", null, RenderMode.OFF);
			assertRegionStable(entityBaseline, entityAfter, Region.SCENE, 8.0D,
				"Post-render scene did not return to its baseline");

			world.getServer().runCommand("kill @e[type=minecraft:armor_stand]");
			runMultiModelBenchmark(context);
			context.getInput().pressKey(options -> options.keyToggleGui);
		} finally {
			MCglTFGameTestClient.hide();
		}
	}

	private static void runMultiModelBenchmark(ClientGameTestContext context) {
		context.getInput().lookAt(new BlockPos(0, 101, 9));
		Path baseline = screenshot(context, "vrm-benchmark-baseline", null, RenderMode.OFF);

		MCglTFGameTestClient.showBenchmark(8);
		context.waitFor(client -> MCglTFGameTestClient.submissionCount(RenderMode.WORLD_BENCHMARK) >= 8L, 1200);
		MCglTFGameTestClient.hide();

		for (int instances : BENCHMARK_INSTANCE_COUNTS) {
			MCglTFGameTestClient.startBenchmark(instances, BENCHMARK_FRAMES);
			context.waitFor(client -> MCglTFGameTestClient.benchmarkComplete(), 2400);
			BenchmarkStats stats = MCglTFGameTestClient.benchmarkStats();
			assertBenchmark(stats);
			double submitMicrosPerFrame = stats.submitNanos() / 1_000.0D / stats.frames();
			double maxSubmitMicrosPerFrame = stats.maxSubmitNanos() / 1_000.0D;
			double submitMicrosPerInstance = stats.submitNanos() / 1_000.0D / stats.instanceSubmissions();
			double frameMillis = stats.elapsedNanos() / 1_000_000.0D / stats.frames();
			double framesPerSecond = stats.frames() * 1_000_000_000.0D / stats.elapsedNanos();
			MCglTF.logger.info(
				"GameTest multi-VRM benchmark: instances={} frames={} submits={} vertices/frame={} submit avg/max={}/{} us/frame, {} us/instance, frame={} ms, render={} fps, iris={}",
				stats.instances(), stats.frames(), stats.instanceSubmissions(), stats.submittedVertices() / stats.frames(),
				String.format(java.util.Locale.ROOT, "%.3f", submitMicrosPerFrame),
				String.format(java.util.Locale.ROOT, "%.3f", maxSubmitMicrosPerFrame),
				String.format(java.util.Locale.ROOT, "%.3f", submitMicrosPerInstance),
				String.format(java.util.Locale.ROOT, "%.3f", frameMillis),
				String.format(java.util.Locale.ROOT, "%.2f", framesPerSecond),
				MCglTF.getInstance().isShaderModActive());

			Path screenshot = context.takeScreenshot("vrm-benchmark-" + instances + "-instances");
			assertRegionChanged(baseline, screenshot, Region.ALL, 0.5D,
				instances + "-instance VRM benchmark was not visible");
			if (instances == 8) {
				assertBandsChanged(baseline, screenshot, 4, 100);
			}
		}
	}

	private static void assertBenchmark(BenchmarkStats stats) {
		long expectedSubmissions = stats.frames() * stats.instances();
		if (stats.frames() != BENCHMARK_FRAMES || stats.instanceSubmissions() != expectedSubmissions
			|| stats.submittedVertices() <= 0L || stats.submitNanos() <= 0L || stats.maxSubmitNanos() <= 0L
			|| stats.elapsedNanos() <= 0L) {
			throw new AssertionError("Invalid multi-VRM benchmark result: " + stats);
		}
	}

	private static void assertSubmitted(RenderMode mode) {
		if (MCglTFGameTestClient.submissionCount(mode) == 0L) {
			throw new AssertionError(mode + " model was never submitted to the renderer");
		}
	}

	private static void assertModelPreparation() {
		var wakgood = MCglTFGameTestClient.model("wakgood");
		var jingburger = MCglTFGameTestClient.model("jingburger");
		if (wakgood == null || jingburger == null) {
			throw new AssertionError("Both VRM fixtures must be prepared");
		}
		if (MCglTFGameTestClient.simpleModel() != wakgood) {
			throw new AssertionError("registerModel handle did not receive the shared rendered model");
		}
		if (wakgood.renderedGltfScenes.getFirst().getPrimitiveCount() != 5
			|| jingburger.renderedGltfScenes.getFirst().getPrimitiveCount() != 35) {
			throw new AssertionError("Prepared primitive counts do not match the fixtures");
		}
		if (MCglTFGameTestClient.prepareNanos("wakgood") > 30_000_000_000L
			|| MCglTFGameTestClient.prepareNanos("jingburger") > 30_000_000_000L) {
			throw new AssertionError("VRM preparation exceeded 30 seconds");
		}
		MCglTF.logger.info("GameTest VRM preparation: wakgood={} ms/{} vertices, jingburger={} ms/{} vertices",
			MCglTFGameTestClient.prepareNanos("wakgood") / 1_000_000L,
			wakgood.renderedGltfScenes.getFirst().getSubmittedVertexCount(),
			MCglTFGameTestClient.prepareNanos("jingburger") / 1_000_000L,
			jingburger.renderedGltfScenes.getFirst().getSubmittedVertexCount());
	}

	private static Path screenshot(ClientGameTestContext context, String name, String model, RenderMode mode) {
		if (model == null) {
			MCglTFGameTestClient.hide();
		} else {
			MCglTFGameTestClient.show(model, mode);
		}
		context.waitTicks(8);
		return context.takeScreenshot(name);
	}

	private static void assertRegionChanged(Path expected, Path actual, Region region, double minimumRms, String message) {
		double rms = rms(expected, actual, region);
		if (rms < minimumRms) {
			throw new AssertionError(message + "; RMS=" + rms);
		}
	}

	private static void assertRegionStable(Path expected, Path actual, Region region, double maximumRms, String message) {
		double rms = rms(expected, actual, region);
		if (rms > maximumRms) {
			throw new AssertionError(message + "; RMS=" + rms);
		}
	}

	private static double rms(Path expectedPath, Path actualPath, Region region) {
		try {
			BufferedImage expected = ImageIO.read(expectedPath.toFile());
			BufferedImage actual = ImageIO.read(actualPath.toFile());
			if (expected == null || actual == null || expected.getWidth() != actual.getWidth()
				|| expected.getHeight() != actual.getHeight()) {
				throw new AssertionError("Screenshot dimensions differ");
			}
			double squared = 0.0D;
			long channels = 0L;
			int width = expected.getWidth();
			int height = expected.getHeight();
			for (int y = 0; y < height; y++) {
				for (int x = 0; x < width; x++) {
					if (!region.includes(x, y, width, height)) {
						continue;
					}
					int a = expected.getRGB(x, y);
					int b = actual.getRGB(x, y);
					for (int shift = 0; shift <= 16; shift += 8) {
						int difference = ((a >>> shift) & 255) - ((b >>> shift) & 255);
						squared += difference * difference;
						channels++;
					}
				}
			}
			return Math.sqrt(squared / Math.max(1L, channels));
		} catch (Exception exception) {
			throw new AssertionError("Could not compare screenshots", exception);
		}
	}

	private static void assertBandsChanged(Path expectedPath, Path actualPath, int bands, int minimumChangedPixels) {
		try {
			BufferedImage expected = ImageIO.read(expectedPath.toFile());
			BufferedImage actual = ImageIO.read(actualPath.toFile());
			if (expected == null || actual == null || expected.getWidth() != actual.getWidth()
				|| expected.getHeight() != actual.getHeight()) {
				throw new AssertionError("Screenshot dimensions differ");
			}
			int[] changed = new int[bands];
			int width = expected.getWidth();
			int height = expected.getHeight();
			int left = width / 4;
			int right = width * 3 / 4;
			for (int y = height / 5; y < height * 4 / 5; y++) {
				for (int x = left; x < right; x++) {
					int before = expected.getRGB(x, y);
					int after = actual.getRGB(x, y);
					if (Math.abs(((before >>> 16) & 255) - ((after >>> 16) & 255)) > 12
						|| Math.abs(((before >>> 8) & 255) - ((after >>> 8) & 255)) > 12
						|| Math.abs((before & 255) - (after & 255)) > 12) {
						changed[Math.min(bands - 1, (x - left) * bands / (right - left))]++;
					}
				}
			}
			for (int band = 0; band < bands; band++) {
				if (changed[band] < minimumChangedPixels) {
					throw new AssertionError("Eight VRMs did not span the rendered screen; changed pixels by band="
						+ java.util.Arrays.toString(changed));
				}
			}
		} catch (AssertionError error) {
			throw error;
		} catch (Exception exception) {
			throw new AssertionError("Could not verify the multi-VRM screenshot", exception);
		}
	}

	private enum Region {
		ALL {
			@Override
			boolean includes(int x, int y, int width, int height) {
				return true;
			}
		},
		CENTER {
			@Override
			boolean includes(int x, int y, int width, int height) {
				return x >= width / 5 && x < width * 4 / 5 && y >= height / 5 && y < height * 4 / 5;
			}
		},
		SCENE {
			@Override
			boolean includes(int x, int y, int width, int height) {
				return y >= height / 4;
			}
		},
		BORDER {
			@Override
			boolean includes(int x, int y, int width, int height) {
				return !CENTER.includes(x, y, width, height);
			}
		};

		abstract boolean includes(int x, int y, int width, int height);
	}
}
