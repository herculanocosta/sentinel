package io.opentakserver.opentakicu;

import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

public class SettingsFragment extends PreferenceFragmentCompat {
    private static final String LOGTAG = "SettingsFragment";

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.preferences, rootKey);

        Preference about = findPreference("about_sentinel");
        if (about != null) {
            about.setOnPreferenceClickListener(p -> { showAboutDialog(); return true; });
        }
    }

    private void showAboutDialog() {
        if (getContext() == null) return;
        String version = "?";
        try {
            PackageInfo pi = getContext().getPackageManager()
                    .getPackageInfo(getContext().getPackageName(), 0);
            version = pi.versionName + " (" + pi.versionCode + ")";
        } catch (Exception e) { Log.w(LOGTAG, "version lookup failed", e); }

        CharSequence body = Html.fromHtml(
                "<p style=\"color:#bbbbbb\">Version " + version + "</p>"
                        + getString(R.string.about_credits_body),
                Html.FROM_HTML_MODE_LEGACY);

        AlertDialog dlg = new AlertDialog.Builder(requireContext())
                .setTitle(R.string.about_credits_title)
                .setMessage(body)
                .setPositiveButton(android.R.string.ok, (d, w) -> d.dismiss())
                .create();
        dlg.show();
        // Make any embedded links tappable.
        TextView msg = dlg.findViewById(android.R.id.message);
        if (msg != null) msg.setMovementMethod(LinkMovementMethod.getInstance());
    }
}
