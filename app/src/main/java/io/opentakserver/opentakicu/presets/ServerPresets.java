package io.opentakserver.opentakicu.presets;

import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import io.opentakserver.opentakicu.contants.Preferences;

/**
 * Persistence + apply/capture helpers for {@link ServerPreset}. The list lives as a JSON array
 * string under {@link Preferences#SERVER_PRESETS} in the default SharedPreferences.
 */
public final class ServerPresets {
    private static final String LOGTAG = "ServerPresets";

    private ServerPresets() {}

    public static List<ServerPreset> load(SharedPreferences prefs) {
        List<ServerPreset> out = new ArrayList<>();
        String raw = prefs.getString(Preferences.SERVER_PRESETS, null);
        if (raw == null || raw.isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) out.add(ServerPreset.fromJson(o));
            }
        } catch (Exception e) {
            Log.e(LOGTAG, "Failed to parse presets", e);
        }
        return out;
    }

    public static void save(SharedPreferences prefs, List<ServerPreset> presets) {
        JSONArray arr = new JSONArray();
        try {
            for (ServerPreset p : presets) arr.put(p.toJson());
        } catch (Exception e) {
            Log.e(LOGTAG, "Failed to serialise presets", e);
        }
        prefs.edit().putString(Preferences.SERVER_PRESETS, arr.toString()).apply();
    }

    /** Snapshot the currently-configured streaming destination as a named preset. */
    public static ServerPreset captureCurrent(SharedPreferences prefs, String name) {
        return new ServerPreset(
                name,
                prefs.getString(Preferences.STREAM_PROTOCOL, Preferences.STREAM_PROTOCOL_DEFAULT),
                prefs.getString(Preferences.STREAM_ADDRESS, Preferences.STREAM_ADDRESS_DEFAULT),
                prefs.getString(Preferences.STREAM_PORT, Preferences.STREAM_PORT_DEFAULT),
                prefs.getString(Preferences.STREAM_PATH, Preferences.STREAM_PATH_DEFAULT),
                prefs.getString(Preferences.STREAM_USERNAME, Preferences.STREAM_USERNAME_DEFAULT),
                prefs.getString(Preferences.STREAM_PASSWORD, Preferences.STREAM_PASSWORD_DEFAULT),
                prefs.getBoolean(Preferences.STREAM_USE_TCP, Preferences.STREAM_USE_TCP_DEFAULT),
                prefs.getString(Preferences.STREAM_OBSERVER_URL, Preferences.STREAM_OBSERVER_URL_DEFAULT));
    }

    /** Write a preset's fields into the live STREAM_* prefs so the next stream uses it. */
    public static void apply(SharedPreferences prefs, ServerPreset p) {
        SharedPreferences.Editor e = prefs.edit();
        if (p.protocol != null) e.putString(Preferences.STREAM_PROTOCOL, p.protocol);
        if (p.address != null)  e.putString(Preferences.STREAM_ADDRESS, p.address);
        if (p.port != null)     e.putString(Preferences.STREAM_PORT, p.port);
        if (p.path != null)     e.putString(Preferences.STREAM_PATH, p.path);
        e.putString(Preferences.STREAM_USERNAME, p.username == null ? "" : p.username);
        e.putString(Preferences.STREAM_PASSWORD, p.password == null ? "" : p.password);
        e.putBoolean(Preferences.STREAM_USE_TCP, p.tcp);
        e.putString(Preferences.STREAM_OBSERVER_URL, p.observerUrl == null ? "" : p.observerUrl);
        e.apply();
    }
}
