package engine.vulkan;

import engine.scene.Vertex;
import engine.vulkan.shader.ShaderCompiler;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static engine.vulkan.shader.ShaderCompiler.ShaderKind.FRAGMENT_SHADER;
import static engine.vulkan.shader.ShaderCompiler.ShaderKind.VERTEX_SHADER;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * W przeciwieństwie do OpenGL, w Vulkanie prawie cały stan rysowania
 * (shadery, format wierzchołków, blending, rasteryzacja, test głębi...) jest
 * zamrożony w jeden niemutowalny obiekt - VkPipeline. Zmiana czegokolwiek
 * (np. włączenie blendingu) wymaga utworzenia nowego pipeline'u. To więcej
 * pracy z góry, ale GPU wie z wyprzedzeniem dokładnie, co ma robić - stąd
 * dużo mniej niespodzianek wydajnościowych niż w OpenGL.
 */
public final class GraphicsPipeline {

    /** Rozmiar push constants: jedna macierz modelu 4x4. */
    public static final int PUSH_CONSTANT_SIZE = 16 * Float.BYTES;

    private final VulkanContext ctx;
    private final long pipelineLayout;
    private final long handle;

    public GraphicsPipeline(VulkanContext ctx, RenderPass renderPass, DescriptorSetLayout frameLayout,
                              DescriptorSetLayout materialLayout, VkExtent2D extent, String vertexShaderResource, String fragmentShaderResource) {
        this.ctx = ctx;

        try (MemoryStack stack = stackPush()) {

            ShaderCompiler.SPIRV vertSpirv = ShaderCompiler.compileFromResource(vertexShaderResource, VERTEX_SHADER);
            ShaderCompiler.SPIRV fragSpirv = ShaderCompiler.compileFromResource(fragmentShaderResource, FRAGMENT_SHADER);

            long vertModule = createShaderModule(vertSpirv.bytecode());
            long fragModule = createShaderModule(fragSpirv.bytecode());

            ByteBuffer entryPoint = stack.UTF8("main");

            VkPipelineShaderStageCreateInfo.Buffer shaderStages = VkPipelineShaderStageCreateInfo.calloc(2, stack);

            shaderStages.get(0)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_VERTEX_BIT)
                    .module(vertModule)
                    .pName(entryPoint);

            shaderStages.get(1)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_FRAGMENT_BIT)
                    .module(fragModule)
                    .pName(entryPoint);

