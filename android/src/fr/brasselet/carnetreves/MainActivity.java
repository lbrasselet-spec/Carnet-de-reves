package fr.brasselet.carnetreves;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.AlarmClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.Locale;

/**
 * Carnet de rêves — partie Android.
 *
 * L'interface complète (journal, dessin, nuit, tests de réalité, guide) est la page assets/index.html,
 * affichée dans une WebView ; les rêves sont enregistrés par la page, sur le téléphone.
 * Cette classe ne fait que ce que la page ne peut pas faire seule :
 *  - ouvrir l'appli Horloge avec l'heure du réveil nocturne pré-remplie ;
 *  - enregistrer la sauvegarde (.json) à l'endroit choisi (Drive, Téléchargements…) et en ouvrir une ;
 *  - ouvrir les liens des sources dans le navigateur ; relayer le bouton « retour ».
 */
public class MainActivity extends Activity {

    /** Adresse locale réservée à ce type d'usage : elle n'existe pas sur Internet. */
    static final String HOST = "appassets.androidplatform.net";
    static final String APP_URL = "https://" + HOST + "/app/index.html";
    static final int REQ_SAVE = 1, REQ_OPEN = 2;
    static final String SAVE_FILE = "sauvegarde.json";
    static final int BG = 0xFF15110F;

    private FrameLayout root;
    private WebView web;
    private boolean pageReady = false;           // la page a signalé qu'elle est prête
    private String pendingJs = null;             // appels mis en attente tant qu'elle ne l'est pas
    private Writer saveOut = null;               // sauvegarde en cours de réception depuis la page
    private String saveName = null;
    private ValueCallback<Uri[]> fileCallback = null;

