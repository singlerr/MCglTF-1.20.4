package com.modularmods.mcgltf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import de.javagl.jgltf.model.impl.DefaultGltfModel;
import de.javagl.jgltf.model.impl.DefaultMeshModel;
import de.javagl.jgltf.model.impl.DefaultNodeModel;

/**
 * The VRM signals these tests cover are what let toon data be derived for a model the
 * code has never seen, so each one pins a rule rather than a particular model.
 */
class ToonVrmDataTest {
	@Test
	void vrm0FacesNegativeZWithPositiveXToTheRight() {
		DefaultGltfModel model = new DefaultGltfModel();
		model.setExtensions(Map.of("VRM", Map.of()));
		ToonVrmData data = ToonVrmData.of(model);
		assertEquals(-1.0F, data.headForward().z);
		assertEquals(1.0F, data.headRight().x);
	}

	@Test
	void vrm1FacesPositiveZWithNegativeXToTheRight() {
		DefaultGltfModel model = new DefaultGltfModel();
		model.setExtensions(Map.of("VRMC_vrm", Map.of()));
		ToonVrmData data = ToonVrmData.of(model);
		assertEquals(1.0F, data.headForward().z);
		assertEquals(-1.0F, data.headRight().x);
	}

	@Test
	void headKeepsTheDeclaredJointWhenItCarriesChildren() {
		DefaultGltfModel model = new DefaultGltfModel();
		DefaultNodeModel head = node(model, "Head");
		DefaultNodeModel eye = node(model, "Eye");
		head.addChild(eye);
		model.setExtensions(vrm0Head(0));
		assertSame(head, ToonVrmData.of(model).head());
	}

	@Test
	void headIsRepairedWhenTheHumanoidMapPointsAtALeaf() {
		// Conversions that fill the humanoid map by joint index land on joints such as an
		// eye. A head carries the eye, hair and jaw joints, so the hub above is the head.
		DefaultGltfModel model = new DefaultGltfModel();
		DefaultNodeModel hub = node(model, "Head");
		DefaultNodeModel eyeRight = node(model, "Eye_R");
		DefaultNodeModel eyeLeft = node(model, "Eye_L");
		DefaultNodeModel chin = node(model, "chin");
		hub.addChild(eyeRight);
		hub.addChild(eyeLeft);
		hub.addChild(chin);
		model.setExtensions(vrm0Head(1));
		ToonVrmData data = ToonVrmData.of(model);
		assertSame(hub, data.head());
		assertTrue(data.headSubtree().containsAll(List.of(hub, eyeRight, eyeLeft, chin)));
	}

	@Test
	void aLeafHeadIsKeptWhenNothingAboveItLooksLikeAHub() {
		DefaultGltfModel model = new DefaultGltfModel();
		DefaultNodeModel neck = node(model, "Neck");
		DefaultNodeModel head = node(model, "Head");
		neck.addChild(head);
		model.setExtensions(vrm0Head(1));
		assertSame(head, ToonVrmData.of(model).head());
	}

	@Test
	void faceMeshComesFromTheExpressionsThatDriveIt() {
		DefaultGltfModel model = new DefaultGltfModel();
		DefaultMeshModel body = mesh(model, "Body");
		DefaultMeshModel face = mesh(model, "Face");
		model.setExtensions(Map.of("VRM", Map.of("blendShapeMaster", Map.of("blendShapeGroups",
			List.of(Map.of("presetName", "blink", "binds", List.of(Map.of("mesh", 1))),
				Map.of("presetName", "unknown", "binds", List.of(Map.of("mesh", 0))))))));
		assertEquals(java.util.Set.of(face), ToonVrmData.of(model).faceMeshes());
		assertTrue(!ToonVrmData.of(model).faceMeshes().contains(body));
	}

	@Test
	void vrm1FaceMeshComesFromTheExpressionNodeBindings() {
		DefaultGltfModel model = new DefaultGltfModel();
		DefaultMeshModel face = mesh(model, "Face");
		DefaultNodeModel node = node(model, "FaceNode");
		node.addMeshModel(face);
		model.setExtensions(Map.of("VRMC_vrm", Map.of("expressions", Map.of("preset",
			Map.of("blink", Map.of("morphTargetBinds", List.of(Map.of("node", 0))))))));
		assertEquals(java.util.Set.of(face), ToonVrmData.of(model).faceMeshes());
	}

	@Test
	void aModelWithoutExpressionsReportsNoFaceMesh() {
		DefaultGltfModel model = new DefaultGltfModel();
		mesh(model, "Plane");
		model.setExtensions(Map.of("VRM", Map.of()));
		assertTrue(ToonVrmData.of(model).faceMeshes().isEmpty());
		assertNull(ToonVrmData.of(model).head());
	}

	private static Map<String, Object> vrm0Head(int node) {
		return Map.of("VRM", Map.of("humanoid",
			Map.of("humanBones", List.of(Map.of("bone", "head", "node", node)))));
	}

	private static DefaultNodeModel node(DefaultGltfModel model, String name) {
		DefaultNodeModel node = new DefaultNodeModel();
		node.setName(name);
		model.addNodeModel(node);
		return node;
	}

	private static DefaultMeshModel mesh(DefaultGltfModel model, String name) {
		DefaultMeshModel mesh = new DefaultMeshModel();
		mesh.setName(name);
		model.addMeshModel(mesh);
		return mesh;
	}
}
