/*
 * Copyright (c) 2016-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.instagram.igdiskcache;

import android.annotation.SuppressLint;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * This cache uses a journal file named "journal" to record the state of a cache entry on disk.
 * A typical journal file looks like this:
 *
 * <pre>
 *    CLEAN 3400330d1dfc7f3f7f4b8d4d803dfcf6 832
 *    DIRTY 335c4c6028171cfddfbaae1a9c313c52
 *    CLEAN 335c4c6028171cfddfbaae1a9c313c52 3934
 *    DIRTY 3400330d1dfc7f3f7f4b8d4d803dfcf6
 * </pre>
 *
 * <p> Each line contains space-separated values: a state, a key, and a optional state-specific
 * value representing the length of the entry data in bytes.
 *
 * <ul><li>
 *   o DIRTY lines track that an entry is actively being created or updated. Every successful
 *     DIRTY action should be followed by a CLEAN action. DIRTY lines without a matching CLEAN
 *     indicate that temporary files may need to be deleted next time the cache got opened. </li>
 * <li>
 *   o CLEAN lines track a cache entry that has been successfully published, Entry key is followed
 *     by the lengths of the Entry data in bytes. </li>
 * </ul>
 *
 * <p> The journal file is appended to as cache operations occur. The journal may occasionally be
 * compacted when the number of lines inside the journal exceeds the rebuild threshold.
 * A temporary file named "journal.tmp" will be used during compaction; that file will be deleted
 * if it exists when the cache is re-opened.
 */

