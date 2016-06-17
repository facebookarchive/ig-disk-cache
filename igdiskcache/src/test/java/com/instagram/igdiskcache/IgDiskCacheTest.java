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
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import android.annotation.SuppressLint;
import android.os.Looper;

import org.junit.Before;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.powermock.core.classloader.annotations.PrepareForTest;

import static com.instagram.igdiskcache.Journal.JOURNAL_FILE;
import static org.fest.assertions.api.Assertions.assertThat;
import static org.junit.Assert.fail;
import static org.powermock.api.mockito.PowerMockito.mock;
import static org.powermock.api.mockito.PowerMockito.spy;
import static org.powermock.api.mockito.PowerMockito.when;

@PrepareForTest({Looper.class})
public final class IgDiskCacheTest extends RobolectricBaseTest {
  private static final Charset US_ASCII = Charset.forName("US-ASCII");
  private File mCacheDir;
  private File mJournalFile;
  private IgDiskCache mCache;

  @Rule
  public TemporaryFolder tempDir = new TemporaryFolder();

  @Before
  public void setUp() throws Exception {
    mCacheDir = tempDir.newFolder("IgDiskCacheTest");
    mJournalFile = new File(mCacheDir, JOURNAL_FILE);
    for (File file : mCacheDir.listFiles()) {
      file.delete();
    }
    Looper looper = mock(Looper.class);
    when(looper.getThread()).thenReturn(mock(Thread.class));
    spy(Looper.class);
    when(Looper.getMainLooper()).thenReturn(looper);
    mCache = new IgDiskCache(mCacheDir, Integer.MAX_VALUE);
  }

  @SuppressLint("DeadVariable")
  @Test
  public void validateKey() throws Exception {
    String key = null;
    try {
      key = "has_space ";
      mCache.edit(key);
      fail("Exepcting an IllegalArgumentException as the key was invalid.");
    } catch (IllegalArgumentException iae) {
      assertThat(iae.getMessage()).isEqualTo(
          "keys must match regex [a-z0-9_-]{1,120}: \"" + key + "\"");
    }
    try {
      key = "has_CR\r";
      mCache.edit(key);
      fail("Exepcting an IllegalArgumentException as the key was invalid.");
    } catch (IllegalArgumentException iae) {
      assertThat(iae.getMessage()).isEqualTo(
          "keys must match regex [a-z0-9_-]{1,120}: \"" + key + "\"");
    }
    try {
      key = "has_LF\n";
      mCache.edit(key);
      fail("Exepcting an IllegalArgumentException as the key was invalid.");
    } catch (IllegalArgumentException iae) {
      assertThat(iae.getMessage()).isEqualTo(
          "keys must match regex [a-z0-9_-]{1,120}: \"" + key + "\"");
    }
    try {
      key = "has_invalid/";
      mCache.edit(key);
      fail("Exepcting an IllegalArgumentException as the key was invalid.");
    } catch (IllegalArgumentException iae) {
      assertThat(iae.getMessage()).isEqualTo(
          "keys must match regex [a-z0-9_-]{1,120}: \"" + key + "\"");
    }
    try {
      key = "has_invalid\u2603";
      mCache.edit(key);
      fail("Exepcting an IllegalArgumentException as the key was invalid.");
    } catch (IllegalArgumentException iae) {
      assertThat(iae.getMessage()).isEqualTo(
          "keys must match regex [a-z0-9_-]{1,120}: \"" + key + "\"");
    }
    try {
      key = "this_is_way_too_long_this_is_way_too_long_this_is_way_too_long_"
          + "this_is_way_too_long_this_is_way_too_long_this_is_way_too_long";
      mCache.edit(key);
      fail("Exepcting an IllegalArgumentException as the key was too long.");
    } catch (IllegalArgumentException iae) {
      assertThat(iae.getMessage()).isEqualTo(
          "keys must match regex [a-z0-9_-]{1,120}: \"" + key + "\"");
    }

    // Exactly 120.
    key = "0123456789012345678901234567890123456789012345678901234567890123456789"
        + "01234567890123456789012345678901234567890123456789";
    abortOptionalOutputStream(mCache.edit(key));
    // Contains all valid characters.
    key = "abcdefghijklmnopqrstuvwxyz_0123456789";
    abortOptionalOutputStream(mCache.edit(key));
    // Contains dash.
    key = "-20384573948576";
    abortOptionalOutputStream(mCache.edit(key));
  }

