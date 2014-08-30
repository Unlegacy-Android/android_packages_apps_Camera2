/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.camera.one.v2;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.CaptureResult.Key;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.CameraProfile;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.support.v4.util.Pools;
import android.view.Surface;

import com.android.camera.CaptureModuleUtil;
import com.android.camera.app.MediaSaver.OnMediaSavedListener;
import com.android.camera.debug.Log;
import com.android.camera.debug.Log.Tag;
import com.android.camera.exif.ExifInterface;
import com.android.camera.exif.ExifTag;
import com.android.camera.exif.Rational;
import com.android.camera.one.AbstractOneCamera;
import com.android.camera.one.OneCamera;
import com.android.camera.one.OneCamera.PhotoCaptureParameters.Flash;
import com.android.camera.one.Settings3A;
import com.android.camera.one.v2.ImageCaptureManager.ImageCaptureListener;
import com.android.camera.one.v2.ImageCaptureManager.MetadataChangeListener;
import com.android.camera.session.CaptureSession;
import com.android.camera.util.CameraUtil;
import com.android.camera.util.ConjunctionListenerMux;
import com.android.camera.util.JpegUtilNative;
import com.android.camera.util.Size;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link OneCamera} implementation directly on top of the Camera2 API with zero
 * shutter lag.<br>
 * TODO: Determine what the maximum number of full YUV capture frames is.
 */
@TargetApi(Build.VERSION_CODES.L)
public class OneCameraZslImpl extends AbstractOneCamera {
    private static final Tag TAG = new Tag("OneCameraZslImpl2");

    /** Default JPEG encoding quality. */
    private static final int JPEG_QUALITY = CameraProfile.getJpegEncodingQualityParameter(
            CameraProfile.QUALITY_HIGH);
    /**
     * The maximum number of images to store in the full-size ZSL ring buffer.
     * <br>
     * TODO: Determine this number dynamically based on available memory and the
     * size of frames.
     */
    private static final int MAX_CAPTURE_IMAGES = 10;
    /**
     * True if zero-shutter-lag images should be captured. Some devices produce
     * lower-quality images for the high-frequency stream, so we may wish to
     * disable ZSL in that case.
     */
    private static final boolean ZSL_ENABLED = true;

    /**
     * Tags which may be used in CaptureRequests.
     */
    private static enum RequestTag {
        /**
         * Indicates that the request was explicitly sent for a single
         * high-quality still capture. Unlike other requests, such as the
         * repeating (ZSL) stream and AF/AE triggers, requests with this tag
         * should always be saved.
         */
        EXPLICIT_CAPTURE
    }

    /**
     * Set to ImageFormat.JPEG to use the hardware encoder, or
     * ImageFormat.YUV_420_888 to use the software encoder. No other image
     * formats are supported.
     */
    private static final int sCaptureImageFormat = ImageFormat.YUV_420_888;
    /**
     * Token for callbacks posted to {@link #mCameraHandler} to resume
     * continuous AF.
     */
    private static final String FOCUS_RESUME_CALLBACK_TOKEN = "RESUME_CONTINUOUS_AF";
    /** Zero weight 3A region, to reset regions per API. */
    MeteringRectangle[] ZERO_WEIGHT_3A_REGION = Settings3A.getZeroWeightRegion();

    /**
     * Thread on which high-priority camera operations, such as grabbing preview
     * frames for the viewfinder, are running.
     */
    private final HandlerThread mCameraThread;
    /** Handler of the {@link #mCameraThread}. */
    private final Handler mCameraHandler;

    /** Thread on which low-priority camera listeners are running. */
    private final HandlerThread mCameraListenerThread;
    private final Handler mCameraListenerHandler;

    /** The characteristics of this camera. */
    private final CameraCharacteristics mCharacteristics;
    /** The underlying Camera2 API camera device. */
    private final CameraDevice mDevice;

