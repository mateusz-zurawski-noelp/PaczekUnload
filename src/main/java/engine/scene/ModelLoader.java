package engine.scene;

import org.joml.Vector2f;
import org.joml.Vector2fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.lwjgl.PointerBuffer;
import org.lwjgl.assimp.*;

import java.io.File;
import java.net.URISyntaxException;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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

    public static Model load(File file, int assimpFlags) {

        try (AIScene scene = aiImportFile(file.getAbsolutePath(), assimpFlags)) {

            if (scene == null || scene.mRootNode() == null) {
                throw new RuntimeException("Nie udało się wczytać modelu " + file + ": " + aiGetErrorString());
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

    /** Znajduje plik zasobu na classpath jako File (Assimp czyta z dysku, nie ze strumienia). */
    public static File resourceFile(String resourcePath) {
        try {
            var url = Objects.requireNonNull(ModelLoader.class.getClassLoader().getResource(resourcePath),
                    "Nie znaleziono zasobu na classpath: " + resourcePath);
            return new File(url.toURI());
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    public static final class Model {
        public final List<Vector3fc> positions = new ArrayList<>();
        public final List<Vector2fc> texCoords = new ArrayList<>();
        public final List<Integer> indices = new ArrayList<>();
    }
}
