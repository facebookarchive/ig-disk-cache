/*
 * Copyright (c) 2016-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.instagram.igdiskcache;

import android.os.AsyncTask;
import android.os.Looper;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Disk cache that uses a bounded amount of space and with a maximum number of entries on the
 * filesystem. Each disk cache entry is identified with a string key (each key must match the
 * regex <strong>[a-z0-9_-]{1,120}</strong>.)
 *
 * <p> The cache stores its data in a directory on the filesystem. This directory must be exclusive
 * to the cache; the cache may delete or overwrite files from its directory. It is an error for
 * multiple processes to use the same cache directory at the same time.
 *
 * <p> This cache limits the number of bytes, and the number of entries that it will store on
 * the filesystem. When the number of entries or stored bytes exceeds the limit, the cache will
 * remove entries in the background until the limits are satisfied. The limits are not strict:
 * the cache may temporarily exceed the limits while waiting for files to be deleted.
 *
 * <p> Clients call {@link #get} to read a snapshot of an entry. The read will observe the value
 * at the time that {@link #get} was called. Updates and removals after the call do not impact
 * ongoing read. Reading from a disk cache entry:
 * <pre>
 *   {@code
 *      OptionalStream<SnapshotInputStream> inputStream = getDiskLruCache().get(key);
 *      if (inputStream.isPresent()) {
 *        try {
 *          readFromInputStream(inputStream.get());
 *        } finally {
 *          inputStream.close();
 *        }
 *      }
 *   }
 * </pre>
 *
 * <p> Clients call {@link #edit} to create or update the values of an entry. An entry may have
 * only one editor at one time; if a value is not available to be edited then {@link #edit} will
 * return OptionalStream.absent().If an error occurs while writing a cache value, the edit will fail
 * silently without throwing IOExceptions.
 * The {@link EditorOutputStream} need to be either commit() or abort() after editing.
 * Writing to a disk cache entry:
 * <pre>
 *   {@code
 *      OptionalStream<EditorOutputStream> output = getStorage().edit(key);
 *      if (output.isPresent()) {
 *        output.get().write(data);
 *        output.get().commit();
 *      }
 *   }
 * </pre>
 *
 * <p> This class will silently handle most of the IOExceptions. If files are missing from the
 * filesystem, the corresponding entries will be dropped from the cache.
 *
 * <p> Note: IgDiskCache should never be initialized or closed from the UI Thread.
 */
public final class IgDiskCache {
  private static final String STRING_KEY_PATTERN = "[a-z0-9_-]{1,120}";
  private static final Pattern LEGAL_KEY_PATTERN = Pattern.compile(STRING_KEY_PATTERN);
  private static final long DEFAULT_MAX_SIZE = 1024 * 1024 * 30; // maximum 30 megs in size
  private static final int DEFAULT_MAX_COUNT = 1000; // maximum 1000 files
  private static final ThreadPoolExecutor DISK_CACHE_EXECUTOR =
          new ThreadPoolExecutor(0, 1, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());
  static final File FAKE_CACHE_DIRECTORY = new File("/dev/null");

  private final File mDirectory;
  private final Object mDiskCacheLock = new Object();
  private final Object mRemoveRetryLock = new Object();
  // Guarded by mDiskCacheLock
  private final LinkedHashMap<String, Entry> mLruEntries;
  // Guarded by mRemoveRetryLock
  private final List<Entry> mRemoveRetryList;
  private final AtomicLong mSizeInBytes = new AtomicLong();
  private final Journal mJournal;
  private int mMaxCount;
  private long mMaxSizeInBytes;
  private int mMissCount;
  private int mHitCount;

  private final Runnable mTrimRunnable = new Runnable() {
    @Override
    public void run() {
      if (mSizeInBytes.get() > mMaxSizeInBytes || count() > mMaxCount) {
        trimToSizeAndCount();
      }
    }
  };

  /**
   * Disk Cache initialization.
   * @param directory directory for disk cache.
   */
  public IgDiskCache(File directory) {
    this(directory, DEFAULT_MAX_SIZE, DEFAULT_MAX_COUNT, AsyncTask.SERIAL_EXECUTOR);
  }

