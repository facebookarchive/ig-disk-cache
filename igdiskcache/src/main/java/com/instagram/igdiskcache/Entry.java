/*
 * Copyright (c) 2016-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.instagram.igdiskcache;

import java.io.File;

/**
 * Disk cache Entry.
 */
/* package */ final class Entry {
  /* package */ static final String CLEAN_FILE_EXTENSION = ".clean";
  /* package */ static final String DIRTY_FILE_EXTENSION = ".tmp";
  private final File mDirectory;
  private final String mKey;
  private long mLengthInBytes;
  private boolean mIsReadable;
  private EditorOutputStream mCurrentEditorStream;

  /* package */ Entry(File directory, String key) {
    mDirectory = directory;
    mKey = key;
    mLengthInBytes = 0;
    mIsReadable = false;
  }

  /* package */ File getCleanFile() {
    return new File(mDirectory, mKey + CLEAN_FILE_EXTENSION);
  }

  /* package */ File getDirtyFile() {
    return new File(mDirectory, mKey + DIRTY_FILE_EXTENSION);
  }

  /* package */ synchronized long getLengthInBytes() {
      return mLengthInBytes;
  }

  /* package */ synchronized boolean isReadable() {
      return mIsReadable;
  }

  /* package */ synchronized EditorOutputStream getCurrentEditorStream() {
      return mCurrentEditorStream;
  }

  /* package */ synchronized void setCurrentEditorStream(EditorOutputStream currentEditorStream) {
      mCurrentEditorStream = currentEditorStream;
  }

  /* package */ String getKey() {
    return mKey;
  }

  /* package */ synchronized void markAsPublished(long newLength) {
      mLengthInBytes = newLength;
      mCurrentEditorStream = null;
      mIsReadable = true;
  }
}