/* package */ class Journal {

  static final String JOURNAL_FILE = "journal";
  static final String JOURNAL_FILE_TEMP = "journal.tmp";
  static final String JOURNAL_FILE_BACKUP = "journal.bkp";
  static final Charset US_ASCII = Charset.forName("US-ASCII");

  private static final String TAG = Journal.class.getSimpleName();
  private static final String CLEAN_ENTRY_PREFIX = "CLEAN";
  private static final String DIRTY_ENTRY_PREFIX = "DIRTY";
  private static final int JOURNAL_REBUILD_THRESHOLD = 1000;

  private final File mDirectory;
  private final File mJournalFile;
  private final File mJournalFileTmp;
  private final File mJournalFileBackup;
  private final IgDiskCache mCache;
  private final Executor mExecutor;

  private Writer mJournalWriter;
  private int mLineCount;

  @SuppressLint("EmptyCatchBlock")
  class WriteToJournalRunnable implements Runnable {
    final String mLine;
    public WriteToJournalRunnable(String line) {
      this.mLine = line;
    }
    @Override
    public void run() {
      try {
        if (mJournalWriter != null) {
          mJournalWriter.write(mLine);
          mJournalWriter.flush();
          mLineCount++;
          rebuildIfNeeded();
        }
      } catch (IOException ignored) {
      }
    }
  }

  /* package */ Journal(File directory, IgDiskCache cache, Executor executor) {
    mJournalFile = new File(directory, JOURNAL_FILE);
    mJournalFileTmp = new File(directory, JOURNAL_FILE_TEMP);
    mJournalFileBackup = new File(directory, JOURNAL_FILE_BACKUP);
    mDirectory = directory;
    mCache = cache;
    mExecutor = executor;
    mLineCount = 0;
  }

  @SuppressLint("EmptyCatchBlock")
  /* package */ LinkedHashMap<String, Entry> retrieveEntriesFromJournal() {
    maybeSwitchToBackupJournalFile(mDirectory);
    File journalFile = new File(mDirectory, JOURNAL_FILE);
    if (journalFile.exists()) {
      LinkedHashMap<String, Entry> lruEntries = new LinkedHashMap<>();
      BufferedReader reader = null;
      try {
        reader = new BufferedReader(new FileReader(journalFile));
        boolean journalIsCorrupted = false;
        Set<String> dirtyEntryKeySet = new HashSet<>();
        String line;
        while ((line = reader.readLine()) != null) {
          String[] lineParts = line.split(" ");
          String state = lineParts[0];
          String key = lineParts[1];
          if (CLEAN_ENTRY_PREFIX.equals(state) && lineParts.length == 3) {
            Entry entry = lruEntries.get(key);
            if (entry == null) {
              entry = new Entry(mDirectory, key);
              lruEntries.put(key, entry);
            }
            entry.markAsPublished(Long.parseLong(lineParts[2]));
            dirtyEntryKeySet.remove(key);
          } else if (DIRTY_ENTRY_PREFIX.equals(state) && lineParts.length == 2) {
            dirtyEntryKeySet.add(key);
          } else {
            journalIsCorrupted = true;
            break;
          }
          mLineCount++;
        }
        if (!journalIsCorrupted) {
          for (String key : dirtyEntryKeySet) {
            Entry entry = lruEntries.get(key);
            if (entry != null) {
              deleteFileIfExists(entry.getCleanFile());
              deleteFileIfExists(entry.getDirtyFile());
            }
            lruEntries.remove(key);
          }
          createJournalWriter();
          return lruEntries;
        }
      } catch (IOException | IndexOutOfBoundsException | NumberFormatException ignored) {
        // Journal is corrupted or IOException occurs while reading the journal.
      } finally {
        closeQuietly(reader);
      }
    }
    deleteUntrackedFiles(mDirectory);
    return null;
  }

  private static void maybeSwitchToBackupJournalFile(File directory) {
    File backupFile = new File(directory, JOURNAL_FILE_BACKUP);
    if (backupFile.exists()) {
      File journalFile = new File(directory, JOURNAL_FILE);
      // If journal file also exists just delete backup file.
      if (journalFile.exists()) {
        backupFile.delete();  //no need to handle the delete fail case, ignore the return value.
      } else {
        backupFile.renameTo(journalFile);
      }
    }
  }

  private void createJournalWriter() {
    try {
      mJournalWriter = new BufferedWriter(
              new OutputStreamWriter(new FileOutputStream(mJournalFile, true), US_ASCII));
    } catch (IOException e) {
      closeQuietly(mJournalWriter);
      mJournalWriter = null;
    }
  }

  /**
   * Creates a new journal that omits redundant journal entries. This replaces the current journal
   * if it exists.
   */
  @SuppressLint("EmptyCatchBlock")
  /* package */ void rebuild() {
    if (mJournalWriter != null) {
      closeQuietly(mJournalWriter);
    }
    Writer writer = null;
    try {
      ArrayList<Entry> entries = mCache.getEntryCollection();
      mLineCount = entries.size();
      writer = new BufferedWriter(
              new OutputStreamWriter(new FileOutputStream(mJournalFileTmp), US_ASCII));

      for (Entry entry : entries) {
        if (entry.isReadable()) {
          writer.write(CLEAN_ENTRY_PREFIX + ' ' + entry.getKey() + ' ' +
                  String.valueOf(entry.getLengthInBytes()) + '\n');
        } else {
          writer.write(DIRTY_ENTRY_PREFIX + ' ' + entry.getKey() + '\n');
        }
      }
      writer.flush();
      if (mJournalFile.exists()) {
        mJournalFile.renameTo(mJournalFileBackup);
      }
      mJournalFileTmp.renameTo(mJournalFile);
      createJournalWriter();
      mJournalFileBackup.delete();
    } catch (IOException ignored) {
    } finally {
      closeQuietly(writer);
    }
  }

  /* package */ void logDirtyFileUpdate(String key) {
    mExecutor.execute(new WriteToJournalRunnable(DIRTY_ENTRY_PREFIX + ' ' + key + '\n'));
  }

  /* package */ void logCleanFileUpdate(String key, long length) {
    mExecutor.execute(
            new WriteToJournalRunnable(
                    CLEAN_ENTRY_PREFIX + ' ' + key + ' ' + String.valueOf(length) + '\n'));
  }

  /* package */ void rebuildIfNeeded() {
    if (mLineCount > JOURNAL_REBUILD_THRESHOLD) {
      mExecutor.execute(
              new Runnable() {
                @Override
                public void run() {
                  if (mLineCount > JOURNAL_REBUILD_THRESHOLD) {
                    rebuild();
                  }
                }
              });
    }
  }

  static void closeQuietly(Closeable closeable) {
    if (closeable != null) {
      try {
        closeable.close();
      } catch (IOException e) {
        // Handle the IOException quietly.
      }
    }
  }

  private static void deleteUntrackedFiles(File dir) {
    if (dir != null && dir.exists()) {
      File[] files = dir.listFiles();
      if (files != null) {
        for (File file : files) {
          String name = file.getName();
          if (name.endsWith(Entry.CLEAN_FILE_EXTENSION) ||
                  name.endsWith(Entry.DIRTY_FILE_EXTENSION)) {
            deleteFileIfExists(file);
          }
        }
      }
    }
  }

  private static void deleteFileIfExists(File file) {
    if (file.exists()) {
      file.delete();
    }
  }
}
