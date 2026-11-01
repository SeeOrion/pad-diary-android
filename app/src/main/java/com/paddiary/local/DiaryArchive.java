package com.paddiary.local;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

final class DiaryArchive {
    private static final long MAX_ARCHIVE_BYTES = 20L * 1024 * 1024 * 1024;
    private static final long MAX_ITEM_BYTES = 80L * 1024 * 1024;
    private static final int MAX_FILES = 100000;
    private static final String BACKUP_PREFIX = "纸间日记-自动备份-";

    static File imageDir(Context context) {
        File dir = new File(context.getFilesDir(), "images");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("无法建立图片目录");
        return dir;
    }

    static void exportTo(Context context, DiaryStore store, OutputStream target) throws Exception {
        JSONObject data = store.snapshot();
        JSONArray photos = data.getJSONArray("photos");
        File images = imageDir(context);
        try (ZipOutputStream zip = new ZipOutputStream(target)) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(data.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            byte[] buffer = new byte[64 * 1024];
            for (int i = 0; i < photos.length(); i++) {
                String name = photos.getJSONObject(i).getString("file_name");
                if (!safeName(name)) throw new IOException("图片名称不合法");
                File file = new File(images, name);
                if (!file.isFile()) throw new IOException("备份中缺少图片：" + name);
                zip.putNextEntry(new ZipEntry("images/" + name));
                try (InputStream in = new FileInputStream(file)) { copy(in, zip, MAX_ITEM_BYTES); }
                zip.closeEntry();
            }
            zip.finish();
        }
    }

    static void importFrom(Context context, DiaryStore store, InputStream source) throws Exception {
        File stage = new File(context.getCacheDir(), "import-" + UUID.randomUUID());
        if (!stage.mkdirs()) throw new IOException("无法建立临时目录");
        ArrayList<File> installed = new ArrayList<>();
        try {
            byte[] manifest = null;
            Set<String> zipNames = new HashSet<>();
            long total = 0; int count = 0;
            try (ZipInputStream zip = new ZipInputStream(source)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (entry.isDirectory() || !zipNames.add(name) || ++count > MAX_FILES) throw new IOException("备份文件结构不正确");
                    if (!name.equals("manifest.json") && !(name.startsWith("images/") && safeName(name.substring(7)))) throw new IOException("备份包含不安全路径");
                    if (name.equals("manifest.json")) {
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        total += copy(zip, out, 50L * 1024 * 1024);
                        manifest = out.toByteArray();
                    } else {
                        try (OutputStream out = new FileOutputStream(new File(stage, name.substring(7)))) {
                            total += copy(zip, out, MAX_ITEM_BYTES);
                        }
                    }
                    if (total > MAX_ARCHIVE_BYTES) throw new IOException("备份文件过大");
                    zip.closeEntry();
                }
            }
            if (manifest == null) throw new IOException("备份缺少目录数据");
            JSONObject data = new JSONObject(new String(manifest, StandardCharsets.UTF_8));
            if (data.getInt("format") != 1) throw new IOException("不支持此备份版本");
            JSONArray categories = data.getJSONArray("categories");
            JSONArray notebooks = data.getJSONArray("notebooks");
            JSONArray entries = data.getJSONArray("entries");
            JSONArray photos = data.getJSONArray("photos");
            if (categories.length() + notebooks.length() + entries.length() + photos.length() > MAX_FILES) throw new IOException("记录数量超出限制");
            File images = imageDir(context);
            for (int i = 0; i < photos.length(); i++) {
                JSONObject photo = photos.getJSONObject(i);
                String oldName = photo.getString("file_name");
                if (!safeName(oldName)) throw new IOException("图片名称不合法");
                File staged = new File(stage, oldName);
                if (!staged.isFile()) throw new IOException("备份中缺少图片：" + oldName);
                String newName = UUID.randomUUID() + extension(oldName);
                File dest = new File(images, newName);
                try (InputStream in = new FileInputStream(staged); OutputStream out = new FileOutputStream(dest)) { copy(in, out, MAX_ITEM_BYTES); }
                installed.add(dest);
                photo.put("file_name", newName);
            }
            JSONObject old = store.snapshot();
            store.replace(data);
            JSONArray oldPhotos = old.getJSONArray("photos");
            for (int i = 0; i < oldPhotos.length(); i++) {
                String name = oldPhotos.getJSONObject(i).optString("file_name");
                if (safeName(name)) new File(images, name).delete();
            }
            installed.clear();
        } catch (Exception e) {
            for (File file : installed) file.delete();
            throw e;
        } finally { deleteTree(stage); }
    }

    static void autoBackup(Context context, DiaryStore store, Uri tree) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
        Uri doc = DocumentsContract.createDocument(resolver, parent, "application/zip", BACKUP_PREFIX + timestamp + ".zip");
        if (doc == null) throw new IOException("无法在所选目录建立备份");
        try {
            OutputStream stream = resolver.openOutputStream(doc, "w");
            if (stream == null) throw new IOException("无法写入备份");
            exportTo(context, store, stream);
        } catch (Exception e) {
            DocumentsContract.deleteDocument(resolver, doc);
            throw e;
        }
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        ArrayList<String> names = new ArrayList<>();
        ArrayList<Uri> uris = new ArrayList<>();
        try (Cursor c = resolver.query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (c != null) while (c.moveToNext()) {
                String name = c.getString(1);
                if (name != null && name.startsWith(BACKUP_PREFIX) && name.endsWith(".zip")) {
                    names.add(name); uris.add(DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)));
                }
            }
        }
        ArrayList<Integer> indices = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) indices.add(i);
        Collections.sort(indices, (a, b) -> names.get(b).compareTo(names.get(a)));
        for (int i = 3; i < indices.size(); i++) DocumentsContract.deleteDocument(resolver, uris.get(indices.get(i)));
    }

    private static boolean safeName(String name) { return name != null && name.matches("[A-Za-z0-9_-][A-Za-z0-9._-]{0,150}") && !name.contains(".."); }
    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? ".img" : name.substring(dot);
    }
    private static long copy(InputStream in, OutputStream out, long limit) throws IOException {
        byte[] buffer = new byte[64 * 1024]; long total = 0; int n;
        while ((n = in.read(buffer)) != -1) {
            total += n; if (total > limit) throw new IOException("单张图片或文件过大");
            out.write(buffer, 0, n);
        }
        return total;
    }
    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }
}