    // ------------------------------------------------------------------ cycle de vie

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        // Android 15 et + : l'appli s'affiche sous les barres système ; on réserve leur place et celle du clavier.
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
                v.setPadding(in.getSystemWindowInsetLeft(), in.getSystemWindowInsetTop(),
                        in.getSystemWindowInsetRight(), in.getSystemWindowInsetBottom());
                return in.consumeSystemWindowInsets();
            }
        });
        setContentView(root);
        createWebView();
    }

    private void createWebView() {
        web = new WebView(this);
        web.setBackgroundColor(BG);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);            // réglages, brouillon et compteur de tests
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setTextZoom(100);                      // mise en page indépendante de la taille de police système
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        web.setWebViewClient(new AppClient());
        web.setWebChromeClient(new ChromeClient());
        web.addJavascriptInterface(new Bridge(), "CRNative");
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        synchronized (this) { pageReady = false; }
        web.loadUrl(APP_URL);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override
    protected void onDestroy() {
        synchronized (this) { closeSave(); }
        if (web != null) {
            root.removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    /** Bouton ou geste « retour » : la page ferme d'abord l'éditeur ou revient au journal. */
    @Override
    public void onBackPressed() {
        if (web == null) {
            super.onBackPressed();
            return;
        }
        web.evaluateJavascript("(window.CR && CR.onBack()) ? 1 : 0", new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                if (!"1".equals(value)) MainActivity.super.onBackPressed();
            }
        });
    }

    // ------------------------------------------------------------------ page et fichiers locaux

    private class AppClient extends WebViewClient {
        /** Sert la page et ses polices (assets) ; rien n'est demandé à Internet. */
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            String scheme = u.getScheme();
            if (!"https".equals(scheme) && !"http".equals(scheme)) return null;   // blob:, data: : gérés par la WebView
            String path = u.getPath();
            if (!HOST.equals(u.getHost()) || path == null || !path.startsWith("/app/")) return notFound();
            String name = path.substring(5);
            if (name.isEmpty() || name.contains("..")) return notFound();
            try {
                String mime = mimeOf(name);
                return new WebResourceResponse(mime, mime.startsWith("text/") ? "utf-8" : null, getAssets().open(name));
            } catch (IOException e) {
                return notFound();
            }
        }

        /** Les liens externes (sources du guide) s'ouvrent dans le navigateur du téléphone. */
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            Uri u = Uri.parse(url);
            if (HOST.equals(u.getHost())) return false;
            if ("https".equals(u.getScheme()) || "http".equals(u.getScheme())) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (ActivityNotFoundException ignored) { }
            }
            return true;
        }

        /** Android 8 et + : si l'affichage s'arrête (manque de mémoire), on le relance au lieu de fermer l'appli.
         *  (pas d'@Override : méthode absente de la plateforme de compilation, appelée par Android 8+) */
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            if (view == web) {
                root.removeView(web);
                web.destroy();
                web = null;
                createWebView();      // les rêves sont déjà enregistrés par la page
            }
            return true;
        }
    }

    /** « Importer une sauvegarde » : sélecteur de fichiers d'Android. */
    private class ChromeClient extends WebChromeClient {
        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = callback;
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");                    // les .json ne sont pas toujours reconnus comme tels
            try {
                startActivityForResult(Intent.createChooser(i, "Choisir la sauvegarde"), REQ_OPEN);
            } catch (ActivityNotFoundException e) {
                fileCallback = null;
                return false;
            }
            return true;
        }
    }

    private static WebResourceResponse notFound() {
        WebResourceResponse r = new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
        r.setStatusCodeAndReasonPhrase(404, "Not Found");
        return r;
    }

    private static String mimeOf(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".html")) return "text/html";
        if (n.endsWith(".js")) return "text/javascript";
        if (n.endsWith(".css")) return "text/css";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".woff2")) return "font/woff2";
        return "application/octet-stream";
    }

    // ------------------------------------------------------------------ pont avec la page (window.CRNative)

    private class Bridge {
        @JavascriptInterface
        public void ready() {
            final String js;
            synchronized (MainActivity.this) {
                pageReady = true;
                js = pendingJs;
                pendingJs = null;
            }
            if (js != null) evalJs(js);
        }

        /** Ouvre l'appli Horloge avec l'heure pré-remplie ; l'utilisateur n'a plus qu'à valider. */
        @JavascriptInterface
        public boolean setAlarm(int hour, int minutes, String label) {
            Intent i = new Intent(AlarmClock.ACTION_SET_ALARM);
            i.putExtra(AlarmClock.EXTRA_HOUR, hour);
            i.putExtra(AlarmClock.EXTRA_MINUTES, minutes);
            i.putExtra(AlarmClock.EXTRA_MESSAGE, label == null ? "Carnet de rêves" : label);
            i.putExtra(AlarmClock.EXTRA_SKIP_UI, false);
            try {
                startActivity(i);
                return true;
            } catch (ActivityNotFoundException | SecurityException e) {
                return false;
            }
        }

        /** Réception de la sauvegarde en plusieurs morceaux de texte (début, morceaux, fin). */
        @JavascriptInterface
        public boolean beginSave(String name) {
            synchronized (MainActivity.this) {
                closeSave();
                saveName = name == null || name.isEmpty() ? "carnet-reves.json" : name.replaceAll("[^A-Za-z0-9._-]", "_");
                try {
                    saveOut = new OutputStreamWriter(new FileOutputStream(new File(getCacheDir(), SAVE_FILE)), "UTF-8");
                    return true;
                } catch (IOException e) {
                    saveOut = null;
                    return false;
                }
            }
        }

        @JavascriptInterface
        public boolean appendSave(String text) {
            synchronized (MainActivity.this) {
                if (saveOut == null) return false;
                try {
                    saveOut.write(text);
                    return true;
                } catch (IOException e) {
                    closeSave();
                    return false;
                }
            }
        }

        /** Demande au téléphone où enregistrer le fichier ; le résultat revient dans CR.onSaved. */
        @JavascriptInterface
        public void finishSave() {
            final String name;
            synchronized (MainActivity.this) {
                if (saveOut == null) {
                    jsCall("CR.onSaved(false, 'sauvegarde interrompue')");
                    return;
                }
                try {
                    saveOut.close();
                } catch (IOException e) {
                    saveOut = null;
                    jsCall("CR.onSaved(false, 'écriture impossible')");
                    return;
                }
                saveOut = null;
                name = saveName;
            }
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("application/json");
                    i.putExtra(Intent.EXTRA_TITLE, name);
                    try {
                        startActivityForResult(i, REQ_SAVE);
                    } catch (ActivityNotFoundException e) {
                        jsCall("CR.onSaved(false, " + JSONObject.quote("aucun gestionnaire de fichiers disponible") + ")");
                    }
                }
            });
        }
    }

    private void closeSave() {
        if (saveOut != null) {
            try { saveOut.close(); } catch (IOException ignored) { }
            saveOut = null;
        }
    }

    private void jsCall(String js) {
        synchronized (this) {
            if (!pageReady) {
                pendingJs = pendingJs == null ? js : pendingJs + ";" + js;
                return;
            }
        }
        evalJs(js);
    }

    private void evalJs(final String js) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (web != null && !isDestroyed()) web.evaluateJavascript(js, null);
            }
        });
    }

    // ------------------------------------------------------------------ retours du téléphone

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == REQ_OPEN) {
            if (fileCallback != null) {
                Uri u = result == RESULT_OK && data != null ? data.getData() : null;
                fileCallback.onReceiveValue(u == null ? null : new Uri[]{u});
                fileCallback = null;
            }
        } else if (request == REQ_SAVE) {
            final File f = new File(getCacheDir(), SAVE_FILE);
            final Uri dest = result == RESULT_OK && data != null ? data.getData() : null;
            if (dest == null) {
                f.delete();
                jsCall("CR.onSaved(false, '')");     // annulé : pas de message
                return;
            }
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        OutputStream out = getContentResolver().openOutputStream(dest, "wt");
                        if (out == null) throw new IOException("destination indisponible");
                        copy(new FileInputStream(f), out);
                        jsCall("CR.onSaved(true, '')");
                    } catch (Exception e) {
                        jsCall("CR.onSaved(false, " + JSONObject.quote("écriture impossible à cet endroit") + ")");
                    } finally {
                        f.delete();
                    }
                }
            }).start();
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        try {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            try { in.close(); } catch (IOException ignored) { }
            out.close();
        }
    }
}
