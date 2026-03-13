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

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import androidx.media3.common.MediaItem;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.ddogleg.DDoglegConcurrency;
import org.ddogleg.struct.DogArray_I32;

import georegression.geometry.ConvertRotation3D_F64;
import georegression.struct.point.Point3D_F64;
import georegression.struct.point.Point4D_F64;
import georegression.struct.so.Rodrigues_F64;

import boofcv.BoofVerbose;
import boofcv.abst.geo.bundle.SceneStructureMetric;
import boofcv.abst.tracker.PointTrack;
import boofcv.abst.tracker.PointTracker;
import boofcv.alg.cloud.PointCloudReader;
import boofcv.alg.geo.bundle.cameras.BundlePinholeSimplified;
import boofcv.alg.mvs.ColorizeMultiViewStereoResults;
import boofcv.alg.similar.ConfigSimilarImagesSceneRecognition;
import boofcv.alg.similar.ConfigSimilarImagesTrackThenMatch;
import boofcv.alg.structure.*;
import boofcv.android.ConvertBitmap;
import boofcv.core.image.LookUpColorRgbFormats;
import boofcv.factory.scene.FactorySceneRecognition;
import boofcv.factory.structure.ConfigGeneratePairwiseImageGraph;
import boofcv.factory.structure.FactorySceneReconstruction;
import boofcv.factory.tracker.ConfigPointTracker;
import boofcv.factory.tracker.FactoryPointTracker;
import boofcv.io.UtilIO;
import boofcv.io.geo.MultiViewIO;
import boofcv.io.image.LookUpImageFilesByIndex;
import boofcv.io.image.UtilImageIO;
import boofcv.io.points.PointCloudIO;
import boofcv.misc.BoofMiscOps;
import boofcv.struct.Point3dRgbI_F64;
import boofcv.struct.image.GrayU8;
import boofcv.struct.image.ImageType;
import boofcv.struct.image.InterleavedU8;
import boofcv.struct.image.Planar;
import static boofcv.misc.BoofMiscOps.checkTrue;

/**
 * Estimate scene parameters using a sparse set of features across uncalibrated images. In this example, a KLT
 * feature tracker will be used due to speed and simplicity even though there are some disadvantages
 * mentioned below. After image features have been tracked across the sequence we will first determine 3D
 * connectivity through two-view geometry, followed my a metric elevation. Then a final refinement
 * using bundle adjustment.
 *
 * This is unusual in that it will estimate intrinsic parameters from scratch with very few assumptions.
 * Most MVS software uses a data base of known camera parameters to provide an initial seed as this can simplify
 * the problem and make it more stable.
 *
 * @author Peter Abeles (original author)
 */
public class MultiViewSparseReconstruction {
    Context context;
    String workDirectory;
    List<String> imageFiles = new ArrayList<>();
    LookUpCameraInfo dbCams = new LookUpCameraInfo();
    LookUpSimilarImages dbSimilar;
    PairwiseImageGraph pairwise = null;
    SceneWorkingGraph working = null;
    SceneStructureMetric scene = null;
    boolean rebuild = true;

    // Set context for class, needed to retrieve video/image files
    MultiViewSparseReconstruction(Context context) {
        this.context = context.getApplicationContext();
    }