  /**
   * Disk Cache initialization.
   * @param directory directory for disk cache.
   * @param serialExecutor Serial Executor for {@link Journal} logging.
   */
  public IgDiskCache(File directory, Executor serialExecutor) {
    this(directory, DEFAULT_MAX_SIZE, DEFAULT_MAX_COUNT, serialExecutor);
  }

  /**
   * Disk Cache initialization.
   * @param directory directory for disk cache.
   * @param maxSizeInBytes limit for the disk cache size (in bytes)
   */
  public IgDiskCache(File directory, long maxSizeInBytes) {
    this(directory, maxSizeInBytes, DEFAULT_MAX_COUNT, AsyncTask.SERIAL_EXECUTOR);
  }

  /**
   * Disk Cache initialization.
   * @param directory directory for disk cache.
   * @param maxSizeInBytes limit for the disk cache size (in bytes).
   * @param serialExecutor Serial Executor for {@link Journal} logging.
   */
  public IgDiskCache(File directory, long maxSizeInBytes, Executor serialExecutor) {
    this(directory, maxSizeInBytes, DEFAULT_MAX_COUNT, serialExecutor);
  }

  /**
   * Disk Cache initialization.
   * @param directory directory for disk cache.
   * @param maxSizeInBytes limit for the disk cache size (in bytes).
   * @param maxCount limit for the number of entries that can be stored in the cache.
   */
  public IgDiskCache(File directory, long maxSizeInBytes, int maxCount) {
    this(directory, maxSizeInBytes, maxCount, AsyncTask.SERIAL_EXECUTOR);
  }

  /**
   * Disk Cache initialization.
   * @param directory directory for disk cache.
   * @param maxSizeInBytes limit for the disk cache size (in bytes).
   * @param maxCount limit for the number of entries that can be stored in the cache.
   * @param serialExecutor Serial Executor for {@link Journal} logging.
   */
  public IgDiskCache(File directory, long maxSizeInBytes, int maxCount, Executor serialExecutor) {
    assertOnNonUIThread();
    mDirectory = (directory == null) ? FAKE_CACHE_DIRECTORY : directory;
    mMaxCount = maxCount;
    mMaxSizeInBytes = maxSizeInBytes;
    mRemoveRetryList = new LinkedList<>();
    mSizeInBytes.set(0);
    mMissCount = 0;
    mHitCount = 0;
    mJournal = new Journal(mDirectory, this, serialExecutor);
    mLruEntries = new LinkedHashMap<>(0, 0.75f, true);
    LinkedHashMap<String, Entry> cachedEntries = mJournal.retrieveEntriesFromJournal();
    if (cachedEntries == null) {
      mDirectory.mkdirs(); //will try to recreate the directory the next time we edit.
      mJournal.rebuild();
    } else {
      mLruEntries.putAll(cachedEntries);
      for (Entry entry : mLruEntries.values()) {
        mSizeInBytes.getAndAdd(entry.getLengthInBytes());
      }
    }
  }

  /**
   * Check if a Entry with the given key exists in the disk cache.
   * @throws IllegalArgumentException if key is not valid.
   */
  public boolean has(String key) {
    validateKey(key);
    Entry entry;
    synchronized (mDiskCacheLock) {
      entry = mLruEntries.get(key);
    }
    return entry != null && entry.isReadable() && entry.getCleanFile().exists();
  }

  /**
   * Get the {@link SnapshotInputStream} of the Entry with the given key. If the Entry doesn't
   * exists or the file system is not accessible, an OptionalStream.absent() will be returned.
   * @throws IllegalArgumentException if key is not valid.
   */
  public OptionalStream<SnapshotInputStream> get(String key) {
    validateKey(key);
    Entry entry;
    synchronized (mDiskCacheLock) {
      entry = mLruEntries.get(key);
    }
    if (entry == null || !entry.isReadable()) {
      mMissCount++;
      return OptionalStream.absent();
    } else {
      mHitCount++;
      try {
        return OptionalStream.of(new SnapshotInputStream(entry));
      } catch (IOException e) {
        return OptionalStream.absent();
      }
    }
  }