    /**
     * The aspect ratio (width/height) of the full resolution for this camera.
     * Usually the native aspect ratio of this camera.
     */
    private final double mFullSizeAspectRatio;
    /** The Camera2 API capture session currently active. */
    private CameraCaptureSession mCaptureSession;
    /** The surface onto which to render the preview. */
    private Surface mPreviewSurface;
    /** Whether closing of this device has been requested. */
    private volatile boolean mIsClosed = false;
    /** A callback that is called when the device is fully closed. */
    private CloseCallback mCloseCallback = null;

    /** Receives the normal captured images. */
    private final ImageReader mCaptureImageReader;

    /**
     * Maintains a buffer of images and their associated {@link CaptureResult}s.
     */
    private ImageCaptureManager mCaptureManager;

    /**
     * The sensor timestamp (which may not be relative to the system time) of
     * the most recently captured image.
     */
    private final AtomicLong mLastCapturedImageTimestamp = new AtomicLong(0);

    /** Thread pool for performing slow jpeg encoding and saving tasks. */
    private final ThreadPoolExecutor mImageSaverThreadPool;

    /** Pool of native byte buffers on which to store jpeg-encoded images. */
    private final Pools.SynchronizedPool<ByteBuffer> mJpegByteBufferPool = new
            Pools.SynchronizedPool<ByteBuffer>(64);

    /** Current zoom value. 1.0 is no zoom. */
    private float mZoomValue = 1f;
    /** Current crop region: set from mZoomValue. */
    private Rect mCropRegion;
    /** Current AE, AF, and AWB regions */
    private MeteringRectangle[] mAFRegions = ZERO_WEIGHT_3A_REGION;
    private MeteringRectangle[] mAERegions = ZERO_WEIGHT_3A_REGION;

    /**
     * Ready state depends on two things:<br>
     * <ol>
     * <li>{@link #mCaptureManager} must be ready.</li>
     * <li>We must not be in the process of capturing a single, high-quality,
     * image.</li>
     * </ol>
     */
    private static enum ReadyStateRequirement {
        CAPTURE_MANAGER_READY,
        CAPTURE_NOT_IN_PROGRESS
    }

    /**
     * Handles the thread-safe logic of dispatching whenever the logical AND of
     * these constraints changes.
     */
    private final ConjunctionListenerMux<ReadyStateRequirement>
            mReadyStateManager = new ConjunctionListenerMux<ReadyStateRequirement>(
                    ReadyStateRequirement.class);

    /**
     * An {@link ImageCaptureListener} which will compress and save an image to
     * disk.
     */
    private class ImageCaptureTask implements ImageCaptureListener {
        private final PhotoCaptureParameters mParams;
        private final CaptureSession mSession;

        public ImageCaptureTask(PhotoCaptureParameters parameters,
                CaptureSession session) {
            mParams = parameters;
            mSession = session;
        }

        @Override
        public void onImageCaptured(Image image, TotalCaptureResult
                captureResult) {
            long timestamp = captureResult.get(CaptureResult.SENSOR_TIMESTAMP);

            // We should only capture the image if it's more recent than the
            // latest one. Synchronization is necessary since this method is
            // called on {@link #mImageSaverThreadPool}.
            synchronized (mLastCapturedImageTimestamp) {
                if (timestamp > mLastCapturedImageTimestamp.get()) {
                    mLastCapturedImageTimestamp.set(timestamp);
                } else {
                    // There was a more recent (or identical) image which has
                    // begun being saved, so abort.
                    return;
                }
            }

            mReadyStateManager.setInput(
                    ReadyStateRequirement.CAPTURE_NOT_IN_PROGRESS, true);

            // TODO Add callback to CaptureModule here to flash the screen.
            mSession.startEmpty();
            savePicture(image, mParams, mSession);
            mParams.callback.onPictureTaken(mSession);
            Log.v(TAG, "Image saved.  Frame number = " + captureResult.getFrameNumber());
        }
    }

