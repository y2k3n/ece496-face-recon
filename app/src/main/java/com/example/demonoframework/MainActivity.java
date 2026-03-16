package com.example.demonoframework;

import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.annotation.NonNull;

import android.content.ContentValues;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.Manifest;
import android.content.pm.PackageManager;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.example.demonoframework.databinding.ActivityMainBinding;
import com.google.common.util.concurrent.ListenableFuture;
import androidx.core.app.ActivityCompat;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.NormalizedKeypoint;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.core.Delegate;
import com.google.mediapipe.tasks.vision.core.RunningMode;

//import android.graphics.Bitmap;


import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;


public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_CAMERA_PERMISSION = 1001;

    // Used to load the 'demonoframework' library on application startup.
    static {
        System.loadLibrary("demonoframework");
    }

    private ActivityMainBinding binding;

    PreviewView previewView;
    private TextView txtCentral;
    private TextView txtStatus;
    Button btnStart;

    private ImageCapture imageCapture;

    SensorManager sensorManager;
    Sensor gyro, acc, rot;


    private String baseName = null;

    private float ax = 0, ay = 0, az = 0;
    private float gx = 0, gy = 0, gz = 0;
    private float rx = 0, ry = 0, rz = 0, rw = 0;

    // store saved photo URIs and per-photo IMU snapshot
    private final List<Uri> photoUris = new ArrayList<>();
    private final List<String> photoImuEntries = new ArrayList<>();
    private int photoCounter = 0;

    private FrameLayout overlayContainer = null;
    // Overlay for data processing
    private FrameLayout nextStepOverlay = null;

    // CameraProvider reference for closing camera preview later
    private ProcessCameraProvider cameraProviderRef = null;

    // FaceLandmarker instance for face detection
    private FaceLandmarker faceLandmarker = null;

    /**
     * A native method that is implemented by the 'demonoframework' native library,
     * which is packaged with this application.
     */
    public native String stringFromJNI();


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        previewView = findViewById(R.id.previewView);
        txtCentral = findViewById(R.id.txtSysStatus);
        txtStatus = findViewById(R.id.txtIMUStatus);
        btnStart = findViewById(R.id.btnCapture);

        // Show initial capture prompt
        if (txtCentral != null) {
            txtCentral.setText("Click the button to capture frame 1");
        }

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        acc = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        rot = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);

        // request camera permission if needed, then start preview
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCameraXPreview();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
        }

        btnStart.setOnClickListener(v -> takePhoto());

        initFaceLandmarker();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCameraXPreview();
            } else {
                runOnUiThread(() -> txtCentral.setText("Camera permission required."));
            }
        }
    }


    private void startCameraXPreview() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                cameraProviderRef = cameraProvider; // Save reference for later closing

                Preview preview = new Preview.Builder()
                        .build();

                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build();

                CameraSelector cameraSelector = new CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                        .build();

                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture);

            } catch (Exception e) {
                Log.e("CameraX", "Error starting CameraX preview", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }


    @Override
    protected void onResume() {
        super.onResume();
        if (acc != null)
            sensorManager.registerListener(imuEventListener, acc, SensorManager.SENSOR_DELAY_GAME);
        if (gyro != null)
            sensorManager.registerListener(imuEventListener, gyro, SensorManager.SENSOR_DELAY_GAME);
        if (rot != null)
            sensorManager.registerListener(imuEventListener, rot, SensorManager.SENSOR_DELAY_GAME);
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(imuEventListener);
    }

    private final SensorEventListener imuEventListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {

            if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                ax = event.values[0];
                ay = event.values[1];
                az = event.values[2];
            } else if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
                gx = event.values[0];
                gy = event.values[1];
                gz = event.values[2];
            } else if (event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
                // rotation vector values length may be 3 or 4; guard against index issues
                rx = event.values.length > 0 ? event.values[0] : 0f;
                ry = event.values.length > 1 ? event.values[1] : 0f;
                rz = event.values.length > 2 ? event.values[2] : 0f;
                rw = event.values.length > 3 ? event.values[3] : 0f;
            }

            // we keep current IMU values updated continuously; snapshots will be taken on shutter

            runOnUiThread(() -> {
                String statStr = String.format(Locale.US,
                        "ACC: %.2f, %.2f, %.2f\nGYR: %.2f, %.2f, %.2f\nROT: %.2f, %.2f, %.2f, %.2f",
                        ax, ay, az, gx, gy, gz, rx, ry, rz, rw);
                if (txtStatus != null) {
                    txtStatus.setText(statStr);
                }
            });
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };

    /**
     * Initialize FaceLandmarker from assets. Model file should be in app/src/main/assets/face_landmarker.task
     */
    private void initFaceLandmarker() {
        try {
            // Copy model from assets to cache dir (required by MediaPipe)
            String modelAssetName = "face_landmarker.task";
            File modelFile = new File(getCacheDir(), modelAssetName);
            if (!modelFile.exists()) {
                InputStream is = getAssets().open(modelAssetName);
                FileOutputStream os = new FileOutputStream(modelFile);
                byte[] buffer = new byte[4096];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    os.write(buffer, 0, read);
                }
                os.close();
                is.close();
            }

            BaseOptions baseOptions = BaseOptions.builder()
                    .setDelegate(Delegate.CPU)
                    .setModelAssetPath(modelFile.getAbsolutePath())
                    .build();

            FaceLandmarker.FaceLandmarkerOptions options = FaceLandmarker.FaceLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setMinFaceDetectionConfidence(0.1f)
                    .setMinFacePresenceConfidence(0.1f)
                    .setMinTrackingConfidence(0.1f)
                    .setNumFaces(1)
                    .setRunningMode(RunningMode.IMAGE)
                    .build();

            faceLandmarker = FaceLandmarker.createFromOptions(this, options);
        } catch (Exception e) {
            e.printStackTrace();
            runOnUiThread(() -> txtCentral.setText("Failed to initialize FaceLandmarker: " + e.getMessage()));
        }
    }


    private void takePhoto() {
        if (imageCapture == null) return;

        // Show which frame is being captured
        runOnUiThread(() -> txtCentral.setText("Capturing frame " + (photoCounter + 1)));
        // Prompt saving
        runOnUiThread(() -> txtCentral.setText("Saving frame " + (photoCounter + 1) + "...") );

        if (baseName == null) {
            baseName = "FaceCapture_" + System.currentTimeMillis();
            photoCounter = 0;
            photoUris.clear();
            photoImuEntries.clear();
        }

        String photoFileName = baseName + "_Cam_" + (photoCounter + 1) + ".jpg";

        ContentValues contentValues = new ContentValues();
        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, photoFileName);
        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
        contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + java.io.File.separator + "FaceCaptures");

        ImageCapture.OutputFileOptions outputOptions = new ImageCapture.OutputFileOptions.Builder(
                getContentResolver(), MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                .build();

        // Capture snapshot of IMU at this moment
        long timestamp = System.currentTimeMillis();
        String imuSnapshot = String.format(Locale.US,
                "%d,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f",
                timestamp, ax, ay, az, gx, gy, gz, rx, ry, rz, rw);

        imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                        Uri savedUri = outputFileResults.getSavedUri();
                        if (savedUri != null) {
                            photoUris.add(savedUri);
                            photoImuEntries.add(imuSnapshot);
                            photoCounter++;

                            runOnUiThread(() -> txtCentral.setText("Frame " + photoCounter + " saved"));

                            // Show captured image overlay with options
                            runOnUiThread(() -> showCapturedOverlay(savedUri));
                        } else {
                            runOnUiThread(() -> txtCentral.setText("Frame saved but URI null"));
                        }
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exception) {
                        Log.e("ImageCapture", "Photo capture failed: " + exception.getMessage(), exception);
                        runOnUiThread(() -> txtCentral.setText("Capture failed: " + exception.getMessage()));
                    }
                });
    }

    private void showCapturedOverlay(Uri imageUri) {
        // Create overlay container if not exists
        if (overlayContainer == null) {
            overlayContainer = new FrameLayout(this);
            ViewGroup root = (ViewGroup) binding.getRoot();
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            root.addView(overlayContainer, lp);
        }
        overlayContainer.removeAllViews();
        overlayContainer.setBackgroundColor(0x99000000); // semi-transparent black

        ImageView imageView = new ImageView(this);
        FrameLayout.LayoutParams ivLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        ivLp.gravity = Gravity.CENTER;
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imageView.setImageURI(imageUri);
        overlayContainer.addView(imageView, ivLp);

        // Buttons container
        FrameLayout buttonsContainer = new FrameLayout(this);
        FrameLayout.LayoutParams btnContLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnContLp.gravity = Gravity.BOTTOM;
        btnContLp.setMargins(20, 20, 20, 40);

        // Retake button
        Button btnRetake = new Button(this);
        btnRetake.setText("Retake");
        FrameLayout.LayoutParams retakeLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        retakeLp.gravity = Gravity.BOTTOM | Gravity.START;
        retakeLp.setMargins(40, 0, 0, 40);
        btnRetake.setOnClickListener(v -> {
            // Delete last saved photo and remove entry
            if (!photoUris.isEmpty()) {
                Uri last = photoUris.remove(photoUris.size() - 1);
                photoImuEntries.remove(photoImuEntries.size() - 1);
                try {
                    getContentResolver().delete(last, null, null);
                } catch (Exception e) {
                    Log.w("IMU", "Failed to delete retaken image: " + e.getMessage());
                }
                photoCounter = Math.max(0, photoCounter - 1);
            }
            overlayContainer.removeAllViews();
            overlayContainer.setVisibility(View.GONE);
            runOnUiThread(() -> binding.btnCapture.setVisibility(View.VISIBLE));
            runOnUiThread(() -> txtCentral.setText("Retaking frame " + (photoCounter + 1)));
        });

        // Continue (keep and continue capturing)
        Button btnContinue = new Button(this);
        btnContinue.setText("Continue");
        FrameLayout.LayoutParams contLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        contLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        contLp.setMargins(0, 0, 0, 40);
        btnContinue.setOnClickListener(v -> {
            overlayContainer.removeAllViews();
            overlayContainer.setVisibility(View.GONE);
            runOnUiThread(() -> binding.btnCapture.setVisibility(View.VISIBLE));
            runOnUiThread(() -> txtCentral.setText("Capturing frame " + (photoCounter + 1)));
        });

        // Done (finish session)
        Button btnDone = new Button(this);
        btnDone.setText("Done");
        FrameLayout.LayoutParams doneLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        doneLp.gravity = Gravity.BOTTOM | Gravity.END;
        doneLp.setMargins(0, 0, 40, 40);
        btnDone.setOnClickListener(v -> {
            overlayContainer.removeAllViews();
            overlayContainer.setVisibility(View.GONE);
            runOnUiThread(() -> txtCentral.setText("Saving IMU data..."));
            finishCaptures();
        });

        buttonsContainer.addView(btnRetake, retakeLp);
        buttonsContainer.addView(btnContinue, contLp);
        buttonsContainer.addView(btnDone, doneLp);

        overlayContainer.addView(buttonsContainer, btnContLp);
        overlayContainer.setVisibility(View.VISIBLE);

        runOnUiThread(() -> binding.btnCapture.setVisibility(View.GONE));
    }

    private void finishCaptures() {
        if (baseName == null) return;

//        String imuFileName = baseName + "_IMU.csv";
//
//        ContentValues contentValues = new ContentValues();
//        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, imuFileName);
//        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "text/csv");
//        contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH,
//                Environment.DIRECTORY_DOWNLOADS + java.io.File.separator + "FaceCaptures");

//        Uri collectionUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
//        Uri imuUri = getContentResolver().insert(collectionUri, contentValues);

//        if (imuUri == null) {
//            runOnUiThread(() -> txtCentral.setText("IMU Save Failed: Failed to create MediaStore entry."));
//            photoUris.clear();
//            photoImuEntries.clear();
//            baseName = null;
//            return;
//        }

//        OutputStream outputStream = null;
//        try {
//            outputStream = getContentResolver().openOutputStream(imuUri);
//            if (outputStream == null) throw new IOException("Failed to open output stream");
//            try (PrintWriter writer = new PrintWriter(outputStream)) {
//                writer.println("photo_index,photo_uri,timestamp,ax,ay,az,gx,gy,gz,rx,ry,rz,rw");
//                for (int i = 0; i < photoImuEntries.size(); i++) {
//                    String imuEntry = photoImuEntries.get(i);
//                    Uri photoUri = photoUris.size() > i ? photoUris.get(i) : null;
//                    String line = String.format(Locale.US, "%d,%s,%s",
//                            i + 1,
//                            photoUri != null ? photoUri.toString() : "",
//                            imuEntry);
//                    writer.println(line);
//                }
//                writer.flush();
//            }

            runOnUiThread(() -> {
                txtCentral.setText("Saved " + photoUris.size() + " photos and IMU data");
                // turn off camera preview to indicate session end
                if (cameraProviderRef != null) {
                    try {
                        cameraProviderRef.unbindAll();
                    } catch (Exception e) {
                        Log.w("CameraX", "Failed to unbind camera: " + e.getMessage());
                    }
                }
                // Hide preview and IMU UI
                if (previewView != null) previewView.setVisibility(View.GONE);
//                if (txtCentral != null) txtCentral.setVisibility(View.GONE);
                if (txtStatus != null) txtStatus.setVisibility(View.GONE);
                if (btnStart != null) btnStart.setVisibility(View.GONE);
                showNextStepOverlay();
            });
//        } catch (IOException e) {
//            try {
//                getContentResolver().delete(imuUri, null, null);
//            } catch (Exception ex) {
//                Log.w("IMU", "Failed to delete incomplete IMU file: " + ex.getMessage());
//            }
//            runOnUiThread(() -> txtCentral.setText("IMU Save Failed: " + e.getMessage()));
//        }

        // Reset session
//        photoUris.clear();
//        photoImuEntries.clear();
//        baseName = null;
//        photoCounter = 0;
    }

    // Show overlay with "Continue to data processing" button
    private void showNextStepOverlay() {
        if (nextStepOverlay == null) {
            nextStepOverlay = new FrameLayout(this);
            ViewGroup root = (ViewGroup) binding.getRoot();
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            root.addView(nextStepOverlay, lp);
        }
        nextStepOverlay.removeAllViews();
        nextStepOverlay.setBackgroundColor(0x99000000); // semi-transparent black

        Button btnNext = new Button(this);
        btnNext.setText("Continue to data processing");
        FrameLayout.LayoutParams btnLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLp.gravity = Gravity.CENTER;
        btnNext.setLayoutParams(btnLp);
        btnNext.setOnClickListener(v -> {
            nextStepOverlay.removeAllViews();
            nextStepOverlay.setVisibility(View.GONE);
            runOnUiThread(() -> txtCentral.setText("Processing face landmarks..."));
            // Run face landmark detection in background
            new Thread(() -> {
                if (faceLandmarker == null) {
                    runOnUiThread(() -> txtCentral.setText("FaceLandmarker not initialized."));
                    return;
                }
                List<FaceLandmarkerResult> results = detectAllPhotoLandmarks(faceLandmarker);
                StringBuilder sb = new StringBuilder();
                sb.append("Face detection results:\n");
                sb.append("Size = ").append(results.size()).append("\n\n");
                for (int i = 0; i < results.size(); i++) {
                    FaceLandmarkerResult r = results.get(i);
                    int count = (r != null && r.faceLandmarks() != null) ? r.faceLandmarks().size() : 0;
                    sb.append("Photo ").append(i + 1).append(": ").append(count).append(" face(s)\n");
                }
                runOnUiThread(() -> txtCentral.setText(sb.toString()));
                filterFramesWithNoFace(results);
                saveLandmarksToPtsFiles(results, photoUris);
            }).start();
        });

        nextStepOverlay.addView(btnNext, btnLp);
        nextStepOverlay.setVisibility(View.VISIBLE);

        runOnUiThread(() -> binding.btnCapture.setVisibility(View.GONE));
    }

    /**
     * Detect face landmarks for all saved photos using MediaPipe FaceLandmarker.
     * @param faceLandmarker Initialized FaceLandmarker instance
     * @return List<FaceLandmarkerResult>, one detection result for each photo (null if detection fails)
     */
    public List<FaceLandmarkerResult> detectAllPhotoLandmarks(FaceLandmarker faceLandmarker) {
        // print to log for debugging
        Log.d("FaceLandmarker", "Starting landmark detection for " + photoUris.size() + " photos");
        List<FaceLandmarkerResult> results = new ArrayList<>();
        for (Uri uri : photoUris) {
            try {
                // Read image as Bitmap
                InputStream inputStream = getContentResolver().openInputStream(uri);
                Bitmap bitmap = BitmapFactory.decodeStream(inputStream);
                if (inputStream != null) inputStream.close();
                if (bitmap == null) {
                    results.add(null);
                    continue;
                }
                // Convert to MPImage
                MPImage mpImage = new BitmapImageBuilder(bitmap).build();
                // Detect landmarks
                FaceLandmarkerResult result = faceLandmarker.detect(mpImage);
                results.add(result);
            } catch (Exception e) {
                e.printStackTrace();
                results.add(null);
            }
        }
        return results;
    }

    private void filterFramesWithNoFace(List<FaceLandmarkerResult> results) {
        for (int i = results.size() - 1; i >= 0; i--) {
            FaceLandmarkerResult r = results.get(i);
            boolean hasFace = (r != null && r.faceLandmarks() != null && r.faceLandmarks().size() != 1);
            if (!hasFace) {
                results.remove(i);
                if (i < photoUris.size()) photoUris.remove(i);
                if (i < photoImuEntries.size()) photoImuEntries.remove(i);
            }
        }
    }

    /**
     * 保存每个 FaceLandmarkerResult 的 landmarks 到 .pts 文件，存储到 Downloads/FaceCaptures 文件夹
     */
    private void saveLandmarksToPtsFiles(List<FaceLandmarkerResult> results, List<Uri> uris) {
        if (baseName == null) return;
        for (int i = 0; i < results.size(); i++) {
            FaceLandmarkerResult result = results.get(i);
            if (result == null || result.faceLandmarks() == null || result.faceLandmarks().isEmpty()) continue;

            List<List<NormalizedLandmark>> faces = result.faceLandmarks();
            // only keep the first face's landmarks
            List<NormalizedLandmark> landmarks = faces.get(0);
            if (landmarks == null || landmarks.isEmpty()) continue;

            String ptsFileName = baseName + "_Cam_" + (i + 1) + ".pts";

            // 构造pts内容
            StringBuilder sb = new StringBuilder();
            sb.append("version: 1\n");
            sb.append("n_points: ").append(landmarks.size()).append("\n");
            sb.append("{\n");
            for (NormalizedLandmark lm : landmarks) {
                sb.append(String.format(Locale.US, "%f %f\n", lm.x(), lm.y()));
            }
            sb.append("}\n");

            // 保存到 Downloads/FaceCaptures
            ContentValues contentValues = new ContentValues();
            contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, ptsFileName);
            contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
            contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + File.separator + "FaceCaptures");
            Uri collectionUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            Uri ptsUri = getContentResolver().insert(collectionUri, contentValues);
            if (ptsUri == null) {
                Log.w("PTS", "Failed to create MediaStore entry for " + ptsFileName);
                continue;
            }
            try (java.io.OutputStream outputStream = getContentResolver().openOutputStream(ptsUri)) {
                if (outputStream == null) throw new IOException("Failed to open output stream");
                outputStream.write(sb.toString().getBytes());
                outputStream.flush();
            } catch (IOException e) {
                Log.w("PTS", "Failed to save pts file: " + ptsFileName + ", " + e.getMessage());
            }
        }
    }

}
