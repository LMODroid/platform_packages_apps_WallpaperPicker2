/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.wallpaper.module;

import static android.app.WallpaperManager.FLAG_LOCK;
import static android.app.WallpaperManager.FLAG_SYSTEM;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.os.AsyncTask;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import androidx.annotation.Nullable;

import com.android.wallpaper.asset.Asset;
import com.android.wallpaper.asset.Asset.BitmapReceiver;
import com.android.wallpaper.asset.Asset.DimensionsReceiver;
import com.android.wallpaper.asset.BitmapUtils;
import com.android.wallpaper.asset.StreamableAsset;
import com.android.wallpaper.asset.StreamableAsset.StreamReceiver;
import com.android.wallpaper.compat.WallpaperManagerCompat;
import com.android.wallpaper.model.WallpaperInfo;
import com.android.wallpaper.module.BitmapCropper.Callback;
import com.android.wallpaper.util.BitmapTransformer;
import com.android.wallpaper.util.DisplayUtils;
import com.android.wallpaper.util.ScreenSizeCalculator;
import com.android.wallpaper.util.WallpaperCropUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Concrete implementation of WallpaperPersister which actually sets wallpapers to the system via
 * the WallpaperManager.
 */
public class DefaultWallpaperPersister implements WallpaperPersister {

    private static final int DEFAULT_COMPRESS_QUALITY = 100;
    private static final String TAG = "WallpaperPersister";

    private final Context mAppContext; // The application's context.
    // Context that accesses files in device protected storage
    private final WallpaperManager mWallpaperManager;
    private final WallpaperManagerCompat mWallpaperManagerCompat;
    private final WallpaperPreferences mWallpaperPreferences;
    private final WallpaperChangedNotifier mWallpaperChangedNotifier;
    private final DisplayUtils mDisplayUtils;

    private WallpaperInfo mWallpaperInfoInPreview;

    @SuppressLint("ServiceCast")
    public DefaultWallpaperPersister(Context context) {
        mAppContext = context.getApplicationContext();
        // Retrieve WallpaperManager using Context#getSystemService instead of
        // WallpaperManager#getInstance so it can be mocked out in test.
        Injector injector = InjectorProvider.getInjector();
        mWallpaperManager = (WallpaperManager) context.getSystemService(Context.WALLPAPER_SERVICE);
        mWallpaperManagerCompat = injector.getWallpaperManagerCompat(context);
        mWallpaperPreferences = injector.getPreferences(context);
        mWallpaperChangedNotifier = WallpaperChangedNotifier.getInstance();
        mDisplayUtils = injector.getDisplayUtils(context);
    }

    @Override
    public void setIndividualWallpaper(final WallpaperInfo wallpaper, Asset asset, boolean highQuality,
            @Nullable Rect cropRect, float scale, @Destination final int destination,
            final SetWallpaperCallback callback) {
        // Set wallpaper without downscaling directly from an input stream if there's no crop rect
        // specified by the caller and the asset is streamable.
        if (cropRect == null && asset instanceof StreamableAsset) {
            ((StreamableAsset) asset).fetchInputStream(new StreamReceiver() {
                @Override
                public void onInputStreamOpened(@Nullable InputStream inputStream) {
                    if (inputStream == null) {
                        callback.onError(null /* throwable */);
                        return;
                    }
                    setIndividualWallpaper(wallpaper, inputStream, destination, callback);
                }
            });
            return;
        }

        // If no crop rect is specified but the wallpaper asset is not streamable, then fall back to
        // using the device's display size, unless high quality is enabled.
        if (cropRect == null) {
            if (highQuality) {
                asset.decodeRawDimensions(null, new DimensionsReceiver() {
                    @Override
                    public void onDimensionsDecoded(@Nullable Point dimensions) {
                        if (dimensions == null) {
                            callback.onError(null);
                            return;
                        }

                        asset.decodeBitmap(dimensions.x, dimensions.y, new BitmapReceiver() {
                            @Override
                            public void onBitmapDecoded(@Nullable Bitmap bitmap) {
                                if (bitmap == null) {
                                    callback.onError(null /* throwable */);
                                    return;
                                }
                                setIndividualWallpaper(wallpaper, bitmap, destination, callback);
                            }
                        });
                    }
                });
            } else {
                Display display = ((WindowManager) mAppContext.getSystemService(Context.WINDOW_SERVICE))
                        .getDefaultDisplay();
                Point screenSize = ScreenSizeCalculator.getInstance().getScreenSize(display);
                asset.decodeBitmap(screenSize.x, screenSize.y, new BitmapReceiver() {
                    @Override
                    public void onBitmapDecoded(@Nullable Bitmap bitmap) {
                        if (bitmap == null) {
                            callback.onError(null /* throwable */);
                            return;
                        }
                        setIndividualWallpaper(wallpaper, bitmap, destination, callback);
                    }
                });
            }
            return;
        }

        BitmapCropper bitmapCropper = InjectorProvider.getInjector().getBitmapCropper();
        bitmapCropper.cropAndScaleBitmap(asset, scale, cropRect, false, new Callback() {
            @Override
            public void onBitmapCropped(Bitmap croppedBitmap) {
                setIndividualWallpaper(wallpaper, croppedBitmap, destination, callback);
            }

            @Override
            public void onError(@Nullable Throwable e) {
                callback.onError(e);
            }
        });
    }