    /**
     * Instantiates a new camera based on Camera 2 API.
     *
     * @param device The underlying Camera 2 device.
     * @param characteristics The device's characteristics.
     * @param pictureSize the size of the final image to be taken.
     */
    OneCameraZslImpl(CameraDevice device, CameraCharacteristics characteristics, Size pictureSize) {
        Log.v(TAG, "Creating new OneCameraZslImpl");

        mDevice = device;
        mCharacteristics = characteristics;
        mFullSizeAspectRatio = calculateFullSizeAspectRatio(characteristics);

        mCameraThread = new HandlerThread("OneCamera2");
        // If this thread stalls, it will delay viewfinder frames.
        mCameraThread.setPriority(Thread.MAX_PRIORITY);
        mCameraThread.start();
        mCameraHandler = new Handler(mCameraThread.getLooper());

        mCameraListenerThread = new HandlerThread("OneCamera2-Listener");
        mCameraListenerThread.start();
        mCameraListenerHandler = new Handler(mCameraListenerThread.getLooper());

        // TODO: Encoding on multiple cores results in preview jank due to
        // excessive GC.
        int numEncodingCores = CameraUtil.getNumCpuCores();
        mImageSaverThreadPool = new ThreadPoolExecutor(numEncodingCores, numEncodingCores, 10,
                TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());

        mCaptureManager = new ImageCaptureManager(MAX_CAPTURE_IMAGES, mCameraListenerHandler,
                mImageSaverThreadPool);
        mCaptureManager.setCaptureReadyListener(new ImageCaptureManager.CaptureReadyListener() {
                @Override
            public void onReadyStateChange(boolean capturePossible) {
                mReadyStateManager.setInput(ReadyStateRequirement.CAPTURE_MANAGER_READY,
                        capturePossible);
            }
        });

        mReadyStateManager.addListener(new ConjunctionListenerMux.OutputChangeListener() {
                @Override
            public void onOutputChange(boolean state) {
                broadcastReadyState(state);
            }
        });

        // Listen for changes to auto focus state and dispatch to
        // mFocusStateListener.
        mCaptureManager.addMetadataChangeListener(CaptureResult.CONTROL_AF_STATE,
                new ImageCaptureManager.MetadataChangeListener() {
                @Override
                    public void onImageMetadataChange(Key<?> key, Object oldValue, Object newValue,
                            CaptureResult result) {
                        mFocusStateListener.onFocusStatusUpdate(
                                AutoFocusHelper.stateFromCamera2State(
                                        result.get(CaptureResult.CONTROL_AF_STATE)));
                    }
                });

        // Allocate the image reader to store all images received from the
        // camera.
        mCaptureImageReader = ImageReader.newInstance(pictureSize.getWidth(),
                pictureSize.getHeight(),
                sCaptureImageFormat, MAX_CAPTURE_IMAGES);

        mCaptureImageReader.setOnImageAvailableListener(mCaptureManager, mCameraHandler);
    }

