package io.local.tvopds;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;
import android.content.ContentValues;
import java.util.ArrayList;
import java.util.List;

public final class LibraryDb extends SQLiteOpenHelper {
    interface Source { void write(BookSink sink) throws Exception; }
    interface BookSink { void add(Book book) throws Exception; }

    public static final class Book {
        public String id, title, author, series, genre, format, uri, entry, filename;
        public long size, modified;
    }

    public LibraryDb(Context context) { super(context, "books.db", null, 4); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE books (id TEXT PRIMARY KEY, title TEXT NOT NULL, author TEXT NOT NULL, series TEXT NOT NULL, format TEXT NOT NULL, uri TEXT NOT NULL, entry TEXT NOT NULL DEFAULT '', filename TEXT NOT NULL, size INTEGER, modified INTEGER)");
        db.execSQL("CREATE TABLE book_genres (book_id TEXT NOT NULL, genre TEXT NOT NULL, PRIMARY KEY(book_id, genre))");
        db.execSQL("CREATE TABLE book_authors (book_id TEXT NOT NULL, author TEXT NOT NULL, PRIMARY KEY(book_id, author))");
        createIndexes(db);
    }

    private static void createIndexes(SQLiteDatabase db) {
        db.execSQL("CREATE INDEX books_title ON books(title COLLATE NOCASE)");
        db.execSQL("CREATE INDEX books_author ON books(author COLLATE NOCASE)");
        db.execSQL("CREATE INDEX books_series ON books(series COLLATE NOCASE)");
        db.execSQL("CREATE INDEX book_genres_genre ON book_genres(genre COLLATE NOCASE)");
        db.execSQL("CREATE INDEX book_authors_author ON book_authors(author COLLATE NOCASE)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE books ADD COLUMN entry TEXT NOT NULL DEFAULT ''");
        if (oldVersion < 3) {
            db.execSQL("CREATE TABLE IF NOT EXISTS book_genres (book_id TEXT NOT NULL, genre TEXT NOT NULL, PRIMARY KEY(book_id, genre))");
            db.execSQL("CREATE INDEX IF NOT EXISTS book_genres_genre ON book_genres(genre COLLATE NOCASE)");
        }
        if (oldVersion < 4) {
            db.execSQL("CREATE TABLE IF NOT EXISTS book_authors (book_id TEXT NOT NULL, author TEXT NOT NULL, PRIMARY KEY(book_id, author))");
            // Preserve the old single author value until the first rescan populates individual coauthors.
            db.execSQL("INSERT OR IGNORE INTO book_authors (book_id, author) SELECT id, author FROM books WHERE author <> ''");
            db.execSQL("CREATE INDEX IF NOT EXISTS book_authors_author ON book_authors(author COLLATE NOCASE)");
        }
    }

