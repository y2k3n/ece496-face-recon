/*
 * Copyright (c) 2022, Peter Abeles. All Rights Reserved.
 *
 * This file is part of BoofCV (http://boofcv.org).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.demonoframework;

import boofcv.android.ConvertBitmap;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import boofcv.BoofVerbose;

import boofcv.alg.cloud.PointCloudReader;
import boofcv.alg.cloud.PointCloudUtils_F64;

import boofcv.alg.structure.SparseSceneToDenseCloud;
import boofcv.factory.disparity.ConfigDisparity;
import boofcv.factory.disparity.ConfigDisparitySGM;
import boofcv.factory.structure.ConfigSparseToDenseCloud;
import boofcv.factory.structure.FactorySceneReconstruction;

import boofcv.io.image.LookUpImageFilesByIndex;
import boofcv.io.points.PointCloudIO;
import boofcv.misc.BoofMiscOps;
import boofcv.struct.Point3dRgbI_F64;
import boofcv.struct.image.GrayU8;
import boofcv.struct.image.ImageType;
import boofcv.struct.image.Planar;
import georegression.struct.point.Point3D_F64;
import gnu.trove.map.hash.TIntObjectHashMap;
import org.ddogleg.struct.DogArray;
import org.ddogleg.struct.DogArray_I32;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * A dense point cloud is created using a previously computed sparse reconstruction and a basic implementation of
 * multiview stereo (MVS). This approach to MVS works by identifying "center" views which have the best set of
 * neighbors for stereo computations using a heuristic. Then a global point cloud is created from the "center" view
 * disparity images while taking care to avoid adding duplicate points.
 *
 * @author Peter Abeles
 */
public class MultiViewDenseReconstruction {

    MultiViewSparseReconstruction example;
    MultiViewDenseReconstruction(MultiViewSparseReconstruction example) {
        this.example = example;
    }

    // Compute Dense Reconstruction SfM
    public void compute() {
        // Looks up images based on their index in the file list
        LookUpImageFilesByIndex imageLookup =
            new LookUpImageFilesByIndex(example.imageFiles, (path, base) -> {

                Bitmap bitmap = BitmapFactory.decodeFile(path);
                if (bitmap == null)
                    throw new RuntimeException("Could not load: " + path);

                bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false);

                int w = bitmap.getWidth();
                int h = bitmap.getHeight();

                if (base instanceof GrayU8) {

                    GrayU8 gray = (GrayU8) base;
                    gray.reshape(w, h);

                    ConvertBitmap.bitmapToGray(bitmap, gray, null);

                } else if (base instanceof Planar) {

                    Planar<GrayU8> color = (Planar<GrayU8>) base;
                    color.reshape(w, h, 3);

                    ConvertBitmap.bitmapToPlanar(bitmap, color, GrayU8.class, null);

                } else {
                    throw new RuntimeException("Unsupported image type: " + base.getClass());
                }

                bitmap.recycle();
            });

        // We will use a high level algorithm that does almost all the work for us. It is highly configurable
        // and just about every parameter can be tweaked using its Config. Internal algorithms can be accessed
        // and customize directly if needed. Specifics for how it work is beyond this example but the code
        // is easily accessible.

        // Let's do some custom configuration for this scenario
        var config = new ConfigSparseToDenseCloud();
        config.disparity.approach = ConfigDisparity.Approach.SGM;
        ConfigDisparitySGM configSgm = config.disparity.approachSGM;
        configSgm.validateRtoL = 0;
        configSgm.texture = 0.75;
        configSgm.disparityRange = 32;
        configSgm.paths = ConfigDisparitySGM.Paths.P4;
        configSgm.configBlockMatch.radiusX = 3;
        configSgm.configBlockMatch.radiusY = 3;
        configSgm.subpixel = true;      // disable subpixel for memory savings

        // Create the sparse to dense reconstruction using a factory
        SparseSceneToDenseCloud<GrayU8> sparseToDense =
                FactorySceneReconstruction.sparseSceneToDenseCloud(config, ImageType.SB_U8);

        // To help make the time go by faster while we wait about 1 to 2 minutes for it to finish, let's print stuff
        sparseToDense.getMultiViewStereo().setVerbose(
                System.out, BoofMiscOps.hashSet(BoofVerbose.RECURSIVE, BoofVerbose.RUNTIME));

        var viewToId = new TIntObjectHashMap<String>();
        BoofMiscOps.forIdx(example.working.listViews, ( workIdxI, wv ) -> viewToId.put(wv.index, wv.pview.id));
        if (!sparseToDense.process(example.scene, null, viewToId, imageLookup))
            throw new RuntimeException("Dense reconstruction failed!");

        saveCloudToDisk(sparseToDense);
    }

    private void saveCloudToDisk( SparseSceneToDenseCloud<GrayU8> sparseToDense ) {

        File outputFile = new File(example.workDirectory, "saved_cloud.ply");

        // Save the dense point cloud to disk in PLY format
        try (FileOutputStream out = new FileOutputStream(outputFile)) {
            // Filter points which are far away to make it easier to view in 3rd party viewers that auto scale
            // You might need to adjust the threshold for your application if too many points are cut
            double distanceThreshold = 50.0;
            List<Point3D_F64> cloud = sparseToDense.getCloud();
            DogArray_I32 colorsRgb = sparseToDense.getColorRgb();

            DogArray<Point3dRgbI_F64> filtered = PointCloudUtils_F64.filter(
                    ( idx, p ) -> p.setTo(cloud.get(idx)), colorsRgb::get, cloud.size(),
                    ( idx ) -> cloud.get(idx).norm() <= distanceThreshold, null);

            PointCloudIO.save3D(PointCloudIO.Format.PLY, PointCloudReader.wrapF64RGB(filtered.toList()), true, out);
            System.out.println("Cloud saved to: " + outputFile.getAbsolutePath());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