    /**
     * Take a picture.
     */
    @Override
    public void takePicture(final PhotoCaptureParameters params, final CaptureSession session) {
        params.checkSanity();

        mReadyStateManager.setInput(
                ReadyStateRequirement.CAPTURE_NOT_IN_PROGRESS, false);

        boolean useZSL = ZSL_ENABLED;

        // We will only capture images from the zsl ring-buffer which satisfy
        // this constraint.
        ArrayList<ImageCaptureManager.CapturedImageConstraint> zslConstraints = new ArrayList<
                ImageCaptureManager.CapturedImageConstraint>();
        zslConstraints.add(new ImageCaptureManager.CapturedImageConstraint() {
                @Override
            public boolean satisfiesConstraint(TotalCaptureResult captureResult) {
                Long timestamp = captureResult.get(CaptureResult.SENSOR_TIMESTAMP);
                Integer lensState = captureResult.get(CaptureResult.LENS_STATE);
                Integer flashState = captureResult.get(CaptureResult.FLASH_STATE);
                Integer flashMode = captureResult.get(CaptureResult.FLASH_MODE);
                Integer aeState = captureResult.get(CaptureResult.CONTROL_AE_STATE);
                Integer afState = captureResult.get(CaptureResult.CONTROL_AF_STATE);
                Integer awbState = captureResult.get(CaptureResult.CONTROL_AWB_STATE);

                if (timestamp <= mLastCapturedImageTimestamp.get()) {
                    // Don't save frames older than the most
                    // recently-captured frame.
                    // TODO This technically has a race condition in which
                    // duplicate frames may be saved, but if a user is
                    // tapping at >30Hz, duplicate images may be what they
                    // expect.
                    return false;
                }

                if (lensState == CaptureResult.LENS_STATE_MOVING) {
                    // If we know the lens was moving, don't use this image.
                    return false;
                }

                if (aeState == CaptureResult.CONTROL_AE_STATE_SEARCHING
                        || aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                    return false;
                }
                switch (params.flashMode) {
                    case OFF:
                        break;
                    case ON:
                        if (flashState != CaptureResult.FLASH_STATE_FIRED
                                || flashMode != CaptureResult.FLASH_MODE_SINGLE) {
                            return false;
                        }
                        break;
                    case AUTO:
                        if (aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED
                                && flashState != CaptureResult.FLASH_STATE_FIRED) {
                            return false;
                        }
                        break;
                }

                if (afState == CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN
                        || afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN) {
                    return false;
                }

                if (awbState == CaptureResult.CONTROL_AWB_STATE_SEARCHING) {
                    return false;
                }

                return true;
            }
        });
        // This constraint lets us capture images which have been explicitly
        // requested. See {@link RequestTag.EXPLICIT_CAPTURE}.
        ArrayList<ImageCaptureManager.CapturedImageConstraint> singleCaptureConstraint = new ArrayList<
                ImageCaptureManager.CapturedImageConstraint>();
        singleCaptureConstraint.add(new ImageCaptureManager.CapturedImageConstraint() {
                @Override
            public boolean satisfiesConstraint(TotalCaptureResult captureResult) {
                Object tag = captureResult.getRequest().getTag();
                return tag == RequestTag.EXPLICIT_CAPTURE;
            }
        });

        // If we can use ZSL, try to save a previously-captured frame, if an
        // acceptable one exists in the buffer.
        if (useZSL) {
            boolean capturedPreviousFrame = mCaptureManager.tryCaptureExistingImage(
                    new ImageCaptureTask(params, session), zslConstraints);
            if (capturedPreviousFrame) {
                Log.v(TAG, "Saving previous frame");
            } else {
                Log.v(TAG, "No good image Available.  Capturing next available good image.");
                // If there was no good frame available in the ring buffer
                // already, capture the next good image.
                // TODO Disable the shutter button until this image is captured.

                if (params.flashMode == Flash.ON || params.flashMode == Flash.AUTO) {
                    // We must issue a request for a single capture using the
                    // flash, including an AE precapture trigger.

                    // The following sets up a sequence of events which will
                    // occur in reverse order to the associated method
                    // calls:
                    // 1. Send a request to trigger the Auto Exposure Precapture
                    // 2. Wait for the AE_STATE to leave the PRECAPTURE state,
                    // and then send a request for a single image, with the
                    // appropriate flash settings.
                    // 3. Capture the next appropriate image, which should be
                    // the one we requested in (2).

                    mCaptureManager.captureNextImage(new ImageCaptureTask(params, session),
                            singleCaptureConstraint);

                    mCaptureManager.addMetadataChangeListener(CaptureResult.CONTROL_AE_STATE,
                            new MetadataChangeListener() {
                            @Override
                                public void onImageMetadataChange(Key<?> key, Object oldValue,
                                        Object newValue, CaptureResult result) {
                                    Log.v(TAG, "AE State Changed");
                                    if (oldValue.equals(
                                            Integer.valueOf(
                                                    CaptureResult.CONTROL_AE_STATE_PRECAPTURE))) {
                                        mCaptureManager.removeMetadataChangeListener(key, this);
                                        sendSingleRequest(params);
                                    }
                                }
                            });

                    sendAutoExposureTriggerRequest(params.flashMode);
                } else {
                    // We may get here if, for example, the auto focus is in the
                    // middle of a scan.
                    // If the flash is off, we should just wait for the next
                    // image that arrives. This will have minimal delay since we
                    // do not need to send a new capture request.
                    mCaptureManager.captureNextImage(new ImageCaptureTask(params, session),
                            zslConstraints);
                }
            }
        } else {
            // TODO If we can't save a previous frame, create a new capture
            // request to do what we need (e.g. flash) and call
            // captureNextImage().
            throw new UnsupportedOperationException("Non-ZSL capture not yet supported");
        }
    }

