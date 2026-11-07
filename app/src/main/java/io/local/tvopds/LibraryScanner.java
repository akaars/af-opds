package io.local.tvopds;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Streams a regular directory or an INPX catalog into SQLite without retaining books in memory. */
final class LibraryScanner {
    private static final int MAX_BOOKS = 1_000_000;
    private static final int MAX_INP_LINE = 64 * 1024;
    private final Context context;
    private final Uri tree;
    private final Progress progress;
    private int emitted;

    interface Progress { void report(int books, String location); }

    LibraryScanner(Context context, Uri tree, Progress progress) {
        this.context = context.getApplicationContext();
        this.tree = tree;
        this.progress = progress;
    }

    void scanInto(LibraryDb.BookSink sink) throws Exception {
        if ("file".equals(tree.getScheme())) {
            RootFiles root = rootFiles(new File(tree.getPath()));
            if (root.inpx == null) throw new IOException("В указанной папке не найден INPX-файл");
            scanInpx(root.inpx, root.archives, sink);
            return;
        }
        String rootId = DocumentsContract.getTreeDocumentId(tree);
        RootFiles root = rootFiles(rootId);
        if (root.inpx != null) scanInpx(root.inpx, root.archives, sink);
        else walk(rootId, 0, sink);
    }

    private static final class RootFiles {
        Uri inpx;
        final Map<String, Uri> archives = new HashMap<>();
    }