  private static void abortOptionalOutputStream(OptionalStream<EditorOutputStream> optional) {
    if (optional.isPresent()) {
      optional.get().abort();
    }
  }

  @Test
  public void writeAndReadEntry() throws Exception {
    set(mCache, "k1", "ABC");
    assertValue(mCache, "k1", "ABC");
  }

  @Test
  public void readAndWriteEntryAfterCacheReOpen() throws Exception {
    set(mCache, "k1", "A");
    IgDiskCache cache2 = new IgDiskCache(mCacheDir, Integer.MAX_VALUE);
    assertValue(cache2, "k1", "A");
  }

  @Test
  public void cannotOperateOnEditAfterCommit() throws Exception {
    OptionalStream<EditorOutputStream> out1 = mCache.edit("k1");
    assertThat(out1.isPresent());
    writeToOutputStream(out1.get(), "AB");
    out1.get().commit();
    try {
      writeToOutputStream(out1.get(), "CDE");
      out1.get().abort();
      fail();
    } catch (IllegalStateException e) {
      assertThat(
          e.getMessage().equals("Try to operate on an EditorOutputStream that is already closed"));
    }
    try {
      writeToOutputStream(out1.get(), "CDE");
      out1.get().commit();
      fail();
    } catch (IllegalStateException e) {
      assertThat(
          e.getMessage().equals("Try to operate on an EditorOutputStream that is already closed"));
    }
  }

  @Test
  public void cannotOperateOnEditAfterAbort() throws Exception {
    OptionalStream<EditorOutputStream> out1 = mCache.edit("k1");
    assertThat(out1.isPresent());
    writeToOutputStream(out1.get(), "AB");
    out1.get().abort();
    try {
      writeToOutputStream(out1.get(), "CDE");
      out1.get().abort();
      fail();
    } catch (IllegalStateException e) {
      assertThat(
          e.getMessage().equals("Try to operate on an EditorOutputStream that is already closed"));
    }
    try {
      writeToOutputStream(out1.get(), "CDE");
      out1.get().commit();
      fail();
    } catch (IllegalStateException e) {
      assertThat(
          e.getMessage().equals("Try to operate on an EditorOutputStream that is already closed"));
    }
  }

  @Test
  public void explicitRemoveAppliedToDiskImmediately() throws Exception {
    set(mCache, "k1", "ABC");
    File k1 = getCleanFile("k1");
    assertThat(readFile(k1)).isEqualTo("ABC");
    mCache.remove("k1");
    assertThat(k1.exists()).isFalse();
  }

  @Test
  public void readAndWriteOverlapsMaintainConsistency() throws Exception {
    set(mCache, "k1", "AAaa");

    OptionalStream<SnapshotInputStream> in1 = mCache.get("k1");
    assertThat(in1.isPresent());
    assertThat(in1.get().read()).isEqualTo('A');
    assertThat(in1.get().read()).isEqualTo('A');

    set(mCache, "k1", "CCcc");
    OptionalStream<SnapshotInputStream> in2 = mCache.get("k1");
    assertThat(in2.isPresent());
    assertThat(readFromInputStream(in2.get())).isEqualTo("CCcc");
    assertThat(in2.get().getLengthInBytes()).isEqualTo(4);
    in2.get().close();

    assertThat(in1.get().read()).isEqualTo('a');
    assertThat(in1.get().read()).isEqualTo('a');
    assertThat(in1.get().getLengthInBytes()).isEqualTo(4);
    in1.get().close();
  }

