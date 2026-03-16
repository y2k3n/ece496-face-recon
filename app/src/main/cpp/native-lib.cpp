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

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_demonoframework_MainActivity_stringFromJNI(
        JNIEnv* env,
        jobject /* this */) {
    eos::core::Landmark<int> testldmk;
    testldmk.name = "abcd";
    std::string hello = "Hello from C++" + testldmk.name;
    return env->NewStringUTF(hello.c_str());
}