    @Override
    public void startPreview(Surface previewSurface, CaptureReadyCallback listener) {
        mPreviewSurface = previewSurface;
        setupAsync(mPreviewSurface, listener);
    }

    @Override
    public void setViewFinderSize(int width, int height) {
        throw new RuntimeException("Not implemented yet.");
    }

    @Override
    public boolean isFlashSupported(boolean enhanced) {
        throw new RuntimeException("Not implemented yet.");
    }

    @Override
    public boolean isSupportingEnhancedMode() {
        throw new RuntimeException("Not implemented yet.");
    }

    @Override
    public void close(CloseCallback closeCallback) {
        if (mIsClosed) {
            Log.w(TAG, "Camera is already closed.");
            return;
        }
        try {
            mCaptureSession.abortCaptures();
        } catch (CameraAccessException e) {
            Log.e(TAG, "Could not abort captures in progress.");
        }
        mIsClosed = true;
        mCloseCallback = closeCallback;
        mCameraThread.quitSafely();
        mDevice.close();
        mCaptureManager.close();
    }

    @Override
    public Size[] getSupportedSizes() {
        StreamConfigurationMap config = mCharacteristics
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        return Size.convert(config.getOutputSizes(sCaptureImageFormat));
    }

    @Override
    public double getFullSizeAspectRatio() {
        return mFullSizeAspectRatio;
    }

    @Override
    public boolean isFrontFacing() {
        return mCharacteristics.get(CameraCharacteristics.LENS_FACING)
                == CameraMetadata.LENS_FACING_FRONT;
    }

    @Override
    public boolean isBackFacing() {
        return mCharacteristics.get(CameraCharacteristics.LENS_FACING)
                == CameraMetadata.LENS_FACING_BACK;
    }

    private void savePicture(Image image, final PhotoCaptureParameters captureParams,
            CaptureSession session) {
        int heading = captureParams.heading;

        int width = image.getWidth();
        int height = image.getHeight();
        int rotation = 0;
        ExifInterface exif = null;

        exif = new ExifInterface();
        // TODO: Add more exif tags here.

        exif.setTag(exif.buildTag(ExifInterface.TAG_PIXEL_X_DIMENSION, width));
        exif.setTag(exif.buildTag(ExifInterface.TAG_PIXEL_Y_DIMENSION, height));

        // TODO: Handle rotation correctly.

        // Set GPS heading direction based on sensor, if location is on.
        if (heading >= 0) {
            ExifTag directionRefTag = exif.buildTag(
                    ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
                    ExifInterface.GpsTrackRef.MAGNETIC_DIRECTION);
            ExifTag directionTag = exif.buildTag(
                    ExifInterface.TAG_GPS_IMG_DIRECTION,
                    new Rational(heading, 1));
            exif.setTag(directionRefTag);
            exif.setTag(directionTag);
        }

        session.saveAndFinish(acquireJpegBytes(image), width, height, rotation, exif,
                new OnMediaSavedListener() {
                @Override
                    public void onMediaSaved(Uri uri) {
                        captureParams.callback.onPictureSaved(uri);
                    }
                });
    }

    /**
     * Asynchronously sets up the capture session.
     *
     * @param previewSurface the surface onto which the preview should be
     *            rendered.
     * @param listener called when setup is completed.
     */
    private void setupAsync(final Surface previewSurface, final CaptureReadyCallback listener) {
        mCameraHandler.post(new Runnable() {
                @Override
            public void run() {
                setup(previewSurface, listener);
            }
        });
    }

