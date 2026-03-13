package com.example.demonoframework;

import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.MediaStoreOutputOptions;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import android.content.ContentValues;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.example.demonoframework.databinding.ActivityMainBinding;
import com.google.common.util.concurrent.ListenableFuture;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import java.io.File;
import java.io.IOException;

import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;


// OpenCV imports
import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDMatch;
import org.opencv.core.MatOfDouble;
import org.opencv.core.MatOfKeyPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.MatOfPoint3f;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import org.opencv.calib3d.Calib3d;
import org.opencv.features2d.ORB;
import org.opencv.features2d.BFMatcher;

import android.widget.ImageView;

// BoofCV
import boofcv.android.ConvertBitmap;
import boofcv.struct.image.GrayU8;

public class MainActivity extends AppCompatActivity {

    // Used to load the 'demonoframework' library on application startup.
    static {
        System.loadLibrary("demonoframework");
    }

    private ActivityMainBinding binding;

    private ImageView imageView;

    PreviewView previewView;
    private TextView txtCentral;
    private TextView txtStatus;
    Button btnStart;

    private VideoCapture<Recorder> videoCapture;
    private Recording currentRecording;

    SensorManager sensorManager;
    Sensor gyro, acc, rot;


    boolean recording = false;
    private List<String> imuDataList = new ArrayList<>();
    private String baseName = null;

    private float ax = 0, ay = 0, az = 0;
    private float gx = 0, gy = 0, gz = 0;
    private float rx = 0, ry = 0, rz = 0, rw = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        imageView = findViewById(R.id.imageView);

        previewView = findViewById(R.id.previewView);
        txtCentral = findViewById(R.id.txtIMU);
        txtStatus = findViewById(R.id.txtStatus);
        btnStart = findViewById(R.id.btnStart);


        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        acc = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        rot = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);

        startCameraXPreview();

        btnStart.setOnClickListener(v -> {
            if (recording) {
                btnStart.setText(R.string.start_collection);
                stopRecording();
            } else {
                btnStart.setText(R.string.stop_collection);
                startRecording();
            }
            recording = !recording;
            if (!recording) startRecording();
            else stopRecording();
        });

        Button button = findViewById(R.id.supabutton);
        button.setOnClickListener(v -> {
            Log.d("BUTTONS", "Activating SfM test");
            ReconstructSfM();
        });
    }

    /**
     * A native method that is implemented by the 'demonoframework' native library,
     * which is packaged with this application.
     */
    public native String stringFromJNI();

    private void startCameraXPreview() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder()
                        .build();

                Recorder recorder = new Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(Quality.FHD))
                        .build();
                videoCapture = VideoCapture.withOutput(recorder);

                CameraSelector cameraSelector = new CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                        .build();

                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, videoCapture);

            } catch (Exception e) {
                Log.e("CameraX", "Error starting CameraX preview", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }


    @Override
    protected void onResume() {
        super.onResume();
        if (acc != null) sensorManager.registerListener(imuEventListener, acc, SensorManager.SENSOR_DELAY_GAME);
        if (gyro != null) sensorManager.registerListener(imuEventListener, gyro, SensorManager.SENSOR_DELAY_GAME);
        if (rot != null) sensorManager.registerListener(imuEventListener, rot, SensorManager.SENSOR_DELAY_GAME);
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(imuEventListener);
    }

    private final SensorEventListener imuEventListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            long timestamp = System.currentTimeMillis();

            if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                ax = event.values[0]; ay = event.values[1]; az = event.values[2];
            } else if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
                gx = event.values[0]; gy = event.values[1]; gz = event.values[2];
            } else if (event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
                rx = event.values[0]; ry = event.values[1]; rz = event.values[2]; rw = event.values[3];
            }

            if (recording) {
                String logEntry = String.format(Locale.US,
                        "%d,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f\n",
                        timestamp, ax, ay, az, gx, gy, gz, rx, ry, rz, rw);
                imuDataList.add(logEntry);
            }

            runOnUiThread(() -> {
                String statStr = String.format(Locale.US,
                        "ACC: %.2f, %.2f, %.2f\nGYR: %.2f, %.2f, %.2f\nROT: %.2f, %.2f, %.2f, %2f",
                        ax, ay, az, gx, gy, gz, rx, ry, rz, rw);
                if (txtStatus != null) {
                    txtStatus.setText(statStr);
                }
            });
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) { }
    };

    private void startRecording() {
        runOnUiThread(() -> txtCentral.setText("Start"));

        if (videoCapture == null) return;
        imuDataList.clear();

        baseName = "FaceCapture_" + System.currentTimeMillis();
        String videoFileName = baseName + "_Cam";

        ContentValues contentValues = new ContentValues();
        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, videoFileName);
        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");

        contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + File.separator + "FaceCaptures");

        MediaStoreOutputOptions outputOptions = new MediaStoreOutputOptions.Builder(
                getContentResolver(), MediaStore.Downloads.EXTERNAL_CONTENT_URI)
                .setContentValues(contentValues)
                .build();

        currentRecording = videoCapture.getOutput().prepareRecording(this, outputOptions)
                .start(ContextCompat.getMainExecutor(this), this::handleRecordingEvent);
    }

    private void stopRecording() {
        if (currentRecording != null) {
            currentRecording.stop();
        }
    }

    private void handleRecordingEvent(VideoRecordEvent event) {
        if (event instanceof VideoRecordEvent.Start) {
            runOnUiThread(() -> txtCentral.setText("Recording..."));
        } else if (event instanceof VideoRecordEvent.Finalize) {
            VideoRecordEvent.Finalize finalizeEvent = (VideoRecordEvent.Finalize) event;
            if (!finalizeEvent.hasError()) {
                runOnUiThread(() -> txtCentral.setText("Video saved. Saving IMU data..."));
                saveImuData();
            } else {
                runOnUiThread(() -> txtCentral.setText("Recording failed: " + finalizeEvent.getError()));
                currentRecording = null;
            }
        }
    }


    private void saveImuData() {
        String imuFileName = baseName + "_IMU";

        ContentValues contentValues = new ContentValues();
        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, imuFileName);
        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "text/csv");

        contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + File.separator + "FaceCaptures");

        Uri collectionUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        Uri imuUri = getContentResolver().insert(collectionUri, contentValues);

        if (imuUri == null) {
            runOnUiThread(() -> txtCentral.setText("IMU Save Failed: Failed to create MediaStore entry."));
            imuDataList.clear();
            return;
        }

        try (OutputStream outputStream = getContentResolver().openOutputStream(imuUri);
            PrintWriter writer = new PrintWriter(outputStream)) {

            writer.println("timestamp,ax,ay,az,gx,gy,gz,rx,ry,rz,rw");
            for (String entry : imuDataList) {
                writer.print(entry);
            }
            writer.flush();

            runOnUiThread(() -> txtCentral.setText("Video and IMU data saved to: Downloads/FaceCaptures/" + baseName + "*"));
        } catch (IOException e) {
            getContentResolver().delete(imuUri, null, null);
            runOnUiThread(() -> txtCentral.setText("IMU Save Failed: " + e.getMessage()));
        }
        imuDataList.clear();
    }

    // Function to execute SfM Reconstruction
    private void ReconstructSfM() {

        // Compute SfM Sparse Reconstruction
        var sparse = new MultiViewSparseReconstruction(this);
        sparse.compute("test.mp4", true);

        // Compute SfM Dense Reconstruction
        var dense = new MultiViewDenseReconstruction(sparse);
        dense.compute();

        System.out.println("### SfM Reconstruction Completed ###");
    }




}