#include <jni.h>
#include <string>

#include "eos/core/Landmark.hpp"
#include "eos/core/LandmarkMapper.hpp"
#include "eos/core/read_pts_landmarks.hpp"
#include "eos/core/Image.hpp"
#include "eos/core/write_obj.hpp"
#include "eos/morphablemodel/MorphableModel.hpp"
#include "eos/morphablemodel/Blendshape.hpp"
#include "eos/fitting/fitting.hpp"
#include "eos/fitting/multi_image_fitting.hpp"
#include "eos/render/texture_extraction.hpp"
#include "eos/render/render.hpp"
#include "eos/cpp17/optional.hpp"

#include <chrono>
#include <sstream>
//#include <omp.h>
//#include <execution>
//#include <numeric>
#include <pthread.h>
#include <thread>


#define TINYGLTF_IMPLEMENTATION

#define STB_IMAGE_IMPLEMENTATION
// #include "stb_image.h"

#define STB_IMAGE_WRITE_IMPLEMENTATION
// #include "stb_image_write.h"

#include "tiny_gltf.h"

constexpr int texture_res = 4096;
constexpr int max_threads = 8;

bool write_mesh_gltf(const eos::core::Mesh &mesh, const std::string &gltf_file,
                     const std::string &texture_file) {
    tinygltf::Model model;
    tinygltf::TinyGLTF gltf;

    // 1. vertex buffer
    std::vector<float> positions;
    for (const auto &v : mesh.vertices) {
        positions.push_back(v[0]);
        positions.push_back(v[1]);
        positions.push_back(v[2]);
    }
    std::vector<float> texcoords;
    for (const auto &uv : mesh.texcoords) {
        texcoords.push_back(uv[0]);
        texcoords.push_back(uv[1]);
    }
    std::vector<unsigned short> indices;
    const auto &face_indices = mesh.tti.empty() ? mesh.tvi : mesh.tti;
    for (const auto &tri : face_indices) {
        indices.push_back(static_cast<unsigned short>(tri[0]));
        indices.push_back(static_cast<unsigned short>(tri[1]));
        indices.push_back(static_cast<unsigned short>(tri[2]));
    }

    // 2. buffer merge
    std::vector<unsigned char> buffer;
    size_t pos_offset = 0;
    size_t tex_offset = 0;
    size_t idx_offset = 0;
    // positions
    pos_offset = 0;
    buffer.resize(positions.size() * sizeof(float));
    memcpy(buffer.data(), positions.data(), positions.size() * sizeof(float));
    // texcoords
    tex_offset = buffer.size();
    buffer.resize(buffer.size() + texcoords.size() * sizeof(float));
    memcpy(buffer.data() + tex_offset, texcoords.data(),
           texcoords.size() * sizeof(float));
    // indices
    idx_offset = buffer.size();
    buffer.resize(buffer.size() + indices.size() * sizeof(unsigned short));
    memcpy(buffer.data() + idx_offset, indices.data(),
           indices.size() * sizeof(unsigned short));

    // 3. add buffer
    tinygltf::Buffer gltf_buffer;
    gltf_buffer.data = buffer;
    model.buffers.push_back(gltf_buffer);

    // 4. add bufferView
    {
        tinygltf::BufferView posView;
        posView.buffer = 0;
        posView.byteOffset = pos_offset;
        posView.byteLength = positions.size() * sizeof(float);
        posView.target = 0; // or use GL_ARRAY_BUFFER if needed
        model.bufferViews.push_back(posView);

        tinygltf::BufferView texView;
        texView.buffer = 0;
        texView.byteOffset = tex_offset;
        texView.byteLength = texcoords.size() * sizeof(float);
        texView.target = 0; // or use GL_ARRAY_BUFFER if needed
        model.bufferViews.push_back(texView);

        tinygltf::BufferView idxView;
        idxView.buffer = 0;
        idxView.byteOffset = idx_offset;
        idxView.byteLength = indices.size() * sizeof(unsigned short);
        idxView.target = 0; // or use GL_ELEMENT_ARRAY_BUFFER if needed
        model.bufferViews.push_back(idxView);
    }

    // 5. add accessor
    // positions
    tinygltf::Accessor acc_pos;
    acc_pos.bufferView = 0;
    acc_pos.byteOffset = 0;
    acc_pos.componentType = TINYGLTF_COMPONENT_TYPE_FLOAT;
    acc_pos.count = mesh.vertices.size();
    acc_pos.type = TINYGLTF_TYPE_VEC3;
    // calc min/max for positions
    if (!mesh.vertices.empty()) {
        float min_x = mesh.vertices[0][0], min_y = mesh.vertices[0][1],
                min_z = mesh.vertices[0][2];
        float max_x = mesh.vertices[0][0], max_y = mesh.vertices[0][1],
                max_z = mesh.vertices[0][2];
        for (const auto &v : mesh.vertices) {
            if (v[0] < min_x)
                min_x = v[0];
            if (v[1] < min_y)
                min_y = v[1];
            if (v[2] < min_z)
                min_z = v[2];
            if (v[0] > max_x)
                max_x = v[0];
            if (v[1] > max_y)
                max_y = v[1];
            if (v[2] > max_z)
                max_z = v[2];
        }
        acc_pos.minValues = {min_x, min_y, min_z};
        acc_pos.maxValues = {max_x, max_y, max_z};
    }
    model.accessors.push_back(acc_pos);
    // texcoords
    tinygltf::Accessor acc_uv;
    acc_uv.bufferView = 1;
    acc_uv.byteOffset = 0;
    acc_uv.componentType = TINYGLTF_COMPONENT_TYPE_FLOAT;
    acc_uv.count = mesh.texcoords.size();
    acc_uv.type = TINYGLTF_TYPE_VEC2;
    // calc min/max for texcoords
    if (!mesh.texcoords.empty()) {
        float min_u = mesh.texcoords[0][0], min_v = mesh.texcoords[0][1];
        float max_u = mesh.texcoords[0][0], max_v = mesh.texcoords[0][1];
        for (const auto &uv : mesh.texcoords) {
            if (uv[0] < min_u)
                min_u = uv[0];
            if (uv[1] < min_v)
                min_v = uv[1];
            if (uv[0] > max_u)
                max_u = uv[0];
            if (uv[1] > max_v)
                max_v = uv[1];
        }
        acc_uv.minValues = {min_u, min_v};
        acc_uv.maxValues = {max_u, max_v};
    }
    model.accessors.push_back(acc_uv);
    // indices
    tinygltf::Accessor acc_idx;
    acc_idx.bufferView = 2;
    acc_idx.byteOffset = 0;
    acc_idx.componentType = TINYGLTF_COMPONENT_TYPE_UNSIGNED_SHORT;
    acc_idx.count = indices.size();
    acc_idx.type = TINYGLTF_TYPE_SCALAR;
    model.accessors.push_back(acc_idx);

    // 6. add image
    tinygltf::Image image;
    image.uri = texture_file;
    model.images.push_back(image);

    // 7. add texture
    tinygltf::Texture texture;
    texture.source = 0;
    model.textures.push_back(texture);

    // 8. add material
    tinygltf::Material material;
    material.pbrMetallicRoughness.baseColorTexture.index = 0;
    model.materials.push_back(material);

    // 9. mesh primitive
    tinygltf::Primitive primitive;
    primitive.attributes["POSITION"] = 0;
    primitive.attributes["TEXCOORD_0"] = 1;
    primitive.indices = 2;
    primitive.material = 0;
    primitive.mode = TINYGLTF_MODE_TRIANGLES;

    tinygltf::Mesh gltf_mesh;
    gltf_mesh.primitives.push_back(primitive);
    model.meshes.push_back(gltf_mesh);

    // 10. node
    tinygltf::Node node;
    node.mesh = 0;
    model.nodes.push_back(node);
    model.scenes.resize(1);
    model.scenes[0].nodes.push_back(0);
    model.defaultScene = 0;

    // 11. save glTF
    return gltf.WriteGltfSceneToFile(&model, gltf_file, false, true, true, false);
}