    // compute Sparse Reconstruction SfM
    public void compute(String videoName, boolean sequential) {
        // Turn on threaded code for bundle adjustment
        DDoglegConcurrency.USE_CONCURRENT = true;

        // Find existing directory to store the work space
        File baseDir = context.getFilesDir();
        String videoBaseName = videoName.substring(0, videoName.lastIndexOf('.'));
        File workDirFile = new File(baseDir, "mvs_work" + File.separator + videoBaseName);

        // Make new directory if does not exist
        if (!workDirFile.exists()) {
            workDirFile.mkdirs();
        }

        // Get path to work directory and video
        workDirectory = workDirFile.getAbsolutePath() + File.separator;
        String videoPath = new File(workDirFile, videoName).getAbsolutePath();

        System.out.println(workDirectory);
        System.out.println(videoPath);

        // Attempt to reload intermediate results if previously computed and rebuild is not set
        if (!rebuild) {
            try {
                pairwise = MultiViewIO.load(new File(workDirectory, "pairwise.yaml").getPath(), (PairwiseImageGraph) null);
            } catch (UncheckedIOException ignore) {
            }
            try {
                working = MultiViewIO.load(new File(workDirectory, "working.yaml").getPath(), pairwise, null);
            } catch (UncheckedIOException ignore) {
            }
            try {
                scene = MultiViewIO.load(new File(workDirectory, "structure.yaml").getPath(), (SceneStructureMetric) null);
            } catch (UncheckedIOException ignore) {
            }
        }

        // Convert the video into an image sequence. Later on we will need to access the images in random order
        var imageDirectory = new File(workDirectory, "images");

        if (imageDirectory.exists()) {
            imageFiles = UtilIO.listSmart(String.format("glob:%simages/*.jpg", workDirectory), true, (f) -> true);
            if (imageFiles.isEmpty()) {
                return;
            }
        } else {
            checkTrue(imageDirectory.mkdirs(), "Failed to image directory");
            System.out.println("----------------------------------------------------------------------------");
            System.out.println("### Decoding Video");
            try {
                // Create your MediaItem from the path (External or Internal)
                Uri videoUri = Uri.parse(videoPath);
                MediaItem mediaItem = MediaItem.fromUri(videoUri);

                // Use MetadataRetriever (The engine behind Media3's frame seeking)
                MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                try {
                    retriever.setDataSource(context, videoUri);

                    // Get video metadata
                    String time = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                    long durationMs = Long.parseLong(time);
                    float fps = 3f; // You can also extract this from metadata
                    long frameTimeUs = (long) (1000000 / fps);

                    // Pre-allocate the Bitmap to avoid OOM (Out of Memory)
                    Bitmap bitmap = null;
                    Bitmap resizedBitmap = null;

                    for (long currentByte = 0; currentByte < durationMs * 1000; currentByte += frameTimeUs) {
                        // Extract frame at specific time (microseconds)
                        // OPTION_CLOSEST syncs better with external high-profile encodes
                        bitmap = retriever.getFrameAtTime(currentByte, MediaMetadataRetriever.OPTION_CLOSEST);

                        if (bitmap == null) continue;

                        // Convert to BoofCV
                        InterleavedU8 boofImage = new InterleavedU8(bitmap.getWidth(), bitmap.getHeight(), 3);
                        ConvertBitmap.bitmapToBoof(bitmap, boofImage, null);

                        // Compress the bitmap before saving
                        int targetWidth = bitmap.getWidth() / 2;
                        int targetHeight = bitmap.getHeight() / 2;
                        resizedBitmap = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true);

                        // Save the file
                        File imageFile = new File(imageDirectory, String.format("frame%04d.jpg", (int) (currentByte / frameTimeUs)));
                        try (FileOutputStream out = new FileOutputStream(imageFile)) {
                            resizedBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
                            imageFiles.add(imageFile.getPath());
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    retriever.release();
                    File videoFile = new File(videoPath);

                    if (videoFile.exists()) {
                        videoFile.delete();
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        // Only determine the visual relationship between images if needed
        if (pairwise == null || working == null) {
            if (sequential) {
                similarImagesFromSequence();
            } else {
                similarImagesFromUnsorted();
            }
        }

        if (pairwise == null) {
            computePairwiseGraph();
        }
        if (working == null) {
            metricFromPairwise();
        }
        if (scene == null) {
            bundleAdjustmentRefine();
        }

        var rod = new Rodrigues_F64();
        System.out.println("----------------------------------------------------------------------------");
        for (PairwiseImageGraph.View pv : pairwise.nodes.toList()) {
            if (!working.containsView(pv.id))
                continue;
            SceneWorkingGraph.View wv = working.lookupView(pv.id);
            int order = working.listViews.indexOf(wv);
            ConvertRotation3D_F64.matrixToRodrigues(wv.world_to_view.R, rod);
            BundlePinholeSimplified intrinsics = working.getViewCamera(wv).intrinsic;
            System.out.printf("view[%2d]='%2s' f=%6.1f k1=%6.3f k2=%6.3f T={%5.1f,%5.1f,%5.1f} R=%4.2f\n",
                    order, wv.pview.id, intrinsics.f, intrinsics.k1, intrinsics.k2,
                    wv.world_to_view.T.x, wv.world_to_view.T.y, wv.world_to_view.T.z, rod.theta);
        }
        System.out.println("   Views used: " + scene.views.size + " / " + pairwise.nodes.size);
    }

    /**
     * For a pairwise graph to be constructed, image feature relationships between frames are needed. For a video
     * sequence, KLT is an easy and fast way to do this. However, KLT will not "close the loop", and it will
     * not realize you're back at the initial location. Typically this results in a noticeable miss alignment.
     */
    private void similarImagesFromSequence() {
        System.out.println("----------------------------------------------------------------------------");
        System.out.println("### Creating Similar Images from an ordered set of images");

        // Configure the KLT tracker
        ConfigPointTracker configTracker = FactorySceneRecognition.createDefaultTrackerConfig();

        PointTracker<GrayU8> tracker = FactoryPointTracker.tracker(configTracker, GrayU8.class, null);
        var activeTracks = new ArrayList<PointTrack>();

        var config = new ConfigSimilarImagesTrackThenMatch();

        final var dbSimilar = FactorySceneReconstruction.createTrackThenMatch(config, ImageType.SB_U8);
        dbSimilar.setVerbose(System.out, BoofMiscOps.hashSet(BoofVerbose.RECURSIVE));

        // Track features across the entire sequence and save the results
        BoofMiscOps.profile(() -> {
            boolean first = true;
            for (int frameId = 0; frameId < imageFiles.size(); frameId++) {

                File imageFile = new File(imageFiles.get(frameId));

                System.out.println(imageFile);

                // 1. Load the image as a standard Android Bitmap
                Bitmap bitmap = BitmapFactory.decodeFile(imageFile.getAbsolutePath());

                // 2. Convert Bitmap to BoofCV format
                GrayU8 frame = new GrayU8(bitmap.getWidth(), bitmap.getHeight());
                ConvertBitmap.bitmapToGray(bitmap, frame, null);

                Objects.requireNonNull(frame, "Failed to load image");
                if (first) {
                    first = false;
                    dbSimilar.initialize(frame.width, frame.height);
                    dbCams.addCameraCanonical(frame.width, frame.height, 60.0);
                }

                tracker.process(frame);
                int activeCount = tracker.getTotalActive();
                int droppedCount = tracker.getDroppedTracks(null).size();
                tracker.spawnTracks();
                tracker.getActiveTracks(activeTracks);
                dbSimilar.processFrame(frame, activeTracks, tracker.getFrameID());
                String id = frameId + "";
                System.out.println("frame id = " + id + " active=" + activeCount + " dropped=" + droppedCount);

                // Everything maps to the same camera
                dbCams.addView(id, 0);
            }

            dbSimilar.finishedTracking();
        }, "Finding Similar");

        this.dbSimilar = dbSimilar;
    }

    /**
     * Assumes that the images are complete unsorted
     */
    private void similarImagesFromUnsorted() {
        System.out.println("----------------------------------------------------------------------------");
        System.out.println("### Creating Similar Images from unordered images");

        var config = new ConfigSimilarImagesSceneRecognition();

        final var similarImages = FactorySceneReconstruction.createSimilarImages(config, ImageType.SB_U8);
        similarImages.setVerbose(System.out, BoofMiscOps.hashSet(BoofVerbose.RECURSIVE));

        // Track features across the entire sequence and save the results
        BoofMiscOps.profile(() -> {
            for (int frameId = 0; frameId < imageFiles.size(); frameId++) {
                String filePath = imageFiles.get(frameId);
                GrayU8 frame = UtilImageIO.loadImage(filePath, GrayU8.class);
                Objects.requireNonNull(frame, "Failed to load image");

                String viewID = frameId + "";

                similarImages.addImage(viewID, frame);
                // Everything maps to the same camera
                if (frameId == 0)
                    dbCams.addCameraCanonical(frame.width, frame.height, 60.0);
                dbCams.addView(viewID, 0);
            }

            similarImages.fixate();
        }, "Finding Similar");

        this.dbSimilar = similarImages;
    }

    /**
     * This step attempts to determine which views have a 3D (not homographic) relationship with each other and which
     * features are real and not fake.
     */
    public void computePairwiseGraph() {
        System.out.println("----------------------------------------------------------------------------");
        System.out.println("### Creating Pairwise");
        var config = new ConfigGeneratePairwiseImageGraph();
        GeneratePairwiseImageGraph generatePairwise = FactorySceneReconstruction.generatePairwise(config);
        BoofMiscOps.profile(() -> {
            generatePairwise.setVerbose(System.out, BoofMiscOps.hashSet(BoofVerbose.RECURSIVE));
            generatePairwise.process(dbSimilar, dbCams);
        }, "Created Pairwise graph");
        pairwise = generatePairwise.getGraph();

        //File internalDir = context.getFilesDir();

        var savePath = new File(workDirectory, "pairwise.yaml");

        System.out.println(savePath.getAbsolutePath());

        MultiViewIO.save(pairwise, savePath.getAbsolutePath());
        System.out.println("  nodes.size=" + pairwise.nodes.size);
        System.out.println("  edges.size=" + pairwise.edges.size);
    }

    /**
     * Next a metric reconstruction is attempted using views with a 3D relationship. This is a tricky step
     * and works by finding clusters of views which are likely to have numerically stable results then expanding
     * the sparse metric reconstruction.
     */
    public void metricFromPairwise() {
        System.out.println("----------------------------------------------------------------------------");
        System.out.println("### Metric Reconstruction");

        var metric = new MetricFromUncalibratedPairwiseGraph();
        metric.setVerbose(System.out, BoofMiscOps.hashSet(BoofVerbose.RECURSIVE));
        BoofMiscOps.profile(() -> {
            if (!metric.process(dbSimilar, dbCams, pairwise)) {
                System.err.println("Reconstruction failed");
                System.exit(0);
            }
        }, "Metric Reconstruction");

        working = metric.getLargestScene();

        var savePath = new File(workDirectory, "working.yaml");
        MultiViewIO.save(working, savePath.getPath());
    }

    /**
     * Here the initial estimate found in the metric reconstruction is refined using Bundle Adjustment, which just
     * means all parameters (camera, view pose, point location) are optimized all at once.
     */
    public void bundleAdjustmentRefine() {
        System.out.println("----------------------------------------------------------------------------");
        System.out.println("Refining the scene");

        var refine = new RefineMetricWorkingGraph();
        BoofMiscOps.profile(() -> {
            // Bundle adjustment is run twice, with the worse 5% of points discarded in an attempt to reduce noise
            refine.metricSba.keepFraction = 0.95;
            refine.metricSba.getSba().setVerbose(System.out, null);
            if (!refine.process(dbSimilar, working)) {
                System.out.println("SBA REFINE FAILED");
            }
        }, "Bundle Adjustment refine");
        scene = refine.metricSba.structure;

        var savePath = new File(workDirectory, "structure.yaml");
        MultiViewIO.save(scene, savePath.getPath());
    }

    /**
     * To visualize the results we will render a sparse point cloud along with the location of each camera in the
     * scene.
     */
    public void visualizeSparseCloud() {
        checkTrue(scene.isHomogeneous());
        List<Point3D_F64> cloudXyz = new ArrayList<>();
        Point4D_F64 world = new Point4D_F64();

        // NOTE: By default the colors found below are not used. Look before to see why and how to turn them on.
        //
        // Colorize the cloud by reprojecting the images. The math is straight forward but there's a lot of book
        // keeping that needs to be done due to the scene data structure. A class is provided to make this process easy

        //  Define the Lookup - explicitly state that this is a lookup for InterleavedU8
        LookUpImageFilesByIndex imageLookup =
            new LookUpImageFilesByIndex(imageFiles, (path, base) -> {

                Bitmap bitmap = BitmapFactory.decodeFile(path);
                if (bitmap == null)
                    throw new RuntimeException("Could not load: " + path);
                Planar<GrayU8> image =
                        new Planar<>(GrayU8.class, bitmap.getWidth(), bitmap.getHeight(), 3);

                ConvertBitmap.bitmapToBoof(bitmap, image, null);
                bitmap.recycle();

                base.setTo(image);
            });

        var colorize = new ColorizeMultiViewStereoResults<>(new LookUpColorRgbFormats.PL_U8(), imageLookup);

        DogArray_I32 rgb = new DogArray_I32();
        rgb.resize(scene.points.size);

        // Process the Scene
        colorize.processScenePoints(scene, null,
                (viewIdx) -> {
                    System.out.println(viewIdx);
                    return Integer.toString(viewIdx);
                },
                (pointIdx, r, g, b) -> rgb.set(pointIdx, (r << 16) | (g << 8) | b));

        // Convert the structure into regular 3D points from homogenous
        for (int i = 0; i < scene.points.size; i++) {
            scene.points.get(i).get(world);
            // If the point is at infinity it's not clear what to do. It would be best to skip it then the color
            // array would be out of sync. Let's just throw it far far away then.
            if (world.w == 0.0)
                continue;

            double x = world.x / world.w;
            double y = world.y / world.w;
            double z = world.z / world.w;

            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
                continue;

            cloudXyz.add(new Point3D_F64(world.x / world.w, world.y / world.w, world.z / world.w));

        }

        // Save to Internal Storage (Replacing the Desktop Viewer)
        // On Android, use the app's internal files directory
        File outputFile = new File(workDirectory, "saved_cloud.ply");

        try (FileOutputStream out = new FileOutputStream(outputFile)) {
            // Create a list of Point3dRgbI_F64 for saving
            List<Point3dRgbI_F64> combinedCloud = new ArrayList<>();

            for (int i = 0; i < scene.points.size; i++) {
                Point3D_F64 p = cloudXyz.get(i);

                int color = rgb.get(i);
                int r = (color >> 16) & 0xFF;
                int g = (color >> 8) & 0xFF;
                int b = color & 0xFF;

                r = Math.min(255, Math.max(0, r));
                g = Math.min(255, Math.max(0, g));
                b = Math.min(255, Math.max(0, b));

                int packedRgb = (r << 16) | (g << 8) | b;

                if (Double.isFinite(p.x) &&
                        Double.isFinite(p.y) &&
                        Double.isFinite(p.z)) {
                    combinedCloud.add(new Point3dRgbI_F64(p.x, p.y, p.z, packedRgb));
                }

                System.out.printf(
                        "XYZ=(%.2f, %.2f, %.2f) RGB=%d%n",
                        p.x, p.y, p.z, packedRgb
                );
            }

            PointCloudIO.save3D(PointCloudIO.Format.PLY, PointCloudReader.wrapF64RGB(combinedCloud), true, out);
            System.out.println("Cloud saved to: " + outputFile.getAbsolutePath());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}