            // ===> Jak czytać bufor wierzchołków <===
            VkPipelineVertexInputStateCreateInfo vertexInputInfo = VkPipelineVertexInputStateCreateInfo.calloc(stack);
            vertexInputInfo.sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO);
            vertexInputInfo.pVertexBindingDescriptions(Vertex.bindingDescription(stack));
            vertexInputInfo.pVertexAttributeDescriptions(Vertex.attributeDescriptions(stack));

            // ===> Z jakich prymitywów budujemy geometrię <===
            VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack);
            inputAssembly.sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO);
            inputAssembly.topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);
            inputAssembly.primitiveRestartEnable(false);

            // ===> Viewport i scissor (tu: cały framebuffer) <===
            VkViewport.Buffer viewport = VkViewport.calloc(1, stack);
            viewport.x(0.0f).y(0.0f);
            viewport.width(extent.width());
            viewport.height(extent.height());
            viewport.minDepth(0.0f).maxDepth(1.0f);

            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
            scissor.offset(VkOffset2D.calloc(stack).set(0, 0));
            scissor.extent(extent);

            VkPipelineViewportStateCreateInfo viewportState = VkPipelineViewportStateCreateInfo.calloc(stack);
            viewportState.sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO);
            viewportState.pViewports(viewport);
            viewportState.pScissors(scissor);

            // ===> Rasteryzacja: trójkąty -> fragmenty <===
            VkPipelineRasterizationStateCreateInfo rasterizer = VkPipelineRasterizationStateCreateInfo.calloc(stack);
            rasterizer.sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO);
            rasterizer.depthClampEnable(false);
            rasterizer.rasterizerDiscardEnable(false);
            rasterizer.polygonMode(VK_POLYGON_MODE_FILL);
            rasterizer.lineWidth(1.0f);
            rasterizer.cullMode(VK_CULL_MODE_BACK_BIT);
            rasterizer.frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE);
            rasterizer.depthBiasEnable(false);

            // ===> Multisampling (wyłączony - tu jeszcze bez MSAA) <===
            VkPipelineMultisampleStateCreateInfo multisampling = VkPipelineMultisampleStateCreateInfo.calloc(stack);
            multisampling.sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO);
            multisampling.sampleShadingEnable(false);
            multisampling.rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);

            // ===> Test głębi <===
            VkPipelineDepthStencilStateCreateInfo depthStencil = VkPipelineDepthStencilStateCreateInfo.calloc(stack);
            depthStencil.sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO);
            depthStencil.depthTestEnable(true);
            depthStencil.depthWriteEnable(true);
            depthStencil.depthCompareOp(VK_COMPARE_OP_LESS);
            depthStencil.depthBoundsTestEnable(false);
            depthStencil.minDepthBounds(0.0f);
            depthStencil.maxDepthBounds(1.0f);
            depthStencil.stencilTestEnable(false);

            // ===> Blending koloru (tu wyłączony - nieprzezroczysta geometria) <===
            VkPipelineColorBlendAttachmentState.Buffer colorBlendAttachment =
                    VkPipelineColorBlendAttachmentState.calloc(1, stack);
            colorBlendAttachment.colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT
                    | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT);
            colorBlendAttachment.blendEnable(false);

            VkPipelineColorBlendStateCreateInfo colorBlending = VkPipelineColorBlendStateCreateInfo.calloc(stack);
            colorBlending.sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO);
            colorBlending.logicOpEnable(false);
            colorBlending.logicOp(VK_LOGIC_OP_COPY);
            colorBlending.pAttachments(colorBlendAttachment);
            colorBlending.blendConstants(stack.floats(0.0f, 0.0f, 0.0f, 0.0f));

            // ===> Layout pipeline'u: jakie descriptor sety są dostępne dla shaderów <===
            VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack);
            pipelineLayoutInfo.sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO);
            pipelineLayoutInfo.pSetLayouts(stack.longs(frameLayout.handle(), materialLayout.handle()));

            // Macierz modelu (mat4) przekazywana per obiekt przez push constants - patrz Renderable.
            VkPushConstantRange.Buffer pushConstantRange = VkPushConstantRange.calloc(1, stack);
            pushConstantRange.stageFlags(VK_SHADER_STAGE_VERTEX_BIT);
            pushConstantRange.offset(0);
            pushConstantRange.size(PUSH_CONSTANT_SIZE);
            pipelineLayoutInfo.pPushConstantRanges(pushConstantRange);

            LongBuffer pPipelineLayout = stack.longs(VK_NULL_HANDLE);
            if (vkCreatePipelineLayout(ctx.device(), pipelineLayoutInfo, null, pPipelineLayout) != VK_SUCCESS) {
                throw new RuntimeException("Nie udało się utworzyć pipeline layoutu");
            }
            pipelineLayout = pPipelineLayout.get(0);

            VkGraphicsPipelineCreateInfo.Buffer pipelineInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack);
            pipelineInfo.sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO);
            pipelineInfo.pStages(shaderStages);
            pipelineInfo.pVertexInputState(vertexInputInfo);
            pipelineInfo.pInputAssemblyState(inputAssembly);
            pipelineInfo.pViewportState(viewportState);
            pipelineInfo.pRasterizationState(rasterizer);
            pipelineInfo.pMultisampleState(multisampling);
            pipelineInfo.pDepthStencilState(depthStencil);
            pipelineInfo.pColorBlendState(colorBlending);
            pipelineInfo.layout(pipelineLayout);
            pipelineInfo.renderPass(renderPass.handle());
            pipelineInfo.subpass(0);
            pipelineInfo.basePipelineHandle(VK_NULL_HANDLE);
            pipelineInfo.basePipelineIndex(-1);

            LongBuffer pPipeline = stack.mallocLong(1);
            if (vkCreateGraphicsPipelines(ctx.device(), VK_NULL_HANDLE, pipelineInfo, null, pPipeline) != VK_SUCCESS) {
                throw new RuntimeException("Nie udało się utworzyć graphics pipeline'u");
            }
            handle = pPipeline.get(0);

            // Moduły shaderów są potrzebne tylko podczas tworzenia pipeline'u.
            vkDestroyShaderModule(ctx.device(), vertModule, null);
            vkDestroyShaderModule(ctx.device(), fragModule, null);
            vertSpirv.free();
            fragSpirv.free();
        }
    }

    private long createShaderModule(ByteBuffer spirvCode) {
        try (MemoryStack stack = stackPush()) {

            VkShaderModuleCreateInfo createInfo = VkShaderModuleCreateInfo.calloc(stack);
            createInfo.sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO);
            createInfo.pCode(spirvCode);

            LongBuffer pShaderModule = stack.mallocLong(1);
            if (vkCreateShaderModule(ctx.device(), createInfo, null, pShaderModule) != VK_SUCCESS) {
                throw new RuntimeException("Nie udało się utworzyć modułu shadera");
            }

            return pShaderModule.get(0);
        }
    }

    public long handle() {
        return handle;
    }

    public long layout() {
        return pipelineLayout;
    }

    public void destroy() {
        vkDestroyPipeline(ctx.device(), handle, null);
        vkDestroyPipelineLayout(ctx.device(), pipelineLayout, null);
    }
}
