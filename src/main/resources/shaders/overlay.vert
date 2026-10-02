#version 450
#extension GL_ARB_separate_shader_objects : enable

// Nakładka 2D (tekst, tła paneli) rysowana bezpośrednio we współrzędnych NDC -
// bez żadnych macierzy, bo nie jest częścią sceny 3D, tylko leży na stałe
// na ekranie niezależnie od kamery (piksele -> NDC liczy engine.ui.TextBatch).
layout(location = 0) in vec2 inPos;
layout(location = 1) in vec2 inUv;
layout(location = 2) in vec4 inColor;

layout(location = 0) out vec2 fragUv;
layout(location = 1) out vec4 fragColor;

void main() {
    gl_Position = vec4(inPos, 0.0, 1.0);
    fragUv = inUv;
    fragColor = inColor;
}