    /**
     * Configures and attempts to create a capture session.
     *
     * @param previewSurface the surface onto which the preview should be
     *            rendered.
     * @param listener called when the setup is completed.
     */
    private void setup(Surface previewSurface, final CaptureReadyCallback listener) {
        try {
            if (mCaptureSession != null) {
                mCaptureSession.abortCaptures();
                mCaptureSession = null;
            }
            List<Surface> outputSurfaces = new ArrayList<Surface>(2);
            outputSurfaces.add(previewSurface);
            outputSurfaces.add(mCaptureImageReader.getSurface());

            mDevice.createCaptureSession(outputSurfaces, new CameraCaptureSession.StateListener() {
                    @Override
                public void onConfigureFailed(CameraCaptureSession session) {
                    listener.onSetupFailed();
                }

                    @Override
                public void onConfigured(CameraCaptureSession session) {
                    mCaptureSession = session;
                    mAFRegions = ZERO_WEIGHT_3A_REGION;
                    mAERegions = ZERO_WEIGHT_3A_REGION;
                    mZoomValue = 1f;
                    mCropRegion = cropRegionForZoom(mZoomValue);
                    boolean success = sendRepeatingCaptureRequest();
                    if (success) {
                        listener.onReadyForCapture();
                    } else {
                        listener.onSetupFailed();
                    }
                }

                    @Override
                public void onClosed(CameraCaptureSession session) {
                    super.onClosed(session);
                    if (mCloseCallback != null) {
                        mCloseCallback.onCameraClosed();
                    }
                }
            }, mCameraHandler);
        } catch (CameraAccessException ex) {
            Log.e(TAG, "Could not set up capture session", ex);
            listener.onSetupFailed();
        }
    }

    private void addRegionsToCaptureRequestBuilder(CaptureRequest.Builder builder) {
        builder.set(CaptureRequest.CONTROL_AE_REGIONS, mAERegions);
        builder.set(CaptureRequest.CONTROL_AF_REGIONS, mAFRegions);
        builder.set(CaptureRequest.SCALER_CROP_REGION, mCropRegion);
    }

    private void addFlashToCaptureRequestBuilder(CaptureRequest.Builder builder, Flash flashMode) {
        switch (flashMode) {
            case ON:
                builder.set(CaptureRequest.CONTROL_AE_MODE,
                        CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH);
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_SINGLE);
                break;
            case OFF:
                builder.set(CaptureRequest.CONTROL_AE_MODE,
                        CaptureRequest.CONTROL_AE_MODE_ON);
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
                break;
            case AUTO:
                builder.set(CaptureRequest.CONTROL_AE_MODE,
                        CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
                break;
        }
    }

