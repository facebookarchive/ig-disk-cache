/*
 * Copyright (c) 2016-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.instagram.igdiskcache;

public class OptionalStream<T> {
  private T mFileStream = null;

  private OptionalStream() {
  }

  private OptionalStream(T fileStream) {
    mFileStream = fileStream;
  }

  /**
   * Check if the {@link T} object is available inside the OptionalStream<T> wrapper.
   */
  public boolean isPresent() {
    return mFileStream != null;
  }

  /**
   * Get the {@link T} object from the OptionalStream<T> wrapper.
   * Should call {@link OptionalStream#isPresent()} first to make sure the {@link T} object is
   * available.
   */
  public T get() {
    if (isPresent()) {
      return mFileStream;
    } else {
      throw new IllegalStateException("OptionalStream.get() cannot be called on an absent value");
    }
  }

  /**
   * The stub {@link OptionalStream<T>} object.
   */
  public static <T> OptionalStream<T> absent() {
    return new OptionalStream<>();
  }

  /**
   * Create a {@link OptionalStream<T>} wrapper using the {@link T} object.
   */
  public static <T> OptionalStream<T> of(T fileStream) {
    return new OptionalStream<>(fileStream);
  }
}
