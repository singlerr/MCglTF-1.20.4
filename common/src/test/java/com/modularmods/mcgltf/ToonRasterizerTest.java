package com.modularmods.mcgltf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.lwjgl.opengl.GL11.GL_POINTS;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import de.javagl.jgltf.model.AccessorFloatData;
import de.javagl.jgltf.model.AccessorIntData;
import de.javagl.jgltf.model.AccessorModel;
import de.javagl.jgltf.model.ElementType;
import de.javagl.jgltf.model.impl.DefaultGltfModel;
import de.javagl.jgltf.model.impl.DefaultMeshModel;
import de.javagl.jgltf.model.impl.DefaultMeshPrimitiveModel;
import de.javagl.jgltf.model.impl.DefaultNodeModel;
import de.javagl.jgltf.model.impl.DefaultSkinModel;
import de.javagl.jgltf.model.v2.MaterialModelV2;

/**
 * The rasteriser answers geometric questions in the layout of the texture that will
 * carry the answers, so these tests pin the texel-to-surface mapping, the rule that
 * decides which triangle owns a shared texel, and what happens when the data is absent.
 *
 * <p>The offline tool in the Celerant repository derives the same sheets, so anything
 * here that looks arbitrary is matching it deliberately: the two must agree texel for
 * texel or a model profiled offline would shade differently from one profiled in game.
 */
class ToonRasterizerTest {
	private static final int SIZE = 4;

	@Test
	void eachTexelReportsTheSurfacePointItsUvAddresses() {
		// The quad's positions are its texture coordinates, so a texel's recorded
		// position is exactly the UV the shader will sample it with.
		DefaultGltfModel model = model("VRMC_vrm", quad(0.0F, 1.0F));
		ToonRasterizer.Sample sample = rasterize(model, false);
		for (int y = 0; y < SIZE; y++) {
			for (int x = 0; x < SIZE; x++) {
				int texel = y * SIZE + x;
				if (!sample.covered(texel)) {
					continue;
				}
				assertEquals((x + 0.5F) / (SIZE - 1), sample.position(texel, 0), 1.0E-5F);
				assertEquals((y + 0.5F) / (SIZE - 1), sample.position(texel, 1), 1.0E-5F);
			}
		}
	}

	@Test
	void aFullyUnwrappedQuadCoversEveryTexelItsCornersReach() {
		// Corners land on texel centres, so the row and column past the last centre fall
		// outside the triangles. The offline tool addresses texels the same way.
		ToonRasterizer.Sample sample = rasterize(model("VRMC_vrm", quad(0.0F, 1.0F)), false);
		for (int y = 0; y < SIZE; y++) {
			for (int x = 0; x < SIZE; x++) {
				assertEquals(x < SIZE - 1 && y < SIZE - 1, sample.covered(y * SIZE + x),
					"texel " + x + "," + y);
			}
		}
	}

	@Test
	void theTriangleFacingTheCharacterOwnsATexelSharedWithItsBack() {
		// Both quads occupy the whole sheet, the back listed first. Whichever faces the
		// way the character does must win, or a VRM 1.0 model would be profiled through
		// the back of its head.
		ToonRasterizer.Sample vrm1 = rasterize(
			model("VRMC_vrm", quad(-5.0F, -1.0F), quad(5.0F, 1.0F)), false);
		ToonRasterizer.Sample vrm0 = rasterize(
			model("VRM", quad(-5.0F, -1.0F), quad(5.0F, 1.0F)), false);
		assertEquals(5.0F, vrm1.position(0, 2), 1.0E-5F);
		assertEquals(-5.0F, vrm0.position(0, 2), 1.0E-5F);
	}

	@Test
	void headWeightsFollowTheSkinIntoTextureSpace() {
		assertEquals(1.0F, rasterize(skinned(true), false).headWeights()[0], 1.0E-5F);
		assertEquals(0.0F, rasterize(skinned(false), false).headWeights()[0], 1.0E-5F);
	}

	@Test
	void aMaterialWithNoTextureSpaceIsReportedRatherThanReturnedBlank() {
		DefaultGltfModel model = model("VRMC_vrm", quad(GL_TRIANGLES, 0.0F, 1.0F, false));
		assertThrows(IllegalArgumentException.class, () -> rasterize(model, false));
		ToonRasterizer.Sample allowed = rasterize(model, true);
		for (int texel = 0; texel < SIZE * SIZE; texel++) {
			assertFalse(allowed.covered(texel));
		}
	}

