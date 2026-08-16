/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.settings.biometrics.face;

import android.content.Context;
import android.os.SystemProperties;
import android.util.AttributeSet;
import android.util.Log;
import android.view.SurfaceView;

/**
 * A square {@link SurfaceView}, used instead of {@link FaceSquareTextureView} when the face HAL
 * renders the enrollment preview itself.
 *
 * <p>A SurfaceView is required rather than a TextureView because the HAL produces protected
 * (secure) graphic buffers. A TextureView has to import its buffer as a GPU texture, and hwui
 * aborts outright when asked to do that with protected memory:
 *
 * <pre>
 *   Abort message: 'frameworks/base/libs/hwui/AutoBackendTextureRelease.cpp
 *                   Invalid GrBackendTexture. Width==960, height==720, protected==1'
 *   (960x720 there is just what the size rounding happened to pick that run, not a fixed size)
 *       #04 libhwui AutoBackendTextureRelease::AutoBackendTextureRelease
 *       #05 libhwui DeferredLayerUpdater::apply()
 *       #07        RenderThread::threadLoop()
 * </pre>
 *
 * A SurfaceView hands its buffers straight to the compositor, which can display protected
 * content. This mirrors what the OEM's own enrollment UI does.
 */
public class FaceSquareSurfaceView extends SurfaceView {

    private static final String TAG = "FaceSquareSurfaceView";

    public FaceSquareSurfaceView(Context context) {
        this(context, null);
    }

    public FaceSquareSurfaceView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public FaceSquareSurfaceView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    /**
     * Camera stream size handed to the HAL, pinned with {@link android.view.SurfaceHolder#
     * setFixedSize}. This MUST be an exact entry of the front camera's
     * android.scaler.availableStreamConfigurations for HAL_PIXEL_FORMAT_IMPLEMENTATION_DEFINED
     * (34). On arcfox camera 1 those include 1080x1080, 1024x768, 960x720, 720x720 and 640x480.
     *
     * <p>Pinning it is not optional. If the surface is left to report its own size, the camera
     * service rounds that to the euclidean-nearest supported entry
     * (SessionConfigurationUtils.cpp roundBufferDimensionNearest, called with
     * respectSurfaceSize=false), so the buffer aspect -- and therefore the correct view aspect --
     * changes as the view changes. There is no fixed point: a 4:3 buffer needs a 1.33 view, which
     * rounds to a 1:1 buffer, which needs a 1.0 view, which rounds back to 4:3. Measured on this
     * device, two pixels of view height (955 vs 957) flipped the buffer between 1024x768 and
     * 1080x1080 and the preview between 21% too wide and 10% too tall.
     *
     * <p>Any exact entry works, and that is the whole point of pinning: once the size is an exact
     * entry the rounding has nothing to round, and {@link #onMeasure} then derives the matching
     * view aspect by construction. Retested on this device with a reboot before each trial and the
     * HAL proven idle first -- 1024x768, 640x480 and 1080x1080 all pinned exactly and all rendered
     * correct proportions, with no protected-buffer abort and no HAL error in any run.
     *
     * <p>NOTE: an earlier round of testing recorded 640x480 and 1080x1080 as misbehaving. That was
     * wrong. Those trials ran against a face HAL left wedged by a previous enrollment (spinning on
     * errorcode 101 while holding the camera), which shows a black preview regardless of the size
     * under test. Do not re-derive a "correct" size from a run without first proving the HAL idle.
     *
     * <p>1080x1080 is the default because it is the only candidate whose aspect matches the square
     * enrollment frame: the view measures 870x870 and fills it exactly. The 4:3 entries measure
     * 870x1160 inside the same 870x870 frame, so a quarter of the vertical field of view is
     * cropped away. It is also the highest-resolution of the three. The OEM's own enrollment
     * activity pins 640x480, which is equally correct and simply lower resolution.
     */
    private static final int DEFAULT_BUFFER_WIDTH = 1080;
    private static final int DEFAULT_BUFFER_HEIGHT = 1080;

    /** Overridable at runtime with {@code setprop persist.sys.face_preview_w|_h}. */
    public static int bufferWidth() {
        return SystemProperties.getInt("persist.sys.face_preview_w", DEFAULT_BUFFER_WIDTH);
    }

    public static int bufferHeight() {
        return SystemProperties.getInt("persist.sys.face_preview_h", DEFAULT_BUFFER_HEIGHT);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        // The camera service applies FLIP_H ^ ROT_90 to every stream of a front-facing camera
        // whose android.sensor.orientation is 270 (CameraUtils::getRotationTransform; arcfox
        // camera 1 reports Facing: Front, Orientation: 270), so a Wb x Hb buffer reaches the
        // layer as Hb x Wb. The preview is undistorted exactly when height/width == Wb/Hb.
        //
        // FaceSquareFrameLayout measures every child with an EXACTLY square spec, so the height
        // has to be overridden here rather than expressed in the layout.
        final int side = Math.min(MeasureSpec.getSize(widthMeasureSpec),
                MeasureSpec.getSize(heightMeasureSpec));
        final int measuredHeight = Math.round(side * (float) bufferWidth() / bufferHeight());
        Log.i(TAG, "onMeasure: side=" + side + " -> " + side + "x" + measuredHeight
                + " (buffer=" + bufferWidth() + "x" + bufferHeight() + ")");
        setMeasuredDimension(side, measuredHeight);
    }
}
