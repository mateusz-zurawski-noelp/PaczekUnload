package engine.scene;

import org.joml.Vector2f;
import org.joml.Vector2fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.lwjgl.PointerBuffer;
import org.lwjgl.assimp.*;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import static java.util.Objects.requireNonNull;
import static org.lwjgl.assimp.Assimp.*;

/**
 * Wczytuje siatkę z pliku modelu (.obj, .fbx, .gltf, ...) przez Assimp -
 * bibliotekę wspierającą dziesiątki formatów, więc Vulkan/silnik w ogóle nie
 * musi wiedzieć nic o formacie OBJ. Assimp zwraca drzewo węzłów (przydatne
 * przy modelach ze szkieletem/hierarchią) - tu spłaszczamy je do jednej listy
 * wierzchołków i indeksów, bo model jest pojedynczą, statyczną siatką.
 */
public final class ModelLoader {

    private ModelLoader() {
    }

    /**
     * Wczytuje model z zawartości pliku w pamięci. Dzięki temu nie ma
     * znaczenia, czy plik leży na dysku, czy wewnątrz jara (tam nie ma
     * ścieżki, którą Assimp mógłby otworzyć) - bajty dostarcza ResourceManager.
     *
     * Ograniczenie: formaty odwołujące się do innych plików (np. .obj z
     * "mtllib", .gltf z osobnym .bin) nie znajdą ich z pamięci - dla takich
     * trzeba by podpiąć Assimpowi własny system plików (AIFileIO).
     *
     * @param data     zawartość pliku (bufor poza stertą, np. z ResourceManager.readBytes)
     * @param name     nazwa/ścieżka pliku - z rozszerzenia Assimp zgaduje format
     */
    public static Model load(ByteBuffer data, String name, int assimpFlags) {
        int dot = name.lastIndexOf('.');
        String formatHint = dot >= 0 ? name.substring(dot + 1) : "";

        try (AIScene scene = aiImportFileFromMemory(data, assimpFlags, formatHint)) {

            if (scene == null || scene.mRootNode() == null) {
                throw new RuntimeException("Nie udało się wczytać modelu " + name + ": " + aiGetErrorString());
            }

            Model model = new Model();
            processNode(scene.mRootNode(), scene, model);
            return model;
        }
    }

    private static void processNode(AINode node, AIScene scene, Model model) {

        if (node.mMeshes() != null) {
            PointerBuffer pMeshes = scene.mMeshes();
            IntBuffer meshIndices = node.mMeshes();

            for (int i = 0; i < meshIndices.capacity(); i++) {
                AIMesh mesh = AIMesh.create(pMeshes.get(meshIndices.get(i)));
                processMesh(mesh, model);
            }
        }

        if (node.mChildren() != null) {
            PointerBuffer children = node.mChildren();
            for (int i = 0; i < node.mNumChildren(); i++) {
                processNode(AINode.create(children.get(i)), scene, model);
            }
        }
    }

    private static void processMesh(AIMesh mesh, Model model) {
        processPositions(mesh, model.positions);
        processTexCoords(mesh, model.texCoords);
        processNormals(mesh, model.normals);
        processIndices(mesh, model.indices);
    }

    private static void processPositions(AIMesh mesh, List<Vector3fc> positions) {
        AIVector3D.Buffer vertices = requireNonNull(mesh.mVertices());
        for (int i = 0; i < vertices.capacity(); i++) {
            AIVector3D v = vertices.get(i);
            positions.add(new Vector3f(v.x(), v.y(), v.z()));
        }
    }

    private static void processTexCoords(AIMesh mesh, List<Vector2fc> texCoords) {
        AIVector3D.Buffer coords = requireNonNull(mesh.mTextureCoords(0));
        for (int i = 0; i < coords.capacity(); i++) {
            AIVector3D c = coords.get(i);
            texCoords.add(new Vector2f(c.x(), c.y()));
        }
    }

    private static void processNormals(AIMesh mesh, List<Vector3fc> normals) {
        AIVector3D.Buffer source = mesh.mNormals();
        if (source == null) {
            throw new RuntimeException("Model nie ma normalnych - wczytaj go z flagą aiProcess_GenSmoothNormals albo aiProcess_GenNormals");
        }
        for (int i = 0; i < source.capacity(); i++) {
            AIVector3D n = source.get(i);
            normals.add(new Vector3f(n.x(), n.y(), n.z()));
        }
    }

    private static void processIndices(AIMesh mesh, List<Integer> indices) {
        AIFace.Buffer faces = mesh.mFaces();
        for (int i = 0; i < mesh.mNumFaces(); i++) {
            AIFace face = faces.get(i);
            IntBuffer faceIndices = face.mIndices();
            for (int j = 0; j < face.mNumIndices(); j++) {
                indices.add(faceIndices.get(j));
            }
        }
    }

    public static final class Model {
        public final List<Vector3fc> positions = new ArrayList<>();
        public final List<Vector2fc> texCoords = new ArrayList<>();
        public final List<Vector3fc> normals = new ArrayList<>();
        public final List<Integer> indices = new ArrayList<>();
    }
}
