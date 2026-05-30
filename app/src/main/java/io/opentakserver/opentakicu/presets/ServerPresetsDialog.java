package io.opentakserver.opentakicu.presets;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import java.util.List;

/**
 * Self-contained management UI for {@link ServerPreset}s, built programmatically so it needs no
 * layout XML. Launched from Streaming settings. Lets the operator save the current destination,
 * apply a saved one (writes the STREAM_* prefs), and add/edit/delete entries.
 */
public final class ServerPresetsDialog {

    private ServerPresetsDialog() {}

    public static void show(Context ctx, SharedPreferences prefs, Runnable onChanged) {
        final List<ServerPreset> presets = ServerPresets.load(prefs);

        AlertDialog.Builder b = new AlertDialog.Builder(ctx).setTitle("Server presets");

        if (presets.isEmpty()) {
            b.setMessage("No presets yet.\n\nTap “Save current” to store the destination from "
                    + "Streaming settings, or “Add new” to enter one manually. Then switch "
                    + "servers in one tap.");
        } else {
            CharSequence[] items = new CharSequence[presets.size()];
            for (int i = 0; i < presets.size(); i++) {
                ServerPreset p = presets.get(i);
                String obs = (p.observerUrl != null && !p.observerUrl.isEmpty())
                        ? "\n▶ " + p.observerUrl : "";
                items[i] = (p.name == null || p.name.isEmpty() ? "(unnamed)" : p.name)
                        + "\n" + p.summary() + obs;
            }
            b.setItems(items, (d, which) -> presetActions(ctx, prefs, presets, which, onChanged));
        }

        b.setPositiveButton("Save current", (d, w) -> promptSaveCurrent(ctx, prefs, onChanged));
        b.setNeutralButton("Add new", (d, w) -> editPreset(ctx, prefs, -1, onChanged));
        b.setNegativeButton("Close", (d, w) -> d.dismiss());
        b.show();
    }

    private static void presetActions(Context ctx, SharedPreferences prefs,
                                      List<ServerPreset> presets, int index, Runnable onChanged) {
        ServerPreset p = presets.get(index);
        String name = p.name == null || p.name.isEmpty() ? "(unnamed)" : p.name;
        new AlertDialog.Builder(ctx)
                .setTitle(name)
                .setItems(new CharSequence[]{"Apply", "Edit", "Delete"}, (d, which) -> {
                    switch (which) {
                        case 0:
                            ServerPresets.apply(prefs, p);
                            Toast.makeText(ctx, "Applied “" + name + "”", Toast.LENGTH_SHORT).show();
                            if (onChanged != null) onChanged.run();
                            break;
                        case 1:
                            editPreset(ctx, prefs, index, onChanged);
                            break;
                        case 2:
                            presets.remove(index);
                            ServerPresets.save(prefs, presets);
                            Toast.makeText(ctx, "Deleted “" + name + "”", Toast.LENGTH_SHORT).show();
                            if (onChanged != null) onChanged.run();
                            break;
                    }
                })
                .setNegativeButton("Back", (d, w) -> show(ctx, prefs, onChanged))
                .show();
    }

    private static void promptSaveCurrent(Context ctx, SharedPreferences prefs, Runnable onChanged) {
        final EditText nameField = new EditText(ctx);
        nameField.setHint("Preset name");
        nameField.setSingleLine(true);
        int pad = dp(ctx, 20);
        nameField.setPadding(pad, dp(ctx, 8), pad, dp(ctx, 8));
        new AlertDialog.Builder(ctx)
                .setTitle("Save current server")
                .setView(nameField)
                .setPositiveButton("Save", (d, w) -> {
                    String name = nameField.getText().toString().trim();
                    if (name.isEmpty()) name = "Preset";
                    List<ServerPreset> presets = ServerPresets.load(prefs);
                    presets.add(ServerPresets.captureCurrent(prefs, name));
                    ServerPresets.save(prefs, presets);
                    Toast.makeText(ctx, "Saved “" + name + "”", Toast.LENGTH_SHORT).show();
                    if (onChanged != null) onChanged.run();
                })
                .setNegativeButton("Cancel", (d, w) -> d.dismiss())
                .show();
    }