    /** Replaces the catalog atomically while the source streams records one by one. */
    public int replace(Source source, Runnable beforeIndexing) throws Exception {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            // Maintaining three B-trees per row makes a 700k-book INPX import impractically slow.
            // SQLite DDL is transactional, so rollback restores both the prior catalog and its indexes.
            db.execSQL("DROP INDEX IF EXISTS books_title");
            db.execSQL("DROP INDEX IF EXISTS books_author");
            db.execSQL("DROP INDEX IF EXISTS books_series");
            db.execSQL("DROP INDEX IF EXISTS book_genres_genre");
            db.execSQL("DROP INDEX IF EXISTS book_authors_author");
            db.delete("book_genres", null, null);
            db.delete("book_authors", null, null);
            db.delete("books", null, null);
            SQLiteStatement insert = db.compileStatement("INSERT INTO books (id,title,author,series,format,uri,entry,filename,size,modified) VALUES (?,?,?,?,?,?,?,?,?,?)");
            SQLiteStatement insertGenre = db.compileStatement("INSERT OR IGNORE INTO book_genres (book_id,genre) VALUES (?,?)");
            SQLiteStatement insertAuthor = db.compileStatement("INSERT OR IGNORE INTO book_authors (book_id,author) VALUES (?,?)");
            final int[] count = {0};
            source.write(book -> {
                insert.clearBindings();
                insert.bindString(1, book.id); insert.bindString(2, book.title);
                insert.bindString(3, book.author); insert.bindString(4, book.series);
                insert.bindString(5, book.format); insert.bindString(6, book.uri);
                insert.bindString(7, book.entry == null ? "" : book.entry);
                insert.bindString(8, book.filename); insert.bindLong(9, book.size);
                insert.bindLong(10, book.modified);
                insert.executeInsert();
                for (String author : book.author.split(",\\s*")) {
                    author = author.trim();
                    if (author.isEmpty()) continue;
                    insertAuthor.clearBindings();
                    insertAuthor.bindString(1, book.id); insertAuthor.bindString(2, author);
                    insertAuthor.executeInsert();
                }
                if (book.genre != null) for (String genre : book.genre.split(":")) {
                    genre = genre.trim();
                    if (genre.isEmpty()) continue;
                    insertGenre.clearBindings();
                    insertGenre.bindString(1, book.id); insertGenre.bindString(2, GenreNames.displayName(genre));
                    insertGenre.executeInsert();
                }
                count[0]++;
            });
            beforeIndexing.run();
            createIndexes(db);
            db.setTransactionSuccessful();
            return count[0];
        } finally { db.endTransaction(); }
    }

    public int count() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM books", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public Book find(String id) {
        try (Cursor c = getReadableDatabase().query("books", null, "id=?", new String[]{id}, null, null, null, "1")) {
            return c.moveToFirst() ? fromCursor(c) : null;
        }
    }

    public List<Book> list(String author, String series, String search, int page, int limit) {
        StringBuilder selection = new StringBuilder("1=1");
        List<String> args = new ArrayList<>();
        if (author != null) { selection.append(" AND id IN (SELECT book_id FROM book_authors WHERE author=?)"); args.add(author); }
        if (series != null) { selection.append(" AND series=?"); args.add(series); }
        if (search != null && !search.isEmpty()) {
            selection.append(" AND (title LIKE ? ESCAPE '\\' OR author LIKE ? ESCAPE '\\' OR series LIKE ? ESCAPE '\\')");
            String pattern = "%" + search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            args.add(pattern); args.add(pattern); args.add(pattern);
        }
        List<Book> result = new ArrayList<>();
        String offsetLimit = Math.max(0L, (long) page * limit) + "," + limit;
        try (Cursor c = getReadableDatabase().query("books", null, selection.toString(), args.toArray(new String[0]), null, null,
                "title COLLATE NOCASE, id", offsetLimit)) {
            while (c.moveToNext()) result.add(fromCursor(c));
        }
        return result;
    }

    public List<String> values(String column, int page, int limit) {
        if (!column.equals("author") && !column.equals("series")) throw new IllegalArgumentException();
        List<String> values = new ArrayList<>();
        long offset = Math.max(0L, (long) page * limit);
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT DISTINCT " + column + " FROM books WHERE " + column + " <> '' ORDER BY " + column + " COLLATE NOCASE LIMIT ? OFFSET ?",
                new String[]{String.valueOf(limit), String.valueOf(offset)})) {
            while (c.moveToNext()) values.add(c.getString(0));
        }
        return values;
    }

    /** All indexed genres. This catalog is intentionally unpaged: an INPX has a small, finite set. */
    public List<String> allGenres() {
        List<String> values = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT DISTINCT genre FROM book_genres WHERE genre <> '' ORDER BY genre COLLATE NOCASE", null)) {
            while (c.moveToNext()) values.add(c.getString(0));
        }
        return values;
    }

    /** Series belonging to one author; books outside that author must not leak into this catalog. */
    public List<String> seriesForAuthor(String author) {
        List<String> values = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT DISTINCT b.series FROM books b JOIN book_authors a ON b.id=a.book_id WHERE a.author=? AND b.series <> '' ORDER BY b.series COLLATE NOCASE",
                new String[]{author})) {
            while (c.moveToNext()) values.add(c.getString(0));
        }
        return values;
    }

    /** Returns the first character of every author name, for OPDS clients without page navigation. */
    public List<String> initials(String column) {
        checkPrefixColumn(column);
        List<String> values = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT DISTINCT substr(" + column + ", 1, 1) FROM books WHERE " + column + " <> '' ORDER BY 1 COLLATE NOCASE", null)) {
            while (c.moveToNext()) values.add(c.getString(0));
        }
        return values;
    }

    /** Author initials are deliberately constrained to the three alphabets exposed in the UI. */
    public List<String> authorInitials() {
        return catalogInitials("book_authors", "author", true);
    }

    public List<String> titleInitials() {
        return catalogInitials("books", "title", false);
    }

    public List<String> genreInitials() {
        return catalogInitials("book_genres", "genre", false);
    }

    private List<String> catalogInitials(String table, String column, boolean includeHebrew) {
        boolean[] russian = new boolean[33];
        boolean[] latin = new boolean[26];
        List<String> hebrew = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT DISTINCT substr(" + column + ", 1, 1) FROM " + table + " WHERE " + column + " <> ''", null)) {
            while (c.moveToNext()) {
                String initial = upperInitial(c.getString(0));
                int ru = "АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ".indexOf(initial);
                if (ru >= 0) russian[ru] = true;
                else if (initial.length() == 1 && initial.charAt(0) >= 'A' && initial.charAt(0) <= 'Z') latin[initial.charAt(0) - 'A'] = true;
                else if (includeHebrew && isHebrewInitial(initial) && !hebrew.contains(initial)) hebrew.add(initial);
            }
        }
        List<String> result = new ArrayList<>();
        String alphabet = "АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ";
        for (int i = 0; i < alphabet.length(); i++) if (russian[i]) result.add(String.valueOf(alphabet.charAt(i)));
        for (int i = 0; i < latin.length; i++) if (latin[i]) result.add(String.valueOf((char) ('A' + i)));
        hebrew.sort(String::compareTo);
        result.addAll(hebrew);
        return result;
    }

    public int distinctCountStarting(String column, String prefix) {
        return distinctCountStarting(column.equals("author") ? "book_authors" : "books", column, prefix);
    }

    private int distinctCountStarting(String table, String column, String prefix) {
        checkPrefixColumn(column);
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(DISTINCT " + column + ") FROM " + table + " WHERE " + startsWith(column), prefixArgs(prefix))) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public int distinctGenreCountStarting(String prefix) {
        return distinctCountStarting("book_genres", "genre", prefix);
    }

    public List<String> nextPrefixes(String column, String prefix) {
        return nextPrefixes(column.equals("author") ? "book_authors" : "books", column, prefix);
    }

    private List<String> nextPrefixes(String table, String column, String prefix) {
        checkPrefixColumn(column);
        List<String> values = new ArrayList<>();
        int character = prefix.codePointCount(0, prefix.length()) + 1;
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT DISTINCT substr(" + column + ", ?, 1) FROM " + table + " WHERE " + startsWith(column) +
                        " AND length(" + column + ") >= " + character + " ORDER BY 1 COLLATE NOCASE",
                new String[]{String.valueOf(character), prefixArgs(prefix)[0], prefixArgs(prefix)[1]})) {
            while (c.moveToNext()) {
                String value = prefix + c.getString(0);
                if (!column.equals("author") || isVisibleAuthor(value)) values.add(value);
            }
        }
        return values;
    }

    public List<String> nextGenrePrefixes(String prefix) {
        return nextPrefixes("book_genres", "genre", prefix);
    }

    public List<String> valuesStarting(String column, String prefix, int limit) {
        return valuesStarting(column.equals("author") ? "book_authors" : "books", column, prefix, limit);
    }

    private List<String> valuesStarting(String table, String column, String prefix, int limit) {
        checkPrefixColumn(column);
        List<String> values = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT DISTINCT " + column + " FROM " + table + " WHERE " + startsWith(column) +
                        " ORDER BY " + column + " COLLATE NOCASE LIMIT ?",
                new String[]{prefixArgs(prefix)[0], prefixArgs(prefix)[1], String.valueOf(limit)})) {
            while (c.moveToNext()) {
                String value = c.getString(0);
                if (!column.equals("author") || isVisibleAuthor(value)) values.add(value);
            }
        }
        return values;
    }

    public List<String> genreValuesStarting(String prefix, int limit) {
        return valuesStarting("book_genres", "genre", prefix, limit);
    }

    public int countStarting(String column, String prefix) {
        checkPrefixColumn(column);
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM books WHERE " + startsWith(column), prefixArgs(prefix))) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public List<Book> listGenre(String genre, int page, int limit) {
        List<Book> result = new ArrayList<>();
        String offsetLimit = Math.max(0L, (long) page * limit) + "," + limit;
        try (Cursor c = getReadableDatabase().query("books", null,
                "id IN (SELECT book_id FROM book_genres WHERE genre=?)", new String[]{genre}, null, null,
                "title COLLATE NOCASE, id", offsetLimit)) {
            while (c.moveToNext()) result.add(fromCursor(c));
        }
        return result;
    }

    public List<Book> listGenre(String genre) {
        List<Book> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("books", null,
                "id IN (SELECT book_id FROM book_genres WHERE genre=?)", new String[]{genre}, null, null,
                "title COLLATE NOCASE, id")) {
            while (c.moveToNext()) result.add(fromCursor(c));
        }
        return result;
    }

    public int countForAuthor(String author) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM book_authors WHERE author=?", new String[]{author})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public List<Book> listStarting(String column, String prefix, int limit) {
        return listStarting(column, prefix, 0, limit);
    }

    public List<Book> listStarting(String column, String prefix, int page, int limit) {
        checkPrefixColumn(column);
        List<Book> result = new ArrayList<>();
        String offsetLimit = Math.max(0L, (long) page * limit) + "," + limit;
        try (Cursor c = getReadableDatabase().query("books", null, startsWith(column), prefixArgs(prefix), null, null,
                column + " COLLATE NOCASE, id", offsetLimit)) {
            while (c.moveToNext()) result.add(fromCursor(c));
        }
        return result;
    }

    private static void checkPrefixColumn(String column) {
        if (!column.equals("author") && !column.equals("series") && !column.equals("title") && !column.equals("genre")) throw new IllegalArgumentException();
    }

    private static String range(String column) {
        return column + " COLLATE NOCASE >= ? AND " + column + " COLLATE NOCASE < ?";
    }

    private static String startsWith(String column) {
        return "substr(" + column + ", 1, ?) = ?";
    }

    private static String[] prefixArgs(String prefix) {
        if (prefix == null || prefix.isEmpty()) throw new IllegalArgumentException();
        return new String[]{String.valueOf(prefix.codePointCount(0, prefix.length())), prefix};
    }

    private static String upperInitial(String value) {
        if (value == null || value.isEmpty()) return "";
        int codePoint = value.codePointAt(0);
        return new String(Character.toChars(Character.toUpperCase(codePoint)));
    }

    private static boolean isHebrewInitial(String value) {
        return value.length() == 1 && value.charAt(0) >= '\u05d0' && value.charAt(0) <= '\u05ea';
    }

    private static String upperFirst(String value) {
        int first = value.codePointAt(0);
        return new String(Character.toChars(Character.toUpperCase(first))) + value.substring(Character.charCount(first));
    }

    private static boolean isVisibleAuthor(String value) {
        int alphabet = 0;
        for (int i = 0; i < value.length();) {
            int c = value.codePointAt(i);
            i += Character.charCount(c);
            if (c == ',') { alphabet = 0; continue; }
            if (Character.isWhitespace(c) || c == '-' || c == '\'' || c == '.') continue;
            int current = (c >= '\u0410' && c <= '\u044f') || c == '\u0401' || c == '\u0451' ? 1 :
                    (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') ? 2 :
                    (c >= '\u05d0' && c <= '\u05ea') ? 3 : 0;
            if (current == 0 || (alphabet != 0 && alphabet != current)) return false;
            alphabet = current;
        }
        return true;
    }

    private static String[] rangeArgs(String prefix) {
        if (prefix == null || prefix.isEmpty()) throw new IllegalArgumentException();
        int start = prefix.offsetByCodePoints(0, prefix.codePointCount(0, prefix.length()) - 1);
        int codePoint = prefix.codePointAt(start);
        if (codePoint >= Character.MAX_CODE_POINT) throw new IllegalArgumentException();
        return new String[]{prefix, prefix.substring(0, start) + new String(Character.toChars(codePoint + 1))};
    }

    private Book fromCursor(Cursor c) {
        Book b = new Book();
        b.id = c.getString(c.getColumnIndexOrThrow("id"));
        b.title = c.getString(c.getColumnIndexOrThrow("title"));
        b.author = c.getString(c.getColumnIndexOrThrow("author"));
        b.series = c.getString(c.getColumnIndexOrThrow("series"));
        b.format = c.getString(c.getColumnIndexOrThrow("format"));
        b.uri = c.getString(c.getColumnIndexOrThrow("uri"));
        b.entry = c.getString(c.getColumnIndexOrThrow("entry"));
        b.filename = c.getString(c.getColumnIndexOrThrow("filename"));
        b.size = c.getLong(c.getColumnIndexOrThrow("size"));
        b.modified = c.getLong(c.getColumnIndexOrThrow("modified"));
        return b;
    }
}