    @Override
    public void setIndividualWallpaperWithPosition(Activity activity, WallpaperInfo wallpaper, Asset asset,
            @Destination int destination, @WallpaperPosition int wallpaperPosition, SetWallpaperCallback callback) {
        Display display = ((WindowManager) mAppContext.getSystemService(Context.WINDOW_SERVICE))
                .getDefaultDisplay();
        Point screenSize = ScreenSizeCalculator.getInstance().getScreenSize(display);

        asset.decodeRawDimensions(activity, new DimensionsReceiver() {
            @Override
            public void onDimensionsDecoded(@Nullable Point dimensions) {
                if (dimensions == null) {
                    callback.onError(null);
                    return;
                }

                switch (wallpaperPosition) {
                    // Crop out screen-sized center portion of the source image if it's larger
                    // than the screen
                    // in both dimensions. Otherwise, decode the entire bitmap and fill the space
                    // around it to fill a new screen-sized bitmap with plain black pixels.
                    case WALLPAPER_POSITION_CENTER:
                        setIndividualWallpaperWithCenterPosition(
                                wallpaper, asset, destination, dimensions, screenSize, callback);
                        break;

                    // Crop out a screen-size portion of the source image and set the bitmap region.
                    case WALLPAPER_POSITION_CENTER_CROP:
                        setIndividualWallpaperWithCenterCropPosition(
                                wallpaper, asset, destination, dimensions, screenSize, callback);
                        break;

                    // Decode full bitmap sized for screen and stretch it to fill the screen
                    // dimensions.
                    case WALLPAPER_POSITION_STRETCH:
                        asset.decodeBitmap(screenSize.x, screenSize.y, new BitmapReceiver() {
                            @Override
                            public void onBitmapDecoded(@Nullable Bitmap bitmap) {
                                setIndividualWallpaperStretch(wallpaper, bitmap,
                                        screenSize /* stretchSize */,
                                        destination, callback);
                            }
                        });
                        break;

                    case WALLPAPER_POSITION_TEXTURE:
                        setIndividualWallpaperWithTexturePosition(
                                wallpaper, asset, destination, dimensions, screenSize, callback);
                        break;

                    default:
                        Log.e(TAG, "Unsupported wallpaper position option specified: "
                                + wallpaperPosition);
                        callback.onError(null);
                }
            }
        });
    }

    /**
     * Sets an individual wallpaper to static wallpaper destinations with a center
     * wallpaper position.
     *
     * @param wallpaper  The wallpaper model object representing the wallpaper to be set.
     * @param asset      The wallpaper asset that should be used to set a wallpaper.
     * @param dimensions Raw dimensions of the wallpaper asset.
     * @param screenSize Dimensions of the device screen.
     * @param callback   Callback used to notify original caller of wallpaper set operation result.
     */
    private void setIndividualWallpaperWithCenterPosition(WallpaperInfo wallpaper, Asset asset,
            @Destination int destination, Point dimensions, Point screenSize, SetWallpaperCallback callback) {
        if (dimensions.x >= screenSize.x && dimensions.y >= screenSize.y) {
            Rect cropRect = new Rect(
                    (dimensions.x - screenSize.x) / 2,
                    (dimensions.y - screenSize.y) / 2,
                    dimensions.x - ((dimensions.x - screenSize.x) / 2),
                    dimensions.y - ((dimensions.y - screenSize.y) / 2));
            asset.decodeBitmapRegion(cropRect, screenSize.x, screenSize.y, false,
                    bitmap -> setIndividualWallpaper(wallpaper, bitmap,
                            destination, callback));
        } else {
            // Decode the full bitmap and pass with the screen size as a fill rect.
            asset.decodeBitmap(dimensions.x, dimensions.y, new BitmapReceiver() {
                @Override
                public void onBitmapDecoded(@Nullable Bitmap bitmap) {
                    if (bitmap == null) {
                        callback.onError(null);
                        return;
                    }

                    setIndividualWallpaperFill(wallpaper, bitmap, screenSize /* fillSize */,
                            destination, callback);
                }
            });
        }
    }

    /**
     * Sets an individual wallpaper to static wallpaper destinations with a center
     * cropped wallpaper position.
     *
     * @param wallpaper  The wallpaper model object representing the wallpaper to be set.
     * @param asset      The wallpaper asset that should be used to set a wallpaper.
     * @param dimensions Raw dimensions of the wallpaper asset.
     * @param screenSize Dimensions of the device screen.
     * @param callback   Callback used to notify original caller of wallpaper set operation result.
     */
    private void setIndividualWallpaperWithCenterCropPosition(WallpaperInfo wallpaper, Asset asset,
            @Destination int destination, Point dimensions, Point screenSize, SetWallpaperCallback callback) {
        float scale = Math.max((float) screenSize.x / dimensions.x,
                (float) screenSize.y / dimensions.y);

        int scaledImageWidth = (int) (dimensions.x * scale);
        int scaledImageHeight = (int) (dimensions.y * scale);

        // Crop rect is in post-scale units.
        Rect cropRect = new Rect(
                (scaledImageWidth - screenSize.x) / 2,
                (scaledImageHeight - screenSize.y) / 2,
                scaledImageWidth - ((scaledImageWidth - screenSize.x) / 2),
                scaledImageHeight - (((scaledImageHeight - screenSize.y) / 2)));

        setIndividualWallpaper(
                wallpaper, asset, false, cropRect, scale, destination, callback);
    }