  /**
   * Get the {@link EditorOutputStream} of the Entry with the given key. If the Entry doesn't
   * exists or the file system is not accessible, an OptionalStream.absent() will be returned.
   * @throws IllegalArgumentException if key is not valid.
   * @throws IllegalStateException if require edit on an entry that is currently under edit.
   */
  public OptionalStream<EditorOutputStream> edit(String key) {
    validateKey(key);
    if (mMaxSizeInBytes == 0 || mMaxCount == 0 || FAKE_CACHE_DIRECTORY.equals(mDirectory)) {
      return OptionalStream.absent();
    } else {
      Entry entry;
      synchronized (mDiskCacheLock) {
        entry = mLruEntries.get(key);
      }
      if (entry == null) {
        entry = new Entry(mDirectory, key);
        synchronized (mDiskCacheLock) {
          mLruEntries.put(key, entry);
        }
      } else if (entry.getCurrentEditorStream() != null) {
        throw new IllegalStateException(
                "Trying to edit a disk cache entry while another edit is in progress.");
      }
      mJournal.logDirtyFileUpdate(key);
      return getOutputStream(entry);
    }
  }

  private synchronized OptionalStream<EditorOutputStream> getOutputStream(Entry entry) {
    if (entry.getCurrentEditorStream() != null) {
      throw new IllegalStateException(
              "Trying to edit a disk cache entry while another edit is in progress.");
    }
    EditorOutputStream outputStream;
    try {
      outputStream = new EditorOutputStream(entry, this);
    } catch (FileNotFoundException e) {
      // Attempt to recreate the cache directory, no need to handle the mkdirs return result.
      mDirectory.mkdirs();
      try {
        outputStream = new EditorOutputStream(entry, this);
      } catch (FileNotFoundException e2) {
        return OptionalStream.absent();
      }
    }
    entry.setCurrentEditorStream(outputStream);
    return OptionalStream.of(outputStream);
  }

  /**
   * Remove the Entry with the given key from cache.
   * If the Entry is still under edit, EditorOutputStream need to be committed/aborted before
   * removing.
   * @throws IllegalArgumentException if key is not valid.
   */
  public void remove(String key) throws IllegalStateException {
    validateKey(key);
    Entry entry;
    synchronized (mDiskCacheLock) {
      entry = mLruEntries.remove(key);
    }
    if (entry != null) {
      if (entry.getCurrentEditorStream() != null) {
        throw new IllegalStateException(
                "trying to remove a disk cache entry that is still under edit.");
      }
      File file = entry.getCleanFile();
      if (!file.exists() || file.delete()) {
        mSizeInBytes.getAndAdd(-entry.getLengthInBytes());
      } else {
        synchronized (mRemoveRetryLock) {
          mRemoveRetryList.add(entry);
        }
      }
    }
  }

  /**
   * Instantly trim the cache to size and count, and rebuild the cache journal if trim happens.
   */
  public void flush() {
    trimToSizeAndCount();
    mJournal.rebuildIfNeeded();
  }

  /**
   * Close IgDiskCache and make sure the journal is updated on close. This could only be called from
   * non-UI thread.
   */
  public void close() {
    assertOnNonUIThread();
    trimToSizeAndCount();
    mJournal.rebuild();
  }

  /**
   * Get the directory of the current IgDiskCache, return null if it's a stub cache instance.
   */
  public File getDirectory() {
    return FAKE_CACHE_DIRECTORY.equals(mDirectory) ? null : mDirectory;
  }

  /**
   * Get the limit for the disk cache size (in bytes).
   */
  public long getMaxSizeInBytes() {
    return mMaxSizeInBytes;
  }

  /**
   * Get the limit for the number of entries that can be stored in the cache.
   */
  public int getMaxCount() {
    return mMaxCount;
  }

  /**
   * Set the limit for the disk cache size (in bytes).
   */
  public void setMaxSizeInBytes(long maxSizeInBytes) {
    mMaxSizeInBytes = maxSizeInBytes;
    DISK_CACHE_EXECUTOR.execute(mTrimRunnable);
  }

