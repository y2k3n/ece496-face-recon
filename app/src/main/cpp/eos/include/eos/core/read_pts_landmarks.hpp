/*
 * eos - A 3D Morphable Model fitting library written in modern C++11/14.
 *
 * File: include/eos/core/read_pts_landmarks.hpp
 *
 * Copyright 2014, 2017 Patrik Huber
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#pragma once

#ifndef EOS_READ_PTS_LANDMARKS_HPP
#define EOS_READ_PTS_LANDMARKS_HPP

#include "eos/core/Landmark.hpp"

#include "Eigen/Core"

#include <algorithm>
#include <fstream>
#include <string>
#include <vector>

namespace eos {
namespace core {

/**
 * Reads an ibug .pts landmark file and returns an ordered vector with
 * the 68 2D landmark coordinates.
 *
 * @param[in] filename Path to a .pts file.
 * @return An ordered vector with the 68 ibug landmarks.
 */
inline LandmarkCollection<Eigen::Vector2f> read_pts_landmarks(std::string filename)
{
    using Eigen::Vector2f;
    using std::getline;
    using std::string;
    LandmarkCollection<Vector2f> landmarks;
    landmarks.reserve(68);

    std::ifstream file(filename);
    if (!file)
    {
        throw std::runtime_error(string("Could not open landmark file: " + filename));
    }

    string line;
    // Skip the first 3 lines, they're header lines:
    getline(file, line); // 'version: 1'
    getline(file, line); // 'n_points : 68'
    getline(file, line); // '{'

    int ibugId = 1;
    while (getline(file, line))
    {
        if (line == "}")
        { // end of the file
            break;
        }
        std::stringstream lineStream(line);

        Landmark<Vector2f> landmark;
        landmark.name = std::to_string(ibugId);
        if (!(lineStream >> landmark.coordinates[0] >> landmark.coordinates[1]))
        {
            throw std::runtime_error(string("Landmark format error while parsing the line: " + line));
        }
        // From the iBug website:
        // "Please note that the re-annotated data for this challenge are saved in the Matlab convention of 1
        // being the first index, i.e. the coordinates of the top left pixel in an image are x=1, y=1."
        // ==> So we shift every point by 1:
        landmark.coordinates[0] -= 1.0f;
        landmark.coordinates[1] -= 1.0f;
        landmarks.emplace_back(landmark);
        ++ibugId;
    }
    return landmarks;
};

// Mediapipe to Dlib 68-point correspondence
inline const int mp2dlib_correspondence[68][2] = {
    {127, 127}, {234, 234}, {93, 93},   {132, 58},  {58, 172},  {136, 136},
    {150, 150}, {176, 176}, {152, 152}, {400, 400}, {379, 379}, {365, 365},
    {397, 288}, {361, 361}, {323, 323}, {454, 454}, {356, 356}, {70, 70},
    {63, 63},   {105, 105}, {66, 66},   {107, 107}, {336, 336}, {296, 296},
    {334, 334}, {293, 293}, {300, 300}, {168, 6},   {197, 195}, {5, 5},
    {4, 4},     {75, 75},   {97, 97},   {2, 2},     {326, 326}, {305, 305},
    {33, 33},   {160, 160}, {158, 158}, {133, 133}, {153, 153}, {144, 144},
    {362, 362}, {385, 385}, {387, 387}, {263, 263}, {373, 373}, {380, 380},
    {61, 61},   {39, 39},   {37, 37},   {0, 0},     {267, 267}, {269, 269},
    {291, 291}, {321, 321}, {314, 314}, {17, 17},   {84, 84},   {91, 91},
    {78, 78},   {82, 82},   {13, 13},   {312, 312}, {308, 308}, {317, 317},
    {14, 14},   {87, 87}};

inline LandmarkCollection<Eigen::Vector2f>
read_convert_MP_landmarks(std::string filename, int w, int h) {
    using Eigen::Vector2f;
    using std::getline;
    using std::string;
    std::vector<Vector2f> MPlandmarks;
    MPlandmarks.reserve(478); // Mediapipe dense landmarks
    LandmarkCollection<Vector2f> ibuglandmarks;
    ibuglandmarks.reserve(68);

    std::ifstream MPfile(filename);
    if (!MPfile) {
        throw std::runtime_error(string("Could not open landmark file: " + filename));
    }

    string line;
    // Skip the first 3 lines, they're header lines:
    getline(MPfile, line); // 'version: 1'
    getline(MPfile, line); // 'n_points : 478'
    getline(MPfile, line); // '{'


    while (getline(MPfile, line)) {
        if (line == "}") { // end of the file
            break;
        }
        std::stringstream lineStream(line);
        Eigen::Vector2f landmark;
        if (!(lineStream >> landmark(0) >> landmark(1))) {
            throw std::runtime_error(string("Landmark format error while parsing the line: " + line));
        }
        landmark(0) *= w;
        landmark(1) *= h;
        MPlandmarks.emplace_back(landmark);
    }

    for (int i = 0; i < 68; ++i) {
        int idx1 = mp2dlib_correspondence[i][0];
        int idx2 = mp2dlib_correspondence[i][1];
        Vector2f pt;
        if (idx1 == idx2) {
            pt = MPlandmarks[idx1];
        } else {
            pt = 0.5f * (MPlandmarks[idx1] + MPlandmarks[idx2]);
        }

        Landmark<Vector2f> landmark;
        landmark.name = std::to_string(i+1);

        landmark.coordinates[0] = pt[0] - 1.0f;
        landmark.coordinates[1] = pt[1] - 1.0f;
        ibuglandmarks.emplace_back(landmark);
    }
    return ibuglandmarks;
}

} /* namespace core */
} /* namespace eos */

#endif /* EOS_READ_PTS_LANDMARKS_HPP */
