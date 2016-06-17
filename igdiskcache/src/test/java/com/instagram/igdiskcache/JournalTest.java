/*
 * Copyright (c) 2016-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */
package com.instagram.igdiskcache;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import android.annotation.SuppressLint;
import android.os.Looper;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.powermock.core.classloader.annotations.PrepareForTest;

import static com.instagram.igdiskcache.Journal.JOURNAL_FILE;
import static com.instagram.igdiskcache.Journal.JOURNAL_FILE_BACKUP;
import static org.fest.assertions.api.Assertions.assertThat;
import static org.powermock.api.mockito.PowerMockito.mock;
import static org.powermock.api.mockito.PowerMockito.spy;
import static org.powermock.api.mockito.PowerMockito.when;


@PrepareForTest({Looper.class})
public class JournalTest extends RobolectricBaseTest {
  private File mCacheDir;
  private File mJournalFile;
  private File mJournalBkpFile;
  private IgDiskCache mCache;
  private Journal mJournal;
  private ThreadPoolExecutor mExecutor;

  @Rule
  public TemporaryFolder tempDir = new TemporaryFolder();

  @Before
  public void setUp() throws Exception {
    mCacheDir = tempDir.newFolder("IgDiskCacheTest");
    mJournalFile = new File(mCacheDir, JOURNAL_FILE);
    mJournalBkpFile = new File(mCacheDir, JOURNAL_FILE_BACKUP);
    for (File file : mCacheDir.listFiles()) {
      file.delete();
    }
    mExecutor =
            new ThreadPoolExecutor(0, 1, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());
    Looper looper = mock(Looper.class);
    when(looper.getThread()).thenReturn(mock(Thread.class));
    spy(Looper.class);
    when(Looper.getMainLooper()).thenReturn(looper);
    mCache = new IgDiskCache(mCacheDir, Integer.MAX_VALUE);
    mJournal = new Journal(mCacheDir, mCache, mExecutor);
  }

  @Test
  public void retrieveEntriesFromJournal() throws Exception {
    FileWriter writer = new FileWriter(mJournalFile);
    writer.write("DIRTY k1\nCLEAN k1 12\n");
    writer.close();
    LinkedHashMap<String, Entry> entries = mJournal.retrieveEntriesFromJournal();
    assertThat(entries.size()).isEqualTo(1);
    assertThat(entries.get("k1")).isNotEqualTo(null);
    assertThat(entries.get("k1").isReadable());
  }

  @Test
  public void retrieveEntriesFromJournalBkp() throws Exception {
    mJournalFile.delete();
    FileWriter writer = new FileWriter(mJournalBkpFile);
    writer.write("DIRTY k1\nCLEAN k1 12\n");
    writer.close();
    LinkedHashMap<String, Entry> entries = mJournal.retrieveEntriesFromJournal();
    assertThat(entries.size()).isEqualTo(1);
    assertThat(entries.get("k1")).isNotEqualTo(null);
    assertThat(entries.get("k1").isReadable());
  }

  @Test
  public void logCleanFileUpdateInJournal() throws Exception {
    mJournal.rebuild();
    mJournal.logCleanFileUpdate("k1", 12);
    assertJournalEqualsAsync("CLEAN k1 12");
  }

  @Test
  public void logDirtyFileUpdateInJournal() throws Exception {
    mJournal.rebuild();
    mJournal.logDirtyFileUpdate("k1");
    assertJournalEqualsAsync("DIRTY k1");
  }

  @Test
  public void journalShouldBeEmptyForEmptyCache() throws Exception {
    assertJournalEquals();
  }

  @Test
  public void journalWithEditAndPublish() throws Exception {
    OptionalStream<EditorOutputStream> out = mCache.edit("k1");
    assertThat(out.isPresent());
    assertJournalEquals("DIRTY k1"); // DIRTY must always be flushed.
    IgDiskCacheTest.writeToOutputStream(out.get(), "AB");
    out.get().commit();
    assertJournalEqualsAsync("DIRTY k1", "CLEAN k1 2");
  }


  @Test
  public void revertedNewFileIsRemoveInJournal() throws Exception {
    OptionalStream<EditorOutputStream> out = mCache.edit("k1");
    assertThat(out.isPresent());
    assertJournalEquals("DIRTY k1"); // DIRTY must always be flushed.
    IgDiskCacheTest.writeToOutputStream(out.get(), "AB");
    out.get().abort();
    assertJournalEqualsAsync("DIRTY k1");
  }

  @Test
  public void unterminatedEditIsDirtyOnClose() throws Exception {
    OptionalStream<EditorOutputStream> out = mCache.edit("k1");
    assertThat(out.isPresent());
    IgDiskCacheTest.writeToOutputStream(out.get(), "AB");
    mCache.flush();
    assertJournalEqualsAsync("DIRTY k1");
  }

