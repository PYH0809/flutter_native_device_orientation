package com.github.rmtmckenzie.native_device_orientation;

import android.app.Activity;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.Settings;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.Map;

import io.flutter.Log;
import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;

/**
 * NativeDeviceOrientationPlugin
 */
public class NativeDeviceOrientationPlugin implements FlutterPlugin, MethodCallHandler, EventChannel.StreamHandler, ActivityAware {
    private MethodChannel channel;
    private EventChannel eventChannel;
    private Activity activity;
    private IOrientationListener listener;
    private boolean checkIsAutoRotate = true;
    // PiliPlus: the listener is made, started and stopped, and the sensor heard, on a thread of
    // its own: registering the sensor held the main thread, which hands Flutter its vsyncs, for
    // tens of milliseconds. Its events go to Dart from the main thread.
    private HandlerThread sensorThread;
    private Handler sensorHandler;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // PiliPlus: stops the listener on its thread.
    private void stopListener() {
        sensorHandler.post(() -> {
            if (listener != null) {
                listener.stopOrientationListener();
                listener = null;
            }
        });
    }

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
        channel = new MethodChannel(flutterPluginBinding.getBinaryMessenger(), "native_device_orientation");
        channel.setMethodCallHandler(this);

        eventChannel = new EventChannel(flutterPluginBinding.getBinaryMessenger(), "native_device_orientation_events");
        eventChannel.setStreamHandler(this);

        // PiliPlus: see sensorThread.
        sensorThread = new HandlerThread("NativeDeviceOrientation");
        sensorThread.start();
        sensorHandler = new Handler(sensorThread.getLooper());
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        // PiliPlus: on its thread, which then ends.
        stopListener();
        sensorThread.quitSafely();
        channel.setMethodCallHandler(null);
        eventChannel.setStreamHandler(null);
    }

    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
        this.activity = binding.getActivity();
    }

    @Override
    public void onDetachedFromActivity() {
        // PiliPlus: on its thread.
        stopListener();
        this.activity = null;
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {
        onDetachedFromActivity();
    }

    @Override
    public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
        this.onAttachedToActivity(binding);
    }

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull final Result result) {
        switch (call.method) {
            case "pause":
                // if a listener is currently active, stop listening. The app is going to the background
                // PiliPlus: on its thread.
                sensorHandler.post(() -> {
                    if (listener != null) {
                        listener.stopOrientationListener();
                    }
                });
                result.success(null);
                break;

            case "resume":
                // start listening for orientation changes again. The app is in the foreground.
                // PiliPlus: on its thread.
                sensorHandler.post(() -> {
                    if (listener != null) {
                        listener.startOrientationListener();
                    }
                });
                result.success(null);
                break;
            default:
                result.notImplemented();
        }
    }

    @Override
    public void onListen(Object parameters, final EventChannel.EventSink eventSink) {
        if (activity == null) {
            throw new IllegalStateException("Cannot start listening while activity is detached");
        }

        int angleDegrees = 30;
        // used hashMap to send parameters to this method. This makes it easier in the future to add new parameters if needed.
        if (parameters instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) parameters;

            if (params.containsKey("checkIsAutoRotate")) {
                Boolean checkIsAutoRotateNullable = (Boolean) params.get("checkIsAutoRotate");
                checkIsAutoRotate = checkIsAutoRotateNullable != null && checkIsAutoRotateNullable;
            }

            if (params.containsKey("angleDegrees")) {
                Integer angleDegreesNullable = (Integer) params.get("angleDegrees");
                if (angleDegreesNullable != null) {
                    angleDegrees = angleDegreesNullable;
                }
            }
        }

        // initialize the callback. It is the same for both listeners.
        // PiliPlus: called on sensorThread, which also reads the setting; the event goes to Dart
        // from the main thread.
        final Activity activity = this.activity;
        final boolean checkIsAutoRotate = this.checkIsAutoRotate;
        IOrientationListener.OrientationCallback callback = orientation -> {
            if (checkIsAutoRotate) {
                Map<String, Object> map = new HashMap<>();
                map.put("orientation", orientation.name());
                map.put("isAutoRotate", Settings.System.getInt(activity.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0) == 1);
                mainHandler.post(() -> eventSink.success(map));
                return;
            }
            mainHandler.post(() -> eventSink.success(orientation.name()));
        };

        // PiliPlus: made and started on sensorThread, see there.
        final int degrees = angleDegrees;
        sensorHandler.post(() -> {
            if (listener != null) {
                listener.stopOrientationListener();
            }
            listener = new SensorOrientationListener(activity, callback, degrees, sensorHandler);
            listener.startOrientationListener();
        });
    }

    @Override
    public void onCancel(Object arguments) {
        // PiliPlus: on its thread.
        stopListener();
    }
}