    /**
     * Request a stream of images.
     *
     * @return true if successful, false if there was an error submitting the
     *         capture request.
     */
    private boolean sendRepeatingCaptureRequest() {
        Log.v(TAG, "sendRepeatingCaptureRequest()");
        try {
            CaptureRequest.Builder builder;
            if (ZSL_ENABLED) {
                builder = mDevice.
                        createCaptureRequest(CameraDevice.TEMPLATE_ZERO_SHUTTER_LAG);
            } else {
                builder = mDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            }

            builder.addTarget(mPreviewSurface);

            if (ZSL_ENABLED) {
                builder.addTarget(mCaptureImageReader.getSurface());
            }

            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);

            builder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);

            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);

            addRegionsToCaptureRequestBuilder(builder);

            mCaptureSession.setRepeatingRequest(builder.build(), mCaptureManager,
                    mCameraHandler);
            return true;
        } catch (CameraAccessException e) {
            if (ZSL_ENABLED) {
                Log.v(TAG, "Could not execute zero-shutter-lag repeating request.", e);
            } else {
                Log.v(TAG, "Could not execute preview request.", e);
            }
            return false;
        }
    }

    /**
     * Request a single image.
     *
     * @return true if successful, false if there was an error submitting the
     *         capture request.
     */
    private boolean sendSingleRequest(OneCamera.PhotoCaptureParameters params) {
        Log.v(TAG, "sendSingleRequest()");
        try {
            CaptureRequest.Builder builder;
            builder = mDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);

            builder.addTarget(mPreviewSurface);

            // Always add this surface for single image capture requests.
            builder.addTarget(mCaptureImageReader.getSurface());

            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);

            addFlashToCaptureRequestBuilder(builder, params.flashMode);
            addRegionsToCaptureRequestBuilder(builder);

            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);

            // Tag this as a special request which should be saved.
            builder.setTag(RequestTag.EXPLICIT_CAPTURE);

            if (sCaptureImageFormat == ImageFormat.JPEG) {
                builder.set(CaptureRequest.JPEG_QUALITY, (byte) (JPEG_QUALITY));
                builder.set(CaptureRequest.JPEG_ORIENTATION,
                        CameraUtil.getJpegRotation(params.orientation, mCharacteristics));
            }

            mCaptureSession.capture(builder.build(), mCaptureManager,
                    mCameraHandler);
            return true;
        } catch (CameraAccessException e) {
            Log.v(TAG, "Could not execute single still capture request.", e);
            return false;
        }
    }

    private boolean sendAutoExposureTriggerRequest(Flash flashMode) {
        Log.v(TAG, "sendAutoExposureTriggerRequest()");
        try {
            CaptureRequest.Builder builder;
            if (ZSL_ENABLED) {
                builder = mDevice.
                        createCaptureRequest(CameraDevice.TEMPLATE_ZERO_SHUTTER_LAG);
            } else {
                builder = mDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            }

            builder.addTarget(mPreviewSurface);

            if (ZSL_ENABLED) {
                builder.addTarget(mCaptureImageReader.getSurface());
            }

            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);

            builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);

            addRegionsToCaptureRequestBuilder(builder);
            addFlashToCaptureRequestBuilder(builder, flashMode);

            mCaptureSession.capture(builder.build(), mCaptureManager,
                    mCameraHandler);

            return true;
        } catch (CameraAccessException e) {
            Log.v(TAG, "Could not execute auto exposure trigger request.", e);
            return false;
        }
    }

    /**
     */
    private boolean sendAutoFocusTriggerRequest() {
        Log.v(TAG, "sendAutoFocusTriggerRequest()");
        try {
            CaptureRequest.Builder builder;
            if (ZSL_ENABLED) {
                builder = mDevice.
                        createCaptureRequest(CameraDevice.TEMPLATE_ZERO_SHUTTER_LAG);
            } else {
                builder = mDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            }

            builder.addTarget(mPreviewSurface);

            if (ZSL_ENABLED) {
                builder.addTarget(mCaptureImageReader.getSurface());
            }

            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);

            addRegionsToCaptureRequestBuilder(builder);

            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO);
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);

            mCaptureSession.capture(builder.build(), mCaptureManager,
                    mCameraHandler);

            return true;
        } catch (CameraAccessException e) {
            Log.v(TAG, "Could not execute auto focus trigger request.", e);
            return false;
        }
    }

    /**
     * Like {@link #sendRepeatingCaptureRequest()}, but with the focus held
     * constant.
     *
     * @return true if successful, false if there was an error submitting the
     *         capture request.
     */
    private boolean sendAutoFocusHoldRequest() {
        Log.v(TAG, "sendAutoFocusHoldRequest()");
        try {
            CaptureRequest.Builder builder;
            if (ZSL_ENABLED) {
                builder = mDevice.
                        createCaptureRequest(CameraDevice.TEMPLATE_ZERO_SHUTTER_LAG);
            } else {
                builder = mDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            }

            builder.addTarget(mPreviewSurface);

            if (ZSL_ENABLED) {
                builder.addTarget(mCaptureImageReader.getSurface());
            }

            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);

            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO);
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);

            addRegionsToCaptureRequestBuilder(builder);
            // TODO: This should fire the torch, if appropriate.

            mCaptureSession.setRepeatingRequest(builder.build(), mCaptureManager, mCameraHandler);

            return true;
        } catch (CameraAccessException e) {
            Log.v(TAG, "Could not execute auto focus hold request.", e);
            return false;
        }
    }

    /**
     * Calculate the aspect ratio of the full size capture on this device.
     *
     * @param characteristics the characteristics of the camera device.
     * @return The aspect ration, in terms of width/height of the full capture
     *         size.
     */
    private static double calculateFullSizeAspectRatio(CameraCharacteristics characteristics) {
        Rect activeArraySize =
                characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        return (double) activeArraySize.width() / activeArraySize.height();
    }

    /**
     * Given an image reader, extracts the JPEG image bytes and then closes the
     * reader.
     *
     * @param img the image from which to extract jpeg bytes or compress to
     *            jpeg.
     * @return The bytes of the JPEG image. Newly allocated.
     */
    private byte[] acquireJpegBytes(Image img) {
        ByteBuffer buffer;

        if (img.getFormat() == ImageFormat.JPEG) {
            Image.Plane plane0 = img.getPlanes()[0];
            buffer = plane0.getBuffer();

            byte[] imageBytes = new byte[buffer.remaining()];
            buffer.get(imageBytes);
            buffer.rewind();
            return imageBytes;
        } else if (img.getFormat() == ImageFormat.YUV_420_888) {
            buffer = mJpegByteBufferPool.acquire();
            if (buffer == null) {
                buffer = ByteBuffer.allocateDirect(img.getWidth() * img.getHeight() * 3);
            }

            int numBytes = JpegUtilNative.compressJpegFromYUV420Image(img, buffer, JPEG_QUALITY);

            if (numBytes < 0) {
                throw new RuntimeException("Error compressing jpeg.");
            }

            buffer.limit(numBytes);

            byte[] imageBytes = new byte[buffer.remaining()];
            buffer.get(imageBytes);

            buffer.clear();
            mJpegByteBufferPool.release(buffer);

            return imageBytes;
        } else {
            throw new RuntimeException("Unsupported image format.");
        }
    }

    private void startAFCycle() {
        // Clean up any existing AF cycle's pending callbacks.
        mCameraHandler.removeCallbacksAndMessages(FOCUS_RESUME_CALLBACK_TOKEN);

        // Send a single CONTROL_AF_TRIGGER_START capture request.
        sendAutoFocusTriggerRequest();

        // Immediately send a request for a regular preview stream, but with
        // CONTROL_AF_MODE_AUTO set so that the focus remains constant after the
        // AF cycle completes.
        sendAutoFocusHoldRequest();

        // Waits Settings3A.getFocusHoldMillis() milliseconds before sending
        // a request for a regular preview stream to resume.
        mCameraHandler.postAtTime(new Runnable() {
            @Override
            public void run() {
                mAERegions = ZERO_WEIGHT_3A_REGION;
                mAFRegions = ZERO_WEIGHT_3A_REGION;
                sendRepeatingCaptureRequest();
            }
        }, FOCUS_RESUME_CALLBACK_TOKEN,
                SystemClock.uptimeMillis() + Settings3A.getFocusHoldMillis());
    }

    /**
     * @see com.android.camera.one.OneCamera#triggerFocusAndMeterAtPoint(float,
     *      float)
     */
    @Override
    public void triggerFocusAndMeterAtPoint(float nx, float ny) {
        // xc, yc is center of tap point in sensor coordinate system.
        int xc = mCropRegion.left + (int) (mCropRegion.width() * ny);
        int yc = mCropRegion.top + (int) (mCropRegion.height() * (1f - nx));

        mAERegions = AutoFocusHelper.aeRegionsForSensorCoord(xc, yc, mCropRegion);
        mAFRegions = AutoFocusHelper.afRegionsForSensorCoord(xc, yc, mCropRegion);

        startAFCycle();
    }

    @Override
    public Size pickPreviewSize(Size pictureSize, Context context) {
        float pictureAspectRatio = pictureSize.getWidth() / (float) pictureSize.getHeight();
        return CaptureModuleUtil.getOptimalPreviewSize(context, getSupportedSizes(),
                pictureAspectRatio);
    }

    @Override
    public float getMaxZoom() {
        return mCharacteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
    }

    @Override
    public void setZoom(float zoom) {
        mZoomValue = zoom;
        mCropRegion = cropRegionForZoom(zoom);
        sendRepeatingCaptureRequest();
    }

    private Rect cropRegionForZoom(float zoom) {
        return AutoFocusHelper.cropRegionForZoom(mCharacteristics, zoom);
    }
}