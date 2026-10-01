#version 450
#extension GL_ARB_separate_shader_objects : enable

// Dane "per klatka": kamera + oświetlenie (patrz engine.scene.UniformBufferObject).
layout(set = 0, binding = 0) uniform FrameUBO {
    mat4 view;
    mat4 proj;
    vec4 cameraPosition;   // xyz
    vec4 sunDirection;     // xyz = kierunek, w którym świeci słońce
    vec4 sunColor;         // rgb = kolor * natężenie
    vec4 skyColor;         // rgb = światło otoczenia z góry
    vec4 groundColor;      // rgb = światło otoczenia z dołu
    vec4 worldUp;          // xyz
} frame;

// Parametry materiału (patrz engine.scene.Material).
layout(set = 1, binding = 0) uniform MaterialUBO {
    vec4 baseColor;   // mnożnik koloru tekstury, a = nieprzezroczystość
    vec4 specular;    // rgb = kolor odblasku, a = połysk (shininess)
    vec4 emissive;    // rgb = światło własne, a = reflectivity
    vec4 params;      // x = siła wpływu drugiej tekstury
} material;

layout(set = 1, binding = 1) uniform sampler2D baseColorMap;
layout(set = 1, binding = 2) uniform sampler2D secondMap;

layout(location = 0) in vec3 fragColor;
layout(location = 1) in vec2 fragTexCoord;
layout(location = 2) in vec3 fragWorldPosition;
layout(location = 3) in vec3 fragWorldNormal;

layout(location = 0) out vec4 outColor;

// Światło otoczenia z kierunku dir: kolor nieba dla kierunków w górę,
// kolor ziemi dla kierunków w dół, płynne przejście pomiędzy.
vec3 hemisphere(vec3 dir) {
    float t = dot(dir, frame.worldUp.xyz) * 0.5 + 0.5;
    return mix(frame.groundColor.rgb, frame.skyColor.rgb, t);
}

void main() {
    // ----- kolor powierzchni (albedo) - jak dotąd: tekstury * baseColor -----
    vec4 albedo = texture(baseColorMap, fragTexCoord) * material.baseColor * vec4(fragColor, 1.0);
    vec3 second = texture(secondMap, fragTexCoord).rgb;
    albedo.rgb *= mix(vec3(1.0), second, material.params.x);

    // ----- wektory potrzebne do oświetlenia (wszystko w przestrzeni świata) -----
    // Interpolacja między wierzchołkami skraca normalną - trzeba ją znormalizować.
    vec3 N = normalize(fragWorldNormal);
    vec3 L = normalize(-frame.sunDirection.xyz);                        // od powierzchni DO słońca
    vec3 V = normalize(frame.cameraPosition.xyz - fragWorldPosition);   // od powierzchni do kamery
    vec3 H = normalize(L + V);                                          // "half vector" (Blinn)

    // ----- diffuse (Lambert): im bardziej powierzchnia zwrócona do światła, tym jaśniej -----
    float NdotL = max(dot(N, L), 0.0);
    vec3 diffuse = frame.sunColor.rgb * NdotL;

    // ----- ambient: światło rozproszone z otoczenia, zależne od kierunku normalnej -----
    vec3 ambient = hemisphere(N);

    // ----- specular (Blinn-Phong): odblask tam, gdzie normalna jest blisko
    // połowy kąta między światłem a kamerą. shininess steruje wielkością
    // plamki: małe = szeroki, matowy odblask, duże = mały i ostry (lakier, metal).
    float shininess = max(material.specular.a, 1.0);
    // Warunek NdotL > 0: powierzchnia odwrócona od słońca nie może mieć odblasku.
    float specularTerm = NdotL > 0.0 ? pow(max(dot(N, H), 0.0), shininess) : 0.0;
    vec3 specular = material.specular.rgb * frame.sunColor.rgb * specularTerm;

    // ----- reflectivity: odbicie "otoczenia" w kierunku lustrzanym. Nie mamy
    // jeszcze mapy otoczenia (cubemap), więc odbijamy gradient niebo/ziemia.
    vec3 R = reflect(-V, N);
    vec3 reflection = hemisphere(R);
    float reflectivity = clamp(material.emissive.a, 0.0, 1.0);

    vec3 lit = albedo.rgb * (ambient + diffuse);
    vec3 color = mix(lit, reflection, reflectivity) + specular + material.emissive.rgb;

    outColor = vec4(color, albedo.a);
}
