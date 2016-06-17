/*
 * Copyright (c) 2016-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.instagram.igdiskcache;

import java.io.FileInputStream;
import java.io.FileNotFoundException;

/**
 * InputStream used for reading data out of the disk cache Entry.
 * All SnapshotInputStream need to {@link #close()} after use to prevent resource leak.
 */
public final class SnapshotInputStream extends FileInputStream {
  private final long mLengthInBytes;
  private final String mPath;

  /* package */ SnapshotInputStream(Entry entry)
          throws FileNotFoundException {
    super(entry.getCleanFile());
    mLengthInBytes = entry.getLengthInBytes();
    mPath = entry.getCleanFile().getAbsolutePath();
  }

  /**
   * Get the disk cache entry's length (in bytes).
   */
  public long getLengthInBytes() {
    return mLengthInBytes;
  }

  /**
   * Get file absolute path
   */
  public String getPath() {
    return mPath;
  }
}
