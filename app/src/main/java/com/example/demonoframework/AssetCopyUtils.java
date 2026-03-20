package com.example.demonoframework;

import android.content.Context;
import android.content.res.AssetManager;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class AssetCopyUtils {
    public static void copyAssetsFolderToExternal(Context context, String assetFolderName) throws IOException {
        File externalDir = context.getExternalFilesDir(null);
        AssetManager assetManager = context.getAssets();
        copyAssetDirRecursive(assetManager, assetFolderName, new File(externalDir, assetFolderName));
    }

    private static void copyAssetDirRecursive(AssetManager assetManager, String assetDir, File outDir) throws IOException {
        String[] assets = assetManager.list(assetDir);
        if (assets == null || assets.length == 0) {
            copyAssetFile(assetManager, assetDir, outDir);
        } else {
            if (!outDir.exists()) outDir.mkdirs();
            for (String asset : assets) {
                String assetPath = assetDir + "/" + asset;
                File outFile = new File(outDir, asset);
                copyAssetDirRecursive(assetManager, assetPath, outFile);
            }
        }
    }

    private static void copyAssetFile(AssetManager assetManager, String assetPath, File outFile) throws IOException {
        if (outFile.exists()) return;
        try (InputStream is = assetManager.open(assetPath); FileOutputStream os = new FileOutputStream(outFile)) {
            byte[] buffer = new byte[4096];
            int len;
            while ((len = is.read(buffer)) != -1) {
                os.write(buffer, 0, len);
            }
        }
    }
}

