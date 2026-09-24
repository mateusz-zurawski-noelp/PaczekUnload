package engine.vulkan.shader;

import org.lwjgl.system.NativeResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.util.shaderc.Shaderc.*;

/**
 * Vulkan przyjmuje shadery wyłącznie w postaci bajtkodu SPIR-V, nie w czystym
 * GLSL. shaderc (biblioteka Google) kompiluje GLSL -> SPIR-V w locie, więc
 * podczas nauki można edytować pliki .vert/.frag i od razu odpalać program
 * bez osobnego kroku budowania (jak w narzędziu glslc z Vulkan SDK).
 */
public final class ShaderCompiler {

    private ShaderCompiler() {
    }

    /** Kompiluje shader z classpath (src/main/resources), np. "shaders/model.vert". */
    public static SPIRV compileFromResource(String resourcePath, ShaderKind kind) {
        String source = readResource(resourcePath);
        return compile(resourcePath, source, kind);
    }

    private static String readResource(String resourcePath) {
        try (InputStream in = ShaderCompiler.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new RuntimeException("Nie znaleziono shadera na classpath: " + resourcePath);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Nie udało się wczytać shadera " + resourcePath, e);
        }
    }

    private static SPIRV compile(String filename, String source, ShaderKind kind) {

        long compiler = shaderc_compiler_initialize();
        if (compiler == NULL) {
            throw new RuntimeException("Nie udało się utworzyć kompilatora shaderc");
        }

        long result = shaderc_compile_into_spv(compiler, source, kind.kind, filename, "main", NULL);
        if (result == NULL) {
            throw new RuntimeException("Kompilacja shadera " + filename + " nie powiodła się");
        }

        if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
            String error = shaderc_result_get_error_message(result);
            shaderc_compiler_release(compiler);
            throw new RuntimeException("Błąd kompilacji shadera " + filename + ":\n" + error);
        }

        shaderc_compiler_release(compiler);

        return new SPIRV(result, shaderc_result_get_bytes(result));
    }

    public enum ShaderKind {
        VERTEX_SHADER(shaderc_glsl_vertex_shader),
        FRAGMENT_SHADER(shaderc_glsl_fragment_shader),
        GEOMETRY_SHADER(shaderc_glsl_geometry_shader);

        final int kind;

        ShaderKind(int kind) {
            this.kind = kind;
        }
    }

    /** Skompilowany bajtkod SPIR-V. Trzeba go zwolnić wywołaniem free() po utworzeniu VkShaderModule. */
    public static final class SPIRV implements NativeResource {

        private final long handle;
        private ByteBuffer bytecode;

        SPIRV(long handle, ByteBuffer bytecode) {
            this.handle = handle;
            this.bytecode = bytecode;
        }

        public ByteBuffer bytecode() {
            return bytecode;
        }

        @Override
        public void free() {
            shaderc_result_release(handle);
            bytecode = null;
        }
    }
}