eos::core::Image4u merge_isomaps(const std::vector<eos::core::Image4u> &isomaps, double threshold = 0.5) {
    int x = isomaps[0].width();
    int y = isomaps[0].height();
    eos::core::Image4u merged_isomap(y, x);
    int alpha_threshold = static_cast<int>(threshold * 255.0);

    for (int r = 0; r < y; ++r) {
        for (int c = 0; c < x; ++c) {
            int visibility_count = 0;
            int merged_colors[4] = {0, 0, 0, 0};

            for (const auto &isomap : isomaps) {
                if (isomap(r, c)[3] > alpha_threshold) {
                    ++visibility_count;
                    merged_colors[0] += isomap(r, c)[0];
                    merged_colors[1] += isomap(r, c)[1];
                    merged_colors[2] += isomap(r, c)[2];
                }
            }

            if (visibility_count > 0) {
                merged_isomap(r, c)[0] = merged_colors[0] / visibility_count;
                merged_isomap(r, c)[1] = merged_colors[1] / visibility_count;
                merged_isomap(r, c)[2] = merged_colors[2] / visibility_count;
                merged_isomap(r, c)[3] = 255; // set to visible
            }

        }
    }

    return merged_isomap;
}

eos::core::Image4u merge_isomaps_weighed(const std::vector<eos::core::Image4u> &isomaps,
                                    double init_thold = 1) {
    int x = isomaps[0].width();
    int y = isomaps[0].height();
    eos::core::Image4u merged_isomap(y, x);

    for (int r = 0; r < y; ++r) {
        for (int c = 0; c < x; ++c) {

            float sum_alpha = 0.0f;
            float sum_rgb[3] = {0.0f, 0.0f, 0.0f};

            int valid_count = 0;
            double thold = init_thold;
            while (true) {
                if (thold < 0.01) {
                    thold = 0;
                }
                valid_count = 0;

                for (const auto &isomap : isomaps) {
                    // std::cout << "isomap(r, c)[3]: " << (int)isomap(r, c)[3] <<
                    // std::endl;
                    float alpha = isomap(r, c)[3] / 255.0f;
                    alpha -= thold;
                    // std::cout << "alpha: " << alpha << std::endl;
                    if (alpha > 1e-5) {
                        sum_rgb[0] += isomap(r, c)[0] * alpha;
                        sum_rgb[1] += isomap(r, c)[1] * alpha;
                        sum_rgb[2] += isomap(r, c)[2] * alpha;
                        sum_alpha += alpha;
                        ++valid_count;
                    }
                }
                if (valid_count > 0 || thold < 1e-5) {
                    if (sum_alpha == 0) {
                        sum_rgb[0] = 0;
                        sum_rgb[1] = 0;
                        sum_rgb[2] = 0;
                    } else {
                        sum_rgb[0] /= sum_alpha;
                        sum_rgb[1] /= sum_alpha;
                        sum_rgb[2] /= sum_alpha;
                    }
                    break;
                }

                thold *= 0.85;
            }

            merged_isomap(r, c)[0] =
                    std::min(static_cast<unsigned char>(sum_rgb[0] + 0.5f),
                             static_cast<unsigned char>(255));
            merged_isomap(r, c)[1] =
                    std::min(static_cast<unsigned char>(sum_rgb[1] + 0.5f),
                             static_cast<unsigned char>(255));
            merged_isomap(r, c)[2] =
                    std::min(static_cast<unsigned char>(sum_rgb[2] + 0.5f),
                             static_cast<unsigned char>(255));
            merged_isomap(r, c)[3] = valid_count > 0 ? 255 : 0;
        }
    }

    return merged_isomap;
}


