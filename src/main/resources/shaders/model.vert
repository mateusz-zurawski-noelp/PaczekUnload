#version 450
#extension GL_ARB_separate_shader_objects : enable

// Dane "per klatka": kamera + oświetlenie (patrz engine.scene.UniformBufferObject).
layout(set = 0, binding = 0) uniform FrameUBO {
    mat4 view;
    mat4 proj;
    vec4 cameraPosition;
    vec4 sunDirection;
    vec4 sunColor;
    vec4 skyColor;
    vec4 groundColor;
    vec4 worldUp;
} frame;

// Macierz modelu jest per obiekt (Renderable), wysyłana push constants.
layout(push_constant) uniform PushConstants {
    mat4 model;
} pc;

layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec3 inColor;
layout(location = 2) in vec2 inTexCoord;
layout(location = 3) in vec3 inNormal;

layout(location = 0) out vec3 fragColor;
layout(location = 1) out vec2 fragTexCoord;
layout(location = 2) out vec3 fragWorldPosition;
layout(location = 3) out vec3 fragWorldNormal;

void main() {
    vec4 worldPosition = pc.model * vec4(inPosition, 1.0);
    gl_Position = frame.proj * frame.view * worldPosition;

    // Normalnych nie wolno przekształcać zwykłą macierzą modelu: przy
    // nierównomiernym skalowaniu (np. scale = (2, 1, 1)) przestałyby być
    // prostopadłe do powierzchni. Poprawna jest odwrotność transponowanej
    // macierzy 3x3 ("normal matrix"). Liczenie jej per wierzchołek jest
    // kosztowne - w większym silniku liczy się ją raz na CPU per obiekt.
    mat3 normalMatrix = transpose(inverse(mat3(pc.model)));
    fragWorldNormal = normalMatrix * inNormal;

    fragWorldPosition = worldPosition.xyz;
    fragColor = inColor;
    fragTexCoord = inTexCoord;
}