    /** The supplied Flibusta catalog keeps its INPX and matching ZIP files in the selected root. */
    private RootFiles rootFiles(String rootId) throws Exception {
        RootFiles result = new RootFiles();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId);
        String[] columns = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE};
        try (Cursor c = context.getContentResolver().query(children, columns, null, null, null)) {
            if (c == null) throw new IOException("Не удалось прочитать каталог: " + children);
            while (c.moveToNext()) {
                if (Document.MIME_TYPE_DIR.equals(c.getString(2))) continue;
                String name = c.getString(1);
                if (name == null) continue;
                String lower = name.toLowerCase(Locale.ROOT);
                Uri uri = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0));
                if (lower.endsWith(".inpx") && result.inpx == null) result.inpx = uri;
                if (lower.endsWith(".zip")) result.archives.put(lower, uri);
            }
        }
        return result;
    }

    private RootFiles rootFiles(File root) throws IOException {
        if (!root.isDirectory() || !root.canRead()) throw new IOException("Папка USB недоступна: " + root);
        RootFiles result = new RootFiles();
        File[] files = root.listFiles();
        if (files == null) throw new IOException("Не удалось прочитать папку USB: " + root);
        for (File file : files) {
            if (!file.isFile()) continue;
            String lower = file.getName().toLowerCase(Locale.ROOT);
            if (lower.endsWith(".inpx") && result.inpx == null) result.inpx = Uri.fromFile(file);
            if (lower.endsWith(".zip")) result.archives.put(lower, Uri.fromFile(file));
        }
        return result;
    }

    private void scanInpx(Uri inpx, Map<String, Uri> archives, LibraryDb.BookSink sink) throws Exception {
        InputStream raw = openInput(inpx);
        if (raw == null) throw new IOException("Не удалось открыть INPX-файл");
        try (ZipInputStream zip = new ZipInputStream(raw)) {
            ZipEntry archiveEntry;
            while ((archiveEntry = zip.getNextEntry()) != null) {
                if (archiveEntry.isDirectory() || !archiveEntry.getName().toLowerCase(Locale.ROOT).endsWith(".inp")) {
                    zip.closeEntry();
                    continue;
                }
                String inpName = archiveEntry.getName();
                int slash = inpName.lastIndexOf('/');
                String zipName = (slash == -1 ? inpName : inpName.substring(slash + 1));
                zipName = zipName.substring(0, zipName.length() - 4).toLowerCase(Locale.ROOT) + ".zip";
                Uri archive = archives.get(zipName);
                if (archive == null) throw new IOException("Для " + inpName + " не найден архив " + zipName);
                if (progress != null) progress.report(emitted, inpName);
                readInp(zip, archive, sink);
                zip.closeEntry();
            }
        }
        if (emitted == 0) throw new IOException("INPX не содержит доступных FB2-книг");
    }

    private InputStream openInput(Uri uri) throws IOException {
        if ("file".equals(uri.getScheme())) return new FileInputStream(new File(uri.getPath()));
        return context.getContentResolver().openInputStream(uri);
    }

    private void readInp(InputStream input, Uri archive, LibraryDb.BookSink sink) throws Exception {
        // Do not close this reader: it shares the ZipInputStream used for subsequent .inp entries.
        BufferedReader lines = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8), 32 * 1024);
        String line;
        while ((line = lines.readLine()) != null) {
            if (line.length() > MAX_INP_LINE) throw new IOException("Строка INPX слишком длинная");
            String[] f = line.split("\u0004", -1);
            // Default INPX layout: AUTHOR;GENRE;TITLE;SERIES;SERNO;FILE;SIZE;LIBID;DEL;EXT;...
            if (f.length < 10 || "1".equals(f[8]) || !"fb2".equalsIgnoreCase(f[9]) || f[5].isEmpty()) continue;
            String entry = f[5] + ".fb2";
            LibraryDb.Book b = new LibraryDb.Book();
            b.id = idFor(archive.toString() + "#" + entry);
            b.uri = archive.toString(); b.entry = entry; b.filename = entry; b.format = "fb2";
            b.title = f[2].isEmpty() ? f[5] : f[2];
            b.author = authors(f[0]); b.series = f[3]; b.genre = f[1]; b.size = number(f[6]); b.modified = 0;
            emit(sink, b);
        }
    }

    private static long number(String value) {
        try { return Long.parseLong(value); } catch (Exception ignored) { return -1; }
    }

    private static String authors(String raw) {
        if (raw == null || raw.isEmpty()) return "Неизвестный автор";
        StringBuilder result = new StringBuilder();
        for (String author : raw.split(":")) {
            if (author.isEmpty()) continue;
            String[] name = author.split(",", -1);
            StringBuilder formatted = new StringBuilder();
            // Keep the INPX surname-first order so the OPDS author index starts with the surname.
            for (int i : new int[]{0, 1, 2}) {
                if (i < name.length && !name[i].trim().isEmpty()) {
                    if (formatted.length() > 0) formatted.append(' ');
                    formatted.append(upperFirst(name[i].trim()));
                }
            }
            if (formatted.length() == 0) formatted.append(author.trim());
            if (formatted.length() > 0) {
                if (result.length() > 0) result.append(", ");
                result.append(formatted);
            }
        }
        return result.length() == 0 ? "Неизвестный автор" : result.toString();
    }

    private static String upperFirst(String value) {
        int first = value.codePointAt(0);
        return new String(Character.toChars(Character.toUpperCase(first))) + value.substring(Character.charCount(first));
    }

    private void walk(String parentId, int depth, LibraryDb.BookSink sink) throws Exception {
        if (depth > 32) throw new IllegalStateException("Папки вложены слишком глубоко");
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        String[] columns = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED};
        try (Cursor c = context.getContentResolver().query(children, columns, null, null, null)) {
            if (c == null) throw new IllegalStateException("Не удалось прочитать каталог: " + children);
            while (c.moveToNext()) {
                String id = c.getString(0), name = c.getString(1), mime = c.getString(2);
                long size = c.isNull(3) ? -1 : c.getLong(3);
                long modified = c.isNull(4) ? 0 : c.getLong(4);
                if (Document.MIME_TYPE_DIR.equals(mime)) { walk(id, depth + 1, sink); continue; }
                if (name == null) continue;
                int dot = name.lastIndexOf('.');
                if (dot < 0) continue;
                String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
                if (!ext.equals("fb2") && !ext.equals("epub") && !ext.equals("pdf") &&
                        !ext.equals("mobi") && !ext.equals("azw3") && !ext.equals("txt")) continue;
                Uri fileUri = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                LibraryDb.Book b = new LibraryDb.Book();
                b.id = idFor(fileUri.toString());
                b.uri = fileUri.toString(); b.entry = ""; b.filename = name; b.format = ext;
                b.size = size; b.modified = modified;
                b.title = name.substring(0, dot); b.author = "Неизвестный автор"; b.series = "";
                try {
                    if (ext.equals("fb2")) readFb2(b);
                    if (ext.equals("epub")) readEpub(b);
                } catch (Exception ignored) { /* Invalid metadata must not hide a readable book. */ }
                emit(sink, b);
            }
        }
    }

    private void emit(LibraryDb.BookSink sink, LibraryDb.Book b) throws Exception {
        if (emitted >= MAX_BOOKS) throw new IllegalStateException("Превышен лимит " + MAX_BOOKS + " книг");
        b.title = upperFirst(b.title);
        sink.add(b);
        emitted++;
        if (progress != null && emitted % 5_000 == 0) progress.report(emitted, null);
    }

    private static String idFor(String text) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder s = new StringBuilder(32);
        for (int i = 0; i < 16; i++) s.append(String.format(Locale.ROOT, "%02x", hash[i] & 0xff));
        return s.toString();
    }

    private static XmlPullParser parser(InputStream in) throws Exception {
        XmlPullParser p = XmlPullParserFactory.newInstance().newPullParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        p.setInput(in, null);
        return p;
    }

    private void readFb2(LibraryDb.Book b) throws Exception {
        try (InputStream in = context.getContentResolver().openInputStream(Uri.parse(b.uri))) {
            if (in == null) return;
            XmlPullParser p = parser(in);
            StringBuilder author = new StringBuilder();
            boolean inTitleInfo = false, inAuthor = false;
            for (int event = p.getEventType(); event != XmlPullParser.END_DOCUMENT; event = p.next()) {
                if (event == XmlPullParser.START_TAG) {
                    String tag = p.getName();
                    if (tag.equals("body")) break;
                    if (tag.equals("title-info")) inTitleInfo = true;
                    else if (inTitleInfo && tag.equals("book-title")) { String value = p.nextText().trim(); if (!value.isEmpty()) b.title = value; }
                    else if (inTitleInfo && tag.equals("author")) inAuthor = true;
                    else if (inTitleInfo && inAuthor && (tag.equals("first-name") || tag.equals("middle-name") || tag.equals("last-name"))) {
                        String value = p.nextText().trim(); if (!value.isEmpty()) { if (author.length() > 0) author.append(' '); author.append(value); }
                    } else if (inTitleInfo && tag.equals("sequence")) {
                        String name = p.getAttributeValue(null, "name"); if (name != null) b.series = name;
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    if (p.getName().equals("author") && inTitleInfo) inAuthor = false;
                    if (p.getName().equals("title-info")) break;
                }
            }
            if (author.length() > 0) b.author = author.toString();
        }
    }

    private void readEpub(LibraryDb.Book b) throws Exception {
        try (InputStream raw = context.getContentResolver().openInputStream(Uri.parse(b.uri))) {
            if (raw == null) return;
            try (ZipInputStream zip = new ZipInputStream(raw)) {
                ZipEntry entry; int count = 0;
                while ((entry = zip.getNextEntry()) != null && ++count <= 2000) {
                    if (!entry.isDirectory() && entry.getName().toLowerCase(Locale.ROOT).endsWith(".opf")) {
                        ByteArrayOutputStream data = new ByteArrayOutputStream();
                        byte[] buffer = new byte[4096]; int n;
                        while ((n = zip.read(buffer)) > 0) {
                            if (data.size() + n > 2_000_000) return;
                            data.write(buffer, 0, n);
                        }
                        XmlPullParser p = parser(new ByteArrayInputStream(data.toByteArray()));
                        for (int event = p.getEventType(); event != XmlPullParser.END_DOCUMENT; event = p.next()) {
                            if (event != XmlPullParser.START_TAG) continue;
                            String tag = p.getName();
                            if (tag.equals("title")) { String title = p.nextText().trim(); if (!title.isEmpty()) b.title = title; }
                            else if (tag.equals("creator")) { String name = p.nextText().trim(); if (!name.isEmpty() && b.author.equals("Неизвестный автор")) b.author = name; }
                            else if (tag.equals("meta") && "calibre:series".equals(p.getAttributeValue(null, "name"))) {
                                String value = p.getAttributeValue(null, "content"); if (value != null) b.series = value;
                            }
                        }
                        return;
                    }
                    zip.closeEntry();
                }
            }
        }
    }
}
