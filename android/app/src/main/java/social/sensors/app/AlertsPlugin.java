package social.sensors.app;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.os.Build;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Bridge for www/app.js: which sensors to watch, notification permission, a test run. */
@CapacitorPlugin(
    name = "Alerts",
    permissions = { @Permission(alias = "notifications", strings = { Manifest.permission.POST_NOTIFICATIONS }) }
)
public class AlertsPlugin extends Plugin {

    private static final String WORK = "air-alerts";

    @PluginMethod
    public void configure(PluginCall call) {
        JSArray sensors = call.getArray("sensors", new JSArray());
        Context ctx = getContext();
        ctx.getSharedPreferences(AlertsWorker.PREFS, Context.MODE_PRIVATE).edit().putString("sensors", sensors.toString()).apply();
        WorkManager wm = WorkManager.getInstance(ctx);
        if (sensors.length() == 0) {
            wm.cancelUniqueWork(WORK);
        } else {
            PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(AlertsWorker.class, 15, TimeUnit.MINUTES)
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build();
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req);
        }
        JSObject ret = new JSObject();
        ret.put("watching", sensors.length());
        call.resolve(ret);
    }

    @PluginMethod
    public void checkNow(PluginCall call) {
        boolean test = call.getBoolean("test", false);
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                JSObject ret = new JSObject();
                ret.put("sent", AlertsWorker.check(getContext(), test));
                call.resolve(ret);
            } catch (Exception e) {
                call.reject(e.getMessage());
            }
        });
    }

    /** Setup step 2: the user joins the Altruist-xxxx network in the system Wi-Fi screen. */
    @PluginMethod
    public void openWifiSettings(PluginCall call) {
        Intent i = new Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(i);
        call.resolve();
    }

    @PluginMethod
    public void permission(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("granted", granted());
        call.resolve(ret);
    }

    @PluginMethod
    public void requestNotifications(PluginCall call) {
        if (granted()) {
            JSObject ret = new JSObject();
            ret.put("granted", true);
            call.resolve(ret);
            return;
        }
        requestPermissionForAlias("notifications", call, "onPermission");
    }

    @PermissionCallback
    private void onPermission(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("granted", granted());
        call.resolve(ret);
    }

    private boolean granted() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true;
        return getPermissionState("notifications") == PermissionState.GRANTED;
    }
}
