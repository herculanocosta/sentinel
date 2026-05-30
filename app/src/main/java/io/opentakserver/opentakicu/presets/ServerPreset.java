package io.opentakserver.opentakicu.presets;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * One saved streaming destination. Holds just the connection fields (not codec/bitrate) so the
 * operator can keep several servers — home MediaMTX, a field relay, a teammate's laptop — and
 * switch between them in a single tap from Streaming settings.
 */
public class ServerPreset {
    public String name;
    public String protocol;
    public String address;
    public String port;
    public String path;
    public String username;
    public String password;
    public boolean tcp;
    /** Viewer/playback URL sent to ATAK via CoT (may differ from the publish URL). Blank = auto. */
    public String observerUrl;

    public ServerPreset() {}

    public ServerPreset(String name, String protocol, String address, String port,
                        String path, String username, String password, boolean tcp, String observerUrl) {
        this.name = name;
        this.protocol = protocol;
        this.address = address;
        this.port = port;
        this.path = path;
        this.username = username;
        this.password = password;
        this.tcp = tcp;
        this.observerUrl = observerUrl;
    }

    /**
     * Suggest a sensible viewer/observer URL from the connection fields. RTSP/RTMP viewer == publish
     * URL (RTSP gets a trailing {@code ?tcp}); SRT viewers use {@code streamid=read:NAME}.
     */
    public static String suggestObserverUrl(String protocol, String address, String port, String path) {
        String a = address == null ? "" : address.trim();
        String p = path == null ? "" : path.trim();
        String pt = port == null ? "" : port.trim();
        String proto = protocol == null ? "rtsp" : protocol.trim().toLowerCase();
        switch (proto) {
            case "rtmp":
            case "rtmps":
                return proto + "://" + a + ":" + (pt.isEmpty() ? "1935" : pt) + "/" + p;
            case "srt":
                return "srt://" + a + ":" + pt + "?streamid=read:" + p;
            case "udp":
                return "udp://" + a + ":" + pt;
            case "rtsp":
            case "rtsps":
            default:
                return proto + "://" + a + ":" + (pt.isEmpty() ? "8554" : pt) + "/" + p + "?tcp";
        }
    }

    /** A one-line "rtsp://host:port/path" summary for the list UI. */
    @NonNull
    public String summary() {
        String p = protocol == null ? "" : protocol;
        String a = address == null ? "" : address;
        String pt = port == null ? "" : port;
        String pa = path == null ? "" : path;
        return p + "://" + a + ":" + pt + "/" + pa;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("name", name);
        o.put("protocol", protocol);
        o.put("address", address);
        o.put("port", port);
        o.put("path", path);
        o.put("username", username);
        o.put("password", password);
        o.put("tcp", tcp);
        o.put("observerUrl", observerUrl);
        return o;
    }

    public static ServerPreset fromJson(JSONObject o) {
        ServerPreset p = new ServerPreset();
        p.name = o.optString("name", "");
        p.protocol = o.optString("protocol", "rtsp");
        p.address = o.optString("address", "");
        p.port = o.optString("port", "8554");
        p.path = o.optString("path", "");
        p.username = o.optString("username", "");
        p.password = o.optString("password", "");
        p.tcp = o.optBoolean("tcp", false);
        p.observerUrl = o.optString("observerUrl", "");
        return p;
    }
}
