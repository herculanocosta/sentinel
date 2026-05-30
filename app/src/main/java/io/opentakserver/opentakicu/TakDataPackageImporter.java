package io.opentakserver.opentakicu;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;
import android.util.Xml;

import androidx.preference.PreferenceManager;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

/**
 * Imports an ATAK / TAK Server Data Package (.zip) into OpenTAK ICU preferences.
 *
 * The zip is expected to contain:
 * - one {@code *.pref} XML file with a {@code cot_streams} preference block
 * - one or more {@code *.p12} files referenced from the .pref by {@code caLocation0}
 *   ({@code trust_store_certificate}) and {@code certificateLocation0} ({@code client_certificate})
 *
 * Used from both {@code ATAKPreferencesFragment} (settings UI) and {@code MainActivity}
 * (share / view intent from a file manager).
 */
public class TakDataPackageImporter {
    private static final String LOGTAG = "TakImporter";

    /** Result of an import attempt. */
    public static class Result {
        public boolean success;
        /** User-friendly summary or error message. */
        public String message;
        /** Server host (if parsed). null on failure. */
        public String host;
        /** Server port (if parsed). 0 on failure. */
        public int port;
        /** true if connectString0 protocol == ssl/https/tls. */
        public boolean ssl;
        /** Absolute path to the trust-store .p12 we extracted, if any. */
        public String trustStorePath;
        public String trustStorePassword;
        /** Absolute path to the client .p12 we extracted, if any. */
        public String clientCertPath;
        public String clientCertPassword;
    }

    public static class ConnectionTestResult {
        public boolean tcpOk;
        public boolean tlsOk;
        public String details;
    }

