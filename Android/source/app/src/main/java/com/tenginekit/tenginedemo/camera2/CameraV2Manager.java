package com.tenginekit.tenginedemo.camera2;


import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.NonNull;

import com.tenginekit.engine.core.TengineKitSdk;
import com.tenginekit.tenginedemo.Constant;

import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class CameraV2Manager {

    public static final String TAG = Constant.LOG_TAG;

    private Activity mActivity;
    private CameraDevice mCameraDevice;
    private String mCameraId;
    private Size mPreviewSize;
    private HandlerThread mCameraThread;
    private Handler mCameraHandler;
    private SurfaceTexture mSurfaceTexture;
    private CaptureRequest.Builder mCaptureRequestBuilder;
    private CaptureRequest mCaptureRequest;
    private CameraCaptureSession mCameraCaptureSession;
    private ImageReader previewReader;
    private Range<Integer>[] fpsRender;
    private CameraV2GLSurfaceView mSurfaceView;

    public void setSurfaceCreated(boolean surfaceCreated) {
        isSurfaceCreated = surfaceCreated;
    }

    public boolean isSurfaceCreated = false;
    private FrameDataCallBack mFrameDataCallBack = null;

    public void updateSegRes(@Nullable Bitmap res) {
        mSurfaceView.updateSegRes(res);
    }


    public interface FrameDataCallBack {
        void onFrameData(byte[] data, int width, int height);
    }


    public CameraV2Manager(Activity activity, boolean useFront, Size size,
                           CameraV2GLSurfaceView surfaceView, FrameDataCallBack callBack) {
        mActivity = activity;
        mSurfaceView = surfaceView;
        surfaceView.init(this, false, activity);
        startCameraThread();
        setupCamera(useFront, size.getWidth(), size.getHeight());
        mFrameDataCallBack = callBack;
    }

    public String setupCamera(boolean useFront, int width, int height) {
        CameraManager cameraManager = (CameraManager) mActivity.getSystemService(Context.CAMERA_SERVICE);
        try {
            String camera = getCameraId(useFront, cameraManager);
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(camera);
            fpsRender = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            mPreviewSize = getOptimalSize(map.getOutputSizes(SurfaceTexture.class), width, height);
            mPreviewSize = new Size(1280, 960);
            mCameraId = camera;
            Log.i(TAG, "preview width = " + mPreviewSize.getWidth() + ", height = " + mPreviewSize.getHeight() + ", cameraId = " + mCameraId);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }


        return mCameraId;
    }


    public String getCameraId(boolean useFront, CameraManager cameraManager) {
        try {
            for (String cameraId : cameraManager.getCameraIdList()) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
                int cameraFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (useFront) {
                    if (cameraFacing == CameraCharacteristics.LENS_FACING_FRONT) {
                        return cameraId;
                    }
                } else {
                    if (cameraFacing == CameraCharacteristics.LENS_FACING_BACK) {
                        return cameraId;
                    }
                }
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        return null;
    }


    public void startCameraThread() {
        mCameraThread = new HandlerThread("CameraThread");
        mCameraThread.start();
        mCameraHandler = new Handler(mCameraThread.getLooper());
    }

    public boolean openCamera() {
        if (!isSurfaceCreated) {
            Log.e(TAG, "surface not created now");
            return false;
        }
        CameraManager cameraManager = (CameraManager) mActivity.getSystemService(Context.CAMERA_SERVICE);
        try {
            cameraManager.openCamera(mCameraId, mStateCallback, mCameraHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }


    public void releaseCamera() {
        if (mCameraDevice != null) {
            mCameraDevice.close();
            mCameraDevice = null;
        }
    }


    public CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            mCameraDevice = camera;
            startPreview();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            camera.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            camera.close();
            mCameraDevice = null;
        }
    };

    private Size getOptimalSize(Size[] sizeMap, int width, int height) {
        List<Size> sizeList = new ArrayList<>();
        for (Size option : sizeMap) {
            if (width > height) {
                if (option.getWidth() > width && option.getHeight() > height) {
                    sizeList.add(option);
                }
            } else {
                if (option.getWidth() > height && option.getHeight() > width) {
                    sizeList.add(option);
                }
            }
        }
        if (sizeList.size() > 0) {
            return Collections.min(sizeList, new Comparator<Size>() {
                @Override
                public int compare(Size lhs, Size rhs) {
                    return Long.signum(lhs.getWidth() * lhs.getHeight() - rhs.getWidth() * rhs.getHeight());
                }
            });
        }
        return sizeMap[0];
    }

    public void setPreviewTexture(SurfaceTexture surfaceTexture) {
        mSurfaceTexture = surfaceTexture;
    }

    public void startPreview() {
        mSurfaceTexture = mSurfaceView.getSurfaceTexture();

        mSurfaceTexture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
        Surface surface = new Surface(mSurfaceTexture);
        try {
            previewReader = ImageReader.newInstance(mPreviewSize.getWidth() / 2, mPreviewSize.getHeight() / 2, ImageFormat.YUV_420_888, 2);
            previewReader.setOnImageAvailableListener(
                    reader -> {
                        Image image = reader.acquireNextImage();
                        if (image != null) {
                            if (mFrameDataCallBack != null) {
                                Log.i(TAG,"image in");
                                byte[] nv21 = TengineKitSdk.getInstance().yuv2nv21camera2(image);
                                mFrameDataCallBack.onFrameData(nv21, image.getWidth(), image.getHeight());
                            }
                            image.close();
                        }
                    },
                    mCameraHandler);
            mCaptureRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mCaptureRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRender[2]);
            mCaptureRequestBuilder.addTarget(previewReader.getSurface());
            mCaptureRequestBuilder.addTarget(surface);
            mCameraDevice.createCaptureSession(Arrays.asList(previewReader.getSurface(), surface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    try {
                        mCaptureRequest = mCaptureRequestBuilder.build();
                        mCameraCaptureSession = session;
                        mCameraCaptureSession.setRepeatingRequest(mCaptureRequest, null, mCameraHandler);
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {

                }
            }, mCameraHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }
}