	@Test
	void aPrimitiveThatIsNotTrianglesIsRejectedInsteadOfSkipped() {
		DefaultGltfModel model = model("VRMC_vrm", quad(GL_POINTS, 0.0F, 1.0F, true));
		assertThrows(IllegalArgumentException.class, () -> rasterize(model, false));
	}

	private static ToonRasterizer.Sample rasterize(DefaultGltfModel model, boolean allowEmpty) {
		return ToonRasterizer.rasterize(model, ToonVrmData.of(model), 0, SIZE, SIZE, allowEmpty);
	}

	/** A quad filling the whole UV square, at a fixed depth and facing along Z. */
	private static DefaultMeshPrimitiveModel quad(float depth, float facing) {
		return quad(GL_TRIANGLES, depth, facing, true);
	}

	private static DefaultMeshPrimitiveModel quad(int mode, float depth, float facing,
		boolean unwrapped) {
		DefaultMeshPrimitiveModel primitive = new DefaultMeshPrimitiveModel(mode);
		if (unwrapped) {
			primitive.putAttribute("TEXCOORD_0", floats(2, 0, 0, 1, 0, 1, 1, 0, 1));
		}
		primitive.putAttribute("POSITION",
			floats(3, 0, 0, depth, 1, 0, depth, 1, 1, depth, 0, 1, depth));
		primitive.putAttribute("NORMAL",
			floats(3, 0, 0, facing, 0, 0, facing, 0, 0, facing, 0, 0, facing));
		primitive.setIndices(indices(0, 1, 2, 0, 2, 3));
		return primitive;
	}

	/**
	 * A quad whose vertices are bound entirely to one joint, which is either the head
	 * itself or a bone the head does not carry.
	 */
	private static DefaultGltfModel skinned(boolean onHead) {
		DefaultGltfModel model = new DefaultGltfModel();
		DefaultNodeModel head = new DefaultNodeModel();
		DefaultNodeModel hair = new DefaultNodeModel();
		DefaultNodeModel hand = new DefaultNodeModel();
		head.addChild(hair);
		model.addNodeModel(head);
		model.addNodeModel(hair);
		model.addNodeModel(hand);
		model.setExtensions(Map.of("VRM", Map.of("humanoid",
			Map.of("humanBones", List.of(Map.of("bone", "head", "node", 0))))));

		DefaultSkinModel skin = new DefaultSkinModel();
		skin.addJoint(onHead ? hair : hand);

		DefaultMeshPrimitiveModel primitive = quad(0.0F, -1.0F);
		primitive.putAttribute("JOINTS_0", floats(4, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
		primitive.putAttribute("WEIGHTS_0", floats(4, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0));
		DefaultNodeModel body = attach(model, primitive);
		body.setSkinModel(skin);
		return model;
	}

	private static DefaultGltfModel model(String extension, DefaultMeshPrimitiveModel... primitives) {
		DefaultGltfModel model = new DefaultGltfModel();
		model.setExtensions(Map.of(extension, Map.of()));
		attach(model, primitives);
		return model;
	}

	private static DefaultNodeModel attach(DefaultGltfModel model,
		DefaultMeshPrimitiveModel... primitives) {
		MaterialModelV2 material = new MaterialModelV2();
		model.addMaterialModel(material);
		DefaultMeshModel mesh = new DefaultMeshModel();
		for (DefaultMeshPrimitiveModel primitive : primitives) {
			primitive.setMaterialModel(material);
			mesh.addMeshPrimitiveModel(primitive);
		}
		model.addMeshModel(mesh);
		DefaultNodeModel node = new DefaultNodeModel();
		node.addMeshModel(mesh);
		model.addNodeModel(node);
		return node;
	}

	private static AccessorModel floats(int components, float... values) {
		ElementType type = switch (components) {
			case 2 -> ElementType.VEC2;
			case 3 -> ElementType.VEC3;
			default -> ElementType.VEC4;
		};
		AccessorModel accessor = AccessorModelCreation.createAccessorModel(5126,
			values.length / components, type, "test.bin");
		AccessorFloatData data = (AccessorFloatData)accessor.getAccessorData();
		for (int element = 0; element < values.length / components; element++) {
			for (int component = 0; component < components; component++) {
				data.set(element, component, values[element * components + component]);
			}
		}
		return accessor;
	}

	private static AccessorModel indices(int... values) {
		AccessorModel accessor = AccessorModelCreation.createAccessorModel(5125, values.length,
			ElementType.SCALAR, "test.bin");
		AccessorIntData data = (AccessorIntData)accessor.getAccessorData();
		for (int element = 0; element < values.length; element++) {
			data.set(element, 0, values[element]);
		}
		return accessor;
	}
}