eos::core::Image4u from_stb(const unsigned char *img_stb, int x, int y, int n_ch) {
    eos::core::Image4u converted(y, x);
    for (int r = 0; r < y; ++r) {
        for (int c = 0; c < x; ++c) {
            int idx = (r * x + c) * n_ch;
            converted(r, c) = {
                    img_stb[idx + 0], img_stb[idx + 1],
                    img_stb[idx + 2], img_stb[idx + 3]};
        }
    }
    return converted;
}

void to_stb(unsigned char *buf_stb, eos::core::Image4u img_core) {
    int y = img_core.height();
    int x = img_core.width();
//    std::cout << "Converting image to stb format, size: " << x << "x" << y << std::endl;
    for (int r = 0; r < y; ++r) {
        for (int c = 0; c < x; ++c) {
            int idx = (r * x + c) * 4;
            buf_stb[idx + 0] = img_core(r, c)[0];
            buf_stb[idx + 1] = img_core(r, c)[1];
            buf_stb[idx + 2] = img_core(r, c)[2];
            buf_stb[idx + 3] = img_core(r, c)[3];
        }
    }
}


extern "C" JNIEXPORT jstring JNICALL
Java_com_example_demonoframework_MainActivity_runJNI(
        JNIEnv* env,
        jobject activity,
        jint w,
        jint h,
        jstring externalDirPath,
        jobjectArray imagePaths,
        jobjectArray landmarkPaths) {

    jclass activityClass = env->GetObjectClass(activity);
    jmethodID updateStatusMethod = env->GetMethodID(activityClass, "updateStatus", "(Ljava/lang/String;)V");
    auto jni_update_status = [&](const std::string& msg) {
        jstring jmsg = env->NewStringUTF(msg.c_str());
        env->CallVoidMethod(activity, updateStatusMethod, jmsg);
        env->DeleteLocalRef(jmsg);
    };

    const char* pathChars = env->GetStringUTFChars(externalDirPath, nullptr);
    std::string pathStr = pathChars ? pathChars : "";
    env->ReleaseStringUTFChars(externalDirPath, pathChars);

    std::stringstream ss_status;
    ss_status << "Start data processing, path=" << pathStr << "\n\n";
    ss_status << "Start loading data...\n";
    jni_update_status(ss_status.str());

    // Convert imagePaths to std::vector<std::string>
    std::vector<std::string> imagefiles;
    jsize imgCount = env->GetArrayLength(imagePaths);
    imagefiles.reserve(imgCount);
    for (jsize i = 0; i < imgCount; ++i) {
        jstring jstr = (jstring)env->GetObjectArrayElement(imagePaths, i);
        const char* cstr = env->GetStringUTFChars(jstr, nullptr);
        imagefiles.emplace_back(cstr ? cstr : "");
        env->ReleaseStringUTFChars(jstr, cstr);
        env->DeleteLocalRef(jstr);
    }

    // Convert landmarkPaths to std::vector<std::string>
    std::vector<std::string> landmarksfiles;
    jsize lmkCount = env->GetArrayLength(landmarkPaths);
    landmarksfiles.reserve(lmkCount);
    for (jsize i = 0; i < lmkCount; ++i) {
        jstring jstr = (jstring)env->GetObjectArrayElement(landmarkPaths, i);
        const char* cstr = env->GetStringUTFChars(jstr, nullptr);
        landmarksfiles.emplace_back(cstr ? cstr : "");
        env->ReleaseStringUTFChars(jstr, cstr);
        env->DeleteLocalRef(jstr);
    }

    // Note: Could make these all std::string, see fit-model.cpp.
    std::string modelfile, mappingsfile, contourfile, edgetopologyfile,
            blendshapesfile, outputfilebase;
    // std::vector<std::string> imagefiles;
    // std::vector<std::string> landmarksfiles;

    modelfile = pathStr + "/share/sfm_shape_3448.bin";
    mappingsfile = pathStr + "/share/ibug_to_sfm.txt";
    contourfile = pathStr + "/share/sfm_model_contours.json";
    edgetopologyfile = pathStr + "/share/sfm_3448_edge_topology.json";
    blendshapesfile = pathStr + "/share/expression_blendshapes_3448.bin";
    outputfilebase = pathStr + "/out";

    if (landmarksfiles.size() != imagefiles.size()) {
        ss_status << "Error: Number of landmark files does not match number of image files.\n";
        return env->NewStringUTF(ss_status.str().c_str());
    }
    int n_frames = static_cast<int>(imagefiles.size());


    auto t_start = std::chrono::high_resolution_clock::now();

    std::vector<eos::core::LandmarkCollection<Eigen::Vector2f>> per_frame_landmarks;
    per_frame_landmarks.reserve(n_frames);
    try {
        for (const auto &landmarksfile : landmarksfiles) {
            per_frame_landmarks.emplace_back(eos::core::read_convert_MP_landmarks(landmarksfile, w, h));
        }
    } catch (const std::runtime_error &e) {
        ss_status << "Error reading the landmarks: " << std::string(e.what()) << "\n";
        return env->NewStringUTF(ss_status.str().c_str());
    }

    std::vector<eos::core::Image4u> images(n_frames);
    for (int i = 0; i < n_frames; ++i) {
        int x, y, ch_src;
        unsigned char *img_stb =
                stbi_load(imagefiles[i].c_str(), &x, &y, &ch_src, 4);
        if (!img_stb) {
            ss_status << "Error reading frame " << i << ": " << imagefiles[i] << "\n";
            return env->NewStringUTF(ss_status.str().c_str());
        }
        ss_status << "Read frame " << i << ", size: " << x << "x" << y
                  << ", n_ch: " << ch_src << std::endl;
        jni_update_status(ss_status.str());
        images[i] = from_stb(img_stb, x, y, 4);
        stbi_image_free(img_stb);
    }


    eos::morphablemodel::MorphableModel morphable_model;
    try {
        morphable_model = eos::morphablemodel::load_model(modelfile);
    } catch (const std::runtime_error &e) {
        ss_status << "Error loading the Morphable Model: " + std::string(e.what()) + "\n";
        return env->NewStringUTF(ss_status.str().c_str());
    }

    // The landmark mapper is used to map ibug landmark identifiers to vertex ids:
    eos::core::LandmarkMapper landmark_mapper =
            mappingsfile.empty() ? eos::core::LandmarkMapper()
                                 : eos::core::LandmarkMapper(mappingsfile);

    // The expression blendshapes:
    std::vector<eos::morphablemodel::Blendshape> blendshapes =
            eos::morphablemodel::load_blendshapes(blendshapesfile);

    // These two are used to fit the front-facing contour to the ibug contour landmarks:
    eos::fitting::ModelContour model_contour =
            contourfile.empty() ? eos::fitting::ModelContour()
                                : eos::fitting::ModelContour::load(contourfile);
    eos::fitting::ContourLandmarks ibug_contour =
            eos::fitting::ContourLandmarks::load(mappingsfile);

    // The edge topology is used to speed up computation of the occluding face contour fitting:
    eos::morphablemodel::EdgeTopology edge_topology =
            eos::morphablemodel::load_edge_topology(edgetopologyfile);

    // Fit the model, get back a mesh and the pose:
    std::vector<eos::core::Mesh> per_frame_meshes;
    std::vector<eos::fitting::RenderingParameters> per_frame_rendering_params;

    std::vector<int> image_widths(n_frames, w);
    std::vector<int> image_heights(n_frames, h);

    std::vector<float> pca_shape_coefficients;
    std::vector<std::vector<float>> blendshape_coefficients;
    std::vector<std::vector<Eigen::Vector2f>> fitted_image_points;

    auto t_load = std::chrono::high_resolution_clock::now();
    std::chrono::duration<double> elapsed_load = t_load - t_start;
    ss_status << "Data loading time: " + std::to_string(elapsed_load.count()) + "s\n\n";
    ss_status << "Start model fitting...\n";
    jni_update_status(ss_status.str());


    std::tie(per_frame_meshes, per_frame_rendering_params) =
            eos::fitting::fit_shape_and_pose(
                    morphable_model, blendshapes, per_frame_landmarks, landmark_mapper,
                    image_widths, image_heights, edge_topology, ibug_contour,
                    model_contour, 5, eos::cpp17::nullopt, 30.0f, eos::cpp17::nullopt,
                    pca_shape_coefficients, blendshape_coefficients,
                    fitted_image_points);

    auto t_model = std::chrono::high_resolution_clock::now();
    std::chrono::duration<double> elapsed_model = t_model - t_load;
    ss_status << "Model fitting time: " + std::to_string(elapsed_model.count()) + "s\n\n";
    ss_status << "Start texture extraction...\n";
    jni_update_status(ss_status.str());


    std::vector<eos::core::Image4u> per_frame_texturemaps(n_frames);

    for (int i = 0; i < n_frames; ++i) {
        per_frame_texturemaps[i] = eos::render::extract_texture(
                per_frame_meshes[i], per_frame_rendering_params[i].get_modelview(),
                per_frame_rendering_params[i].get_projection(),
                eos::render::ProjectionType::Orthographic, images[i], texture_res, true);
    }

//    int num_threads = std::thread::hardware_concurrency();
//    if (num_threads == 0) num_threads = 1; // fallback
//    if (num_threads > n_frames) num_threads = n_frames;
////    num_threads = 1;
//    ss_status << "Using " << num_threads << " threads..." << std::endl;
//    jni_update_status(ss_status.str());
//
//    std::vector<std::thread> threads(num_threads);
//
//    auto worker = [&](int tid) {
//        int chunk = (n_frames + num_threads - 1) / num_threads;
//        int begin = tid * chunk;
//        int end = std::min(begin + chunk, n_frames);
////        ss_status << "Thread " << tid << " processing frames " << begin << " to "
////                  << end - 1 << std::endl;
//        for (int i = begin; i < end; ++i) {
//            per_frame_texturemaps[i] = eos::render::extract_texture(
//                    per_frame_meshes[i], per_frame_rendering_params[i].get_modelview(),
//                    per_frame_rendering_params[i].get_projection(),
//                    eos::render::ProjectionType::Orthographic, images[i], texture_res, true);
//        }
//    };

//    for (int t = 0; t < num_threads; ++t) {
//        threads[t] = std::thread(worker, t);
//    }
//    for (auto &th : threads) {
//        th.join();
//    }
//
//    per_frame_texturemaps[0] = eos::render::extract_texture(
//                    per_frame_meshes[0], per_frame_rendering_params[0].get_modelview(),
//                    per_frame_rendering_params[0].get_projection(),
//                    eos::render::ProjectionType::Orthographic, images[0], texture_res);

//
//    int omp_num_threads = omp_get_max_threads();
//    if (omp_num_threads > max_threads) {
//        omp_num_threads = max_threads;
//    }
//    omp_set_num_threads(omp_num_threads);
//    ss_status << "Using " << omp_num_threads << " threads" << std::endl;
//
//#pragma omp parallel for
//    for (int i = 0; i < n_frames; ++i) {
//        // The 3D head pose can be recovered as follows - the function returns an
//        // Eigen::Vector3f with yaw, pitch, and roll angles:
//        const float yaw_angle =
//                per_frame_rendering_params[i].get_yaw_pitch_roll()[0];
//
//        // Extract the texture from the image using given mesh and camera
//        // parameters: Have to fiddle around with converting between core::Image
//        // and cv::Mat
//        per_frame_texturemaps[i] = eos::render::extract_texture(
//                per_frame_meshes[i], per_frame_rendering_params[i].get_modelview(),
//                per_frame_rendering_params[i].get_projection(),
//                eos::render::ProjectionType::Orthographic, images[i], texture_res);
//    }
//
    auto texturemap = merge_isomaps_weighed(per_frame_texturemaps);

    auto t_texture = std::chrono::high_resolution_clock::now();
    std::chrono::duration<double> elapsed_texture = t_texture - t_model;
    ss_status << "Texture mapping time: " << elapsed_texture.count() << "s\n";
    jni_update_status(ss_status.str());


    int text_x = texturemap.width(), text_y = texturemap.height();
    ss_status << "Texture size: " << text_x << "x" << text_y << std::endl;
    auto *out_buf = new unsigned char[text_x * text_y * 4];
    to_stb(out_buf, texturemap);
    std::string texture_file = outputfilebase + ".texture.png";
    int write_result = stbi_write_png(texture_file.c_str(), text_x, text_y, 4, out_buf, text_x * 4);
    if (write_result == 0) {
        ss_status << "Error: Failed to write texture file: " << texture_file << "\n";
        jni_update_status(ss_status.str());
        delete[] out_buf;
        return env->NewStringUTF(ss_status.str().c_str());
    }
    delete[] out_buf;

    std::string outputfile = outputfilebase + ".obj";
    auto mesh = morphable_model.draw_sample(pca_shape_coefficients,
                                            std::vector<float>());
    try {
        eos::core::write_textured_obj(mesh, outputfile);
    } catch (const std::exception& e) {
        ss_status << "Error: Failed to write obj file: " << outputfile << ". " << e.what() << "\n";
        jni_update_status(ss_status.str());
        return env->NewStringUTF(ss_status.str().c_str());
    }

    // Save as glTF with texture
    std::string gltf_file = outputfilebase + ".gltf";
    bool gltf_success = write_mesh_gltf(mesh, gltf_file, texture_file);
    if (!gltf_success) {
        ss_status << "Error: Failed to write glTF file." << std::endl;
        jni_update_status(ss_status.str());
        return env->NewStringUTF(ss_status.str().c_str());
    }



    auto t_save = std::chrono::high_resolution_clock::now();
    std::chrono::duration<double> elapsed_save = t_save - t_texture;
    ss_status << "Model saving time: " << elapsed_save.count() << "s\n";
    std::chrono::duration<double> elapsed_total = t_save - t_start;
    ss_status << "Total processing time: " << elapsed_total.count() << "s\n";
    jni_update_status(ss_status.str());



//    std::ofstream ofs(outputfilebase + ".txt");
//    ofs << "Model file: " << modelfile << std::endl;
//    ofs << "Mappings file: " << mappingsfile << std::endl;
//    ofs << "Contour file: " << contourfile << std::endl;
//    ofs << "Edge topology file: " << edgetopologyfile << std::endl;
//    ofs << "Blendshapes file: " << blendshapesfile << std::endl;


    return env->NewStringUTF(ss_status.str().c_str());

}