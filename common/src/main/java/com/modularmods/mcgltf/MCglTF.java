package com.modularmods.mcgltf;

import de.javagl.jgltf.model.GltfModel;
import de.javagl.jgltf.model.io.Buffers;
import de.javagl.jgltf.model.io.GltfModelReader;
import dev.architectury.registry.ReloadListenerRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.util.Unit;
import net.minecraft.util.profiling.ProfilerFiller;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.*;
import simplelibs.SimpleConfig;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class MCglTF {

    public static final String MODID = "mcgltf";
    public static final String RESOURCE_LOCATION = "resourceLocation";

    public static final Logger logger = LogManager.getLogger(MODID);

    private static MCglTF INSTANCE;

    protected final EnumRenderedModelGLProfile renderedModelGLProfile;
    private final Map<ResourceLocation, Supplier<ByteBuffer>> loadedBufferResources = new HashMap<ResourceLocation, Supplier<ByteBuffer>>();
    private final Map<ResourceLocation, Supplier<ByteBuffer>> loadedImageResources = new HashMap<ResourceLocation, Supplier<ByteBuffer>>();
    private final List<IGltfModelReceiver> gltfModelReceivers = new ArrayList<IGltfModelReceiver>();
    private final List<Runnable> gltfRenderData = new ArrayList<Runnable>();
    private int glProgramSkinnig = -1;
    private int defaultColorMap;
    private int defaultNormalMap;
    private AbstractTexture lightTexture;
    private BooleanSupplier shaderModActive;

    public MCglTF() {
        INSTANCE = this;
        renderedModelGLProfile = EnumRenderedModelGLProfile.valueOf(SimpleConfig.of(MODID).provider(this::provider).request().getOrDefault("RenderedModelGLProfile", "AUTO"));
    }

    public static MCglTF getInstance() {
        return INSTANCE;
    }

    protected void generateModel(Map<ResourceLocation, MutablePair<GltfModel, List<IGltfModelReceiver>>> lookup){
        switch (renderedModelGLProfile) {
            case GL43:
                processRenderedGltfModelsGL43(lookup);
                break;
            case GL40:
                processRenderedGltfModelsGL40(lookup);
                break;
            case GL33:
                processRenderedGltfModelsGL33(lookup);
                break;
            case GL30:
                processRenderedGltfModelsGL30(lookup);
                break;
            default:
                GLCapabilities glCapabilities = GL.getCapabilities();
                if (glCapabilities.glTexBufferRange != 0) processRenderedGltfModelsGL43(lookup);
                else if (glCapabilities.glGenTransformFeedbacks != 0) processRenderedGltfModelsGL40(lookup);
                else processRenderedGltfModelsGL33(lookup);
                break;
        }
    }

    public RenderedGltfModel createModel(ResourceLocation location){
        return switch (renderedModelGLProfile) {
            case GL43 -> processRenderedGltfModelsGL43(location);
            case GL40 -> processRenderedGltfModelsGL40(location);
            case GL33 -> processRenderedGltfModelsGL33(location);
            case GL30 -> processRenderedGltfModelsGL30(location);
            default -> {
                GLCapabilities glCapabilities = GL.getCapabilities();
                if (glCapabilities.glTexBufferRange != 0) yield processRenderedGltfModelsGL43(location);
                else if (glCapabilities.glGenTransformFeedbacks != 0) yield processRenderedGltfModelsGL40(location);
                else yield processRenderedGltfModelsGL33(location);
            }
        };
    }

    public void onInitialize(boolean shaderModExists, BooleanSupplier isShaderPackInUse) {
        Consumer<Map<ResourceLocation, MutablePair<GltfModel, List<IGltfModelReceiver>>>> processRenderedGltfModelSelector = this::generateModel;
        if (shaderModExists) {
            shaderModActive = isShaderPackInUse;
        } else {
            shaderModActive = () -> false;
        }

        Minecraft.getInstance().execute(() -> {
            lightTexture = Minecraft.getInstance().getTextureManager().getTexture(new ResourceLocation("dynamic/light_map_1"));

            switch (renderedModelGLProfile) {
                case GL43:
                    createSkinningProgramGL43();
                    break;
                case GL40:
                case GL33:
                    createSkinningProgramGL33();
                    break;
                case GL30:
                    break;
                default:
                    //Since max OpenGL version on Windows from GLCapabilities will always return 3.2 as of Minecraft 1.17, this is a workaround to check if OpenGL 4.3 is available.
                    if (GL.getCapabilities().glTexBufferRange != 0) createSkinningProgramGL43();
                    else createSkinningProgramGL33();
                    break;
            }

            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);

            int currentTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

            defaultColorMap = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, defaultColorMap);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, 2, 2, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, Buffers.create(new byte[]{-1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1}));
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_BASE_LEVEL, 0);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);

            defaultNormalMap = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, defaultNormalMap);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, 2, 2, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, Buffers.create(new byte[]{-128, -128, -1, -1, -128, -128, -1, -1, -128, -128, -1, -1, -128, -128, -1, -1}));
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_BASE_LEVEL, 0);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);

            GL11.glBindTexture(GL11.GL_TEXTURE_2D, currentTexture);
        });
        ReloadListenerRegistry.register(PackType.CLIENT_RESOURCES, (preparationBarrier, resourceManager, profilerFiller, profilerFiller2, executor, executor2) -> {
            gltfRenderData.forEach(Runnable::run);
            gltfRenderData.clear();

            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);

            int currentTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

            Map<ResourceLocation, MutablePair<GltfModel, List<IGltfModelReceiver>>> lookup = new HashMap<>();
            gltfModelReceivers.forEach((receiver) -> {
                ResourceLocation modelLocation = receiver.getModelLocation();
                MutablePair<GltfModel, List<IGltfModelReceiver>> receivers = lookup.get(modelLocation);
                if (receivers == null) {
                    receivers = MutablePair.of(null, new ArrayList<>());
                    lookup.put(modelLocation, receivers);
                }
                receivers.getRight().add(receiver);
            });
            lookup.entrySet().parallelStream().forEach((entry) -> {
                try {
                    entry.getValue().setLeft(new GltfModelReader().readWithoutReferences(new BufferedInputStream(Minecraft.getInstance().getResourceManager().getResource(entry.getKey()).orElseThrow().open())));
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
            processRenderedGltfModelSelector.accept(lookup);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
            GL30.glBindVertexArray(0);

            GL11.glBindTexture(GL11.GL_TEXTURE_2D, currentTexture);

            loadedBufferResources.clear();
            loadedImageResources.clear();
            return preparationBarrier.wait(Unit.INSTANCE).thenRunAsync(() -> {

            });
        }, new ResourceLocation(MODID, "gltf_reload_listener"));
    }

    public int getGlProgramSkinnig() {
        return glProgramSkinnig;
    }

    public int getDefaultColorMap() {
        return defaultColorMap;
    }

    public int getDefaultNormalMap() {
        return defaultNormalMap;
    }

    public int getDefaultSpecularMap() {
        return 0;
    }

    public AbstractTexture getLightTexture() {
        return lightTexture;
    }

    public ByteBuffer getBufferResource(ResourceLocation location) {
        Supplier<ByteBuffer> supplier;
        synchronized (loadedBufferResources) {
            supplier = loadedBufferResources.get(location);
            if (supplier == null) {
                supplier = new Supplier<ByteBuffer>() {
                    ByteBuffer bufferData;

                    @Override
                    public synchronized ByteBuffer get() {
                        if (bufferData == null) {
                            try {
                                bufferData = Buffers.create(IOUtils.toByteArray(new BufferedInputStream(Minecraft.getInstance().getResourceManager().getResource(location).orElseThrow().open())));
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                        return bufferData;
                    }

                };
                loadedBufferResources.put(location, supplier);
            }
        }
        return supplier.get();
    }

    public ByteBuffer getImageResource(ResourceLocation location) {
        Supplier<ByteBuffer> supplier;
        synchronized (loadedImageResources) {
            supplier = loadedImageResources.get(location);
            if (supplier == null) {
                supplier = new Supplier<ByteBuffer>() {
                    ByteBuffer bufferData;

                    @Override
                    public synchronized ByteBuffer get() {
                        if (bufferData == null) {
                            try {
                                bufferData = Buffers.create(IOUtils.toByteArray(new BufferedInputStream(Minecraft.getInstance().getResourceManager().getResource(location).orElseThrow().open())));
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                        return bufferData;
                    }

                };
                loadedImageResources.put(location, supplier);
            }
        }
        return supplier.get();
    }

    public synchronized void addGltfModelReceiver(IGltfModelReceiver receiver) {
        gltfModelReceivers.add(receiver);
    }

    public synchronized boolean removeGltfModelReceiver(IGltfModelReceiver receiver) {
        return gltfModelReceivers.remove(receiver);
    }

    public boolean isShaderModActive() {
        return shaderModActive.getAsBoolean();
    }

    private void createSkinningProgramGL43() {
        int glShader = GL20.glCreateShader(GL20.GL_VERTEX_SHADER);
        GL20.glShaderSource(glShader,
                "#version 430\r\n"
                        + "layout(location = 0) in vec4 joint;"
                        + "layout(location = 1) in vec4 weight;"
                        + "layout(location = 2) in vec3 position;"
                        + "layout(location = 3) in vec3 normal;"
                        + "layout(location = 4) in vec4 tangent;"
                        + "layout(std430, binding = 0) readonly buffer jointMatrixBuffer {mat4 jointMatrices[];};"
                        + "out vec3 outPosition;"
                        + "out vec3 outNormal;"
                        + "out vec4 outTangent;"
                        + "void main() {"
                        + "mat4 skinMatrix ="
                        + " weight.x * jointMatrices[int(joint.x)] +"
                        + " weight.y * jointMatrices[int(joint.y)] +"
                        + " weight.z * jointMatrices[int(joint.z)] +"
                        + " weight.w * jointMatrices[int(joint.w)];"
                        + "outPosition = (skinMatrix * vec4(position, 1.0)).xyz;"
                        + "mat3 upperLeft = mat3(skinMatrix);"
                        + "outNormal = upperLeft * normal;"
                        + "outTangent.xyz = upperLeft * tangent.xyz;"
                        + "outTangent.w = tangent.w;"
                        + "}");
        GL20.glCompileShader(glShader);

        glProgramSkinnig = GL20.glCreateProgram();
        GL20.glAttachShader(glProgramSkinnig, glShader);
        GL20.glDeleteShader(glShader);
        GL30.glTransformFeedbackVaryings(glProgramSkinnig, new CharSequence[]{"outPosition", "outNormal", "outTangent"}, GL30.GL_SEPARATE_ATTRIBS);
        GL20.glLinkProgram(glProgramSkinnig);
    }

    private void createSkinningProgramGL33() {
        int glShader = GL20.glCreateShader(GL20.GL_VERTEX_SHADER);
        GL20.glShaderSource(glShader,
                "#version 330\r\n"
                        + "layout(location = 0) in vec4 joint;"
                        + "layout(location = 1) in vec4 weight;"
                        + "layout(location = 2) in vec3 position;"
                        + "layout(location = 3) in vec3 normal;"
                        + "layout(location = 4) in vec4 tangent;"
                        + "uniform samplerBuffer jointMatrices;"
                        + "out vec3 outPosition;"
                        + "out vec3 outNormal;"
                        + "out vec4 outTangent;"
                        + "void main() {"
                        + "int jx = int(joint.x) * 4;"
                        + "int jy = int(joint.y) * 4;"
                        + "int jz = int(joint.z) * 4;"
                        + "int jw = int(joint.w) * 4;"
                        + "mat4 skinMatrix ="
                        + " weight.x * mat4(texelFetch(jointMatrices, jx), texelFetch(jointMatrices, jx + 1), texelFetch(jointMatrices, jx + 2), texelFetch(jointMatrices, jx + 3)) +"
                        + " weight.y * mat4(texelFetch(jointMatrices, jy), texelFetch(jointMatrices, jy + 1), texelFetch(jointMatrices, jy + 2), texelFetch(jointMatrices, jy + 3)) +"
                        + " weight.z * mat4(texelFetch(jointMatrices, jz), texelFetch(jointMatrices, jz + 1), texelFetch(jointMatrices, jz + 2), texelFetch(jointMatrices, jz + 3)) +"
                        + " weight.w * mat4(texelFetch(jointMatrices, jw), texelFetch(jointMatrices, jw + 1), texelFetch(jointMatrices, jw + 2), texelFetch(jointMatrices, jw + 3));"
                        + "outPosition = (skinMatrix * vec4(position, 1.0)).xyz;"
                        + "mat3 upperLeft = mat3(skinMatrix);"
                        + "outNormal = upperLeft * normal;"
                        + "outTangent.xyz = upperLeft * tangent.xyz;"
                        + "outTangent.w = tangent.w;"
                        + "}");
        GL20.glCompileShader(glShader);

        glProgramSkinnig = GL20.glCreateProgram();
        GL20.glAttachShader(glProgramSkinnig, glShader);
        GL20.glDeleteShader(glShader);
        GL30.glTransformFeedbackVaryings(glProgramSkinnig, new CharSequence[]{"outPosition", "outNormal", "outTangent"}, GL30.GL_SEPARATE_ATTRIBS);
        GL20.glLinkProgram(glProgramSkinnig);
    }

    protected void processRenderedGltfModels(Map<ResourceLocation, MutablePair<GltfModel, List<IGltfModelReceiver>>> lookup, BiFunction<List<Runnable>, GltfModel, RenderedGltfModel> renderedGltfModelBuilder) {
        lookup.forEach((modelLocation, receivers) -> {
            Iterator<IGltfModelReceiver> iterator = receivers.getRight().iterator();
            do {
                IGltfModelReceiver receiver = iterator.next();
                if (receiver.isReceiveSharedModel(receivers.getLeft(), gltfRenderData)) {
                    RenderedGltfModel renderedModel = renderedGltfModelBuilder.apply(gltfRenderData, receivers.getLeft());
                    receiver.onReceiveSharedModel(renderedModel);
                    while (iterator.hasNext()) {
                        receiver = iterator.next();
                        if (receiver.isReceiveSharedModel(receivers.getLeft(), gltfRenderData)) {
                            receiver.onReceiveSharedModel(renderedModel);
                        }
                    }
                    return;
                }
            }
            while (iterator.hasNext());
        });
    }

    protected RenderedGltfModel processRenderedGltfModels(ResourceLocation location, BiFunction<List<Runnable>, GltfModel, RenderedGltfModel> renderedGltfModelBuilder) {
        try {
            return renderedGltfModelBuilder.apply(gltfRenderData, new GltfModelReader().readWithoutReferences(new BufferedInputStream(Minecraft.getInstance().getResourceManager().getResource(location).orElseThrow().open())));
        } catch (IOException e) {
            return null;
        }
    }

    protected void processRenderedGltfModelsGL43(Map<ResourceLocation, MutablePair<GltfModel, List<IGltfModelReceiver>>> lookup) {
        processRenderedGltfModels(lookup, RenderedGltfModel::new);
        GL15.glBindBuffer(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 0);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
        GL40.glBindTransformFeedback(GL40.GL_TRANSFORM_FEEDBACK, 0);
    }

    protected void processRenderedGltfModelsGL40(Map<ResourceLocation, MutablePair<GltfModel, List<IGltfModelReceiver>>> lookup) {
        processRenderedGltfModels(lookup, RenderedGltfModelGL40::new);
        GL15.glBindBuffer(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 0);
        GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, 0);
        GL40.glBindTransformFeedback(GL40.GL_TRANSFORM_FEEDBACK, 0);
    }

    protected void processRenderedGltfModelsGL33(Map<ResourceLocation, MutablePair<GltfModel, List<IGltfModelReceiver>>> lookup) {
        processRenderedGltfModels(lookup, RenderedGltfModelGL33::new);
        GL15.glBindBuffer(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 0);
        GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, 0);
    }

    protected void processRenderedGltfModelsGL30(Map<ResourceLocation, MutablePair<GltfModel, List<IGltfModelReceiver>>> lookup) {
        processRenderedGltfModels(lookup, RenderedGltfModelGL30::new);
    }

    protected RenderedGltfModel processRenderedGltfModelsGL43(ResourceLocation location) {
        RenderedGltfModel m = processRenderedGltfModels(location, RenderedGltfModel::new);
        GL15.glBindBuffer(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 0);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
        GL40.glBindTransformFeedback(GL40.GL_TRANSFORM_FEEDBACK, 0);
        return m;
    }

    protected RenderedGltfModel processRenderedGltfModelsGL40(ResourceLocation location) {
        RenderedGltfModel m = processRenderedGltfModels(location, RenderedGltfModelGL40::new);
        GL15.glBindBuffer(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 0);
        GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, 0);
        GL40.glBindTransformFeedback(GL40.GL_TRANSFORM_FEEDBACK, 0);
        return m;
    }

    protected RenderedGltfModel processRenderedGltfModelsGL33(ResourceLocation location) {
        RenderedGltfModel m = processRenderedGltfModels(location, RenderedGltfModelGL33::new);
        GL15.glBindBuffer(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 0);
        GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, 0);
        return m;
    }

    protected RenderedGltfModel processRenderedGltfModelsGL30(ResourceLocation location) {
        return processRenderedGltfModels(location, RenderedGltfModelGL30::new);
    }


    public EnumRenderedModelGLProfile getRenderedModelGLProfile() {
        return renderedModelGLProfile;
    }

    private String provider(String filename) {
        return "#Set maximum version of OpenGL to enable some optimizations for rendering glTF model.\n"
                + "#The AUTO means it will select maximum OpenGL version available based on your hardware. The GL43 is highest it may select.\n"
                + "#The lower OpenGL version you set, the more negative impact on performance you will probably get.\n"
                + "#The GL30 is a special profile which essentially the GL33 and above but replace hardware(GPU) skinning with software(CPU) skinning. This will trade a lots of CPU performance for a few GPU performance increase.\n"
                + "#Allowed Values: AUTO, GL43, GL40, GL33, GL30\n"
                + "RenderedModelGLProfile=AUTO";
    }

    public enum EnumRenderedModelGLProfile {
        AUTO,
        GL43,
        GL40,
        GL33,
        GL30
    }

}
