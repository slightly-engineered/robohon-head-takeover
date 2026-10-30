package com.colson.projtest;

import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;
import java.io.*;
import java.net.*;

public class DevServerService extends Service {
    private static final String TAG = "DevServer";
    private static final int PORT = 8080;
    private volatile boolean running = false;
    private ServerSocket server;

    @Override public int onStartCommand(Intent i, int f, int id) {
        if (!running) { running = true; new Thread(new Runnable(){ public void run(){ serve(); }}).start(); }
        return START_STICKY;
    }
    @Override public IBinder onBind(Intent i) { return null; }
    @Override public void onDestroy() { running = false; try { if (server != null) server.close(); } catch (Exception e) {} }

    private void serve() {
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(PORT));
            Log.i(TAG, "listening on " + PORT);
            while (running) {
                Socket s = server.accept();
                try { handle(s); } catch (Exception e) { Log.w(TAG, "handle " + e); }
                finally { try { s.close(); } catch (Exception e) {} }
            }
        } catch (Exception e) { Log.e(TAG, "serve failed", e); running = false; }
    }

    private void handle(Socket s) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
        String line = in.readLine();
        if (line == null) return;
        Log.i(TAG, "req: " + line);
        String path = "";
        String[] parts = line.split(" ");
        if (parts.length >= 2) path = parts[1];
        String h; while ((h = in.readLine()) != null && h.length() > 0) {}   // drain headers

        OutputStream os = s.getOutputStream();
        if (path.startsWith("/log")) {
            respond(os, runCmd("logcat -d -v time").getBytes("UTF-8"));
        } else if (path.startsWith("/install")) {
            String url = param(path, "url");
            respond(os, (url == null ? "usage: /install?url=http://<PC>:8000/app-debug.apk"
                    : downloadAndInstall(url)).getBytes("UTF-8"));
        } else if (path.startsWith("/start")) {
            String a = param(path, "a");
            String msg;
            try {
                Intent it = new Intent();
                it.setClassName("com.colson.projtest", "com.colson.projtest." + a);
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(it); msg = "started " + a;
            } catch (Exception e) { msg = "err " + e; }
            respond(os, msg.getBytes("UTF-8"));
        } else {
            respond(os, ("ProjTest DevServer\n/log\n/install?url=http://<PC>:8000/app-debug.apk\n"
                    + "/start?a=MainActivity|CameraActivity|TrackActivity|ServoProbeActivity\n").getBytes("UTF-8"));
        }
    }

    private String param(String path, String key) {
        int q = path.indexOf('?'); if (q < 0) return null;
        for (String kv : path.substring(q + 1).split("&")) {
            String[] p = kv.split("=", 2);
            if (p.length == 2 && p[0].equals(key)) {
                try { return URLDecoder.decode(p[1], "UTF-8"); } catch (Exception e) { return p[1]; }
            }
        }
        return null;
    }

    private String runCmd(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(cmd);
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder(); String l;
            while ((l = r.readLine()) != null) sb.append(l).append("\n");
            return sb.toString();
        } catch (Exception e) { return "cmd err " + e; }
    }

    private String downloadAndInstall(String url) {
        try {
            File out = new File(Environment.getExternalStorageDirectory(), "update.apk");
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000); c.setReadTimeout(30000);
            InputStream is = c.getInputStream();
            FileOutputStream fos = new FileOutputStream(out);
            byte[] buf = new byte[8192]; int n; long total = 0;
            while ((n = is.read(buf)) > 0) { fos.write(buf, 0, n); total += n; }
            fos.close(); is.close();
            Intent it = new Intent(Intent.ACTION_VIEW);
            it.setDataAndType(Uri.fromFile(out), "application/vnd.android.package-archive");
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(it);
            return "downloaded " + total + " bytes -> launching installer (tap Install on device)";
        } catch (Exception e) { return "install err " + e; }
    }

    private void respond(OutputStream os, byte[] body) throws IOException {
        String head = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: "
                + body.length + "\r\nConnection: close\r\n\r\n";
        os.write(head.getBytes("UTF-8")); os.write(body); os.flush();
    }
}