    /**
     * Sets an individual wallpaper to static wallpaper destinations with a texture
     * wallpaper position.
     *
     * @param wallpaper  The wallpaper model object representing the wallpaper to be set.
     * @param asset      The wallpaper asset that should be used to set a wallpaper.
     * @param dimensions Raw dimensions of the wallpaper asset.
     * @param screenSize Dimensions of the device screen.
     * @param callback   Callback used to notify original caller of wallpaper set operation result.
     */
    private void setIndividualWallpaperWithTexturePosition(WallpaperInfo wallpaper, Asset asset,
            @Destination int destination, Point dimensions, Point screenSize, SetWallpaperCallback callback) {
        if (dimensions.x >= screenSize.x && dimensions.y >= screenSize.y) {
            Rect cropRect = new Rect(
                    (dimensions.x - screenSize.x) / 2,
                    (dimensions.y - screenSize.y) / 2,
                    dimensions.x - ((dimensions.x - screenSize.x) / 2),
                    dimensions.y - ((dimensions.y - screenSize.y) / 2));
            asset.decodeBitmapRegion(cropRect, screenSize.x, screenSize.y, false,
                    bitmap -> setIndividualWallpaper(wallpaper, bitmap,
                            destination, callback));
        } else {
            // Decode the full bitmap and pass with the screen size as a texture rect.
            asset.decodeBitmap(dimensions.x, dimensions.y, new BitmapReceiver() {
                @Override
                public void onBitmapDecoded(@Nullable Bitmap bitmap) {
                    if (bitmap == null) {
                        callback.onError(null);
                        return;
                    }
                    final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
                    final Shader mShader1 = new BitmapShader(bitmap.copy(Bitmap.Config.ARGB_8888, true), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
                    paint.setShader(mShader1);
                    final int maxSize = Math.max(screenSize.x, screenSize.y);
                    final Bitmap output = Bitmap.createBitmap(maxSize, maxSize, Bitmap.Config.ARGB_8888);
                    final Canvas cv = new Canvas(output);
                    cv.drawPaint(paint);
                    setIndividualWallpaper(wallpaper, output, destination, callback);
                }
            });
        }
    }

    /**
     * Sets a static individual wallpaper to the system via the WallpaperManager.
     *
     * @param wallpaper     Wallpaper model object.
     * @param croppedBitmap Bitmap representing the individual wallpaper image.
     * @param destination   The destination - where to set the wallpaper to.
     * @param callback      Called once the wallpaper was set or if an error occurred.
     */
    private void setIndividualWallpaper(WallpaperInfo wallpaper, Bitmap croppedBitmap,
            @Destination int destination, SetWallpaperCallback callback) {
        SetWallpaperTask setWallpaperTask =
                new SetWallpaperTask(wallpaper, croppedBitmap, destination, callback);
        setWallpaperTask.execute();
    }

    /**
     * Sets a static individual wallpaper to the system via the WallpaperManager with a fill option.
     *
     * @param wallpaper     Wallpaper model object.
     * @param croppedBitmap Bitmap representing the individual wallpaper image.
     * @param fillSize      Specifies the final bitmap size that should be set to WallpaperManager.
     *                      This final bitmap will show the visible area of the provided bitmap
     *                      after applying a mask with black background the source bitmap and
     *                      centering. There may be black borders around the original bitmap if
     *                      it's smaller than the fillSize in one or both dimensions.
     * @param destination   The destination - where to set the wallpaper to.
     * @param callback      Called once the wallpaper was set or if an error occurred.
     */
    private void setIndividualWallpaperFill(WallpaperInfo wallpaper, Bitmap croppedBitmap,
            Point fillSize, @Destination int destination, SetWallpaperCallback callback) {
        SetWallpaperTask setWallpaperTask =
                new SetWallpaperTask(wallpaper, croppedBitmap, destination, callback);
        setWallpaperTask.setFillSize(fillSize);
        setWallpaperTask.execute();
    }

    /**
     * Sets a static individual wallpaper to the system via the WallpaperManager with a stretch
     * option.
     *
     * @param wallpaper     Wallpaper model object.
     * @param croppedBitmap Bitmap representing the individual wallpaper image.
     * @param stretchSize   Specifies the final size to which the bitmap should be stretched
     *                      prior
     *                      to being set to the device.
     * @param destination   The destination - where to set the wallpaper to.
     * @param callback      Called once the wallpaper was set or if an error occurred.
     */
    private void setIndividualWallpaperStretch(WallpaperInfo wallpaper, Bitmap croppedBitmap,
            Point stretchSize, @Destination int destination, SetWallpaperCallback callback) {
        SetWallpaperTask setWallpaperTask =
                new SetWallpaperTask(wallpaper, croppedBitmap, destination, callback);
        setWallpaperTask.setStretchSize(stretchSize);
        setWallpaperTask.execute();
    }

    /**
     * Sets a static individual wallpaper stream to the system via the WallpaperManager.
     *
     * @param wallpaper   Wallpaper model object.
     * @param inputStream JPEG or PNG stream of wallpaper image's bytes.
     * @param destination The destination - where to set the wallpaper to.
     * @param callback    Called once the wallpaper was set or if an error occurred.
     */
    private void setIndividualWallpaper(WallpaperInfo wallpaper, InputStream inputStream,
            @Destination int destination, SetWallpaperCallback callback) {
        SetWallpaperTask setWallpaperTask =
                new SetWallpaperTask(wallpaper, inputStream, destination, callback);
        setWallpaperTask.execute();
    }

    @Override
    public boolean setWallpaperInRotation(Bitmap wallpaperBitmap, List<String> attributions,
            int actionLabelRes, int actionIconRes, String actionUrl, String collectionId) {

        return setWallpaperInRotationStatic(wallpaperBitmap, attributions, actionUrl,
                actionLabelRes, actionIconRes, collectionId);
    }

    @Override
    public int setWallpaperBitmapInNextRotation(Bitmap wallpaperBitmap, List<String> attributions,
            String actionUrl, String collectionId) {
        return cropAndSetWallpaperBitmapInRotationStatic(wallpaperBitmap,
                attributions, actionUrl, collectionId);
    }

    @Override
    public boolean finalizeWallpaperForNextRotation(List<String> attributions, String actionUrl,
            int actionLabelRes, int actionIconRes, String collectionId, int wallpaperId) {
        return saveStaticWallpaperMetadata(attributions, actionUrl, actionLabelRes,
                actionIconRes, collectionId, wallpaperId);
    }

    /**
     * Sets wallpaper image and attributions when a static wallpaper is responsible for presenting
     * the
     * current "daily wallpaper".
     */
    private boolean setWallpaperInRotationStatic(Bitmap wallpaperBitmap, List<String> attributions,
            String actionUrl, int actionLabelRes, int actionIconRes, String collectionId) {
        final int wallpaperId = cropAndSetWallpaperBitmapInRotationStatic(wallpaperBitmap,
                attributions, actionUrl, collectionId);

        if (wallpaperId == 0) {
            return false;
        }

        return saveStaticWallpaperMetadata(attributions, actionUrl, actionLabelRes,
                actionIconRes, collectionId, wallpaperId);
    }

    @Override
    public boolean saveStaticWallpaperMetadata(List<String> attributions,
            String actionUrl,
            int actionLabelRes,
            int actionIconRes,
            String collectionId,
            int wallpaperId) {
        mWallpaperPreferences.clearHomeWallpaperMetadata();

        boolean isLockWallpaperSet = isSeparateLockScreenWallpaperSet();

        // Persist wallpaper IDs if the rotating wallpaper component
        mWallpaperPreferences.setHomeWallpaperManagerId(wallpaperId);

        // Only copy over wallpaper ID to lock wallpaper if no explicit lock wallpaper is set
        // (so metadata isn't lost if a user explicitly sets a home-only wallpaper).
        if (!isLockWallpaperSet) {
            mWallpaperPreferences.setLockWallpaperId(wallpaperId);
        }


        mWallpaperPreferences.setHomeWallpaperAttributions(attributions);
        mWallpaperPreferences.setHomeWallpaperActionUrl(actionUrl);
        mWallpaperPreferences.setHomeWallpaperActionLabelRes(actionLabelRes);
        mWallpaperPreferences.setHomeWallpaperActionIconRes(actionIconRes);
        // Only set base image URL for static Backdrop images, not for rotation.
        mWallpaperPreferences.setHomeWallpaperBaseImageUrl(null);
        mWallpaperPreferences.setHomeWallpaperCollectionId(collectionId);

        // Set metadata to lock screen also when the rotating wallpaper so if user sets a home
        // screen-only wallpaper later, these attributions will still be available.
        if (!isLockWallpaperSet) {
            mWallpaperPreferences.setLockWallpaperAttributions(attributions);
            mWallpaperPreferences.setLockWallpaperActionUrl(actionUrl);
            mWallpaperPreferences.setLockWallpaperActionLabelRes(actionLabelRes);
            mWallpaperPreferences.setLockWallpaperActionIconRes(actionIconRes);
            mWallpaperPreferences.setLockWallpaperCollectionId(collectionId);
        }

        return true;
    }

    /**
     * Sets a wallpaper in rotation as a static wallpaper to the {@link WallpaperManager} with the
     * option allowBackup=false to save user data.
     *
     * @return wallpaper ID for the wallpaper bitmap.
     */
    private int cropAndSetWallpaperBitmapInRotationStatic(Bitmap wallpaperBitmap,
            List<String> attributions, String actionUrl, String collectionId) {
        // Calculate crop and scale of the wallpaper to match the default one used in preview
        Point wallpaperSize = new Point(wallpaperBitmap.getWidth(), wallpaperBitmap.getHeight());
        Resources resources = mAppContext.getResources();
        Display croppingDisplay = mDisplayUtils.getWallpaperDisplay();
        Point defaultCropSurfaceSize = WallpaperCropUtils.getDefaultCropSurfaceSize(
                resources, croppingDisplay);
        Point screenSize = ScreenSizeCalculator.getInstance().getScreenSize(croppingDisplay);

        // Determine minimum zoom to fit maximum visible area of wallpaper on crop surface.
        float minWallpaperZoom =
                WallpaperCropUtils.calculateMinZoom(wallpaperSize, screenSize);

        PointF centerPosition = WallpaperCropUtils.calculateDefaultCenter(mAppContext,
                wallpaperSize, WallpaperCropUtils.calculateVisibleRect(wallpaperSize, screenSize));

        Point scaledCenter = new Point((int) (minWallpaperZoom * centerPosition.x),
                (int) (minWallpaperZoom * centerPosition.y));

        int offsetX = Math.max(0, -(screenSize.x / 2 - scaledCenter.x));
        int offsetY = Math.max(0, -(screenSize.y / 2 - scaledCenter.y));

        Rect cropRect = WallpaperCropUtils.calculateCropRect(mAppContext, minWallpaperZoom,
                wallpaperSize, defaultCropSurfaceSize, screenSize, offsetX,
                offsetY, /* cropExtraWidth= */ true);

        Rect scaledCropRect = new Rect(
                (int) Math.floor((float) cropRect.left / minWallpaperZoom),
                (int) Math.floor((float) cropRect.top / minWallpaperZoom),
                (int) Math.floor((float) cropRect.right / minWallpaperZoom),
                (int) Math.floor((float) cropRect.bottom / minWallpaperZoom));

        // Scale and crop the bitmap
        wallpaperBitmap = Bitmap.createBitmap(wallpaperBitmap,
                scaledCropRect.left,
                scaledCropRect.top,
                scaledCropRect.width(),
                scaledCropRect.height());
        int whichWallpaper = getDefaultWhichWallpaper();

        int wallpaperId = setBitmapToWallpaperManagerCompat(wallpaperBitmap,
                /* allowBackup */ false, whichWallpaper);
        if (wallpaperId > 0) {
            mWallpaperPreferences.storeLatestWallpaper(whichWallpaper,
                    String.valueOf(wallpaperId), attributions, actionUrl, collectionId,
                    wallpaperBitmap, WallpaperColors.fromBitmap(wallpaperBitmap));
        }
        return wallpaperId;
    }

    /*
     * Note: this method will return use home-only (FLAG_SYSTEM) instead of both home and lock
     * if there's a distinct lock-only static wallpaper set so we don't override the lock wallpaper.
     */
    @Override
    public int getDefaultWhichWallpaper() {
        return isSeparateLockScreenWallpaperSet()
                ? WallpaperManagerCompat.FLAG_SYSTEM
                : WallpaperManagerCompat.FLAG_SYSTEM | WallpaperManagerCompat.FLAG_LOCK;
    }

    @Override
    public int setBitmapToWallpaperManagerCompat(Bitmap wallpaperBitmap, boolean allowBackup,
            int whichWallpaper) {
        ByteArrayOutputStream tmpOut = new ByteArrayOutputStream();
        if (wallpaperBitmap.compress(CompressFormat.PNG, DEFAULT_COMPRESS_QUALITY, tmpOut)) {
            try {
                byte[] outByteArray = tmpOut.toByteArray();
                return mWallpaperManagerCompat.setStream(
                        new ByteArrayInputStream(outByteArray),
                        null /* visibleCropHint */,
                        allowBackup,
                        whichWallpaper);
            } catch (IOException e) {
                Log.e(TAG, "unable to write stream to wallpaper manager");
                return 0;
            }
        } else {
            Log.e(TAG, "unable to compress wallpaper");
            try {
                return mWallpaperManagerCompat.setBitmap(
                        wallpaperBitmap,
                        null /* visibleCropHint */,
                        allowBackup,
                        whichWallpaper);
            } catch (IOException e) {
                Log.e(TAG, "unable to set wallpaper");
                return 0;
            }
        }
    }

    private int setStreamToWallpaperManagerCompat(InputStream inputStream, boolean allowBackup,
            int whichWallpaper) {
        try {
            return mWallpaperManagerCompat.setStream(inputStream, null, allowBackup,
                    whichWallpaper);
        } catch (IOException e) {
            return 0;
        }
    }

    @Override
    public void setWallpaperInfoInPreview(WallpaperInfo wallpaper) {
        mWallpaperInfoInPreview = wallpaper;
    }

    @Override
    public void onLiveWallpaperSet(@Destination int destination) {
        android.app.WallpaperInfo currentWallpaperComponent = mWallpaperManager.getWallpaperInfo();
        android.app.WallpaperInfo previewedWallpaperComponent = mWallpaperInfoInPreview != null
                ? mWallpaperInfoInPreview.getWallpaperComponent() : null;

        // If there is no live wallpaper set on the WallpaperManager or it doesn't match the
        // WallpaperInfo which was last previewed, then do nothing and nullify last previewed
        // wallpaper.
        if (currentWallpaperComponent == null || previewedWallpaperComponent == null
                || !currentWallpaperComponent.getServiceName()
                .equals(previewedWallpaperComponent.getServiceName())) {
            mWallpaperInfoInPreview = null;
            return;
        }

        setLiveWallpaperMetadata(mWallpaperInfoInPreview, mWallpaperInfoInPreview.getEffectNames(),
                destination);
    }

    /**
     * Returns whether a separate lock-screen (static) wallpaper is set to the WallpaperManager.
     */
    private boolean isSeparateLockScreenWallpaperSet() {
        ParcelFileDescriptor lockWallpaperFile =
                mWallpaperManagerCompat.getWallpaperFile(WallpaperManagerCompat.FLAG_LOCK);

        boolean isLockWallpaperSet = false;

        if (lockWallpaperFile != null) {
            isLockWallpaperSet = true;

            try {
                lockWallpaperFile.close();
            } catch (IOException e) {
                Log.e(TAG, "Unable to close PFD for lock wallpaper", e);
            }
        }

        return isLockWallpaperSet;
    }

    @Override
    public void setLiveWallpaperMetadata(WallpaperInfo wallpaperInfo, String effects,
            @Destination int destination) {
        android.app.WallpaperInfo component = wallpaperInfo.getWallpaperComponent();

        if (destination == WallpaperPersister.DEST_HOME_SCREEN
                || destination == WallpaperPersister.DEST_BOTH) {
            mWallpaperPreferences.clearHomeWallpaperMetadata();
            mWallpaperPreferences.setHomeWallpaperServiceName(component.getServiceName());
            mWallpaperPreferences.setHomeWallpaperEffects(effects);

            // Since rotation affects home screen only, disable it when setting home live wp
            mWallpaperPreferences.setWallpaperPresentationMode(
                    WallpaperPreferences.PRESENTATION_MODE_STATIC);
            mWallpaperPreferences.clearDailyRotations();
        }

        if (destination == WallpaperPersister.DEST_LOCK_SCREEN
                || destination == WallpaperPersister.DEST_BOTH) {
            mWallpaperPreferences.clearLockWallpaperMetadata();
            mWallpaperPreferences.setLockWallpaperServiceName(component.getServiceName());
            mWallpaperPreferences.setLockWallpaperEffects(effects);
        }
    }

    private class SetWallpaperTask extends AsyncTask<Void, Void, Boolean> {

        private final WallpaperInfo mWallpaper;
        @Destination
        private final int mDestination;
        private final WallpaperPersister.SetWallpaperCallback mCallback;

        private Bitmap mBitmap;
        private InputStream mInputStream;

        /**
         * Optional parameters for applying a post-decoding fill or stretch transformation.
         */
        @Nullable
        private Point mFillSize;
        @Nullable
        private Point mStretchSize;

        SetWallpaperTask(WallpaperInfo wallpaper, Bitmap bitmap, @Destination int destination,
                WallpaperPersister.SetWallpaperCallback callback) {
            super();
            mWallpaper = wallpaper;
            mBitmap = bitmap;
            mDestination = destination;
            mCallback = callback;
        }

        /**
         * Constructor for SetWallpaperTask which takes an InputStream instead of a bitmap. The task
         * will close the InputStream once it is done with it.
         */
        SetWallpaperTask(WallpaperInfo wallpaper, InputStream stream,
                @Destination int destination, WallpaperPersister.SetWallpaperCallback callback) {
            mWallpaper = wallpaper;
            mInputStream = stream;
            mDestination = destination;
            mCallback = callback;
        }

        void setFillSize(Point fillSize) {
            if (mStretchSize != null) {
                throw new IllegalArgumentException(
                        "Can't pass a fill size option if a stretch size is "
                                + "already set.");
            }
            mFillSize = fillSize;
        }

        void setStretchSize(Point stretchSize) {
            if (mFillSize != null) {
                throw new IllegalArgumentException(
                        "Can't pass a stretch size option if a fill size is "
                                + "already set.");
            }
            mStretchSize = stretchSize;
        }

        @Override
        protected Boolean doInBackground(Void... unused) {
            int whichWallpaper;
            if (mDestination == DEST_HOME_SCREEN) {
                whichWallpaper = WallpaperManagerCompat.FLAG_SYSTEM;
            } else if (mDestination == DEST_LOCK_SCREEN) {
                whichWallpaper = WallpaperManagerCompat.FLAG_LOCK;
            } else { // DEST_BOTH
                whichWallpaper = WallpaperManagerCompat.FLAG_SYSTEM
                        | WallpaperManagerCompat.FLAG_LOCK;
            }


            boolean wasLockWallpaperSet =
                    InjectorProvider.getInjector().getWallpaperStatusChecker().isLockWallpaperSet(
                            mAppContext);

            boolean allowBackup = mWallpaper.getBackupPermission() == WallpaperInfo.BACKUP_ALLOWED;
            final int wallpaperId;
            if (mBitmap != null) {
                // Apply fill or stretch transformations on mBitmap if necessary.
                if (mFillSize != null) {
                    mBitmap = BitmapTransformer.applyFillTransformation(mBitmap.copy(Bitmap.Config.ARGB_8888, true), mFillSize);
                }
                if (mStretchSize != null) {
                    mBitmap = Bitmap.createScaledBitmap(mBitmap.copy(Bitmap.Config.ARGB_8888, true), mStretchSize.x, mStretchSize.y,
                            true);
                }

                wallpaperId = setBitmapToWallpaperManagerCompat(mBitmap, allowBackup,
                        whichWallpaper);
            } else if (mInputStream != null) {
                wallpaperId = setStreamToWallpaperManagerCompat(mInputStream, allowBackup,
                        whichWallpaper);
            } else {
                Log.e(TAG,
                        "Both the wallpaper bitmap and input stream are null so we're unable to "
                                + "set any "
                                + "kind of wallpaper here.");
                wallpaperId = 0;
            }

            if (wallpaperId > 0) {
                if (mDestination == DEST_HOME_SCREEN
                        && mWallpaperPreferences.getWallpaperPresentationMode()
                        == WallpaperPreferences.PRESENTATION_MODE_ROTATING
                        && !wasLockWallpaperSet
                        && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    copyRotatingWallpaperToLock();
                }
                setImageWallpaperMetadata(mDestination, wallpaperId);
                return true;
            } else {
                return false;
            }
        }

        @Override
        protected void onPostExecute(Boolean isSuccess) {
            if (mInputStream != null) {
                try {
                    mInputStream.close();
                } catch (IOException e) {
                    Log.e(TAG, "Failed to close input stream " + e);
                    mCallback.onError(e /* throwable */);
                    return;
                }
            }

            if (isSuccess) {
                mCallback.onSuccess(mWallpaper, mDestination);
                mWallpaperChangedNotifier.notifyWallpaperChanged();
            } else {
                mCallback.onError(null /* throwable */);
            }
        }

        /**
         * Copies home wallpaper metadata to lock, and if rotation was enabled with a live wallpaper
         * previously, then copies over the rotating wallpaper image to the WallpaperManager also.
         * <p>
         * Used to accommodate the case where a user had gone from a home+lock daily rotation to
         * selecting a static wallpaper on home-only. The image and metadata that was previously
         * rotating is now copied to the lock screen.
         */
        private void copyRotatingWallpaperToLock() {

            mWallpaperPreferences.setLockWallpaperAttributions(
                    mWallpaperPreferences.getHomeWallpaperAttributions());
            mWallpaperPreferences.setLockWallpaperActionUrl(
                    mWallpaperPreferences.getHomeWallpaperActionUrl());
            mWallpaperPreferences.setLockWallpaperActionLabelRes(
                    mWallpaperPreferences.getHomeWallpaperActionLabelRes());
            mWallpaperPreferences.setLockWallpaperActionIconRes(
                    mWallpaperPreferences.getHomeWallpaperActionIconRes());
            mWallpaperPreferences.setLockWallpaperCollectionId(
                    mWallpaperPreferences.getHomeWallpaperCollectionId());

            // Set the lock wallpaper ID to what Android set it to, following its having
            // copied the system wallpaper over to the lock screen when we changed from
            // "both" to distinct system and lock screen wallpapers.
            mWallpaperPreferences.setLockWallpaperId(
                    mWallpaperManagerCompat.getWallpaperId(WallpaperManagerCompat.FLAG_LOCK));

        }

        /**
         * Sets the image wallpaper's metadata on SharedPreferences. This method is called after the
         * set wallpaper operation is successful.
         *
         * @param destination Which destination of wallpaper the metadata corresponds to (home
         *                    screen, lock screen, or both).
         * @param wallpaperId The ID of the static wallpaper returned by WallpaperManager, which
         *                    on N and later versions of Android uniquely identifies a wallpaper
         *                    image.
         */
        private void setImageWallpaperMetadata(@Destination int destination, int wallpaperId) {
            if (destination == DEST_HOME_SCREEN || destination == DEST_BOTH) {
                mWallpaperPreferences.clearHomeWallpaperMetadata();
                mWallpaperPreferences.setHomeWallpaperEffects(null);
                setImageWallpaperHomeMetadata(wallpaperId);

                // Reset presentation mode to STATIC if an individual wallpaper is set to the
                // home screen
                // because rotation always affects at least the home screen.
                mWallpaperPreferences.setWallpaperPresentationMode(
                        WallpaperPreferences.PRESENTATION_MODE_STATIC);
            }

            if (destination == DEST_LOCK_SCREEN || destination == DEST_BOTH) {
                mWallpaperPreferences.clearLockWallpaperMetadata();
                setImageWallpaperLockMetadata(wallpaperId);
            }

            mWallpaperPreferences.clearDailyRotations();
        }

        private void setImageWallpaperHomeMetadata(int homeWallpaperId) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                mWallpaperPreferences.setHomeWallpaperManagerId(homeWallpaperId);
            }

            // Compute bitmap hash code after setting the wallpaper because JPEG compression has
            // likely changed many pixels' color values. Forget the previously loaded wallpaper
            // bitmap so that WallpaperManager doesn't return the old wallpaper drawable. Do this
            // on N+ devices in addition to saving the wallpaper ID for the purpose of backup &
            // restore.
            mWallpaperManager.forgetLoadedWallpaper();
            mBitmap = ((BitmapDrawable) mWallpaperManagerCompat.getDrawable()).getBitmap();
            long bitmapHash = BitmapUtils.generateHashCode(mBitmap);
            WallpaperColors colors = WallpaperColors.fromBitmap(mBitmap);

            mWallpaperPreferences.setHomeWallpaperHashCode(bitmapHash);

            mWallpaperPreferences.setHomeWallpaperAttributions(
                    mWallpaper.getAttributions(mAppContext));
            mWallpaperPreferences.setHomeWallpaperBaseImageUrl(mWallpaper.getBaseImageUrl());
            mWallpaperPreferences.setHomeWallpaperActionUrl(mWallpaper.getActionUrl(mAppContext));
            mWallpaperPreferences.setHomeWallpaperActionLabelRes(
                    mWallpaper.getActionLabelRes(mAppContext));
            mWallpaperPreferences.setHomeWallpaperActionIconRes(
                    mWallpaper.getActionIconRes(mAppContext));
            mWallpaperPreferences.setHomeWallpaperCollectionId(
                    mWallpaper.getCollectionId(mAppContext));
            mWallpaperPreferences.setHomeWallpaperRemoteId(mWallpaper.getWallpaperId());
            mWallpaperPreferences.storeLatestWallpaper(FLAG_SYSTEM,
                    TextUtils.isEmpty(mWallpaper.getWallpaperId()) ? String.valueOf(bitmapHash)
                            : mWallpaper.getWallpaperId(),
                    mWallpaper, mBitmap, colors);
        }