    /** index < 0 → add new; otherwise edit the preset at that index. */
    private static void editPreset(Context ctx, SharedPreferences prefs, int index, Runnable onChanged) {
        final List<ServerPreset> presets = ServerPresets.load(prefs);
        final ServerPreset p = (index >= 0 && index < presets.size())
                ? presets.get(index)
                : ServerPresets.captureCurrent(prefs, "");

        ScrollView scroll = new ScrollView(ctx);
        LinearLayout form = new LinearLayout(ctx);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(ctx, 20);
        form.setPadding(pad, dp(ctx, 8), pad, dp(ctx, 8));
        scroll.addView(form);

        final EditText name = field(ctx, form, "Name", p.name, InputType.TYPE_CLASS_TEXT);

        // Protocol picker — selecting one fills in its standard port automatically.
        TextView protoLabel = new TextView(ctx);
        protoLabel.setText("Protocol");
        protoLabel.setPadding(0, dp(ctx, 10), 0, 0);
        form.addView(protoLabel);
        final Spinner protocol = new Spinner(ctx);
        protocol.setAdapter(new ArrayAdapter<>(ctx, android.R.layout.simple_spinner_dropdown_item, PROTOCOLS));
        int pidx = indexOf(PROTOCOLS, p.protocol == null ? "rtsp" : p.protocol);
        protocol.setSelection(pidx < 0 ? 0 : pidx);
        form.addView(protocol);

        final EditText address = field(ctx, form, "Address (host or IP)", p.address,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        final EditText port = field(ctx, form, "Port", p.port, InputType.TYPE_CLASS_NUMBER);
        final EditText path = field(ctx, form, "Path / stream name", p.path, InputType.TYPE_CLASS_TEXT);

        // Auto-fill the suggested port when the protocol changes (unless a custom port was typed).
        protocol.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
                String cur = port.getText().toString().trim();
                if (cur.isEmpty() || isKnownDefaultPort(cur)) port.setText(defaultPortFor(PROTOCOLS[pos]));
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        final EditText user = field(ctx, form, "Username (optional)", p.username, InputType.TYPE_CLASS_TEXT);
        final EditText pass = field(ctx, form, "Password (optional)", p.password, InputType.TYPE_CLASS_TEXT);
        final CheckBox tcp = new CheckBox(ctx);
        tcp.setText("Use TCP (RTSP interleaved)");
        tcp.setChecked(p.tcp);
        form.addView(tcp);

        // Observer / viewer URL — what's advertised to ATAK in the CoT __video (peers WATCH this).
        // Differs from the publish URL for SRT (read: vs publish:) and RTSP usually wants ?tcp.
        final EditText observer = field(ctx, form,
                "Observer / viewer URL — sent to ATAK (blank = auto)",
                p.observerUrl, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        Button suggest = new Button(ctx);
        suggest.setText("Suggest viewer URL from fields above");
        suggest.setOnClickListener(v -> observer.setText(ServerPreset.suggestObserverUrl(
                (String) protocol.getSelectedItem(), name(address), name(port), name(path))));
        form.addView(suggest);

        new AlertDialog.Builder(ctx)
                .setTitle(index < 0 ? "Add server preset" : "Edit server preset")
                .setView(scroll)
                .setPositiveButton("Save", (d, w) -> {
                    p.name = text(name, "Preset");
                    p.protocol = (String) protocol.getSelectedItem();
                    p.address = text(address, "");
                    p.port = text(port, "8554");
                    p.path = text(path, "");
                    p.username = name(user);
                    p.password = name(pass);
                    p.tcp = tcp.isChecked();
                    p.observerUrl = name(observer);
                    if (index < 0) presets.add(p);
                    ServerPresets.save(prefs, presets);
                    Toast.makeText(ctx, "Saved “" + p.name + "”", Toast.LENGTH_SHORT).show();
                    if (onChanged != null) onChanged.run();
                })
                .setNegativeButton("Cancel", (d, w) -> d.dismiss())
                .show();
    }

    // ---- small view helpers -------------------------------------------------------------------

    private static EditText field(Context ctx, LinearLayout parent, String hint, String value, int inputType) {
        TextView label = new TextView(ctx);
        label.setText(hint);
        label.setPadding(0, dp(ctx, 10), 0, 0);
        parent.addView(label);
        EditText et = new EditText(ctx);
        et.setSingleLine(true);
        et.setInputType(inputType);
        if (value != null) et.setText(value);
        parent.addView(et);
        return et;
    }

    private static String text(EditText et, String fallback) {
        String s = et.getText().toString().trim();
        return s.isEmpty() ? fallback : s;
    }

    private static String name(EditText et) {
        return et.getText().toString().trim();
    }

    private static int dp(Context ctx, int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                ctx.getResources().getDisplayMetrics());
    }

    static final String[] PROTOCOLS = {"rtsp", "rtsps", "rtmp", "rtmps", "srt", "udp"};

    /** MediaMTX default ingest ports per protocol. */
    private static String defaultPortFor(String proto) {
        if (proto == null) return "8554";
        switch (proto) {
            case "rtmp":
            case "rtmps": return "1935";
            case "srt":   return "8890";
            case "udp":   return "1234";
            default:      return "8554";   // rtsp / rtsps
        }
    }

    private static boolean isKnownDefaultPort(String p) {
        return "8554".equals(p) || "1935".equals(p) || "8890".equals(p) || "1234".equals(p);
    }

    private static int indexOf(String[] arr, String v) {
        for (int i = 0; i < arr.length; i++) if (arr[i].equalsIgnoreCase(v)) return i;
        return -1;
    }
}
