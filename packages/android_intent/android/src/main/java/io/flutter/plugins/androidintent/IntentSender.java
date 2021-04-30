package io.flutter.plugins.androidintent;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** Forms and launches intents. */
public final class IntentSender {
  private static final String TAG = "IntentSender";

  @Nullable private Activity activity;
  @Nullable private Context applicationContext;

  /**
   * Caches the given {@code activity} and {@code applicationContext} to use for sending intents
   * later.
   *
   * <p>Either may be null initially, but at least {@code applicationContext} should be set before
   * calling {@link #send}.
   *
   * <p>See also {@link #setActivity}, {@link #setApplicationContext}, and {@link #send}.
   */
  public IntentSender(@Nullable Activity activity, @Nullable Context applicationContext) {
    this.activity = activity;
    this.applicationContext = applicationContext;
  }

  /**
   * Creates and launches an intent with the given params using the cached {@link Activity} and
   * {@link Context}.
   *
   * <p>This will fail to create and send the intent if {@code applicationContext} hasn't been set
   * at the time of calling.
   *
   * <p>This uses {@code activity} to start the intent whenever it's not null. Otherwise it falls
   * back to {@code applicationContext} and adds {@link Intent#FLAG_ACTIVITY_NEW_TASK} to the intent
   * before launching it.
   */
  void send(Intent intent) {
    if (applicationContext == null) {
      Log.wtf(TAG, "Trying to send an intent before the applicationContext was initialized.");
      return;
    }

    Log.v(TAG, "Sending intent " + intent);

    if (activity != null) {
      activity.startActivity(intent);
    } else {
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      applicationContext.startActivity(intent);
    }
  }

  void shareFiles(Intent intent, List<String> paths, List<String> mimeTypes)
          throws IOException {
    if (paths == null || paths.isEmpty()) {
      throw new IllegalArgumentException("Non-empty path expected");
    }

    clearExternalShareFolder();
    ArrayList<Uri> fileUris = getUrisForPaths(paths);

    if (fileUris.isEmpty()) {
      send(intent);
      return;
    } else if (fileUris.size() == 1) {
      intent.setAction(Intent.ACTION_SEND);
      intent.putExtra(Intent.EXTRA_STREAM, fileUris.get(0));
      intent.setType(
              !mimeTypes.isEmpty() && mimeTypes.get(0) != null ? mimeTypes.get(0) : "*/*");
    } else {
      intent.setAction(Intent.ACTION_SEND_MULTIPLE);
      intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, fileUris);
      intent.setType(reduceMimeTypes(mimeTypes));
    }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    Intent chooserIntent = Intent.createChooser(intent, null /* dialog title optional */);

    List<ResolveInfo> resInfoList =
            applicationContext
                    .getPackageManager()
                    .queryIntentActivities(chooserIntent, PackageManager.MATCH_DEFAULT_ONLY);
    for (ResolveInfo resolveInfo : resInfoList) {
      String packageName = resolveInfo.activityInfo.packageName;
      for (Uri fileUri : fileUris) {
        applicationContext
                .grantUriPermission(
                        packageName,
                        fileUri,
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
      }
    }

    applicationContext.startActivity(chooserIntent);
  }

  @SuppressWarnings("ResultOfMethodCallIgnored")
  private void clearExternalShareFolder() {
    File folder = getExternalShareFolder();
    if (folder.exists()) {
      for (File file : folder.listFiles()) {
        file.delete();
      }
      folder.delete();
    }
  }

  private String reduceMimeTypes(List<String> mimeTypes) {
    if (mimeTypes.size() > 1) {
      String reducedMimeType = mimeTypes.get(0);
      for (int i = 1; i < mimeTypes.size(); i++) {
        String mimeType = mimeTypes.get(i);
        if (!reducedMimeType.equals(mimeType)) {
          if (getMimeTypeBase(mimeType).equals(getMimeTypeBase(reducedMimeType))) {
            reducedMimeType = getMimeTypeBase(mimeType) + "/*";
          } else {
            reducedMimeType = "*/*";
            break;
          }
        }
      }
      return reducedMimeType;
    } else if (mimeTypes.size() == 1) {
      return mimeTypes.get(0);
    } else {
      return "*/*";
    }
  }

  @NonNull
  private String getMimeTypeBase(String mimeType) {
    if (mimeType == null || !mimeType.contains("/")) {
      return "*";
    }

    return mimeType.substring(0, mimeType.indexOf("/"));
  }

  private ArrayList<Uri> getUrisForPaths(List<String> paths) throws IOException {
    ArrayList<Uri> uris = new ArrayList<>(paths.size());
    for (String path : paths) {
      File file = new File(path);
      if (!fileIsOnExternal(file)) {
        file = copyToExternalShareFolder(file);
      }
      uris.add(
              FileProvider.getUriForFile(
                      applicationContext, applicationContext.getPackageName() + ".flutter.share_provider", file));
    }
    return uris;
  }