        private void setImageWallpaperLockMetadata(int lockWallpaperId) {
            mWallpaperPreferences.setLockWallpaperId(lockWallpaperId);
            mWallpaperPreferences.setLockWallpaperAttributions(
                    mWallpaper.getAttributions(mAppContext));
            mWallpaperPreferences.setLockWallpaperActionUrl(mWallpaper.getActionUrl(mAppContext));
            mWallpaperPreferences.setLockWallpaperActionLabelRes(
                    mWallpaper.getActionLabelRes(mAppContext));
            mWallpaperPreferences.setLockWallpaperActionIconRes(
                    mWallpaper.getActionIconRes(mAppContext));
            mWallpaperPreferences.setLockWallpaperCollectionId(
                    mWallpaper.getCollectionId(mAppContext));
            mWallpaperPreferences.setLockWallpaperRemoteId(mWallpaper.getWallpaperId());

            // Save the lock wallpaper image's hash code as well for the sake of backup & restore
            // because WallpaperManager-generated IDs are specific to a physical device and
            // cannot be  used to identify a wallpaper image on another device after restore is
            // complete.
            Bitmap lockBitmap = getLockWallpaperBitmap();
            if (lockBitmap != null) {
                saveLockWallpaperHashCode(lockBitmap);
                mWallpaperPreferences.storeLatestWallpaper(FLAG_LOCK,
                        TextUtils.isEmpty(mWallpaper.getWallpaperId())
                                ? String.valueOf(mWallpaperPreferences.getLockWallpaperHashCode())
                                : mWallpaper.getWallpaperId(),
                        mWallpaper, lockBitmap, WallpaperColors.fromBitmap(lockBitmap));
            }
        }

        private Bitmap getLockWallpaperBitmap() {
            ParcelFileDescriptor parcelFd = mWallpaperManagerCompat.getWallpaperFile(
                    WallpaperManagerCompat.FLAG_LOCK);

            if (parcelFd == null) {
                return null;
            }

            try (InputStream fileStream = new FileInputStream(parcelFd.getFileDescriptor())) {
                return BitmapFactory.decodeStream(fileStream);
            } catch (IOException e) {
                Log.e(TAG, "IO exception when closing the file stream.", e);
                return null;
            } finally {
                try {
                    parcelFd.close();
                } catch (IOException e) {
                    Log.e(TAG, "IO exception when closing the file descriptor.", e);
                }
            }
        }

        private long saveLockWallpaperHashCode(Bitmap lockBitmap) {
            if (lockBitmap != null) {
                long bitmapHash = BitmapUtils.generateHashCode(lockBitmap);
                mWallpaperPreferences.setLockWallpaperHashCode(bitmapHash);
                return bitmapHash;
            }
            return 0;
        }
    }
}