  /**
   * Get disk cache's current size in bytes.
   */
  public long size() {
    return mSizeInBytes.get();
  }

  /**
   * Get disk cache's entry count.
   */
  public int count() {
    synchronized (mDiskCacheLock) {
      return mLruEntries.size();
    }
  }

  /**
   * Get the disk cache's hit rate in the from of a String:
   * <strong>IgDiskCache[mMaxSizeInBytes=...,hits=...,misses=...,hitRate=...%]</strong>
   */
  public final String getHitRateString() {
    int accesses = mHitCount + mMissCount;
    int hitPercent = accesses != 0 ? (100 * mHitCount / accesses) : 0;
    return String.format(
            Locale.US,
            "IgDiskCache[mMaxSizeInBytes=%d,hits=%d,misses=%d,hitRate=%d%%]",
            mMaxSizeInBytes,
            mHitCount,
            mMissCount,
            hitPercent);
  }

  private void removeFilesForRemoveRetryList() {
    synchronized (mRemoveRetryLock) {
      Iterator<Entry> iterator = mRemoveRetryList.listIterator();
      while (iterator.hasNext()) {
        Entry entry = iterator.next();
        if (entry != null) {
          File file = entry.getCleanFile();
          if (file.exists() && file.delete()) {
            mSizeInBytes.getAndAdd(-entry.getLengthInBytes());
            iterator.remove();
          }
        }
      }
    }
  }

  private void trimToSizeAndCount() {
    removeFilesForRemoveRetryList();
    synchronized (mDiskCacheLock) {
      while (mSizeInBytes.get() > mMaxSizeInBytes || mLruEntries.size() > mMaxCount) {
        try {
          Map.Entry<String, Entry> toEvict = mLruEntries.entrySet().iterator().next();
          remove(toEvict.getKey());
        } catch (IllegalStateException | NoSuchElementException ignored) {
          // If the Entry is still under edit or the set is empty,
          // keep the Entry without throwing out any Exceptions.
        }
      }
    }
  }

  private static void validateKey(String key) {
    Matcher matcher = LEGAL_KEY_PATTERN.matcher(key);
    if (!matcher.matches()) {
      throw new IllegalArgumentException(
              "keys must match regex " + STRING_KEY_PATTERN + ": \"" + key + "\"");
    }
  }

  /* package */ void commitEdit(Entry entry) {
    File dirty = entry.getDirtyFile();
    if (!dirty.exists()) {
      entry.setCurrentEditorStream(null);
      updateEntry(entry);
    } else {
      File clean = entry.getCleanFile();
      if (dirty.renameTo(clean)) {
        long oldLength = entry.getLengthInBytes();
        long newLength = clean.length();
        entry.markAsPublished(newLength);
        mSizeInBytes.getAndAdd(newLength - oldLength);
        updateEntry(entry);
      } else {
        abortEdit(entry);
        remove(entry.getKey());
      }
    }
  }

  /* package */ void abortEdit(Entry entry) {
    File dirty = entry.getDirtyFile();
    if (dirty.exists()) {
      dirty.delete(); // No need to handle the fail case. Ignore the return.
    }
    entry.setCurrentEditorStream(null);
    updateEntry(entry);
  }

  private void updateEntry(Entry entry) {
    if (entry.isReadable()) {
      mJournal.logCleanFileUpdate(entry.getKey(), entry.getLengthInBytes());
    } else {
      synchronized (mDiskCacheLock) {
        mLruEntries.remove(entry.getKey());
      }
    }
    if (mSizeInBytes.get() > mMaxSizeInBytes || count() > mMaxCount) {
      DISK_CACHE_EXECUTOR.execute(mTrimRunnable);
    }
  }

  /* package */ ArrayList<Entry> getEntryCollection() {
    synchronized (mDiskCacheLock) {
      return new ArrayList<>(mLruEntries.values());
    }
  }

  private static void assertOnNonUIThread() throws IllegalStateException {
    if (Looper.getMainLooper().getThread() == Thread.currentThread()) {
      throw new IllegalStateException("This operation can't be run on UI thread.");
    }
  }
}

