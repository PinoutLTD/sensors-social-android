package social.sensors.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Periodic air check for the followed sensors (WorkManager, about every 15 minutes).
 * For each sensor: average the last 30 min of readings and notify once when the air turns poor,
 * once when it is good again, and once when the sensor has been silent for 2 hours.
 * Thresholds match the tiles in www/app.js.
 */
public class AlertsWorker extends Worker {

    static final String PREFS = "alerts";
    static final String CHANNEL = "air_alerts";
    private static final String API = "https://roseman.robonomics.network/";
    private static final long OFFLINE_AFTER = 2 * 3600;
    // Fallback until the app hands over the map's zones (www/zones.js → AlertsPlugin.configure):
    // poor above the last calm zone, good again in the green one. Same numbers as the map on 11.11.2026.
    private static final String DEFAULT_RULES = "["
        + "{\"key\":\"pm25\",\"label\":\"PM2.5\",\"unit\":\"µg/m³\",\"dec\":1,\"good\":30,\"poor\":55,\"poorLabel\":\"Unhealthy\"},"
        + "{\"key\":\"pm10\",\"label\":\"PM10\",\"unit\":\"µg/m³\",\"dec\":1,\"good\":50,\"poor\":100,\"poorLabel\":\"Unhealthy\"},"
        + "{\"key\":\"co2\",\"label\":\"CO₂\",\"unit\":\"ppm\",\"dec\":0,\"good\":1000,\"poor\":1000,\"poorLabel\":\"Elevated\"}"
        + "]";

    public AlertsWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            check(getApplicationContext(), false);
            return Result.success();
        } catch (Exception e) {
            return Result.retry();
        }
    }

    /** Runs one check; returns the number of notifications posted. */
    static int check(Context ctx, boolean test) throws Exception {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONArray sensors = new JSONArray(prefs.getString("sensors", "[]"));
        JSONArray rules = new JSONArray(prefs.getString("rules", DEFAULT_RULES));
        JSONObject state = new JSONObject(prefs.getString("state", "{}"));
        long now = System.currentTimeMillis() / 1000;
        int sent = 0;
        ensureChannel(ctx);

        for (int i = 0; i < sensors.length(); i++) {
            JSONObject s = sensors.getJSONObject(i);
            String id = s.getString("id");
            String name = s.optString("name", id);
            JSONObject st = state.optJSONObject(id);
            if (st == null) st = new JSONObject();

            JSONArray rows;
            try {
                JSONObject res = new JSONObject(get(API + "api/v2/sensor/" + id + "/" + (now - 3 * 3600) + "/" + now));
                rows = res.optJSONArray("result");
            } catch (Exception e) {
                continue; // network trouble: try again next run
            }
            if (rows == null) rows = new JSONArray();

            long last = 0;
            for (int r = 0; r < rows.length(); r++) last = Math.max(last, rows.getJSONObject(r).optLong("timestamp"));

            if (last == 0 || now - last > OFFLINE_AFTER) {
                if (!st.optBoolean("offline") || test) {
                    notify(ctx, id + ":offline", name + " is offline", "No readings for over 2 hours. Check power and Wi-Fi.");
                    sent++;
                }
                st.put("offline", true);
                state.put(id, st);
                continue;
            }
            st.put("offline", false);

            boolean reported = false;
            for (int ri = 0; ri < rules.length(); ri++) {
                JSONObject rule = rules.getJSONObject(ri);
                String k = rule.getString("key");
                double sum = 0;
                int n = 0;
                for (int r = 0; r < rows.length(); r++) {
                    JSONObject row = rows.getJSONObject(r);
                    if (row.optLong("timestamp") < last - 1800) continue;
                    JSONObject data = row.optJSONObject("data");
                    if (data == null || !data.has(k)) continue;
                    Object v = data.opt(k);
                    if (v instanceof Number) {
                        sum += ((Number) v).doubleValue();
                        n++;
                    }
                }
                if (n == 0) continue;
                double avg = sum / n;
                double good = rule.getDouble("good");
                double poor = rule.getDouble("poor");
                String level = avg > poor ? "poor" : avg <= good ? "good" : "fair";
                String was = st.optString(k, "good");
                String text = String.format(Locale.US, "%s %." + rule.optInt("dec", 1) + "f %s (30-min average)",
                    rule.optString("label", k), avg, rule.optString("unit", ""));
                if (level.equals("poor") && !was.equals("poor")) {
                    notify(ctx, id + ":" + k, rule.optString("poorLabel", "Poor air") + " · " + name, text);
                    sent++;
                    reported = true;
                } else if (level.equals("good") && was.equals("poor")) {
                    notify(ctx, id + ":" + k, "Air is good again · " + name, text);
                    sent++;
                    reported = true;
                } else if (test && !reported) {
                    notify(ctx, id + ":test", "Test · " + name, text + ". Alerts are on.");
                    sent++;
                    reported = true;
                }
                // "fair" keeps the previous state, so poor → fair → poor does not notify twice
                if (!level.equals("fair")) st.put(k, level);
            }
            if (test && !reported) {
                notify(ctx, id + ":test", "Test · " + name, "Alerts are on. No PM or CO₂ readings in the last 30 minutes.");
                sent++;
            }
            state.put(id, st);
        }
        prefs.edit().putString("state", state.toString()).apply();
        return sent;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        try (BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null) sb.append(line);
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }

    static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Air alerts", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("Poor air and offline sensors you follow");
            ctx.getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private static void notify(Context ctx, String key, String title, String body) {
        Intent open = new Intent(ctx, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_air)
            .setColor(0xFF3FB950)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true);
        try {
            NotificationManagerCompat.from(ctx).notify(key.hashCode(), b.build());
        } catch (SecurityException e) {
            // notifications permission revoked: nothing to post to
        }
    }
}
