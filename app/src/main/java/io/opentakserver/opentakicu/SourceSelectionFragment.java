package io.opentakserver.opentakicu;

import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import io.opentakserver.opentakicu.contants.Preferences;

/**
 * Initial landing page: pick the broadcast source before going to the streaming screen.
 *
 * Because the source can't be switched mid-broadcast, choosing it up front is the cleaner flow.
 * Picking a source writes the {@link Preferences#VIDEO_SOURCE} pref and navigates to
 * {@link Camera2Fragment}. For GoPro we also pass {@code autoConnectGoPro=true} so the streaming
 * screen immediately kicks off the BLE→Wi-Fi connect flow.
 */
public class SourceSelectionFragment extends Fragment {

    public static final String ARG_AUTO_CONNECT_GOPRO = "autoConnectGoPro";

    public SourceSelectionFragment() {
        super(R.layout.fragment_source_selection);
    }

    @Override
    public void onResume() {
        super.onResume();
        // The source-selection page is the app's portrait "home" screen. (The camera/streaming
        // screen flips itself to landscape, where the 16:9 preview fits without stretching.)
        requireActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        final SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(requireContext());

        view.findViewById(R.id.btn_source_camera).setOnClickListener(v ->
                choose(pref, Preferences.VIDEO_SOURCE_DEFAULT, false));
        view.findViewById(R.id.btn_source_usb).setOnClickListener(v ->
                choose(pref, Preferences.VIDEO_SOURCE_USB, false));
        view.findViewById(R.id.btn_source_screen).setOnClickListener(v ->
                choose(pref, Preferences.VIDEO_SOURCE_SCREEN, false));
        view.findViewById(R.id.btn_source_gopro).setOnClickListener(v ->
                choose(pref, Preferences.VIDEO_SOURCE_GOPRO, true));
    }

    private void choose(SharedPreferences pref, String source, boolean autoConnectGoPro) {
        // For GoPro we DON'T set the source yet — the streaming screen flips VIDEO_SOURCE to gopro
        // only after the BLE→Wi-Fi connect succeeds (otherwise a GoProSource is built before its
        // network/keep-alive client exists, and never tells the camera to push). Until then the
        // streaming screen shows the normal camera preview.
        if (!autoConnectGoPro) {
            pref.edit().putString(Preferences.VIDEO_SOURCE, source).apply();
        }

        // Orient the activity for the streaming screen BEFORE we navigate. The streaming screen is
        // landscape (per the chosen STREAM_ORIENTATION); if we let it rotate AFTER the camera
        // preview is already preparing, the first frames are built portrait and shown stretched in
        // the landscape view. Rotating here means the camera is prepared in its final orientation.
        String streamOri = pref.getString(Preferences.STREAM_ORIENTATION, Preferences.STREAM_ORIENTATION_DEFAULT);
        requireActivity().setRequestedOrientation(
                Preferences.STREAM_ORIENTATION_PORTRAIT.equals(streamOri)
                        ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                        : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);

        Bundle args = new Bundle();
        if (autoConnectGoPro) args.putBoolean(ARG_AUTO_CONNECT_GOPRO, true);

        requireActivity().getSupportFragmentManager().beginTransaction()
                .setReorderingAllowed(true)
                .replace(R.id.fragment_container_view, Camera2Fragment.class, args)
                .addToBackStack("camera")
                .commit();
    }
}
