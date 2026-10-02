#version 450
#extension GL_ARB_separate_shader_objects : enable

// Atlas czcionki: kolor biały, alfa = jaka część piksela jest pokryta literą
// (antyaliasing wypalony przez stb_truetype). Tła paneli celują w biały,
// w pełni nieprzezroczysty pasek atlasu, więc dostają po prostu fragColor.
layout(set = 0, binding = 0) uniform sampler2D fontAtlas;

layout(location = 0) in vec2 fragUv;
layout(location = 1) in vec4 fragColor;

layout(location = 0) out vec4 outColor;

void main() {
    float coverage = texture(fontAtlas, fragUv).a;
    outColor = vec4(fragColor.rgb, fragColor.a * coverage);
}
