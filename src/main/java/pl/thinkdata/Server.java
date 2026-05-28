package pl.thinkdata;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class Server {

    private static final AtomicBoolean generating = new AtomicBoolean(false);
    private static final AtomicReference<String> lastGenerated = new AtomicReference<>("nigdy");
    private static String outputFile;
    private static String lastGeneratedFile;

    public static void main(String[] args) throws Exception {
        String stanyUrl = System.getenv("CSV_STANY_URL");
        String pubUrl   = System.getenv("CSV_PUBLIKACJE_URL");
        String eanFile  = System.getenv().getOrDefault("EAN_FILE",  "/app/ean.txt");
        String cnyFile  = System.getenv().getOrDefault("CNY_FILE",  "/app/cny.txt");
        int port        = Integer.parseInt(System.getenv().getOrDefault("PORT", "8089"));
        outputFile          = System.getenv().getOrDefault("OUTPUT_FILE", "/app/data/output.xml");
        lastGeneratedFile   = Paths.get(outputFile).getParent().resolve("last_generated.txt").toString();

        if (stanyUrl == null || pubUrl == null) {
            System.err.println("Wymagane zmienne: CSV_STANY_URL, CSV_PUBLIKACJE_URL");
            System.exit(1);
        }

        int refreshHours = Integer.parseInt(System.getenv().getOrDefault("REFRESH_HOURS", "1"));

        Files.createDirectories(Paths.get(outputFile).getParent());

        Path lgPath = Paths.get(lastGeneratedFile);
        if (Files.exists(lgPath))
            lastGenerated.set(Files.readString(lgPath).trim());

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(
                () -> generate(stanyUrl, pubUrl, eanFile, cnyFile),
                0, refreshHours, TimeUnit.HOURS);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/output.xml", exchange -> serveFile(exchange));
        server.createContext("/refresh",    exchange -> handleRefresh(exchange, stanyUrl, pubUrl, eanFile, cnyFile));
        server.createContext("/",           exchange -> handleRoot(exchange));
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.start();
        System.out.println("Serwer uruchomiony na porcie " + port + ", odswiezanie co " + refreshHours + "h");
    }

    private static void generate(String stanyUrl, String pubUrl, String eanFile, String cnyFile) {
        if (!generating.compareAndSet(false, true)) {
            System.out.println("Generowanie juz w toku, pomijam.");
            return;
        }
        String stanyTmp = null;
        String pubTmp   = null;
        try {
            System.out.println("Pobieranie: " + stanyUrl);
            stanyTmp = downloadToTemp(stanyUrl, "stany");
            System.out.println("Pobieranie: " + pubUrl);
            pubTmp = downloadToTemp(pubUrl, "pub");
            Main.run(eanFile, cnyFile, stanyTmp, pubTmp, outputFile);
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            lastGenerated.set(ts);
            try { Files.writeString(Paths.get(lastGeneratedFile), ts); } catch (Exception ignored) {}
        } catch (Exception e) {
            System.err.println("Blad generowania: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (stanyTmp != null) new File(stanyTmp).delete();
            if (pubTmp   != null) new File(pubTmp).delete();
            generating.set(false);
        }
    }

    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final int READ_TIMEOUT_MS    = 120_000;

    private static String downloadToTemp(String urlStr, String prefix) throws IOException {
        Path tmp = Files.createTempFile(prefix, ".csv");
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        try (InputStream  in  = conn.getInputStream();
             OutputStream out = Files.newOutputStream(tmp)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        } finally {
            conn.disconnect();
        }
        return tmp.toString();
    }

    private static void serveFile(HttpExchange exchange) throws IOException {
        File f = new File(outputFile);
        if (!f.exists()) {
            respond(exchange, 404, "text/plain; charset=utf-8",
                    "output.xml jeszcze nie istnieje — poczekaj na generowanie lub wywolaj /refresh");
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "application/xml; charset=utf-8");
        exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"output.xml\"");
        exchange.sendResponseHeaders(200, f.length());
        try (OutputStream os = exchange.getResponseBody();
             InputStream  is = new FileInputStream(f)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = is.read(buf)) != -1) os.write(buf, 0, n);
        }
    }

    private static void handleRefresh(HttpExchange exchange,
                                      String stanyUrl, String pubUrl,
                                      String eanFile, String cnyFile) throws IOException {
        if (generating.get()) {
            respond(exchange, 409, "text/plain; charset=utf-8",
                    "Generowanie juz w toku. Sprobuj pozniej.");
            return;
        }
        new Thread(() -> generate(stanyUrl, pubUrl, eanFile, cnyFile)).start();
        respond(exchange, 202, "text/plain; charset=utf-8",
                "Generowanie rozpoczete. Za kilka minut pobierz /output.xml");
    }

    private static void handleRoot(HttpExchange exchange) throws IOException {
        String status = generating.get() ? "Generowanie w toku..." : "Gotowy";
        String html = "<!DOCTYPE html><html><head><meta charset='utf-8'>" +
                "<title>MediaEkspert XML Generator</title></head><body>" +
                "<h2>MediaEkspert XML Generator</h2>" +
                "<ul>" +
                "<li><a href='/output.xml'>Pobierz output.xml</a></li>" +
                "<li><a href='/refresh'>Odswiez dane (ponownie pobierz CSV i wygeneruj XML)</a></li>" +
                "</ul>" +
                "<p>Status: <strong>" + status + "</strong></p>" +
                "<p>Ostatnie generowanie: <strong>" + lastGenerated.get() + "</strong></p>" +
                "</body></html>";
        respond(exchange, 200, "text/html; charset=utf-8", html);
    }

    private static void respond(HttpExchange exchange, int code, String contentType, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(code, data.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(data);
        }
    }
}