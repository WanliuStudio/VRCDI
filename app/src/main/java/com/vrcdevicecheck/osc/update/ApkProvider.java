package com.vrcdevicecheck.osc.update;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 极简 ContentProvider，只用来把下载好的 APK 以 content:// 形式交给系统安装器。
 *
 * 为什么需要它：Android 7.0 起禁止用 file:// 分享文件（FileUriExposedException），
 * 而 AndroidX 的 FileProvider 本工程没有依赖，所以自己写一个最小实现：
 * 只读、只暴露 cache/update 目录下的文件、不对第三方导出。
 */
public class ApkProvider extends ContentProvider {

    public static final String SUFFIX = ".apk";

    public static Uri uriFor(Context context, File file) {
        return new Uri.Builder()
                .scheme("content")
                .authority(context.getPackageName() + SUFFIX)
                .appendPath(file.getName())
                .build();
    }

    public static File updateDir(Context context) {
        File dir = new File(context.getCacheDir(), "update");
        if (!dir.isDirectory()) dir.mkdirs();
        return dir;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        Context context = getContext();
        if (context == null) throw new FileNotFoundException("no context");
        String name = uri.getLastPathSegment();
        if (name == null || name.contains("/") || name.contains("..")) {
            throw new FileNotFoundException("bad name");
        }
        File file = new File(updateDir(context), name);
        try {
            String base = updateDir(context).getCanonicalPath();
            if (!file.getCanonicalPath().startsWith(base + File.separator)) {
                throw new FileNotFoundException("outside update dir");
            }
        } catch (FileNotFoundException e) {
            throw e;
        } catch (Throwable t) {
            throw new FileNotFoundException("bad path");
        }
        if (!file.isFile()) throw new FileNotFoundException("apk not found");
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }
}
