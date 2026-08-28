package com.modularmods.mcgltf.gametest;

import com.modularmods.mcgltf.gametest.MCglTFNeoForgeGameTestClient.RenderMode;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import net.neoforged.neoforge.common.NeoForge;

@Mod("mcgltf-gametest")
public final class MCglTFNeoForgeGameTestMod {

	private static final int SMOKE_BENCHMARK_FRAMES = 8;
	private static int smokeTicks;
	private static boolean smokeStarted;
	private static boolean smokeFinished;

	public MCglTFNeoForgeGameTestMod(IEventBus modEventBus) {
		if (!FMLEnvironment.getDist().isClient()) {
			return;
		}
		MCglTFNeoForgeGameTestClient.initializeClient();
		NeoForge.EVENT_BUS.addListener(MCglTFNeoForgeGameTestMod::onSubmitCustomGeometry);
		if (Boolean.getBoolean("mcgltf.gametest.smoke")) {
			NeoForge.EVENT_BUS.addListener(MCglTFNeoForgeGameTestMod::onClientTick);
		}
	}

	private static void onSubmitCustomGeometry(SubmitCustomGeometryEvent event) {
		MCglTFNeoForgeGameTestClient.onSubmitCustomGeometry(event);
	}

	private static void onClientTick(ClientTickEvent.Post event) {
		if (smokeFinished || !MCglTFNeoForgeGameTestClient.ready()) {
			return;
		}
		smokeTicks++;
		if (!smokeStarted && smokeTicks > 40) {
			MCglTFNeoForgeGameTestClient.show("wakgood", RenderMode.WORLD_CENTERED);
			MCglTFNeoForgeGameTestClient.startBenchmark(4, SMOKE_BENCHMARK_FRAMES);
			smokeStarted = true;
		}
		if (smokeStarted && MCglTFNeoForgeGameTestClient.benchmarkComplete()) {
			if (MCglTFNeoForgeGameTestClient.submissionCount() == 0L) {
				throw new IllegalStateException("NeoForge GameTest smoke did not submit any geometry");
			}
			MCglTFNeoForgeGameTestClient.logSmokeResult();
			MCglTFNeoForgeGameTestClient.hide();
			smokeFinished = true;
		}
	}
}
