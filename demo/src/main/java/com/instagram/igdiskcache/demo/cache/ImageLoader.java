/*
 * Copyright (c) 2016-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE-examples file in the root directory of this source tree.
 */

package com.instagram.igdiskcache.demo.cache;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.util.Log;
import android.widget.ImageView;

import com.instagram.igdiskcache.EditorOutputStream;
import com.instagram.igdiskcache.IgDiskCache;
import com.instagram.igdiskcache.OptionalStream;
import com.instagram.igdiskcache.SnapshotInputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * A simple asynchronous image loader class
 * It caches the raw file downloaded from the network using {@link IgDiskCache}, and also provide
 * an option to use a {@link BitmapCache} for resized bitmaps.
 * Note: To make the code simple and readable, the loader class doesn't handle loading priorities or
 * task canceling.
 */

public class ImageLoader {
  private static final String TAG = ImageLoader.class.getSimpleName();
  private static final String DOWNLOAD_CACHE_DIR = "http";
  private static final int IO_BUFFER_SIZE = 8 * 1024;
  private static final int MAX_THREAD_POOL_SIZE = 2;
  private static final Object DECODE_LOCK = new Object();

  private BitmapCache mBitmapCache;
  private Context mContext;
  private IgDiskCache mDownloadCache;
  private Resources mResources;
  private ThreadPoolExecutor mThreadPoolExecutor;

  private static ImageLoader sInstance;

  public static ImageLoader getInstance() {
    return sInstance;
  }

  public static void init(Context context, boolean enableBitmapCache) {
    sInstance = new ImageLoader(context, enableBitmapCache);
  }

  abstract class Callback {
    protected WeakReference<ImageView> mImageView;

    public Callback(ImageView imageView) {
      mImageView = new WeakReference<>(imageView);
    }

    abstract void onSuccess(Bitmap bitmap);

    abstract void onFail();
  }

  private ImageLoader(final Context context, boolean enableBitmapCache) {
    mContext = context;
    mResources = context.getResources();
    mThreadPoolExecutor = new ThreadPoolExecutor(
        0,
        MAX_THREAD_POOL_SIZE,
        60L,
        TimeUnit.SECONDS,
        new LinkedBlockingQueue<Runnable>());
    if (enableBitmapCache) {
      mBitmapCache = new BitmapCache(context);
    }
  }

  /**
   * Load an image asynchronously into a ImageView. The raw image file and the decoded Bitmap can be
   * cached locally in memory and on disk.
   * @param url image URL
   * @param target target ImageView the image will load to
   * @param height image height
   * @param width image width
   * @param loadingResId resource id of the loading indicator image
   */
  public void loadImage(
      String url,
      ImageView target,
      int height,
      int width,
      int loadingResId) {
    Bitmap value = null;
    if (mBitmapCache != null) {
      value = mBitmapCache.getBitmapFromMemCache(getKeyForBitmapCache(url, height, width));
    }
    if (value != null) {
      target.setImageBitmap(value);
    } else {
      target.setImageDrawable(mResources.getDrawable(loadingResId));
      final BitmapWorkerTask task = new BitmapWorkerTask(
          url,
          height,
          width,
          new Callback(target) {
            @Override
            public void onSuccess(Bitmap bitmap) {
              if (bitmap != null && mImageView.get() != null) {
                mImageView.get().setImageBitmap(bitmap);
              }
            }

            @Override
            public void onFail() {
              Log.e(TAG, "fail to load image.");
            }
          });
      task.executeOnExecutor(mThreadPoolExecutor);
    }
  }

  private class BitmapWorkerTask extends AsyncTask<Void, Void, Bitmap> {
    private String mUrl;
    private int mHeight, mWidth;
    private Callback mCallback;

    public BitmapWorkerTask(String url, int height, int width, Callback callback) {
      mUrl = url;
      mHeight = height;
      mWidth = width;
      mCallback = callback;
    }

    @Override
    protected Bitmap doInBackground(Void... params) {
      Bitmap bitmap = null;
      if (mBitmapCache != null) {
        bitmap = mBitmapCache.getBitmapFromDiskCache(getKeyForBitmapCache(mUrl, mHeight, mWidth));
      }
      if (bitmap == null) {
        bitmap = loadBitmap(mUrl, mHeight, mWidth);
      }
      return bitmap;
    }

    @Override
    protected void onPostExecute(Bitmap bitmap) {
      if (bitmap != null) {
        mCallback.onSuccess(bitmap);
        if (mBitmapCache != null) {
          mBitmapCache.addBitmapToCache(getKeyForBitmapCache(mUrl, mHeight, mWidth), bitmap);
        }
      } else {
        mCallback.onFail();
      }
    }
  }

  /**
   * Close the caches after use.
   */
  public void close() {
    mThreadPoolExecutor.execute(new Runnable() {
      @Override
      public void run() {
        if (mBitmapCache != null) {
          mBitmapCache.close();
        }
        if (mDownloadCache != null) {
          mDownloadCache.close();
        }
      }
    });
  }

  private Bitmap loadBitmap(String url, int height, int width) {
    String key = Integer.toHexString(url.hashCode());
    OptionalStream<SnapshotInputStream> input = getDownloadCache().get(key);
    if (!input.isPresent()) {
      OptionalStream<EditorOutputStream> output = getDownloadCache().edit(key);
      if (output.isPresent()) {
        if (downloadUrlToStream(url, output.get())) {
          output.get().commit();
        } else {
          output.get().abort();
        }
      }
      input = getDownloadCache().get(key);
    }

    Bitmap bitmap = null;
    if (input.isPresent()) {
      try {
        FileDescriptor fileDescriptor = input.get().getFD();
        if (fileDescriptor != null) {
          synchronized (DECODE_LOCK) {
            Bitmap tmp = BitmapFactory.decodeFileDescriptor(fileDescriptor);
            bitmap = Bitmap.createScaledBitmap(tmp, width, height, false);
            tmp.recycle();
          }
        }
      } catch (IOException e) {
        Log.e(TAG, "loadBitmap - " + e);
      } finally {
        Utils.closeQuietly(input.get());
      }
    }
    return bitmap;
  }

  private IgDiskCache getDownloadCache() {
    // lazy initialization of IgDiskCache to avoid calling it from the main UI thread.
    if (mDownloadCache == null) {
      mDownloadCache = new IgDiskCache(Utils.getCacheDirectory(mContext, DOWNLOAD_CACHE_DIR));
    }
    return mDownloadCache;
  }

  private static boolean downloadUrlToStream(String urlString, OutputStream outputStream) {
    HttpURLConnection urlConnection = null;
    BufferedOutputStream out = null;
    BufferedInputStream in = null;
    try {
      final URL url = new URL(urlString);
      urlConnection = (HttpURLConnection) url.openConnection();
      in = new BufferedInputStream(urlConnection.getInputStream(), IO_BUFFER_SIZE);
      out = new BufferedOutputStream(outputStream, IO_BUFFER_SIZE);

      int b;
      while ((b = in.read()) != -1) {
        out.write(b);
      }
      return true;
    } catch (final IOException e) {
      Log.e(TAG, "Error in downloadBitmap - " + e);
    } finally {
      if (urlConnection != null) {
        urlConnection.disconnect();
      }
      Utils.closeQuietly(out);
      Utils.closeQuietly(in);
    }
    return false;
  }

  private static String getKeyForBitmapCache(String url, int height, int width) {
    String str = url + "-" + Integer.toString(height) + "-" + Integer.toString(width);
    return Integer.toHexString(str.hashCode());
  }
}