  @Test
  public void openWithDirtyKeyDeletesAllFilesForThatKey() throws Exception {
    File cleanFile = getCleanFile("k1");
    File dirtyFile = getDirtyFile("k1");
    writeFile(cleanFile, "A");
    writeFile(dirtyFile, "D");
    createJournal("CLEAN k1 1", "DIRTY k1");
    mCache = new IgDiskCache(mCacheDir, Integer.MAX_VALUE);

    assertThat(cleanFile.exists()).isFalse();
    assertThat(dirtyFile.exists()).isFalse();
    assertThat(mCache.get("k1").isPresent()).isFalse();
  }

  @Test
  public void openWithInvalidJournalLineClearsDirectory() throws Exception {
    generateSomeGarbageFiles();
    createJournal("CLEAN k1 1 1", "BOGUS");
    mCache = new IgDiskCache(mCacheDir, Integer.MAX_VALUE);
    assertGarbageFilesAllDeleted();
    assertThat(mCache.get("k1").isPresent()).isFalse();
  }

  @Test
  public void openWithInvalidFileSizeClearsDirectory() throws Exception {
    generateSomeGarbageFiles();
    createJournal("CLEAN k1 0000x001");
    mCache = new IgDiskCache(mCacheDir, Integer.MAX_VALUE);
    assertGarbageFilesAllDeleted();
    assertThat(mCache.get("k1").isPresent()).isFalse();
  }

  @Test
  public void openWithTooManyFileSizesClearsDirectory() throws Exception {
    generateSomeGarbageFiles();
    createJournal("CLEAN k1 1 1 1");
    mCache = new IgDiskCache(mCacheDir, Integer.MAX_VALUE);
    assertGarbageFilesAllDeleted();
    assertThat(mCache.get("k1").isPresent()).isFalse();
  }