    /**
     * Extract the zip, parse the .pref, apply matching keys to the default SharedPreferences,
     * and return a {@link Result} describing what was imported.
     */
    public static Result importFromUri(Context ctx, Uri zipUri) {
        Result res = new Result();
        File filesDir = ctx.getFilesDir();
        Map<String, File> extracted = new HashMap<>();
        String prefXml = null;

        try (InputStream is = ctx.getContentResolver().openInputStream(zipUri);
             ZipInputStream zis = new ZipInputStream(is)) {
            ZipEntry entry;
            byte[] buf = new byte[4096];
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    zis.closeEntry();
                    continue;
                }
                String name = entry.getName().replace('\\', '/');
                String baseName = name.substring(name.lastIndexOf('/') + 1);
                String lower = baseName.toLowerCase();

                if (lower.endsWith(".pref")) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    int n;
                    while ((n = zis.read(buf)) > 0) baos.write(buf, 0, n);
                    prefXml = baos.toString(StandardCharsets.UTF_8.name());
                } else if (lower.endsWith(".p12") || lower.endsWith(".pem") || lower.endsWith(".jks")) {
                    File dest = new File(filesDir, baseName);
                    try (FileOutputStream fos = new FileOutputStream(dest)) {
                        int n;
                        while ((n = zis.read(buf)) > 0) fos.write(buf, 0, n);
                    }
                    extracted.put(baseName, dest);
                    Log.d(LOGTAG, "Extracted " + baseName + " -> " + dest.getAbsolutePath());
                }
                zis.closeEntry();
            }
        } catch (Exception e) {
            res.success = false;
            res.message = "Could not read zip: " + e.getMessage();
            return res;
        }

        if (prefXml == null) {
            res.success = false;
            res.message = "No .pref file found inside the zip.";
            return res;
        }

        Map<String, String> values;
        try {
            values = parseCotStreamsPref(prefXml);
        } catch (Exception e) {
            res.success = false;
            res.message = "Could not parse .pref: " + e.getMessage();
            return res;
        }

        String connectString = values.get("connectString0");
        if (connectString == null) {
            res.success = false;
            res.message = "The .pref file is missing a connectString.";
            return res;
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        SharedPreferences.Editor editor = prefs.edit();
        StringBuilder summary = new StringBuilder();

        String[] parts = connectString.split(":");
        if (parts.length >= 1) {
            res.host = parts[0];
            editor.putString("atak_address", res.host);
            summary.append("Server: ").append(res.host).append('\n');
        }
        if (parts.length >= 2) {
            try {
                res.port = Integer.parseInt(parts[1].trim());
            } catch (NumberFormatException ignored) {}
            editor.putString("atak_port", parts[1]);
            summary.append("Port: ").append(parts[1]).append('\n');
        }
        if (parts.length >= 3) {
            String proto = parts[2].toLowerCase();
            res.ssl = proto.equals("ssl") || proto.equals("https") || proto.equals("tls");
            editor.putBoolean("atak_ssl", res.ssl);
            summary.append("Protocol: ").append(parts[2]).append('\n');
        }

        if ("true".equalsIgnoreCase(values.get("enabled0"))) {
            editor.putBoolean("send_cot", true);
            summary.append("CoT streaming: enabled\n");
        }

        String description = values.get("description0");
        if (description != null) {
            summary.insert(0, "Imported: " + description + "\n\n");
        }

        String caLoc = values.get("caLocation0");
        if (caLoc != null) {
            String basename = caLoc.replace('\\', '/');
            basename = basename.substring(basename.lastIndexOf('/') + 1);
            File f = extracted.get(basename);
            if (f != null) {
                res.trustStorePath = f.getAbsolutePath();
                editor.putString("trust_store_certificate", res.trustStorePath);
                summary.append("Trust store: ").append(basename).append('\n');
            }
        }
        if (values.containsKey("caPassword0")) {
            res.trustStorePassword = values.get("caPassword0");
            editor.putString("trust_store_cert_password", res.trustStorePassword);
        }

        String certLoc = values.get("certificateLocation0");
        if (certLoc != null) {
            String basename = certLoc.replace('\\', '/');
            basename = basename.substring(basename.lastIndexOf('/') + 1);
            File f = extracted.get(basename);
            if (f != null) {
                res.clientCertPath = f.getAbsolutePath();
                editor.putString("client_certificate", res.clientCertPath);
                summary.append("Client cert: ").append(basename).append('\n');
            }
        }
        if (values.containsKey("clientPassword0")) {
            res.clientCertPassword = values.get("clientPassword0");
            editor.putString("client_cert_password", res.clientCertPassword);
        }

        editor.apply();

        res.success = true;
        res.message = summary.toString();
        return res;
    }

    /** Parse an ATAK preference XML and return entries from the {@code cot_streams} block. */
    private static Map<String, String> parseCotStreamsPref(String xml) throws XmlPullParserException, IOException {
        Map<String, String> values = new HashMap<>();
        XmlPullParser parser = Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        parser.setInput(new StringReader(xml));

        boolean inCotStreams = false;
        String currentKey = null;
        StringBuilder textBuf = new StringBuilder();

        int event = parser.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            switch (event) {
                case XmlPullParser.START_TAG:
                    String tag = parser.getName();
                    if ("preference".equals(tag)) {
                        inCotStreams = "cot_streams".equals(parser.getAttributeValue(null, "name"));
                    } else if ("entry".equals(tag) && inCotStreams) {
                        currentKey = parser.getAttributeValue(null, "key");
                        textBuf.setLength(0);
                    }
                    break;
                case XmlPullParser.TEXT:
                    if (currentKey != null) textBuf.append(parser.getText());
                    break;
                case XmlPullParser.END_TAG:
                    String endTag = parser.getName();
                    if ("entry".equals(endTag) && currentKey != null) {
                        values.put(currentKey, textBuf.toString().trim());
                        currentKey = null;
                        textBuf.setLength(0);
                    } else if ("preference".equals(endTag)) {
                        inCotStreams = false;
                    }
                    break;
            }
            event = parser.next();
        }
        return values;
    }

    /**
     * Test a TCP (and, if {@code ssl}, TLS) connection to {@code host:port}. Network call —
     * must run off the main thread.
     */
    public static ConnectionTestResult testConnection(Result res, int timeoutMs) {
        ConnectionTestResult ct = new ConnectionTestResult();
        if (res == null || res.host == null || res.port <= 0) {
            ct.details = "no host/port to test";
            return ct;
        }
        // Plain TCP reachability.
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(res.host, res.port), timeoutMs);
            ct.tcpOk = true;
        } catch (Exception e) {
            ct.details = "TCP connect failed: " + e.getMessage();
            return ct;
        }

        if (!res.ssl) {
            ct.tlsOk = true;
            ct.details = "TCP reachable";
            return ct;
        }

        // TLS handshake with client cert + trust store, if present.
        try {
            KeyManagerFactory kmf = null;
            if (res.clientCertPath != null) {
                KeyStore clientKs = KeyStore.getInstance("PKCS12");
                try (FileInputStream fis = new FileInputStream(res.clientCertPath)) {
                    clientKs.load(fis, res.clientCertPassword == null ? new char[0] : res.clientCertPassword.toCharArray());
                }
                kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                kmf.init(clientKs, res.clientCertPassword == null ? new char[0] : res.clientCertPassword.toCharArray());
            }

            TrustManagerFactory tmf = null;
            if (res.trustStorePath != null) {
                KeyStore trustKs = KeyStore.getInstance("PKCS12");
                try (FileInputStream fis = new FileInputStream(res.trustStorePath)) {
                    trustKs.load(fis, res.trustStorePassword == null ? new char[0] : res.trustStorePassword.toCharArray());
                }
                tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(trustKs);
            }

            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf == null ? null : kmf.getKeyManagers(),
                    tmf == null ? null : tmf.getTrustManagers(),
                    new SecureRandom());

            SSLSocketFactory factory = ctx.getSocketFactory();
            try (SSLSocket ssl = (SSLSocket) factory.createSocket()) {
                ssl.connect(new InetSocketAddress(res.host, res.port), timeoutMs);
                ssl.setSoTimeout(timeoutMs);
                ssl.startHandshake();
                ct.tlsOk = true;
                ct.details = "TLS handshake OK (" + ssl.getSession().getProtocol() + ")";
            }
        } catch (Exception e) {
            ct.details = "TLS handshake failed: " + e.getMessage();
        }
        return ct;
    }
}
