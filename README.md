#IgDiskCache

[![Build Status][build-status-svg]][build-status-link]
[![Maven Central][maven-svg]][maven-link]
[![License][license-svg]][license-link]


Exception handling is always a cumbersome but unavoidable part of dealing with disk cache on Android. Complex error handling not only makes your code hard to understand, but also prone to developer errors. IgDiskCache is a fault-tolerant Android disk cache library that helps simplify the error handling logic and makes your file caching code cleaner and much easier to maintain.

For more Instagram engineering updates and shared insights, please visit the [Instagram Engineering blog][eng-blog].

#Getting Started


##Usage

### Initialization 

- When initializing the IgDiskCache, we can limit the number of bytes, and the number of file entries that can be stored in the cache. We can also use our own serialExecutor to handle Journal logging tasks. 

- For the following cases, the class constructor will return a stub instance of the IgDiskCache: cache directory is NULL or not accessible, maxCacheSizeInBytes or maxFileCount is invalid.  

- A non-UI thread assertion is introduced before the IgDiskCache initialization to prevent running expensive disk IO operations on the UI thread. 

- Note: the cache limits are not strict: the cache may temporarily exceed the cache size or file count limit when waiting for the least-recently-used files to be removed.

``` java
IgDiskCache(File directory) 
IgDiskCache(File directory, long maxCacheSizeInBytes)
IgDiskCache(File directory, long maxCacheSizeInBytes, int maxFileCount)
IgDiskCache(File directory, long maxCacheSizeInBytes, int maxFileCount, Executor serialExecutor)

mDiskCache = new IgDiskCache(cacheDir, maxCacheSizeInBytes);
```

### Writing 

- Call **edit(key)** to get an outputStream for the cache entry. The cache key must match the regex **[a-z0-9_-]{1,120}**. The method will return an **OptionalStream<EditorOutputStream>**.

- If the cache is a stub instance or the cache entry is not available for editing, the **edit** operation will return an **OptionalStream.absent()** instead. 

- If an error occurs while writing to an **EditorOutputStream**, the operation will fail silently without throwing IOExceptions. The partial change will not be committed to the cache, and the stale entry with the same key (if exist) will also be discarded.

- After editing, instead of **close()** the output stream, the **EditorOutputStream** need to either **commit()** or **abort()** the change. 

- If we try to edit the same cache entry from two different places at the same time, an **IllegalStateException** will be thrown to notify the developer there's a race condition.

``` java
OptionalStream<EditorOutputStream> outputStream = mDiskCache.edit(key);
if (outputStream.isPresent()) {
	try {
		writeFileToStream(outputStream.get());
		outputStream.get().commit();
	} finally {
		outputStream.get().abortUnlessCommitted();
	}
}
```

### Reading

- Call **has(key)** to know if the cache entry associated with a certain key exists and ready-for-read. The method will return **True** if the entry is available, is not currently under editing, and not corrupted because of the previous writing failure. 

- Call **get(key)** to get an inputStream of the cache entry using a cache key. The method will return an **OptionalStream<SnapshotInputStream>**.

- If the cache is a stub instance, the file entry is not available, or the file entry is still under editing, an **OptionalStream.absent()** will be returned. 

- If any error occurs while reading from the SnapshotInputStream, **IOExceptions** will still be thrown out as normal FileInputStream does. 

- Similar to FileInputStream, use **close()** to close the **SnapshotInputStream** after use.

``` java
OptionalStream<SnapshotInputStream> inputStream = mDiskCache.get(key);
if (inputStream.isPresent()) {
	try {
		readFromInputStream(inputStream.get());
	} finally {
		inputStream.get().close();
	}
}
```

### Closing
- Request the disk cache to trim to size or file count.

``` java
mDiskCache.flush();
```

- Finish using the disk cache, you could use **close()** to close the cache (note: **close()** can only be called from non-UI thread):

``` java
mDiskCache.close();
```

##Compile a AAR

``` 
./gradlew clean assembleRelease
```
Outputs can be found in igdiskcache/build/outputs/

##Run the Tests
```
./gradlew clean test
```

#Download

##Maven

``` xml
<dependency>
  <groupId>com.instagram.igdiskcache</groupId>
  <artifactId>ig-disk-cache</artifactId>
  <version>1.0.0</version>
  <type>aar</type>
</dependency>
```

##Gradle

``` groovy
dependencies {
    compile 'com.instagram.igdiskcache:ig-disk-cache:1.0.0@aar'
}
```

#Other Instagram Android Projects
- [ig-json-parser for Android][ig-json-parser-link]


#License

```	
Copyright (c) 2016-present, Facebook, Inc.
All rights reserved.

This source code is licensed under the BSD-style license found in the
LICENSE file in the root directory of this source tree. An additional grant
of patent rights can be found in the PATENTS file in the same directory.
```
	 
 [eng-blog]: http://engineering.instagram.com/
 
 [build-status-svg]: https://travis-ci.org/Instagram/ig-disk-cache.svg
 [build-status-link]: https://travis-ci.org/Instagram/ig-disk-cache
 [maven-svg]: https://maven-badges.herokuapp.com/maven-central/com.instagram.igdiskcache/ig-disk-cache/badge.svg?style=flat
 [maven-link]: https://maven-badges.herokuapp.com/maven-central/com.instagram.igdiskcache/ig-disk-cache
 
 [ig-json-parser-link]: https://github.com/Instagram/ig-json-parser
 
 [license-svg]: https://img.shields.io/badge/license-BSD-lightgrey.svg
 [license-link]: https://github.com/Instagram/ig-disk-cache/blob/master/LICENSE