  @SuppressWarnings("ResultOfMethodCallIgnored")
  private File copyToExternalShareFolder(File file) throws IOException {
    File folder = getExternalShareFolder();
    if (!folder.exists()) {
      folder.mkdirs();
    }

    File newFile = new File(folder, file.getName());
    copy(file, newFile);
    return newFile;
  }

  @NonNull
  private File getExternalShareFolder() {
    return new File(applicationContext.getExternalCacheDir(), "share");
  }

  private boolean fileIsOnExternal(File file) {
    try {
      String filePath = file.getCanonicalPath();
      File externalDir = applicationContext.getExternalFilesDir(null);
      return externalDir != null && filePath.startsWith(externalDir.getCanonicalPath());
    } catch (IOException e) {
      return false;
    }
  }

  private static void copy(File src, File dst) throws IOException {
    InputStream in = new FileInputStream(src);
    try {
      OutputStream out = new FileOutputStream(dst);
      try {
        // Transfer bytes from in to out
        byte[] buf = new byte[1024];
        int len;
        while ((len = in.read(buf)) > 0) {
          out.write(buf, 0, len);
        }
      } finally {
        out.close();
      }
    } finally {
      in.close();
    }
  }
  /**
   * Verifies the given intent and returns whether the application context class can resolve it.
   *
   * <p>This will fail to create and send the intent if {@code applicationContext} hasn't been set *
   * at the time of calling.
   *
   * <p>This currently only supports resolving activities.
   *
   * @param intent Fully built intent.
   * @see #buildIntent(String, Integer, String, Uri, Bundle, String, ComponentName, String)
   * @return Whether the package manager found {@link android.content.pm.ResolveInfo} using its
   *     {@link PackageManager#resolveActivity(Intent, int)} method.
   */
  boolean canResolveActivity(Intent intent) {
    if (applicationContext == null) {
      Log.wtf(TAG, "Trying to resolve an activity before the applicationContext was initialized.");
      return false;
    }

    final PackageManager packageManager = applicationContext.getPackageManager();

    return packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null;
  }

  /** Caches the given {@code activity} to use for {@link #send}. */
  void setActivity(@Nullable Activity activity) {
    this.activity = activity;
  }

  /** Caches the given {@code applicationContext} to use for {@link #send}. */
  void setApplicationContext(@Nullable Context applicationContext) {
    this.applicationContext = applicationContext;
  }

  /**
   * Constructs a new intent with the data specified.
   *
   * @param action the Intent action, such as {@code ACTION_VIEW}.
   * @param flags forwarded to {@link Intent#addFlags(int)} if non-null.
   * @param category forwarded to {@link Intent#addCategory(String)} if non-null.
   * @param data forwarded to {@link Intent#setData(Uri)} if non-null and 'type' parameter is null.
   *     If both 'data' and 'type' is non-null they're forwarded to {@link
   *     Intent#setDataAndType(Uri, String)}
   * @param arguments forwarded to {@link Intent#putExtras(Bundle)} if non-null.
   * @param packageName forwarded to {@link Intent#setPackage(String)} if non-null. This is forced
   *     to null if it can't be resolved.
   * @param componentName forwarded to {@link Intent#setComponent(ComponentName)} if non-null.
   * @param type forwarded to {@link Intent#setType(String)} if non-null and 'data' parameter is
   *     null. If both 'data' and 'type' is non-null they're forwarded to {@link
   *     Intent#setDataAndType(Uri, String)}
   * @return Fully built intent.
   */
  Intent buildIntent(
      @Nullable String action,
      @Nullable Integer flags,
      @Nullable String category,
      @Nullable Uri data,
      @Nullable Bundle arguments,
      @Nullable String packageName,
      @Nullable ComponentName componentName,
      @Nullable String type) {
    if (applicationContext == null) {
      Log.wtf(TAG, "Trying to build an intent before the applicationContext was initialized.");
      return null;
    }

    Intent intent = new Intent();

    if (action != null) {
      intent.setAction(action);
    }
    if (flags != null) {
      intent.addFlags(flags);
    }
    if (!TextUtils.isEmpty(category)) {
      intent.addCategory(category);
    }
    if (data != null && type == null) {
      intent.setData(data);
    }
    if (type != null && data == null) {
      intent.setType(type);
    }
    if (type != null && data != null) {
      intent.setDataAndType(data, type);
    }
    if (arguments != null) {
      intent.putExtras(arguments);
    }
    if (!TextUtils.isEmpty(packageName)) {
      intent.setPackage(packageName);
      if (componentName != null) {
        intent.setComponent(componentName);
      }
      if (intent.resolveActivity(applicationContext.getPackageManager()) == null) {
        Log.i(TAG, "Cannot resolve explicit intent - ignoring package");
        intent.setPackage(null);
      }
    }

    return intent;
  }
}
