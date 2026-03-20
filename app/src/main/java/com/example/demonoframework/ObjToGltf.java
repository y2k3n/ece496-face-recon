package com.example.demonoframework;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

public class ObjToGltf {

    public static void convertObjToGltf(File objFile, File gltfFile, File binFile) throws IOException, JSONException {
        List<float[]> vertices = new ArrayList<>();
        List<float[]> normals = new ArrayList<>();
        List<int[]> faces = new ArrayList<>();

        // 1. Read OBJ file
        try (BufferedReader br = new BufferedReader(new FileReader(objFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("v ")) {
                    String[] parts = line.split("\\s+");
                    vertices.add(new float[]{
                            Float.parseFloat(parts[1]),
                            Float.parseFloat(parts[2]),
                            Float.parseFloat(parts[3])
                    });
                } else if (line.startsWith("vn ")) {
                    String[] parts = line.split("\\s+");
                    normals.add(new float[]{
                            Float.parseFloat(parts[1]),
                            Float.parseFloat(parts[2]),
                            Float.parseFloat(parts[3])
                    });
                } else if (line.startsWith("f ")) {
                    String[] parts = line.split("\\s+");
                    int[] face = new int[3]; // assuming triangles
                    for (int i = 0; i < 3; i++) {
                        String[] idx = parts[i + 1].split("/");
                        face[i] = Integer.parseInt(idx[0]) - 1; // OBJ indices start at 1
                    }
                    faces.add(face);
                }
            }
        }

        // 2. Prepare binary buffer
        int vertexCount = vertices.size();
        int normalCount = normals.size();
        int indexCount = faces.size() * 3;

        ByteBuffer buffer = ByteBuffer.allocate((vertexCount + normalCount) * 3 * 4 + indexCount * 4);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        // Add vertices
        for (float[] v : vertices) buffer.putFloat(v[0]).putFloat(v[1]).putFloat(v[2]);
        // Add normals
        for (float[] n : normals) buffer.putFloat(n[0]).putFloat(n[1]).putFloat(n[2]);
        // Add indices
        for (int[] f : faces) buffer.putInt(f[0]).putInt(f[1]).putInt(f[2]);

        buffer.flip();

        // 3. Write buffer.bin file
        try (FileOutputStream fos = new FileOutputStream(binFile)) {
            fos.write(buffer.array());
        }

        // 4. Build glTF JSON
        JSONObject gltf = new JSONObject();
        gltf.put("asset", new JSONObject().put("version", "2.0"));

        // Buffers
        JSONObject bufferJson = new JSONObject();
        bufferJson.put("byteLength", buffer.capacity());
        bufferJson.put("uri", binFile.getName()); // reference the external buffer
        gltf.put("buffers", new JSONArray().put(bufferJson));

        // BufferViews
        JSONArray bufferViews = new JSONArray();
        int vertexOffset = 0;
        int normalOffset = vertexCount * 3 * 4;
        int indexOffset = normalOffset + normalCount * 3 * 4;

        bufferViews.put(new JSONObject()
                .put("buffer", 0)
                .put("byteOffset", vertexOffset)
                .put("byteLength", vertexCount * 3 * 4));
        bufferViews.put(new JSONObject()
                .put("buffer", 0)
                .put("byteOffset", normalOffset)
                .put("byteLength", normalCount * 3 * 4));
        bufferViews.put(new JSONObject()
                .put("buffer", 0)
                .put("byteOffset", indexOffset)
                .put("byteLength", indexCount * 4)
                .put("target", 34963)); // ELEMENT_ARRAY_BUFFER
        gltf.put("bufferViews", bufferViews);

        // Accessors
        JSONArray accessors = new JSONArray();
        accessors.put(new JSONObject()
                .put("bufferView", 0)
                .put("componentType", 5126) // FLOAT
                .put("count", vertexCount)
                .put("type", "VEC3"));
        accessors.put(new JSONObject()
                .put("bufferView", 1)
                .put("componentType", 5126) // FLOAT
                .put("count", normalCount)
                .put("type", "VEC3"));
        accessors.put(new JSONObject()
                .put("bufferView", 2)
                .put("componentType", 5125) // UNSIGNED_INT
                .put("count", indexCount)
                .put("type", "SCALAR"));
        gltf.put("accessors", accessors);

        // Mesh
        JSONObject mesh = new JSONObject();
        mesh.put("primitives", new JSONArray().put(new JSONObject()
                .put("attributes", new JSONObject()
                        .put("POSITION", 0)
                        .put("NORMAL", 1))
                .put("indices", 2)
        ));
        gltf.put("meshes", new JSONArray().put(mesh));

        // Node
        JSONObject node = new JSONObject();
        node.put("mesh", 0);
        gltf.put("nodes", new JSONArray().put(node));

        // Scene
        gltf.put("scenes", new JSONArray().put(new JSONObject().put("nodes", new JSONArray().put(0))));
        gltf.put("scene", 0);

        // 5. Write glTF JSON file
        try (FileWriter fw = new FileWriter(gltfFile)) {
            fw.write(gltf.toString(2));
        }

        System.out.println("Conversion done!");
        System.out.println("Saved glTF: " + gltfFile.getAbsolutePath());
        System.out.println("Saved binary buffer: " + binFile.getAbsolutePath());
    }

//    public static void main(String[] args) throws IOException {
//        File obj = new File("outmerged.obj");        // your input OBJ file
//        File gltf = new File("outmerged.gltf");      // output glTF JSON
//        File bin = new File("buffer.bin");           // output buffer
//        convertObjToGltf(obj, gltf, bin);
//    }
}
