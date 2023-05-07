/*
 * Copyright (C) 2023 droid-ng
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
package com.android.wallpaper.picker;

import com.android.wallpaper.R;
import com.android.wallpaper.module.WallpaperPersister;
import com.android.wallpaper.module.WallpaperPersister.WallpaperPosition;

public enum FitStrategy {
    AS_PREVIEW(false, null, R.string.fit_strategy_as_previewed),
    HIGH_QUALITY(true, null, R.string.fit_strategy_high_quality),
    CENTER(false, WallpaperPersister.WALLPAPER_POSITION_CENTER, R.string.fit_strategy_center),
    CENTER_CROP(false, WallpaperPersister.WALLPAPER_POSITION_CENTER_CROP, R.string.fit_strategy_crop),
    STRETCH(false, WallpaperPersister.WALLPAPER_POSITION_STRETCH, R.string.fit_strategy_stretch),
    TEXTURE(false, WallpaperPersister.WALLPAPER_POSITION_TEXTURE, R.string.fit_strategy_texture);

    private final boolean mHighQuality;
    private final Integer mWallpaperPosition;
    private final int mAndroidString;

    private FitStrategy(boolean highQuality, @WallpaperPosition Integer wallpaperPosition, int androidString) {
        this.mHighQuality = highQuality;
        this.mWallpaperPosition = wallpaperPosition;
        this.mAndroidString = androidString;
    }

    public boolean isHighQuality() {
        return mHighQuality;
    }

    public Integer toWallpaperPosition() {
        return mWallpaperPosition;
    }

    public int toAndroidString() {
        return mAndroidString;
    }
}
