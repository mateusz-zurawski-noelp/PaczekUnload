#version 450
#extension GL_ARB_separate_shader_objects : enable

// Parametry materiału (patrz engine.scene.Material).
layout(set = 1, binding = 0) uniform MaterialUBO {
    vec4 baseColor;   // mnożnik koloru tekstury, a = nieprzezroczystość
    vec4 specular;    // rgb = kolor odblasku, a = połysk
    vec4 emissive;    // rgb = światło własne, a = reflectivity
    vec4 params;      // x = siła wpływu drugiej tekstury
} material;

layout(set = 1, binding = 1) uniform sampler2D baseColorMap;
layout(set = 1, binding = 2) uniform sampler2D secondMap;

layout(location = 0) in vec3 fragColor;
layout(location = 1) in vec2 fragTexCoord;

layout(location = 0) out vec4 outColor;

void main() {
    vec4 color = texture(baseColorMap, fragTexCoord) * material.baseColor;

    // Druga tekstura mnożona z pierwszą; bez niej materiał ma białą, więc nic się nie zmienia.
    vec3 second = texture(secondMap, fragTexCoord).rgb;
    color.rgb *= mix(vec3(1.0), second, material.params.x);

    color.rgb += material.emissive.rgb;
    outColor = color;
}