  @SuppressLint("EmptyCatchBlock")
  @Test
  public void keyWithSpaceNotPermitted() throws Exception {
    try {
      mCache.edit("my key");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @SuppressLint("EmptyCatchBlock")
  @Test
  public void keyWithNewlineNotPermitted() throws Exception {
    try {
      mCache.edit("my\nkey");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @SuppressLint("EmptyCatchBlock")
  @Test
  public void keyWithCarriageReturnNotPermitted() throws Exception {
    try {
      mCache.edit("my\rkey");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @SuppressLint("EmptyCatchBlock")
  @Test
  public void nullKeyThrows() throws Exception {
    try {
      mCache.edit(null);
      fail();
    } catch (NullPointerException expected) {
    }
  }

  @Test
  public void growMaxSize() throws Exception {
    mCache = new IgDiskCache(mCacheDir, 7);
    set(mCache, "a", "aaa"); // size 3
    set(mCache, "b", "bbbb"); // size 4
    mCache.setMaxSizeInBytes(20);
    set(mCache, "c", "c"); // size 8
    assertThat(mCache.size()).isEqualTo(8);
  }

  @Test
  public void evictOnInsert() throws Exception {
    mCache = new IgDiskCache(mCacheDir, 7);

    set(mCache, "a", "aaa"); // size 3
    set(mCache, "b", "bbbb"); // size 4
    assertThat(mCache.size()).isEqualTo(7);

    // Cause the size to grow to 8 should evict 'A'.
    set(mCache, "c", "c");
    mCache.flush();
    assertThat(mCache.size()).isEqualTo(5);
    assertAbsent(mCache, "a");
    assertValue(mCache, "b", "bbbb");
    assertValue(mCache, "c", "c");

    // Causing the size to grow to 6 should evict nothing.
    set(mCache, "d", "d");
    mCache.flush();
    assertThat(mCache.size()).isEqualTo(6);
    assertAbsent(mCache, "a");
    assertValue(mCache, "b", "bbbb");
    assertValue(mCache, "c", "c");
    assertValue(mCache, "d", "d");

    // Causing the size to grow to 12 should evict 'B' and 'C'.
    set(mCache, "e", "eeeeee");
    mCache.flush();
    assertThat(mCache.size()).isEqualTo(7);
    assertAbsent(mCache, "a");
    assertAbsent(mCache, "b");
    assertAbsent(mCache, "c");
    assertValue(mCache, "d", "d");
    assertValue(mCache, "e", "eeeeee");
  }

  @Test
  public void evictOnUpdate() throws Exception {
    mCache = new IgDiskCache(mCacheDir, 7);

    set(mCache, "a", "aa"); // size 2
    set(mCache, "b", "bb"); // size 2
    set(mCache, "c", "cc"); // size 2
    assertThat(mCache.size()).isEqualTo(6);

    // Causing the size to grow to 8 should evict 'a'.
    set(mCache, "b", "bbbb");
    mCache.flush();
    assertThat(mCache.size()).isEqualTo(6);
    assertAbsent(mCache, "a");
    assertValue(mCache, "b", "bbbb");
    assertValue(mCache, "c", "cc");
  }

  @Test
  public void evictionHonorsLruFromCurrentSession() throws Exception {
    mCache = new IgDiskCache(mCacheDir, 5);
    set(mCache, "a", "a");
    set(mCache, "b", "b");
    set(mCache, "c", "c");
    set(mCache, "d", "d");
    set(mCache, "e", "e");
    mCache.get("b").get().close(); // 'B' is now least recently used.

    // Causing the size to grow to 6 should evict 'A'.
    set(mCache, "f", "f");
    // Causing the size to grow to 6 should evict 'C'.
    set(mCache, "g", "g");
    mCache.flush();
    assertThat(mCache.size()).isEqualTo(5);
    assertValue(mCache, "b", "b");
    assertValue(mCache, "d", "d");
    assertValue(mCache, "e", "e");
    assertValue(mCache, "f", "f");
    assertAbsent(mCache, "a");
    assertAbsent(mCache, "c");
  }

  @Test
  public void evictionHonorsLruFromPreviousSession() throws Exception {
    set(mCache, "a", "a");
    set(mCache, "b", "b");
    set(mCache, "c", "c");
    set(mCache, "d", "d");
    set(mCache, "e", "e");
    set(mCache, "f", "f");
    mCache.get("b").get().close(); // 'B' is now least recently used.
    assertThat(mCache.size()).isEqualTo(6);
    mCache.close();
    mCache = new IgDiskCache(mCacheDir, 5);
    set(mCache, "g", "g");
    set(mCache, "h", "h");
    mCache.flush();
    assertThat(mCache.size()).isEqualTo(5);
    assertAbsent(mCache, "a");
    assertValue(mCache, "b", "b");
    assertAbsent(mCache, "c");
    assertValue(mCache, "d", "d");
    assertValue(mCache, "e", "e");
    assertValue(mCache, "f", "f");
    assertValue(mCache, "g", "g");
  }

  @Test
  public void cacheSingleValueOfSizeGreaterThanMaxSize() throws Exception {
    mCache = new IgDiskCache(mCacheDir, 5);
    set(mCache, "a", "aaaaaa"); // size=6
    mCache.flush();
    assertAbsent(mCache, "a");
  }

  @Test
  public void removeAbsentElement() throws Exception {
    mCache.remove("a");
  }

  @Test
  public void openCreatesDirectoryIfNecessary() throws Exception {
    File dir = tempDir.newFolder("testOpenCreatesDirectoryIfNecessary");
    mCache = new IgDiskCache(dir, Integer.MAX_VALUE);
    set(mCache, "a", "a");
    assertThat(new File(dir, "a.clean").exists()).isTrue();
    assertThat(new File(dir, "journal").exists()).isTrue();
  }

  @Test
  public void fileDeletedExternally() throws Exception {
    set(mCache, "a", "a");
    getCleanFile("a").delete();
    assertThat(mCache.get("a").isPresent()).isFalse();
  }

  @SuppressLint("DeadVariable")
  @Test
  public void editSameVersion() throws Exception {
    set(mCache, "a", "a");
    SnapshotInputStream snapshot = mCache.get("a").get();
    EditorOutputStream editor = mCache.edit("a").get();
    writeToOutputStream(editor, "a2");
    editor.commit();
    assertValue(mCache, "a", "a2");
  }

  @Test
  public void editSinceEvicted() throws Exception {
    mCache = new IgDiskCache(mCacheDir, 7);
    set(mCache, "a", "aaa"); // size 3
    set(mCache, "b", "bbb"); // size 6
    set(mCache, "c", "ccc"); // size 9; will evict 'a'
    mCache.flush();
    assertThat(mCache.get("a").isPresent()).isFalse();
  }


  @Test
  public void editSinceEvictedAndRecreated() throws Exception {
    mCache = new IgDiskCache(mCacheDir, 7);
    set(mCache, "a", "aaa"); // size 3
    set(mCache, "b", "bbb"); // size 6
    set(mCache, "c", "ccc"); // size 9; will evict 'a'
    set(mCache, "a", "aaaa"); // size 10; will evict 'b'
    mCache.flush();
    assertThat(mCache.get("a").isPresent());
    assertThat(mCache.get("b").isPresent()).isFalse();
  }

  @Test
  public void aggressiveClearingHandlesWrite() throws Exception {
    deletePathRecursively(mCacheDir);
    set(mCache, "a", "a");
    assertValue(mCache, "a", "a");
  }

  @Test
  public void aggressiveClearingHandlesEdit() throws Exception {
    set(mCache, "a", "a");
    EditorOutputStream a = mCache.edit("a").get();
    deletePathRecursively(mCacheDir);
    writeToOutputStream(a, "a2");
    a.commit();
  }

  @Test
  public void removeHandlesMissingFile() throws Exception {
    set(mCache, "a", "a");
    getCleanFile("a").delete();
    mCache.remove("a");
  }

  @Test
  public void aggressiveClearingHandlesPartialEdit() throws Exception {
    set(mCache, "a", "a");
    set(mCache, "b", "b");
    EditorOutputStream a = mCache.edit("a").get();
    writeToOutputStream(a, "a1");
    deletePathRecursively(mCacheDir);
    a.commit();
    assertThat(mCache.get("a").isPresent()).isFalse();
  }

  @Test
  public void aggressiveClearingHandlesRead() throws Exception {
    deletePathRecursively(mCacheDir);
    assertThat(mCache.get("a").isPresent()).isFalse();
  }

  @Test
  public void editSameEntry() throws Exception {
    try {
      mCache.edit("k1");
      mCache.edit("k1");
      fail();
    } catch (IllegalStateException expected) {
      assertThat(expected.getMessage()
          .equals("Trying to edit a disk cache entry while another edit is in progress."));
    }
  }

  @SuppressLint("EmptyCatchBlock")
  @Test
  public void editSameEntryConcurrently() throws Exception {
    final CountDownLatch countDown = new CountDownLatch(1);
    final ThreadPoolExecutor executor =
        new ThreadPoolExecutor(1, 1, 1, TimeUnit.SECONDS, new LinkedBlockingDeque<Runnable>());
    final String key = "k1";
    Future<Boolean> future = executor.submit(
        new Callable<Boolean>() {
          @Override
          public Boolean call() throws Exception {
            try {
              countDown.countDown();
              mCache.edit(key);
            } catch (IllegalStateException expected) {
              return Boolean.TRUE;
            }
            return Boolean.FALSE;
          }
        });
    countDown.await();
    boolean exception2 = false;
    try {
      mCache.edit(key);
    } catch (IllegalStateException expected) {
      exception2 = true;
    }
    boolean exception1 = future.get();
    assertThat(exception1 ^ exception2); //one of these two should be true.
  }

  @Test
  public void createCacheWithNullDirectory() throws Exception {
    mCache = new IgDiskCache(null);
    assertEmptyNoOpCache();
  }

  @Test
  public void createCacheWithZeroMaxSize() throws Exception {
    mCache = new IgDiskCache(mCacheDir, 0);
    assertEmptyNoOpCache();
  }

  @Test
  public void createCacheWithZeroMaxCount() throws Exception {
    mCache = new IgDiskCache(mCacheDir, 10, 0);
    assertEmptyNoOpCache();
  }

  private void assertEmptyNoOpCache() throws Exception {
    assertThat(mCache.edit("k1").isPresent()).isFalse();
    assertThat(mCache.get("k1").isPresent()).isFalse();
    mCache.remove("k1");
    mCache.flush();
    File journal = new File(IgDiskCache.FAKE_CACHE_DIRECTORY.getPath(), JOURNAL_FILE);
    assertThat(journal.exists()).isFalse();
  }

  private void createJournal(String... bodyLines) throws Exception {
    Writer writer = new FileWriter(mJournalFile);
    for (String line : bodyLines) {
      writer.write(line);
      writer.write('\n');
    }
    writer.flush();
    writer.close();
  }

  private File getCleanFile(String key) {
    return new File(mCacheDir, key + Entry.CLEAN_FILE_EXTENSION);
  }

  private File getDirtyFile(String key) {
    return new File(mCacheDir, key + Entry.DIRTY_FILE_EXTENSION);
  }

  private static String readFile(File file) throws Exception {
    Reader reader = new FileReader(file);
    StringWriter writer = new StringWriter();
    char[] buffer = new char[1024];
    int count;
    while ((count = reader.read(buffer)) != -1) {
      writer.write(buffer, 0, count);
    }
    reader.close();
    return writer.toString();
  }

  public static void writeFile(File file, String content) throws Exception {
    FileWriter writer = new FileWriter(file);
    writer.write(content);
    writer.close();
  }

  private void generateSomeGarbageFiles() throws Exception {
    File dir1 = new File(mCacheDir, "dir1");
    writeFile(getCleanFile("g1"), "A");
    writeFile(getCleanFile("g2"), "B");
    writeFile(getCleanFile("g2"), "C");
    writeFile(new File(mCacheDir, "otherFile0.tmp"), "E");
    writeFile(new File(mCacheDir, "otherFile1.clean"), "F");
    dir1.mkdir();
  }

  private void assertGarbageFilesAllDeleted() throws Exception {
    assertThat(getCleanFile("g1")).doesNotExist();
    assertThat(getCleanFile("g2")).doesNotExist();
    assertThat(new File(mCacheDir, "otherFile0.tmp")).doesNotExist();
    assertThat(new File(mCacheDir, "otherFile1.clean")).doesNotExist();
  }

  static void set(IgDiskCache cache, String key, String value) throws Exception {
    OptionalStream<EditorOutputStream> out = cache.edit(key);
    if (out.isPresent()) {
      writeToOutputStream(out.get(), value);
      out.get().commit();
    } else {
      fail();
    }
  }

  private void assertAbsent(IgDiskCache cache, String key) throws Exception {
    OptionalStream<SnapshotInputStream> in = cache.get(key);
    if (in.isPresent()) {
      in.get().close();
      fail();
    }
    assertThat(getCleanFile(key)).doesNotExist();
    assertThat(getDirtyFile(key)).doesNotExist();
  }

  private void assertValue(IgDiskCache cache, String key, String value) throws Exception {
    OptionalStream<SnapshotInputStream> in = cache.get(key);
    if (in.isPresent()) {
      assertThat(readFromInputStream(in.get())).isEqualTo(value);
      assertThat(getCleanFile(key)).exists();
      in.get().close();
    }
  }

  static String readFromInputStream(SnapshotInputStream in) {
    Reader reader = null;
    try {
      reader = new InputStreamReader(in, US_ASCII);
      StringWriter writer = new StringWriter();
      char[] buffer = new char[1024];
      int count;
      while ((count = reader.read(buffer)) != -1) {
        writer.write(buffer, 0, count);
      }
      return writer.toString();
    } catch (IOException e) {
      return null;
    } finally {
      Journal.closeQuietly(reader);
    }
  }

  @SuppressLint("EmptyCatchBlock")
  static void writeToOutputStream(EditorOutputStream out, String str) {
    Writer writer = null;
    try {
      writer = new OutputStreamWriter(out, US_ASCII);
      writer.write(str);
    } catch (IOException ignored) {
    } finally {
      Journal.closeQuietly(writer);
    }
  }

  static void deletePathRecursively(File directory) {
    if (directory != null) {
      if (directory.isDirectory()) {
        for (File child : directory.listFiles()) {
          deletePathRecursively(child);
        }
      }
      directory.delete();
    }
  }
}
