package com.tj.linkmeter;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** What kind of thing a file is, from its extension: which icon it gets, whether it has a thumbnail, its MIME type. No Android in it. */
enum FileKind {
    FOLDER("Folder", false),
    IMAGE("Image", true),
    VIDEO("Video", true),
    AUDIO("Audio", false),
    PDF("PDF", true),
    DOC("Document", false),
    SHEET("Spreadsheet", false),
    SLIDES("Slides", false),
    TEXT("Text", false),
    CODE("Code", false),
    ARCHIVE("Archive", false),
    APK("App", true),
    OTHER("File", false);

    final String label;
    private final boolean thumb;

    FileKind(String label, boolean thumb) {
        this.label = label;
        this.thumb = thumb;
    }

    /** Can a small picture of the content be made (so the list shows a thumbnail instead of an icon)? */
    boolean hasThumbnail() {
        return thumb;
    }

    private static final Map<String, FileKind> EXT = new HashMap<>();
    private static final Map<String, String> MIME = new HashMap<>();

    private static void add(FileKind k, String mime, String... exts) {
        for (String e : exts) {
            EXT.put(e, k);
            if (mime != null && !MIME.containsKey(e)) {
                MIME.put(e, mime);
            }
        }
    }

    static {
        add(IMAGE, "image/jpeg", "jpg", "jpeg", "jpe");
        add(IMAGE, "image/png", "png");
        add(IMAGE, "image/gif", "gif");
        add(IMAGE, "image/webp", "webp");
        add(IMAGE, "image/bmp", "bmp");
        add(IMAGE, "image/heic", "heic");
        add(IMAGE, "image/heif", "heif");
        add(IMAGE, "image/avif", "avif");
        add(IMAGE, "image/x-adobe-dng", "dng");
        add(IMAGE, "image/svg+xml", "svg");
        add(VIDEO, "video/mp4", "mp4", "m4v");
        add(VIDEO, "video/x-matroska", "mkv");
        add(VIDEO, "video/webm", "webm");
        add(VIDEO, "video/3gpp", "3gp", "3gpp");
        add(VIDEO, "video/quicktime", "mov");
        add(VIDEO, "video/x-msvideo", "avi");
        add(VIDEO, "video/mpeg", "mpg", "mpeg");
        add(VIDEO, "video/x-ms-wmv", "wmv");
        add(VIDEO, "video/x-flv", "flv");
        add(AUDIO, "audio/mpeg", "mp3");
        add(AUDIO, "audio/mp4", "m4a");
        add(AUDIO, "audio/aac", "aac");
        add(AUDIO, "audio/x-wav", "wav");
        add(AUDIO, "audio/flac", "flac");
        add(AUDIO, "audio/ogg", "ogg", "oga");
        add(AUDIO, "audio/opus", "opus");
        add(AUDIO, "audio/amr", "amr");
        add(AUDIO, "audio/x-ms-wma", "wma");
        add(AUDIO, "audio/midi", "mid", "midi");
        add(PDF, "application/pdf", "pdf");
        add(DOC, "application/msword", "doc");
        add(DOC, "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx");
        add(DOC, "application/vnd.oasis.opendocument.text", "odt");
        add(DOC, "application/rtf", "rtf");
        add(SHEET, "application/vnd.ms-excel", "xls");
        add(SHEET, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx");
        add(SHEET, "application/vnd.oasis.opendocument.spreadsheet", "ods");
        add(SHEET, "text/csv", "csv");
        add(SLIDES, "application/vnd.ms-powerpoint", "ppt");
        add(SLIDES, "application/vnd.openxmlformats-officedocument.presentationml.presentation", "pptx");
        add(SLIDES, "application/vnd.oasis.opendocument.presentation", "odp");
        add(TEXT, "text/plain", "txt", "log", "md", "ini", "conf", "cfg");
        add(TEXT, "application/json", "json");
        add(TEXT, "text/xml", "xml");
        add(TEXT, "text/html", "html", "htm");
        add(CODE, "text/plain", "java", "kt", "py", "js", "ts", "c", "cpp", "h", "sh", "css", "gradle", "yml", "yaml");
        add(ARCHIVE, "application/zip", "zip");
        add(ARCHIVE, "application/vnd.rar", "rar");
        add(ARCHIVE, "application/x-7z-compressed", "7z");
        add(ARCHIVE, "application/x-tar", "tar");
        add(ARCHIVE, "application/gzip", "gz", "tgz");
        add(ARCHIVE, "application/x-bzip2", "bz2");
        add(ARCHIVE, "application/x-xz", "xz");
        add(APK, "application/vnd.android.package-archive", "apk");
        add(APK, "application/octet-stream", "apks", "xapk", "aab");
    }

    /** The kind of an entry. A folder is FOLDER; a file by its extension (case does not matter); anything unknown is OTHER. */
    static FileKind of(String name, boolean dir) {
        if (dir) {
            return FOLDER;
        }
        FileKind k = EXT.get(FsPath.ext(name));
        return k == null ? OTHER : k;
    }

    /** The MIME type to hand to another app, from the extension. "application/octet-stream" when it is not known. */
    static String mime(String name) {
        String m = MIME.get(FsPath.ext(name).toLowerCase(Locale.US));
        return m == null ? "application/octet-stream" : m;
    }
}
