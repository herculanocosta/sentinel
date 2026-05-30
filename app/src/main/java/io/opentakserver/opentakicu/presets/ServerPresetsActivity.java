package io.opentakserver.opentakicu.presets;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.List;

import io.opentakserver.opentakicu.R;
import io.opentakserver.opentakicu.contants.Preferences;

/**
 * Material 3 management screen for {@link ServerPreset}s: a card list with apply/edit/delete, an
 * Add FAB, an empty state, and a clean Material edit dialog. Replaces the old programmatic dialog.
 */
public class ServerPresetsActivity extends AppCompatActivity {

    static final String[] PROTOCOLS = {"rtsp", "rtsps", "rtmp", "rtmps", "srt", "udp"};
    /** Protocols ATAK can play — the viewer URL can use any of these, independent of publish. */
    static final String[] VIEWER_PROTOCOLS = {"rtsp", "rtmp", "srt"};

    private SharedPreferences prefs;
    private final List<ServerPreset> presets = new ArrayList<>();
    private PresetAdapter adapter;
    private RecyclerView recycler;
    private View empty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_server_presets);
        prefs = PreferenceManager.getDefaultSharedPreferences(this);

        MaterialToolbar toolbar = findViewById(R.id.presets_toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        recycler = findViewById(R.id.presets_recycler);
        empty = findViewById(R.id.presets_empty);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new PresetAdapter();
        recycler.setAdapter(adapter);

        final ExtendedFloatingActionButton fab = findViewById(R.id.presets_add);
        fab.setOnClickListener(v -> showEdit(null));
        recycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (dy > 4) fab.shrink(); else if (dy < -4) fab.extend();
            }
        });

        applyInsets();
        refresh();
    }

    private void applyInsets() {
        View root = findViewById(R.id.server_presets_root);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, wi) -> {
            Insets bars = wi.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        ViewCompat.requestApplyInsets(root);
    }

    private void refresh() {
        presets.clear();
        presets.addAll(ServerPresets.load(prefs));
        adapter.notifyDataSetChanged();
        empty.setVisibility(presets.isEmpty() ? View.VISIBLE : View.GONE);
        recycler.setVisibility(presets.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private boolean isActive(ServerPreset p) {
        return eq(p.protocol, prefs.getString(Preferences.STREAM_PROTOCOL, Preferences.STREAM_PROTOCOL_DEFAULT))
                && eq(p.address, prefs.getString(Preferences.STREAM_ADDRESS, Preferences.STREAM_ADDRESS_DEFAULT))
                && eq(p.port, prefs.getString(Preferences.STREAM_PORT, Preferences.STREAM_PORT_DEFAULT))
                && eq(p.path, prefs.getString(Preferences.STREAM_PATH, Preferences.STREAM_PATH_DEFAULT));
    }

    private void apply(ServerPreset p) {
        ServerPresets.apply(prefs, p);
        Toast.makeText(this, getString(R.string.preset_applied, displayName(p)), Toast.LENGTH_SHORT).show();
        adapter.notifyDataSetChanged();
    }

    private void confirmDelete(ServerPreset p) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.preset_delete_title, displayName(p)))
                .setMessage(R.string.preset_delete_body)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    presets.remove(p);
                    ServerPresets.save(prefs, presets);
                    refresh();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ---- edit / add dialog --------------------------------------------------------------------

    private void showEdit(ServerPreset existing) {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_server_preset_edit, null);
        final TextInputEditText name = v.findViewById(R.id.f_name);
        final MaterialAutoCompleteTextView protocol = v.findViewById(R.id.f_protocol);
        final TextInputEditText address = v.findViewById(R.id.f_address);
        final TextInputEditText port = v.findViewById(R.id.f_port);
        final TextInputEditText path = v.findViewById(R.id.f_path);
        final TextInputLayout tilPath = v.findViewById(R.id.til_path);
        final MaterialAutoCompleteTextView viewerProtocol = v.findViewById(R.id.f_viewer_protocol);
        final TextInputEditText observer = v.findViewById(R.id.f_observer);
        final TextInputEditText username = v.findViewById(R.id.f_username);
        final TextInputEditText password = v.findViewById(R.id.f_password);
        final MaterialSwitch tcp = v.findViewById(R.id.f_tcp);
        final MaterialButton suggest = v.findViewById(R.id.f_suggest);

        protocol.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, PROTOCOLS));
        viewerProtocol.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, VIEWER_PROTOCOLS));

        final ServerPreset p = existing != null ? existing : ServerPresets.captureCurrent(prefs, "");
        // userEdited: once the viewer URL is typed by hand, stop overwriting it.
        // viewerChosen: once a viewer protocol is explicitly picked (or restored from a saved URL),
        // don't let a publish-protocol change override it — they're independent from then on.
        final boolean[] userEdited = { p.observerUrl != null && !p.observerUrl.isEmpty() };
        final boolean[] viewerChosen = { userEdited[0] };
        final boolean[] suppress = { false };

        name.setText(p.name);
        protocol.setText((p.protocol == null || p.protocol.isEmpty()) ? "rtsp" : p.protocol, false);
        address.setText(p.address);
        port.setText(p.port);
        path.setText(p.path);
        username.setText(p.username);
        password.setText(p.password);
        tcp.setChecked(p.tcp);
        observer.setText(p.observerUrl);
        // Viewer protocol = scheme of the saved viewer URL, else the publish protocol mapped to a
        // playable one. Independent of publish from here on.
        viewerProtocol.setText(userEdited[0] ? schemeOf(p.observerUrl) : mapToViewer(str(protocol)), false);
        updatePathHelper(str(protocol), tilPath);

        // Rebuild the viewer URL from the viewer protocol + shared host/name (SRT -> read:).
        final Runnable rebuild = () -> {
            suppress[0] = true;
            observer.setText(ServerPreset.suggestObserverUrl(
                    str(viewerProtocol), str(address), defaultPortFor(str(viewerProtocol)), str(path)));
            suppress[0] = false;
        };
        if (!userEdited[0]) rebuild.run();

        // Publish protocol: auto-fill its port + the SRT publish/read hint. If the viewer hasn't
        // been explicitly chosen yet, follow it (so picking SRT publish gives an SRT read: viewer).
        protocol.setOnItemClickListener((parent, view, pos, id) -> {
            String cur = str(port);
            if (cur.isEmpty() || isKnownDefaultPort(cur)) port.setText(defaultPortFor(PROTOCOLS[pos]));
            updatePathHelper(PROTOCOLS[pos], tilPath);
            if (!viewerChosen[0] && !userEdited[0]) {
                viewerProtocol.setText(mapToViewer(PROTOCOLS[pos]), false);
                rebuild.run();
            }
        });
        // Picking a viewer protocol pins it (independent of publish) and (re)builds the URL.
        viewerProtocol.setOnItemClickListener((parent, view, pos, id) -> {
            viewerChosen[0] = true;
            userEdited[0] = false;
            rebuild.run();
        });
        suggest.setOnClickListener(b -> { userEdited[0] = false; rebuild.run(); });
        // Manual edits to the viewer URL win; shared fields keep the auto URL fresh until then.
        onTextChange(observer, () -> { if (!suppress[0]) userEdited[0] = true; });
        onTextChange(address, () -> { if (!userEdited[0]) rebuild.run(); });
        onTextChange(path, () -> { if (!userEdited[0]) rebuild.run(); });

        new MaterialAlertDialogBuilder(this)
                .setTitle(existing == null ? R.string.preset_add_title : R.string.preset_edit_title)
                .setView(v)
                .setPositiveButton(R.string.save, (d, w) -> {
                    p.name = orDefault(str(name), "Preset");
                    p.protocol = orDefault(str(protocol), "rtsp");
                    p.address = str(address);
                    p.port = orDefault(str(port), defaultPortFor(p.protocol));
                    p.path = str(path);
                    p.observerUrl = str(observer);
                    p.username = str(username);
                    p.password = str(password);
                    p.tcp = tcp.isChecked();
                    if (existing == null) presets.add(p);
                    ServerPresets.save(prefs, presets);
                    refresh();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void updatePathHelper(String publishProto, TextInputLayout tilPath) {
        if ("srt".equalsIgnoreCase(publishProto)) {
            tilPath.setHelperTextEnabled(true);
            tilPath.setHelperText(getString(R.string.preset_srt_path_helper));
        } else {
            tilPath.setHelperText(null);
            tilPath.setHelperTextEnabled(false);
        }
    }

    private static void onTextChange(EditText e, Runnable r) {
        e.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { r.run(); }
        });
    }

    /** Map a publish protocol to the playable viewer protocol ATAK should default to. */
    private static String mapToViewer(String publishProto) {
        if (publishProto == null) return "rtsp";
        switch (publishProto.toLowerCase()) {
            case "srt":   return "srt";
            case "rtmp":
            case "rtmps": return "rtmp";
            default:      return "rtsp";   // rtsp / rtsps / udp -> rtsp
        }
    }

    /** Scheme of a viewer URL, normalised to a playable viewer protocol. */
    private static String schemeOf(String url) {
        if (url == null) return "rtsp";
        int s = url.indexOf("://");
        String scheme = s > 0 ? url.substring(0, s).toLowerCase() : "rtsp";
        return mapToViewer(scheme);
    }

    // ---- adapter ------------------------------------------------------------------------------

    private class PresetAdapter extends RecyclerView.Adapter<PresetVH> {
        @NonNull
        @Override
        public PresetVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_server_preset, parent, false);
            return new PresetVH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull PresetVH h, int position) {
            ServerPreset p = presets.get(position);
            h.name.setText(displayName(p));
            h.summary.setText(p.summary());
            boolean hasObs = p.observerUrl != null && !p.observerUrl.isEmpty();
            h.observer.setVisibility(hasObs ? View.VISIBLE : View.GONE);
            if (hasObs) h.observer.setText("▶ " + p.observerUrl);
            boolean active = isActive(p);
            h.active.setVisibility(active ? View.VISIBLE : View.GONE);
            ((MaterialCardView) h.itemView).setChecked(active);
            ((MaterialCardView) h.itemView).setStrokeWidth(active ? dp(2) : 0);

            h.itemView.setOnClickListener(v -> apply(p));
            h.edit.setOnClickListener(v -> showEdit(p));
            h.delete.setOnClickListener(v -> confirmDelete(p));
        }

        @Override
        public int getItemCount() {
            return presets.size();
        }
    }

    private static class PresetVH extends RecyclerView.ViewHolder {
        final TextView name, summary, observer, active;
        final ImageButton edit, delete;

        PresetVH(@NonNull View v) {
            super(v);
            name = v.findViewById(R.id.preset_name);
            summary = v.findViewById(R.id.preset_summary);
            observer = v.findViewById(R.id.preset_observer);
            active = v.findViewById(R.id.preset_active);
            edit = v.findViewById(R.id.preset_edit);
            delete = v.findViewById(R.id.preset_delete);
        }
    }

    // ---- helpers ------------------------------------------------------------------------------

    private static String displayName(ServerPreset p) {
        return (p.name == null || p.name.isEmpty()) ? "(unnamed)" : p.name;
    }

    private static String str(TextView t) {
        CharSequence c = t.getText();
        return c == null ? "" : c.toString().trim();
    }

    private static String orDefault(String s, String fallback) {
        return s.isEmpty() ? fallback : s;
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    static String defaultPortFor(String proto) {
        if (proto == null) return "8554";
        switch (proto) {
            case "rtmp":
            case "rtmps": return "1935";
            case "srt":   return "8890";
            case "udp":   return "1234";
            default:      return "8554";
        }
    }

    static boolean isKnownDefaultPort(String p) {
        return "8554".equals(p) || "1935".equals(p) || "8890".equals(p) || "1234".equals(p);
    }
}
