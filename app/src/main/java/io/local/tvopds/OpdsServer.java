package io.local.tvopds;

import android.content.Context;
import android.net.Uri;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class OpdsServer {
    private static final int PAGE_SIZE = 100;
    private static final String FEED_MIME = "application/atom+xml;profile=opds-catalog;kind=";
    private final Context context;
    private final int port;
    private final ExecutorService clients = Executors.newFixedThreadPool(4);
    private ServerSocket listener;
    private Thread acceptThread;

    OpdsServer(Context context, int port) { this.context = context; this.port = port; }

    void start() throws IOException {
        listener = new ServerSocket(port, 16);
        acceptThread = new Thread(() -> {
            while (!listener.isClosed()) {
                try {
                    Socket client = listener.accept();
                    client.setSoTimeout(15000);
                    clients.execute(() -> handle(client));
                } catch (Exception e) {
                    if (!listener.isClosed()) android.util.Log.w("AF-OPDS", "Accept error", e);
                }
            }
        }, "opds-accept");
        acceptThread.start();
    }

    void close() {
        try { if (listener != null) listener.close(); } catch (IOException ignored) {}
        clients.shutdownNow();
    }

    private static String readLine(InputStream input) throws IOException {
        byte[] data = new byte[8192]; int n = 0, c;
        while ((c = input.read()) != -1) {
            if (c == '\n') break;
            if (n == data.length) throw new IOException("Oversized HTTP request line");
            if (c != '\r') data[n++] = (byte)c;
        }
        if (n == 0 && c == -1) return null;
        return new String(data, 0, n, StandardCharsets.ISO_8859_1);
    }

    private void handle(Socket socket) {
        try (Socket s = socket;
             InputStream input = new BufferedInputStream(s.getInputStream());
             OutputStream out = new BufferedOutputStream(s.getOutputStream())) {
            String first = readLine(input);
            if (first == null) return;
            String[] words = first.split(" ");
            if (words.length != 3 || words[1].length() > 4096) {
                send(out, 400, "text/plain; charset=utf-8", "Bad request"); return;
            }
            int contentLength = 0;
            for (int i = 0; i < 80; i++) {
                String line = readLine(input);
                if (line == null || line.isEmpty()) break;
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                    try { contentLength = Integer.parseInt(line.substring(colon + 1).trim()); }
                    catch (NumberFormatException ignored) { contentLength = -1; }
                }
                if (i == 79) { send(out, 400, "text/plain; charset=utf-8", "Too many headers"); return; }
            }
            if (!words[0].equals("GET") && !words[0].equals("HEAD") && !words[0].equals("POST")) {
                send(out, 405, "text/plain; charset=utf-8", "GET/HEAD/POST only"); return;
            }
            if (contentLength < 0 || contentLength > 4096) {
                send(out, 400, "text/plain; charset=utf-8", "Invalid request body"); return;
            }
            boolean head = words[0].equals("HEAD");
            String target = words[1];
            int qm = target.indexOf('?');
            String path = qm == -1 ? target : target.substring(0, qm);
            Map<String,String> query = parseQuery(qm == -1 ? "" : target.substring(qm + 1));
            if (words[0].equals("POST")) query.putAll(parseQuery(readBody(input, contentLength)));
            if (!path.equals("/")) EventLog.add("HTTP " + words[0] + " " + path);
            try (LibraryDb db = new LibraryDb(context)) {
                if (path.equals("/")) {
                    send(out, 200, "text/html; charset=utf-8", controlPanel(bookCount(db), ""), head);
                } else if (path.equals("/control")) {
                    String message = control(query);
                    send(out, 200, "text/html; charset=utf-8", controlPanel(bookCount(db), message), head);
                } else if (path.equals("/opds") || path.equals("/opds/")) {
                    send(out, 200, FEED_MIME + "navigation; charset=utf-8", root(), head);
                } else if (path.equals("/opds/all")) {
                    send(out, 200, "application/atom+xml;profile=opds-catalog; charset=utf-8",
                            titles(db, query.get("prefix"), page(query.get("page"))), head);
                } else if (path.equals("/opds/authors")) {
                    String prefix = query.get("prefix");
                    send(out, 200, FEED_MIME + "navigation; charset=utf-8",
                            authors(db, prefix), head);
                } else if (path.equals("/opds/genres")) {
                    send(out, 200, FEED_MIME + "navigation; charset=utf-8",
                            genres(db), head);
                } else if (path.equals("/opds/genre")) {
                    String name = query.get("name");
                    if (name == null) { send(out, 400, "text/plain", "Missing name"); return; }
                    String base = "/opds/genre?name=" + url(name);
                    send(out, 200, FEED_MIME + "acquisition; charset=utf-8",
                            books(db.listGenre(name), "Жанр: " + name, base, 0, false), head);
                } else if (path.equals("/opds/series")) {
                    int page = page(query.get("page"));
                    send(out, 200, FEED_MIME + "navigation; charset=utf-8",
                            categories(db.values("series", page, PAGE_SIZE), "Серии", "series", page), head);
                } else if (path.equals("/opds/author")) {
                    String name = query.get("name");
                    if (name == null) { send(out, 400, "text/plain", "Missing name"); return; }
                    send(out, 200, FEED_MIME + "navigation; charset=utf-8",
                            authorCatalog(name, db.countForAuthor(name)), head);
                } else if (path.equals("/opds/author/books")) {
                    String name = query.get("name");
                    if (name == null) { send(out, 400, "text/plain", "Missing name"); return; }
                    int page = page(query.get("page"));
                    String base = "/opds/author/books?name=" + url(name);
                    send(out, 200, FEED_MIME + "acquisition; charset=utf-8",
                            books(db.list(name, null, null, page, PAGE_SIZE), "Книги: " + name, base, page), head);
                } else if (path.equals("/opds/author/series")) {
                    String name = query.get("name");
                    if (name == null) { send(out, 400, "text/plain", "Missing name"); return; }
                    send(out, 200, FEED_MIME + "navigation; charset=utf-8",
                            authorSeries(db, name), head);
                } else if (path.equals("/opds/author/serie")) {
                    String author = query.get("author");
                    String series = query.get("name");
                    if (author == null || series == null) { send(out, 400, "text/plain", "Missing author or name"); return; }
                    int page = page(query.get("page"));
                    String base = "/opds/author/serie?author=" + url(author) + "&name=" + url(series);
                    send(out, 200, FEED_MIME + "acquisition; charset=utf-8",
                            books(db.list(author, series, null, page, PAGE_SIZE), "Серия: " + series, base, page), head);
                } else if (path.equals("/opds/serie")) {
                    String name = query.get("name");
                    if (name == null) { send(out, 400, "text/plain", "Missing name"); return; }
                    int page = page(query.get("page"));
                    String base = "/opds/serie?name=" + url(name);
                    send(out, 200, FEED_MIME + "acquisition; charset=utf-8",
                            books(db.list(null, name, null, page, PAGE_SIZE), name, base, page), head);
                } else if (path.equals("/opds/search")) {
                    String q = query.getOrDefault("q", "");
                    int page = page(query.get("page"));
                    String base = "/opds/search?q=" + url(q);
                    send(out, 200, FEED_MIME + "acquisition; charset=utf-8",
                            books(db.list(null, null, q, page, PAGE_SIZE), "Поиск: " + q, base, page), head);
                } else if (path.equals("/opds/opensearch.xml")) {
                    String host = s.getLocalAddress().getHostAddress();
                    if (host.contains(":")) host = "[" + host + "]";
                    String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                            "<OpenSearchDescription xmlns=\"http://a9.com/-/spec/opensearch/1.1/\">" +
                            "<ShortName>AF-OPDS</ShortName><Description>Book search</Description>" +
                            "<Url type=\"application/atom+xml;profile=opds-catalog;kind=acquisition\" " +
                            "template=\"http://" + xml(host) + ":" + port + "/opds/search?q={searchTerms}\"/>" +
                            "</OpenSearchDescription>";
                    send(out, 200, "application/opensearchdescription+xml; charset=utf-8", xml, head);
                } else if (path.startsWith("/download/")) {
                    LibraryDb.Book book = db.find(path.substring("/download/".length()));
                    if (book == null) { send(out, 404, "text/plain", "Not found"); return; }
                    download(out, book, head);
                } else {
                    send(out, 404, "text/plain; charset=utf-8", "Not found");
                }
            }
        } catch (Exception e) {
            android.util.Log.w("AF-OPDS", "Request failed", e);
        }
    }

    private static String readBody(InputStream input, int length) throws IOException {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = input.read(data, offset, length - offset);
            if (read < 0) throw new IOException("Unexpected end of request body");
            offset += read;
        }
        return new String(data, StandardCharsets.UTF_8);
    }

    private String control(Map<String,String> query) {
        String action = query.getOrDefault("action", "");
        try {
            if (action.equals("set_path")) {
                LibraryTasks.setPath(context, query.getOrDefault("path", "").trim());
                return "Папка выбрана.";
            }
            if (action.equals("check")) return LibraryTasks.check(context);
            if (action.equals("scan")) {
                LibraryTasks.startScan(context);
                return LibraryTasks.state() + ".";
            }
            if (action.equals("restart")) {
                ServerService.restart(context);
                return "Сервер перезапускается.";
            }
            return "Неизвестное действие.";
        } catch (Exception e) {
            String message = e.getMessage();
            EventLog.add("Ошибка веб-панели: " + (message == null ? e.getClass().getSimpleName() : message));
            return "Ошибка: " + (message == null ? e.getClass().getSimpleName() : message);
        }
    }

    private static int bookCount(LibraryDb db) {
        if (LibraryTasks.isScanning()) return -1;
        try { return db.count(); }
        catch (Exception ignored) { return -1; }
    }

    private String controlPanel(int books, String message) {
        String folder = LibraryTasks.selectedFolder(context);
        String path = context.getSharedPreferences("tvopds", Context.MODE_PRIVATE).getString("path", "");
        String refresh = LibraryTasks.isScanning() ? "<meta http-equiv=\"refresh\" content=\"5\">" : "";
        return "<!doctype html><html lang=\"ru\"><meta charset=\"utf-8\">" + refresh +
                "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>AF-OPDS</title>" +
                "<style>body{font:16px sans-serif;max-width:760px;margin:24px auto;padding:0 16px}input{width:70%;padding:8px}button{padding:8px 12px;margin:4px}pre{white-space:pre-wrap;background:#f1f1f1;padding:12px;max-height:420px;overflow:auto}.note{color:#075a20}</style>" +
                "<h1>AF-OPDS</h1><p>Сервер: запущен · Книг: " + (books < 0 ? "индекс обновляется" : books) + " · Сканирование: " + xml(LibraryTasks.state()) + "</p>" +
                "<p>Папка: " + xml(folder == null ? "не выбрана" : folder) + " · <a href=\"/opds\">OPDS-каталог</a></p>" +
                (message.isEmpty() ? "" : "<p class=\"note\">" + xml(message) + "</p>") +
                "<form action=\"/control\" method=\"post\"><input type=\"hidden\" name=\"action\" value=\"set_path\"><label>Путь к папке USB<br><input name=\"path\" value=\"" + xml(path) + "\" placeholder=\"/storage/XXXX-XXXX/Books\"></label><button>Использовать папку</button></form>" +
                "<p>Веб-браузер не может выдать приложению Android системное разрешение SAF; здесь доступен только путь к локально читаемой папке.</p>" +
                controlButton("check", "Проверить диск") + controlButton("scan", "Сканировать библиотеку") + controlButton("restart", "Перезапустить сервер") +
                "<h2>Живой журнал</h2><pre>" + xml(EventLog.text()) + "</pre></html>";
    }

    private static String controlButton(String action, String label) {
        return "<form action=\"/control\" method=\"post\" style=\"display:inline\"><input type=\"hidden\" name=\"action\" value=\"" + action + "\"><button>" + label + "</button></form>";
    }

    private void download(OutputStream out, LibraryDb.Book book, boolean head) throws IOException {
        String mime;
        switch (book.format) {
            case "epub": mime = "application/epub+zip"; break;
            case "fb2": mime = "application/fb2+xml"; break;
            case "pdf": mime = "application/pdf"; break;
            case "mobi": mime = "application/x-mobipocket-ebook"; break;
            case "azw3": mime = "application/vnd.amazon.mobi8-ebook"; break;
            default: mime = "text/plain; charset=utf-8";
        }
        InputStream stream;
        try {
            stream = book.entry == null || book.entry.isEmpty()
                    ? context.getContentResolver().openInputStream(Uri.parse(book.uri))
                    : ZipEntryStream.open(context, Uri.parse(book.uri), book.entry);
        }
        catch (Exception e) {
            send(out, 503, "text/plain; charset=utf-8", "Book unavailable. Check USB drive connection.", head);
            return;
        }
        if (stream == null) {
            send(out, 503, "text/plain; charset=utf-8", "Book unavailable. Check USB drive connection.", head);
            return;
        }
        try (InputStream in = stream) {
            StringBuilder headers = new StringBuilder("HTTP/1.1 200 OK\r\nContent-Type: " + mime +
                    "\r\nConnection: close\r\nCache-Control: no-store\r\n");
            // Content length may be unknown for a cloud-backed DocumentsProvider.
            if (book.size >= 0) headers.append("Content-Length: ").append(book.size).append("\r\n");
            headers.append("Content-Disposition: attachment; filename*=UTF-8''")
                    .append(url(book.filename).replace("+", "%20")).append("\r\n\r\n");
            out.write(headers.toString().getBytes(StandardCharsets.UTF_8));
            if (!head) { byte[] buffer = new byte[16384]; int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            }
            out.flush();
        }
    }

    private static void send(OutputStream out, int status, String mime, String body) throws IOException {
        send(out, status, mime, body, false);
    }
    private static void send(OutputStream out, int status, String mime, String body, boolean head) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String label = status == 200 ? "OK" : status == 404 ? "Not Found" :
                status == 405 ? "Method Not Allowed" : status == 503 ? "Service Unavailable" : "Bad Request";
        String headers = "HTTP/1.1 " + status + " " + label + "\r\nContent-Type: " + mime +
                "\r\nContent-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.UTF_8));
        if (!head) out.write(bytes);
        out.flush();
    }

    private static int page(String raw) {
        try { return Math.min(10000, Math.max(0, Integer.parseInt(raw))); }
        catch (Exception ignored) { return 0; }
    }

    private static Map<String,String> parseQuery(String query) {
        Map<String,String> values = new HashMap<>();
        for (String component : query.split("&", 20)) {
            if (component.isEmpty()) continue;
            String[] pair = component.split("=", 2);
            try {
                values.put(URLDecoder.decode(pair[0], "UTF-8"),
                        URLDecoder.decode(pair.length > 1 ? pair[1] : "", "UTF-8"));
            } catch (Exception ignored) {}
        }
        return values;
    }

    private static String url(String value) {
        try { return URLEncoder.encode(value, "UTF-8"); }
        catch (Exception impossible) { return ""; }
    }

    private static String xml(String value) {
        if (value == null) return "";
        StringBuilder r = new StringBuilder(value.length() + 10);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&': r.append("&amp;"); break;
                case '<': r.append("&lt;"); break;
                case '>': r.append("&gt;"); break;
                case '\"': r.append("&quot;"); break;
                case '\'': r.append("&apos;"); break;
                default: if (c >= 0x20 || c == '\n' || c == '\r' || c == '\t') r.append(c);
            }
        }
        return r.toString();
    }

    private static String utc() {
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        df.setTimeZone(TimeZone.getTimeZone("UTC"));
        return df.format(new Date());
    }

    private static String feed(String title, String href, String kind) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<feed xmlns=\"http://www.w3.org/2005/Atom\">" +
                "<id>urn:tvopds:" + xml(href) + "</id>" +
                "<title>" + xml(title) + "</title><updated>" + utc() + "</updated>" +
                "<author><name>AF-OPDS</name></author>" +
                "<link rel=\"self\" href=\"" + xml(href) + "\" type=\"" + FEED_MIME + kind + "\"/>" +
                "<link rel=\"start\" href=\"/opds\" type=\"" + FEED_MIME + "navigation\"/>" +
                "<link rel=\"search\" href=\"/opds/opensearch.xml\" type=\"application/opensearchdescription+xml\"/>";
    }

    private static String navEntry(String name, String href, String kind) {
        return "<entry><id>urn:tvopds:" + xml(href) + "</id><title>" + xml(name) +
                "</title><updated>" + utc() + "</updated>" +
                "<link rel=\"subsection\" href=\"" + xml(href) + "\" type=\"" +
                FEED_MIME + kind + "\"/></entry>";
    }

    private static String root() {
        return feed("AF-OPDS — библиотека", "/opds", "navigation") +
                navEntry("Все книги", "/opds/all", "navigation") +
                navEntry("Авторы", "/opds/authors", "navigation") +
                navEntry("Жанры", "/opds/genres", "navigation") +
                navEntry("Серии", "/opds/series", "navigation") + "</feed>";
    }

    private static String categories(List<String> values, String title, String field, int page) {
        String path = field.equals("author") ? "/opds/authors" : "/opds/series";
        String target = field.equals("author") ? "/opds/author" : "/opds/serie";
        String href = path + "?page=" + page;
        StringBuilder sb = new StringBuilder(feed(title, href, "navigation"));
        if (page > 0) {
            String previous = path + "?page=" + (page - 1);
            sb.append("<link rel=\"previous\" href=\"").append(previous).append("\"/>");
            sb.append(navEntry("← Предыдущая страница", previous, "navigation"));
        }
        if (values.size() >= PAGE_SIZE) {
            String next = path + "?page=" + (page + 1);
            sb.append("<link rel=\"next\" href=\"").append(next).append("\"/>");
            sb.append(navEntry("Следующая страница →", next, "navigation"));
        }
        for (String item : values) sb.append(navEntry(item, target + "?name=" + url(item), "acquisition"));
        return sb.append("</feed>").toString();
    }

    private static String authors(LibraryDb db, String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            StringBuilder sb = new StringBuilder(feed("Авторы", "/opds/authors", "navigation"));
            for (String initial : db.authorInitials()) {
                sb.append(navEntry(initial, "/opds/authors?prefix=" + url(initial), "navigation"));
            }
            return sb.append("</feed>").toString();
        }
        int count = db.distinctCountStarting("author", prefix);
        if (count <= PAGE_SIZE) {
            StringBuilder sb = new StringBuilder(feed("Авторы: " + prefix,
                    "/opds/authors?prefix=" + url(prefix), "navigation"));
            for (String author : db.valuesStarting("author", prefix, PAGE_SIZE)) {
                sb.append(navEntry(author, "/opds/author?name=" + url(author), "acquisition"));
            }
            return sb.append("</feed>").toString();
        }
        StringBuilder sb = new StringBuilder(feed("Авторы: " + prefix,
                "/opds/authors?prefix=" + url(prefix), "navigation"));
        for (String nextPrefix : db.nextPrefixes("author", prefix)) {
            sb.append(navEntry(nextPrefix, "/opds/authors?prefix=" + url(nextPrefix), "navigation"));
        }
        return sb.append("</feed>").toString();
    }

    private static String titles(LibraryDb db, String prefix, int page) {
        if (prefix == null || prefix.isEmpty()) {
            StringBuilder sb = new StringBuilder(feed("Все книги — по названию", "/opds/all", "navigation"));
            for (String initial : db.titleInitials()) {
                sb.append(navEntry(initial, "/opds/all?prefix=" + url(initial), "navigation"));
            }
            return sb.append("</feed>").toString();
        }
        int count = db.countStarting("title", prefix);
        String base = "/opds/all?prefix=" + url(prefix);
        if (count <= PAGE_SIZE) {
            return books(db.listStarting("title", prefix, PAGE_SIZE), "Все книги: " + prefix, base, 0, false);
        }
        List<String> next = db.nextPrefixes("title", prefix);
        if (next.isEmpty()) {
            // A very common identical title cannot be split further; retain a reachable paged feed.
            return books(db.listStarting("title", prefix, page, PAGE_SIZE), "Все книги: " + prefix, base, page, true);
        }
        StringBuilder sb = new StringBuilder(feed("Все книги: " + prefix, base, "navigation"));
        for (String nextPrefix : next) {
            sb.append(navEntry(nextPrefix, "/opds/all?prefix=" + url(nextPrefix), "navigation"));
        }
        return sb.append("</feed>").toString();
    }

    private static String authorCatalog(String author, int bookCount) {
        String href = "/opds/author?name=" + url(author);
        return feed(author + " — книг: " + bookCount, href, "navigation") +
                navEntry("Книги по алфавиту", "/opds/author/books?name=" + url(author), "acquisition") +
                navEntry("Серии", "/opds/author/series?name=" + url(author), "navigation") + "</feed>";
    }

    private static String authorSeries(LibraryDb db, String author) {
        String href = "/opds/author/series?name=" + url(author);
        StringBuilder sb = new StringBuilder(feed("Серии: " + author, href, "navigation"));
        for (String series : db.seriesForAuthor(author)) {
            sb.append(navEntry(series, "/opds/author/serie?author=" + url(author) + "&name=" + url(series), "acquisition"));
        }
        return sb.append("</feed>").toString();
    }

    private static String genres(LibraryDb db) {
        StringBuilder sb = new StringBuilder(feed("Жанры", "/opds/genres", "navigation"));
        for (String genre : db.allGenres()) {
            sb.append(navEntry(genre, "/opds/genre?name=" + url(genre), "acquisition"));
        }
        return sb.append("</feed>").toString();
    }

    private static String books(List<LibraryDb.Book> books, String title, String base, int page) {
        return books(books, title, base, page, true);
    }

    private static String books(List<LibraryDb.Book> books, String title, String base, int page, boolean paginated) {
        String href = base + (base.contains("?") ? "&" : "?") + "page=" + page;
        StringBuilder sb = new StringBuilder(feed(title, href, "acquisition"));
        if (paginated && page > 0) {
            String prev = base + (base.contains("?") ? "&" : "?") + "page=" + (page - 1);
            sb.append("<link rel=\"previous\" href=\"").append(xml(prev)).append("\"/>");
        }
        if (paginated && books.size() >= PAGE_SIZE) {
            String next = base + (base.contains("?") ? "&" : "?") + "page=" + (page + 1);
            sb.append("<link rel=\"next\" href=\"").append(xml(next)).append("\"/>");
        }
        for (LibraryDb.Book b : books) {
            String mime;
            switch (b.format) {
                case "epub": mime = "application/epub+zip"; break;
                case "fb2": mime = "application/fb2+xml"; break;
                case "pdf": mime = "application/pdf"; break;
                case "mobi": mime = "application/x-mobipocket-ebook"; break;
                case "azw3": mime = "application/vnd.amazon.mobi8-ebook"; break;
                default: mime = "text/plain";
            }
            sb.append("<entry><id>urn:tvopds:book:").append(b.id)
                .append("</id><title>").append(xml(b.title)).append("</title>")
                .append("<updated>").append(utc()).append("</updated>")
                .append("<author><name>").append(xml(b.author)).append("</name></author>");
            if (!b.series.isEmpty()) sb.append("<summary type=\"text\">Серия: ").append(xml(b.series)).append("</summary>");
            sb.append("<link rel=\"http://opds-spec.org/acquisition\" href=\"/download/")
              .append(b.id).append("\" type=\"").append(mime).append("\"/></entry>");
        }
        return sb.append("</feed>").toString();
    }
}
