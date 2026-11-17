package fr.appli.coquehtml;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.MimeTypeMap;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Coque « Appli HTML » : ouvre les pages HTML du dossier AppsHTML de la tablette.
 * Le dossier est servi à la page sous une adresse https virtuelle : la page est donc en contexte
 * sécurisé (caméra en direct autorisée) et peut charger ses fichiers voisins (dossier lib/ par exemple).
 */
public class MainActivity extends Activity {

    static final String HOST = "appassets.androidplatform.net";
    static final String ORIGIN = "https://" + HOST;
    static final String ROOT_NAME = "AppsHTML";
    static final String SAVE_NAME = "Enregistrements";

    static final int REQ_FILE = 11, REQ_STORAGE = 12, REQ_MEDIA = 13, REQ_ALLFILES = 14;

    private WebView web;
    private File root, saveDir;
    private PermissionRequest pendingPerm;
    private ValueCallback<Uri[]> fileCb;
    private boolean loaded = false, asking = false;
    private String hookJs = "";
    private final Map<String, FileOutputStream> saves = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------ cycle de vie

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root = new File(Environment.getExternalStorageDirectory(), ROOT_NAME);
        saveDir = new File(root, SAVE_NAME);
        hookJs = readAsset("hook.js");
        web = new WebView(this);
        setContentView(web);
        setupWebView();
        immersive();
    }

    @Override
    protected void onResume() {
        super.onResume();
        immersive();
        if (storageOk()) {
            root.mkdirs();
            saveDir.mkdirs();
            if (!loaded) {
                loaded = true;
                web.loadUrl(ORIGIN + "/");
            }
        } else {
            askStorage();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immersive();
    }

    @Override
    protected void onDestroy() {
        for (FileOutputStream o : saves.values()) {
            try { o.close(); } catch (Exception e) { /* ignore */ }
        }
        if (web != null) web.destroy();
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (web.canGoBack()) {
            web.goBack();
            return;
        }
        String u = web.getUrl();
        if (u != null && !u.equals(ORIGIN + "/")) {
            new AlertDialog.Builder(this)
                    .setMessage("Quitter cette page et revenir à la liste ?")
                    .setPositiveButton("Oui", (dlg, which) -> web.loadUrl(ORIGIN + "/"))
                    .setNegativeButton("Non", null)
                    .show();
        } else {
            super.onBackPressed();
        }
    }

    @SuppressWarnings("deprecation")
    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    // ------------------------------------------------------------------ accès aux fichiers

    private boolean storageOk() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        if (Build.VERSION.SDK_INT >= 23) {
            return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void askStorage() {
        if (asking) return;
        asking = true;
        String msg = Build.VERSION.SDK_INT >= 30
                ? "Pour lire tes pages HTML dans le dossier « " + ROOT_NAME + " » de la tablette, l'application a besoin de l'accès aux fichiers.\n\nSur l'écran suivant, active « Autoriser l'accès pour gérer tous les fichiers », puis reviens à l'application."
                : "Pour lire tes pages HTML dans le dossier « " + ROOT_NAME + " » de la tablette, l'application a besoin de l'accès aux fichiers. Accepte la demande qui va s'afficher.";
        new AlertDialog.Builder(this)
                .setTitle("Accès aux fichiers")
                .setMessage(msg)
                .setCancelable(false)
                .setPositiveButton("Continuer", (dlg, which) -> {
                    asking = false;
                    openStorageSettings();
                })
                .show();
    }

    private void openStorageSettings() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(i, REQ_ALLFILES);
            } catch (Exception e) {
                startActivityForResult(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION), REQ_ALLFILES);
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_MEDIA && pendingPerm != null) {
            boolean ok = results.length > 0;
            for (int r : results) if (r != PackageManager.PERMISSION_GRANTED) ok = false;
            if (ok) pendingPerm.grant(pendingPerm.getResources()); else pendingPerm.deny();
            pendingPerm = null;
        }
        // REQ_STORAGE : onResume() revérifie
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == REQ_FILE) {
            if (fileCb != null) {
                Uri[] r = null;
                if (result == RESULT_OK && data != null) {
                    r = WebChromeClient.FileChooserParams.parseResult(result, data);
                }
                fileCb.onReceiveValue(r);
                fileCb = null;
            }
        }
        // REQ_ALLFILES : onResume() revérifie
    }

    // ------------------------------------------------------------------ WebView

    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        WebView.setWebContentsDebuggingEnabled(true);
        web.addJavascriptInterface(new Bridge(), "AndroidBridge");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (!HOST.equals(u.getHost())) return null;
                return serve(u);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                if (!r.isForMainFrame()) return false;
                Uri u = r.getUrl();
                if (HOST.equals(u.getHost())) return false;
                String sc = u.getScheme();
                if ("http".equals(sc) || "https".equals(sc) || "mailto".equals(sc)) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception e) { /* ignore */ }
                    return true;
                }
                return false;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest req) {
                runOnUiThread(() -> handlePermission(req));
            }

            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                if (p.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                if (Build.VERSION.SDK_INT >= 26) {
                    try {
                        i.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                                DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:" + ROOT_NAME));
                    } catch (Exception e) { /* ignore */ }
                }
                try {
                    startActivityForResult(i, REQ_FILE);
                } catch (ActivityNotFoundException e) {
                    fileCb = null;
                    return false;
                }
                return true;
            }

            @Override
            public boolean onJsAlert(WebView v, String url, String msg, final JsResult r) {
                new AlertDialog.Builder(MainActivity.this).setMessage(msg).setCancelable(false)
                        .setPositiveButton("OK", (dlg, which) -> r.confirm()).show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView v, String url, String msg, final JsResult r) {
                new AlertDialog.Builder(MainActivity.this).setMessage(msg).setCancelable(false)
                        .setPositiveButton("OK", (dlg, which) -> r.confirm())
                        .setNegativeButton("Annuler", (dlg, which) -> r.cancel()).show();
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView v, String url, String msg, String def, final JsPromptResult r) {
                final EditText et = new EditText(MainActivity.this);
                if (def != null) et.setText(def);
                new AlertDialog.Builder(MainActivity.this).setMessage(msg).setView(et).setCancelable(false)
                        .setPositiveButton("OK", (dlg, which) -> r.confirm(et.getText().toString()))
                        .setNegativeButton("Annuler", (dlg, which) -> r.cancel()).show();
                return true;
            }
        });
    }

    private void handlePermission(PermissionRequest req) {
        if (req.getOrigin() == null || !HOST.equals(req.getOrigin().getHost())) { req.deny(); return; }
        List<String> missing = new ArrayList<>();
        for (String res : req.getResources()) {
            String p = null;
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(res)) p = Manifest.permission.CAMERA;
            else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(res)) p = Manifest.permission.RECORD_AUDIO;
            if (p != null && checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED && !missing.contains(p)) missing.add(p);
        }
        if (missing.isEmpty()) { req.grant(req.getResources()); return; }
        if (pendingPerm != null) pendingPerm.deny();
        pendingPerm = req;
        requestPermissions(missing.toArray(new String[0]), REQ_MEDIA);
    }

    // ------------------------------------------------------------------ serveur de fichiers virtuel

    private WebResourceResponse serve(Uri u) {
        String path = u.getPath();
        if (path == null || path.isEmpty() || path.equals("/")) return home();
        File f = resolve(path);
        if (f == null || !f.isFile()) return notFound();
        String name = f.getName().toLowerCase(Locale.ROOT);
        try {
            if (name.endsWith(".html") || name.endsWith(".htm")) {
                String html = new String(readAll(f), StandardCharsets.UTF_8);
                return page("text/html", injectHook(html).getBytes(StandardCharsets.UTF_8));
            }
            String mime = mimeOf(name);
            boolean text = mime.startsWith("text/") || mime.equals("application/json") || mime.endsWith("javascript");
            return new WebResourceResponse(mime, text ? "UTF-8" : null, 200, "OK", headers(), new FileInputStream(f));
        } catch (IOException e) {
            return notFound();
        }
    }

    private File resolve(String path) {
        try {
            File f = new File(root, path.substring(1)).getCanonicalFile();
            String rc = root.getCanonicalPath();
            if (!f.getPath().equals(rc) && !f.getPath().startsWith(rc + File.separator)) return null;
            return f;
        } catch (IOException e) {
            return null;
        }
    }

    private Map<String, String> headers() {
        Map<String, String> h = new HashMap<>();
        h.put("Cache-Control", "no-store");
        return h;
    }

    private WebResourceResponse page(String mime, byte[] data) {
        return new WebResourceResponse(mime, "UTF-8", 200, "OK", headers(), new ByteArrayInputStream(data));
    }

    private WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", headers(),
                new ByteArrayInputStream("Fichier introuvable".getBytes(StandardCharsets.UTF_8)));
    }

    private static String mimeOf(String lowerName) {
        int dot = lowerName.lastIndexOf('.');
        String ext = dot >= 0 ? lowerName.substring(dot + 1) : "";
        switch (ext) {
            case "js": case "mjs": return "text/javascript";
            case "json": return "application/json";
            case "glb": return "model/gltf-binary";
            case "gltf": return "model/gltf+json";
            case "wasm": return "application/wasm";
            case "css": return "text/css";
            case "svg": return "image/svg+xml";
            case "txt": return "text/plain";
            default:
                String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                return m != null ? m : "application/octet-stream";
        }
    }

    private static byte[] readAll(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(Math.max(32, (int) f.length()));
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.e("CoqueHtml", "asset " + name, e);
            return "";
        }
    }

    /** Insère le script de la coque juste après <head> (sans changer le mode d'affichage de la page). */
    private String injectHook(String html) {
        if (hookJs.isEmpty()) return html;
        String tag = "<script>" + hookJs + "</script>";
        Matcher m = Pattern.compile("<head[^>]*>", Pattern.CASE_INSENSITIVE).matcher(html);
        if (!m.find()) m = Pattern.compile("<html[^>]*>", Pattern.CASE_INSENSITIVE).matcher(html);
        if (m.find(0)) {
            return new StringBuilder(html).insert(m.end(), tag).toString();
        }
        return tag + html;
    }

    // ------------------------------------------------------------------ page d'accueil : liste des pages

    private void collect(File dir, int depth, List<File> out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            String n = k.getName();
            if (n.startsWith(".")) continue;
            if (k.isDirectory()) {
                String ln = n.toLowerCase(Locale.ROOT);
                if (depth < 3 && !ln.equals("lib") && !ln.equals("node_modules") && !n.equals(SAVE_NAME)) collect(k, depth + 1, out);
            } else {
                String ln = n.toLowerCase(Locale.ROOT);
                if (ln.endsWith(".html") || ln.endsWith(".htm")) out.add(k);
            }
        }
    }

    private String relPath(File f) {
        String p = f.getPath();
        String r = root.getPath();
        return p.startsWith(r + File.separator) ? p.substring(r.length() + 1) : f.getName();
    }

    private String titleOf(File f) {
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[16384];
            int n = in.read(buf);
            if (n > 0) {
                String h = new String(buf, 0, n, StandardCharsets.UTF_8);
                Matcher m = Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(h);
                if (m.find()) {
                    String t = m.group(1).trim();
                    if (!t.isEmpty()) return t;
                }
            }
        } catch (IOException e) { /* ignore */ }
        return f.getName();
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private WebResourceResponse home() {
        List<File> list = new ArrayList<>();
        collect(root, 0, list);
        Collections.sort(list, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return relPath(a).toLowerCase(Locale.ROOT).compareTo(relPath(b).toLowerCase(Locale.ROOT));
            }
        });
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><html lang=\"fr\"><head><meta charset=\"utf-8\">")
          .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Appli HTML</title><style>")
          .append("body{margin:0;padding:22px;font:17px system-ui,sans-serif;background:#e8edf3;color:#1b2635}")
          .append("h1{margin:0 0 4px;font-size:24px}.m{color:#66758a;font-size:14px;margin:2px 0 14px}")
          .append(".c{display:block;background:#fff;border:1px solid #d3dae3;border-radius:12px;padding:14px 16px;margin:10px 0;text-decoration:none;color:inherit;box-shadow:0 2px 8px rgba(30,50,80,.08)}")
          .append(".c b{display:block;color:#1769e0;font-size:19px}.c span{color:#66758a;font-size:13px}")
          .append(".r{display:inline-block;margin-top:10px;padding:10px 18px;border-radius:10px;background:#1769e0;color:#fff;text-decoration:none}")
          .append("</style></head><body><h1>Mes applications HTML</h1><div class=\"m\">Dossier : ")
          .append(esc(root.getPath())).append("</div>");
        if (list.isEmpty()) {
            sb.append("<div class=\"c\"><b>Aucune page trouvée</b><span>Copie tes fichiers .html (avec leur dossier lib si besoin) dans le dossier ci-dessus ")
              .append("avec le gestionnaire de fichiers, puis appuie sur Actualiser.</span></div>");
        }
        for (File f : list) {
            String rel = relPath(f);
            StringBuilder href = new StringBuilder();
            for (String seg : rel.split(Pattern.quote(File.separator))) href.append('/').append(Uri.encode(seg));
            sb.append("<a class=\"c\" href=\"").append(href).append("\"><b>").append(esc(titleOf(f)))
              .append("</b><span>").append(esc(rel)).append("</span></a>");
        }
        sb.append("<a class=\"r\" href=\"/\">Actualiser</a>")
          .append("<div class=\"m\" style=\"margin-top:16px\">Les fichiers enregistrés par les pages arrivent dans : ")
          .append(esc(saveDir.getPath())).append("</div></body></html>");
        return page("text/html", sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ pont JavaScript (enregistrement, impression)

    private void toast(final String t) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, t, Toast.LENGTH_LONG).show());
    }

    private static String safeName(String n) {
        if (n == null) n = "";
        n = n.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (n.isEmpty() || n.equals(".") || n.equals("..")) n = "fichier";
        return n;
    }

    private class Bridge {
        @JavascriptInterface
        public String beginSave(String name) {
            try {
                saveDir.mkdirs();
                File f = new File(saveDir, safeName(name));      // un fichier du même nom est remplacé
                saves.put(f.getPath(), new FileOutputStream(f));
                return f.getPath();
            } catch (Exception e) {
                toast("Enregistrement impossible : " + e.getMessage());
                return "";
            }
        }

        @JavascriptInterface
        public void appendSave(String token, String b64) {
            FileOutputStream o = saves.get(token);
            if (o == null || b64 == null || b64.isEmpty()) return;
            try {
                o.write(Base64.decode(b64, Base64.DEFAULT));
            } catch (Exception e) {
                Log.e("CoqueHtml", "append", e);
            }
        }

        @JavascriptInterface
        public void endSave(String token) {
            FileOutputStream o = saves.remove(token);
            if (o == null) return;
            try { o.close(); } catch (Exception e) { /* ignore */ }
            toast("Enregistré : " + ROOT_NAME + "/" + SAVE_NAME + "/" + new File(token).getName());
        }

        @JavascriptInterface
        public void print() {
            runOnUiThread(() -> {
                try {
                    PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
                    String n = "Impression";
                    pm.print(n, web.createPrintDocumentAdapter(n), new PrintAttributes.Builder().build());
                } catch (Exception e) {
                    toast("Impression impossible : " + e.getMessage());
                }
            });
        }
    }
}
