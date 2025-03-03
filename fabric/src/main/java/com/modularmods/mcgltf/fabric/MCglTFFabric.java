package com.modularmods.mcgltf.fabric;

import com.modularmods.mcgltf.IGltfModelReceiver;
import com.modularmods.mcgltf.MCglTF;
import com.modularmods.mcgltf.fabric.iris.RenderedGltfModelGL30Iris;
import com.modularmods.mcgltf.fabric.iris.RenderedGltfModelGL33Iris;
import com.modularmods.mcgltf.fabric.iris.RenderedGltfModelGL40Iris;
import com.modularmods.mcgltf.fabric.iris.RenderedGltfModelIris;
import de.javagl.jgltf.model.GltfModel;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.irisshaders.iris.api.v0.IrisApi;
import net.minecraft.resources.ResourceLocation;
import org.apache.commons.lang3.tuple.MutablePair;
import org.lwjgl.opengl.*;

import java.util.List;
import java.util.Map;

public final class MCglTFFabric extends MCglTF implements ModInitializer {

    public MCglTFFabric() {
        super();
    }

    @Override
    public void onInitialize() {
        onInitialize(FabricLoader.getInstance().isModLoaded("iris"), IrisApi.getInstance()::isShaderPackInUse, this::generateModel);
    }

    private void generateModel(Map<ResourceLocation, MutablePair<GltfModel, List<IGltfModelReceiver>>> lookup) {
        switch (renderedModelGLProfile) {
            case GL43:
                processRenderedGltfModelsGL43Iris(lookup);
                break;
            case GL40:
                processRenderedGltfModelsGL40Iris(lookup);
                break;
            case GL33:
                processRenderedGltfModelsGL33Iris(lookup);
                break;
            case GL30:
                processRenderedGltfModelsGL30Iris(lookup);
                break;
            default:
                GLCapabilities glCapabilities = GL.getCapabilities();
                if (glCapabilities.glTexBufferRange != 0) processRenderedGltfModelsGL43Iris(lookup);
                else if (glCapabilities.glGenTransformFeedbacks != 0) processRenderedGltfModelsGL40Iris(lookup);
                else processRenderedGltfModelsGL33Iris(lookup);
                break;
        }
    }

    private void processRenderedGltfModelsGL43Iris(Map<ResourceLocation, MutablePair<GltfModel, List<IGltfModelReceiver>>> lookup) {
        processRenderedGltfModels(lookup, RenderedGltfModelIris::new);
        GL15.glBindBuffer(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 0);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
        GL40.glBindTransformFeedback(GL40.GL_TRANSFORM_FEEDBACK, 0);
    }

    private void processRenderedGltfModelsGL40Iris(Map<ResourceLocation, MutablePair<GltfModel, List<IGltfModelReceiver>>> lookup) {
        processRenderedGltfModels(lookup, RenderedGltfModelGL40Iris::new);
        GL15.glBindBuffer(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 0);
        GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, 0);
        GL40.glBindTransformFeedback(GL40.GL_TRANSFORM_FEEDBACK, 0);
    }

    private void processRenderedGltfModelsGL33Iris(Map<ResourceLocation, MutablePair<GltfModel, List<IGltfModelReceiver>>> lookup) {
        processRenderedGltfModels(lookup, RenderedGltfModelGL33Iris::new);
        GL15.glBindBuffer(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 0);
        GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, 0);
    }

    private void processRenderedGltfModelsGL30Iris(Map<ResourceLocation, MutablePair<GltfModel, List<IGltfModelReceiver>>> lookup) {
        processRenderedGltfModels(lookup, RenderedGltfModelGL30Iris::new);
    }
}