  @Test
  public void journalWithEditAndPublishAndRead() throws Exception {
    OptionalStream<EditorOutputStream> out1 = mCache.edit("k1");
    assertThat(out1.isPresent());
    IgDiskCacheTest.writeToOutputStream(out1.get(), "AB");
    out1.get().commit();
    OptionalStream<EditorOutputStream> out2 = mCache.edit("k2");
    assertThat(out2.isPresent());
    IgDiskCacheTest.writeToOutputStream(out2.get(), "DEF");
    out2.get().commit();
    OptionalStream<SnapshotInputStream> in = mCache.get("k1");
    assertThat(in.isPresent());
    in.get().close();
    assertJournalEqualsAsync("DIRTY k1", "CLEAN k1 2", "DIRTY k2", "CLEAN k2 3");
  }

  @Test
  public void rebuildJournalOnRepeatedEdits() throws Exception {
    long lastJournalLength = 0;
    while (true) {
      long journalLength = mJournalFile.length();
      IgDiskCacheTest.set(mCache, "a", "a");
      IgDiskCacheTest.set(mCache, "b", "b");
      if (journalLength < lastJournalLength) {
        System.out
                .printf(
                        "Journal compacted from %s bytes to %s bytes\n",
                        lastJournalLength,
                        journalLength);
        break;
      }
      lastJournalLength = journalLength;
    }
    assertValue("a", "a");
    assertValue("b", "b");
  }

  @Test
  public void restoreBackupFile() throws Exception {
    IgDiskCacheTest.set(mCache, "k1", "ABC");
    mCache.flush();

    assertThat(mJournalFile.renameTo(mJournalBkpFile)).isTrue();
    assertThat(mJournalFile.exists()).isFalse();
    assertThat(mJournalBkpFile.exists());

    mCache = new IgDiskCache(mCacheDir, Integer.MAX_VALUE);
    SnapshotInputStream snapshot = mCache.get("k1").get();
    assertThat(IgDiskCacheTest.readFromInputStream(snapshot)).isEqualTo("ABC");
    assertThat(snapshot.getLengthInBytes()).isEqualTo(3);

    assertThat(mJournalBkpFile.exists()).isFalse();
    assertThat(mJournalFile.exists()).isTrue();
  }

  @Test
  public void journalFileIsPreferredOverBackupFile() throws Exception {
    IgDiskCacheTest.set(mCache, "k1", "ABC");
    copyFile(mJournalFile, mJournalBkpFile);
    IgDiskCacheTest.set(mCache, "k2", "F");

    assertThat(mJournalFile.exists()).isTrue();
    assertThat(mJournalBkpFile.exists()).isTrue();

    mCache = new IgDiskCache(mCacheDir, Integer.MAX_VALUE);

    SnapshotInputStream snapshotA = mCache.get("k1").get();
    assertThat(IgDiskCacheTest.readFromInputStream(snapshotA)).isEqualTo("ABC");
    assertThat(snapshotA.getLengthInBytes()).isEqualTo(3);

    SnapshotInputStream snapshotB = mCache.get("k2").get();
    assertThat(IgDiskCacheTest.readFromInputStream(snapshotB)).isEqualTo("F");
    assertThat(snapshotB.getLengthInBytes()).isEqualTo(1);

    assertThat(mJournalBkpFile.exists()).isFalse();
    assertThat(mJournalFile.exists()).isTrue();
  }

  private void assertJournalEquals(String... expectedBodyLines) throws Exception {
    List<String> expectedLines = new ArrayList<String>();
    expectedLines.addAll(Arrays.asList(expectedBodyLines));
    assertThat(readJournalLines()).isEqualTo(expectedLines);
  }

  @SuppressLint("BadCatchBlock")
  private void assertJournalEqualsAsync(final String... expectedBodyLines) throws Exception {
    Callable<Boolean> assertTask = new Callable<Boolean>() {
      @Override
      public Boolean call() {
        try {
          assertJournalEquals(expectedBodyLines);
        } catch (Exception e) {
          return false;
        }
        return true;
      }
    };
    assertThat(mExecutor.submit(assertTask).get().booleanValue());
  }

  private List<String> readJournalLines() throws Exception {
    List<String> result = new ArrayList<String>();
    BufferedReader reader = new BufferedReader(new FileReader(mJournalFile));
    String line;
    while ((line = reader.readLine()) != null) {
      result.add(line);
    }
    reader.close();
    return result;
  }

  private void assertValue(String key, String value) throws Exception {
    OptionalStream<SnapshotInputStream> in = mCache.get(key);
    if (in.isPresent()) {
      assertThat(IgDiskCacheTest.readFromInputStream(in.get())).isEqualTo(value);
      assertThat(new File(mCacheDir, key + Entry.CLEAN_FILE_EXTENSION)).exists();
      in.get().close();
    }
  }

  static void copyFile(File from, File to) {
    BufferedInputStream inputStream = null;
    BufferedOutputStream outputStream = null;
    try {
      inputStream = new BufferedInputStream(new FileInputStream(from));
      outputStream = new BufferedOutputStream(new FileOutputStream(to));
      byte[] buffer = new byte[1024];
      int length;
      while ((length = inputStream.read(buffer)) > 0) {
        outputStream.write(buffer, 0, length);
      }
    } catch (IOException e) {
      // Handle Exception quietly
    } finally {
      Journal.closeQuietly(inputStream);
      Journal.closeQuietly(outputStream);
    }
  }
}
