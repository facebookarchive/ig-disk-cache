/*
 * Copyright (c) 2016-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.instagram.igdiskcache;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * OutputStream used for writing data into the disk cache Entry. If you need to use
 * BufferedOutputStream, you might want to wrap the EditorOutputStream up with the
 * BufferedOutputStream yourself.
 * <p> After edit, instead of {@link #close()} the EditorOutputStream, the OutputStream need to
 * {@link #commit()} to write the change to cache, or {@link #abort()} to discard the change.
 * <p> All EditorOutputStream should be committed or aborted after use to prevent resource leak.
 */
public final class EditorOutputStream extends FileOutputStream {
  private IgDiskCache mCache;
  private Entry mEntry;
  private boolean mHasErrors;
  private boolean mIsClosed;

  /* package */ EditorOutputStream(Entry entry, IgDiskCache cache) throws FileNotFoundException {
    super(entry.getDirtyFile());
    mCache = cache;
    mEntry = entry;
    mHasErrors = false;
  }

  /**
   * Commit change to disk cache.
   * @return true if the change is successfully committed to disk cache. In case of IOExceptions,
   * the method will return false instead of throwing out the IOExceptions.
   */
  public synchronized boolean commit() {
    checkNotClosedOrEditingConcurrently();
    close();
    mIsClosed = true;
    if (mHasErrors) {
      mCache.abortEdit(mEntry);
      mCache.remove(mEntry.getKey()); // Previous entry is stale.
      return false;
    } else {
      mCache.commitEdit(mEntry);
      return true;
    }
  }

  /**
   * Abort the change made to the EditorOutputStream.
   */
  public synchronized void abort() {
    checkNotClosedOrEditingConcurrently();
    close();
    mIsClosed = true;
    mCache.abortEdit(mEntry);
  }

  /**
   * Abort the change if it is not already committed. This is commonly used in the {@code finally}
   * block of error try-cache to make sure the EditorOutputStream is properly closed.
   */
  public synchronized void abortUnlessCommitted() {
    if (!mIsClosed) {
      abort();
    }
  }

  @Override
  public void write(byte[] buffer) {
    try {
      super.write(buffer);
    } catch (IOException e) {
      mHasErrors = true;
    }
  }

  @Override
  public void write(byte[] buffer, int byteOffset, int byteCount) {
    try {
      super.write(buffer, byteOffset, byteCount);
    } catch (IOException e) {
      mHasErrors = true;
    }
  }

  /**
   * Deprecated, should use {@link #commit()} or {@link #abort()} instead.
   */
  @Deprecated
  @Override
  public void close() {
    try {
      super.close();
    } catch (IOException e) {
      mHasErrors = true;
    }
  }

  @Override
  public void flush() {
    try {
      super.flush();
    } catch (IOException e) {
      mHasErrors = true;
    }
  }

  private void checkNotClosedOrEditingConcurrently() {
    if (mIsClosed) {
      throw new IllegalStateException(
              "Try to operate on an EditorOutputStream that is already closed");
    }
    if (mEntry.getCurrentEditorStream() != this) {
      throw new IllegalStateException(
              "Two editors trying to write to the same cached file");
    }
  }
}
