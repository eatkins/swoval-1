package com.swoval.files.impl;

import static com.swoval.functional.Filters.AllPass;

import com.swoval.files.CacheEntry;
import com.swoval.functional.IOFunction;
import com.swoval.files.FileTreeRepository;
import com.swoval.files.api.FileTreeView;
import com.swoval.files.FileTreeViews;
import com.swoval.files.TypedPath;
import com.swoval.files.impl.functional.EitherImpl;
import com.swoval.functional.Either;
import com.swoval.functional.Filter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Provides a mutable in-memory cache of files and subdirectories with basic CRUD functionality. The
 * CachedDirectory can be fully recursive as the subdirectories are themselves stored as recursive
 * (when the CachedDirectory is initialized without the recursive toggle, the subdirectories are
 * stored as {@link CacheEntry} instances. The primary use case is the implementation of {@link
 * FileTreeRepository} and {@link NioPathWatcher}. Directly handling CachedDirectory instances is
 * discouraged because it is inherently mutable so it's better to let the FileTreeRepository manage
 * it and query the cache rather than CachedDirectory directly.
 *
 * <p>The CachedDirectory should cache all of the files and subdirectories up the maximum depth. A
 * maximum depth of zero means that the CachedDirectory should cache the subdirectories, but not
 * traverse them. A depth {@code < 0} means that it should not cache any files or subdirectories
 * within the directory. In the event that a loop is created by symlinks, the CachedDirectory will
 * include the symlink that completes the loop, but will not descend further (inducing a loop).
 *
 * @param <T> the cache value type.
 */
public class CachedDirectoryImpl<T> implements CachedDirectory<T> {
  private final AtomicReference<CacheEntry<T>> _cacheEntry;
  private final int depth;
  private final FileTreeView<TypedPath> fileTreeView;
  private final boolean followLinks;
  private final IOFunction<TypedPath, T> converter;
  private final Filter<? super TypedPath> pathFilter;
  private final LockableMap<Path, CachedDirectoryImpl<T>> subdirectories = new LockableMap<>();
  private final Map<Path, CacheEntry<T>> files = new HashMap<>();

  private interface ListTransformer<T, R> {
    R apply(final CacheEntry<T> cacheEntry);
  }

  CachedDirectoryImpl(
      final TypedPath typedPath,
      final IOFunction<TypedPath, T> converter,
      final int depth,
      final Filter<? super TypedPath> filter,
      final boolean followLinks,
      final FileTreeView<TypedPath> fileTreeView) {
    this.converter = converter;
    this.depth = depth;
    this._cacheEntry = new AtomicReference<>(Entries.get(typedPath, converter, typedPath));
    this.pathFilter = filter;
    this.fileTreeView = fileTreeView;
    this.followLinks = followLinks;
  }

  public CachedDirectoryImpl(
      final Path path,
      final IOFunction<TypedPath, T> converter,
      final int depth,
      final Filter<? super TypedPath> filter,
      final boolean followLinks) {
    this(
        TypedPaths.get(path),
        converter,
        depth,
        filter,
        followLinks,
        followLinks ? FileTreeViews.followSymlinks() : FileTreeViews.noFollowSymlinks());
  }

  /**
   * Returns the name components of a path in an array.
   *
   * @param path The path from which we extract the parts.
   * @return Empty array if the path is an empty relative path, otherwise return the name parts.
   */
  private static List<Path> parts(final Path path) {
    final Iterator<Path> it = path.iterator();
    final List<Path> result = new ArrayList<>();
    while (it.hasNext()) result.add(it.next());
    return result;
  }

  @Override
  public int getMaxDepth() {
    return depth;
  }

  @Override
  public List<CacheEntry<T>> list(
      final Path path, final int maxDepth, final Filter<? super CacheEntry<T>> filter) {
    if (this.subdirectories.lock()) {
      try {
        final Either<CacheEntry<T>, CachedDirectoryImpl<T>> findResult = find(path);
        if (findResult != null) {
          if (findResult.isRight()) {
            final List<CacheEntry<T>> result = new ArrayList<>();
            EitherImpl.getRight(findResult)
                .<CacheEntry<T>>listImpl(
                    maxDepth,
                    filter,
                    result,
                    new ListTransformer<T, CacheEntry<T>>() {
                      @Override
                      public CacheEntry<T> apply(final CacheEntry<T> cacheEntry) {
                        return cacheEntry;
                      }
                    });
            return result;
          } else {
            final CacheEntry<T> cacheEntry = EitherImpl.getLeft(findResult);
            final List<CacheEntry<T>> result = new ArrayList<>();
            if (cacheEntry != null && filter.accept(cacheEntry)) result.add(cacheEntry);
            return result;
          }
        } else {
          return Collections.emptyList();
        }
      } finally {
        this.subdirectories.unlock();
      }
    } else {
      return Collections.emptyList();
    }
  }

  @Override
  public List<CacheEntry<T>> list(int maxDepth, Filter<? super CacheEntry<T>> filter) {
    return list(getPath(), maxDepth, filter);
  }

  @Override
  public CacheEntry<T> getEntry() {
    return _cacheEntry.get();
  }

  @Override
  public void close() {
    subdirectories.clear();
    files.clear();
  }

  /**
   * CacheUpdates the CachedDirectory entry for a particular typed typedPath.
   *
   * @param typedPath the typedPath to update
   * @return a list of updates for the typedPath. When the typedPath is new, the updates have the
   *     oldCachedPath field set to null and will contain all of the children of the new typedPath
   *     when it is a directory. For an existing typedPath, the List contains a single CacheUpdates
   *     that contains the previous and new {@link CacheEntry}.
   * @throws IOException when the updated Path is a directory and an IOException is encountered
   *     traversing the directory.
   */
  @Override
  public CacheUpdates<T> update(final TypedPath typedPath) throws IOException {
    return update(typedPath, true);
  }

  /**
   * CacheUpdates the CachedDirectory entry for a particular typed typedPath.
   *
   * @param typedPath the typedPath to update
   * @param rescanDirectoriesOnUpdate if true, rescan the entire subtree for this directory. This
   *     can be very expensive.
   * @return a list of updates for the typedPath. When the typedPath is new, the updates have the
   *     oldCachedPath field set to null and will contain all of the children of the new typedPath
   *     when it is a directory. For an existing typedPath, the List contains a single CacheUpdates
   *     that contains the previous and new {@link CacheEntry}.
   * @throws IOException when the updated Path is a directory and an IOException is encountered
   *     traversing the directory.
   */
  @Override
  public CacheUpdates<T> update(final TypedPath typedPath, final boolean rescanDirectoriesOnUpdate)
      throws IOException {
    if (pathFilter.accept(typedPath)) {
      if (typedPath.exists()) {
        return updateImpl(
            typedPath.getPath().equals(this.getPath())
                ? new ArrayList<Path>()
                : parts(this.getPath().relativize(typedPath.getPath())),
            typedPath,
            rescanDirectoriesOnUpdate);
      } else {
        final Iterator<CacheEntry<T>> it = remove(typedPath.getPath()).iterator();
        final CacheUpdates<T> result = new CacheUpdates<>();
        while (it.hasNext()) result.onDelete(it.next());
        return result;
      }
    } else {
      return new CacheUpdates<T>();
    }
  }

  private Path getPath() {
    return getEntry().getTypedPath().getPath();
  }

  private TypedPath getTypedPath() {
    return getEntry().getTypedPath();
  }
  /**
   * Remove a path from the directory.
   *
   * @param path the path to remove
   * @return a List containing the CacheEntry instances for the removed path. The result also
   *     contains the cache entries for any children of the path when the path is a non-empty
   *     directory.
   */
  public List<CacheEntry<T>> remove(final Path path) {
    if (path.isAbsolute() && path.startsWith(this.getPath()) && !path.equals(this.getPath())) {
      return removeImpl(parts(this.getPath().relativize(path)));
    } else {
      return Collections.emptyList();
    }
  }

  @Override
  public String toString() {
    return "CachedDirectory(" + getPath() + ", maxDepth = " + depth + ")";
  }

  private int subdirectoryDepth() {
    return depth == Integer.MAX_VALUE ? depth : depth > 0 ? depth - 1 : 0;
  }

  @SuppressWarnings("EmptyCatchBlock")
  private void addDirectory(
      final CachedDirectoryImpl<T> currentDir,
      final TypedPath typedPath,
      final CacheUpdates<T> updates) {
    final Path path = typedPath.getPath();
    final CachedDirectoryImpl<T> dir =
        new CachedDirectoryImpl<>(
            path, converter, currentDir.subdirectoryDepth(), pathFilter, followLinks);
    boolean exists = true;
    try {
      final TypedPath tp = dir.getEntry().getTypedPath();
      if (tp.isDirectory() && (followLinks || !tp.isSymbolicLink())) dir.init();
      else {
        currentDir.files.put(tp.getPath(), dir.getEntry());
        exists = false;
      }
    } catch (final NoSuchFileException nsfe) {
      exists = false;
    } catch (final IOException e) {
    }
    if (exists) {
      final Map<Path, CacheEntry<T>> oldEntries = new HashMap<>();
      final Map<Path, CacheEntry<T>> newEntries = new HashMap<>();
      final CachedDirectoryImpl<T> previous =
          currentDir.subdirectories.put(path.getFileName(), dir);
      if (previous != null) {
        oldEntries.put(previous.getEntry().getTypedPath().getPath(), previous.getEntry());
        final Iterator<CacheEntry<T>> entryIterator =
            previous.list(Integer.MAX_VALUE, AllPass).iterator();
        while (entryIterator.hasNext()) {
          final CacheEntry<T> cacheEntry = entryIterator.next();
          oldEntries.put(cacheEntry.getTypedPath().getPath(), cacheEntry);
        }
        previous.close();
      }
      newEntries.put(dir.getEntry().getTypedPath().getPath(), dir.getEntry());
      final Iterator<CacheEntry<T>> it =
          dir.list(Paths.get(""), Integer.MAX_VALUE, AllPass).iterator();
      while (it.hasNext()) {
        final CacheEntry<T> cacheEntry = it.next();
        newEntries.put(cacheEntry.getTypedPath().getPath(), cacheEntry);
      }
      MapOps.diffDirectoryEntries(oldEntries, newEntries, updates);
    } else {
      final Iterator<CacheEntry<T>> it = remove(dir.getPath()).iterator();
      while (it.hasNext()) {
        updates.onDelete(it.next());
      }
    }
  }

  private boolean isLoop(final Path path, final Path realPath) {
    return path.startsWith(realPath) && !path.equals(realPath);
  }

  private void updateDirectory(
      final CachedDirectoryImpl<T> dir,
      final CacheUpdates<T> result,
      final CacheEntry<T> cacheEntry) {
    result.onUpdate(dir.getEntry(), cacheEntry);
    dir._cacheEntry.set(cacheEntry);
  }

  private CacheUpdates<T> updateImpl(
      final List<Path> parts, final TypedPath typedPath, final boolean rescanOnDirectoryUpdate)
      throws IOException {
    final CacheUpdates<T> result = new CacheUpdates<>();
    if (this.subdirectories.lock()) {
      try {
        if (!parts.isEmpty()) {
          final Iterator<Path> it = parts.iterator();
          CachedDirectoryImpl<T> currentDir = this;
          while (it.hasNext() && currentDir != null && currentDir.depth >= 0) {
            final Path p = it.next();
            if (p.toString().isEmpty()) return result;
            final Path resolved = currentDir.getPath().resolve(p);
            if (!it.hasNext()) {
              // We will always return from this block
              final boolean isDirectory =
                  typedPath.isDirectory() && (followLinks || !typedPath.isSymbolicLink());
              if (!isDirectory
                  || currentDir.depth <= 0
                  || isLoop(resolved, TypedPaths.expanded(typedPath))) {
                final CachedDirectoryImpl<T> previousCachedDirectoryImpl =
                    isDirectory ? currentDir.subdirectories.get(p) : null;
                final CacheEntry<T> fileCacheEntry = currentDir.files.remove(p);
                final CacheEntry<T> oldCacheEntry =
                    fileCacheEntry != null
                        ? fileCacheEntry
                        : previousCachedDirectoryImpl != null
                            ? previousCachedDirectoryImpl.getEntry()
                            : null;
                final CacheEntry<T> newCacheEntry =
                    Entries.get(
                        TypedPaths.getDelegate(resolved, typedPath),
                        converter,
                        TypedPaths.getDelegate(resolved, typedPath));
                if (isDirectory) {
                  final CachedDirectoryImpl<T> previous = currentDir.subdirectories.get(p);
                  if (previous == null || rescanOnDirectoryUpdate) {
                    currentDir.subdirectories.put(
                        p,
                        new CachedDirectoryImpl<>(
                            TypedPaths.getDelegate(resolved, typedPath),
                            converter,
                            -1,
                            pathFilter,
                            followLinks,
                            fileTreeView));
                  } else {
                    updateDirectory(previous, result, newCacheEntry);
                  }
                } else {
                  currentDir.files.put(p, newCacheEntry);
                }
                final CacheEntry<T> oldResolvedCacheEntry =
                    oldCacheEntry == null
                        ? null
                        : Entries.resolve(currentDir.getPath(), oldCacheEntry);
                if (oldResolvedCacheEntry == null) {
                  result.onCreate(Entries.resolve(currentDir.getPath(), newCacheEntry));
                } else {
                  result.onUpdate(
                      oldResolvedCacheEntry, Entries.resolve(currentDir.getPath(), newCacheEntry));
                }
                return result;
              } else {
                final CachedDirectoryImpl<T> previous = currentDir.subdirectories.get(p);
                if (previous == null || rescanOnDirectoryUpdate) {
                  addDirectory(currentDir, typedPath, result);
                } else {
                  updateDirectory(previous, result, Entries.get(typedPath, converter, typedPath));
                }
                return result;
              }
            } else {
              final CachedDirectoryImpl<T> dir = currentDir.subdirectories.get(p);
              if (dir == null && currentDir.depth > 0) {
                addDirectory(currentDir, TypedPaths.get(currentDir.getPath().resolve(p)), result);
              }
              currentDir = dir;
            }
          }
        } else if (typedPath.isDirectory() && rescanOnDirectoryUpdate) {
          final List<CacheEntry<T>> oldEntries = list(getMaxDepth(), AllPass);
          init();
          final List<CacheEntry<T>> newEntries = list(getMaxDepth(), AllPass);
          MapOps.diffDirectoryEntries(oldEntries, newEntries, result);
        } else {
          final CacheEntry<T> oldCacheEntry = getEntry();
          final TypedPath tp =
              TypedPaths.getDelegate(TypedPaths.expanded(getTypedPath()), typedPath);
          final CacheEntry<T> newCacheEntry = Entries.get(typedPath, converter, tp);
          _cacheEntry.set(newCacheEntry);
          result.onUpdate(oldCacheEntry, getEntry());
        }
      } finally {
        this.subdirectories.unlock();
      }
    }
    return result;
  }

  private Either<CacheEntry<T>, CachedDirectoryImpl<T>> findImpl(final List<Path> parts) {
    final Iterator<Path> it = parts.iterator();
    CachedDirectoryImpl<T> currentDir = this;
    Either<CacheEntry<T>, CachedDirectoryImpl<T>> result = null;
    while (it.hasNext() && currentDir != null && result == null) {
      final Path p = it.next();
      if (!it.hasNext()) {
        final CachedDirectoryImpl<T> subdir = currentDir.subdirectories.get(p);
        if (subdir != null) {
          result = EitherImpl.right(subdir);
        } else {
          final CacheEntry<T> cacheEntry = currentDir.files.get(p);
          if (cacheEntry != null)
            result = EitherImpl.left(Entries.resolve(currentDir.getPath(), cacheEntry));
        }
      } else {
        currentDir = currentDir.subdirectories.get(p);
      }
    }
    return result;
  }

  private Either<CacheEntry<T>, CachedDirectoryImpl<T>> find(final Path path) {
    if (!getEntry().getTypedPath().exists()) {
      return null;
    } else if (path.equals(this.getPath()) || path.equals(Paths.get(""))) {
      return EitherImpl.right(this);
    } else if (path.isAbsolute() && path.startsWith(this.getPath())) {
      return findImpl(parts(this.getPath().relativize(path)));
    } else {
      return null;
    }
  }

  private <R> void listImpl(
      final int maxDepth,
      final Filter<? super R> filter,
      final List<R> result,
      final ListTransformer<T, R> function) {
    if (this.depth < 0 || maxDepth < 0) {
      result.add(function.apply(this.getEntry()));
    } else {
      if (subdirectories.lock()) {
        try {
          final Collection<CacheEntry<T>> files = new ArrayList<>(this.files.values());
          final Collection<CachedDirectoryImpl<T>> subdirectories =
              new ArrayList<>(this.subdirectories.values());
          final Iterator<CacheEntry<T>> filesIterator = files.iterator();
          while (filesIterator.hasNext()) {
            final CacheEntry<T> cacheEntry = filesIterator.next();
            final R resolved = function.apply(Entries.resolve(getPath(), cacheEntry));
            if (filter.accept(resolved)) result.add(resolved);
          }
          final Iterator<CachedDirectoryImpl<T>> subdirIterator = subdirectories.iterator();
          while (subdirIterator.hasNext()) {
            final CachedDirectoryImpl<T> subdir = subdirIterator.next();
            final CacheEntry<T> cacheEntry = subdir.getEntry();
            final R resolved = function.apply(Entries.resolve(getPath(), cacheEntry));
            if (filter.accept(resolved)) result.add(resolved);
            if (maxDepth > 0) subdir.<R>listImpl(maxDepth - 1, filter, result, function);
          }
        } finally {
          subdirectories.unlock();
        }
      }
    }
  }

  private List<CacheEntry<T>> removeImpl(final List<Path> parts) {
    final List<CacheEntry<T>> result = new ArrayList<>();
    if (this.subdirectories.lock()) {
      try {
        final Iterator<Path> it = parts.iterator();
        CachedDirectoryImpl<T> currentDir = this;
        while (it.hasNext() && currentDir != null) {
          final Path p = it.next();
          if (!it.hasNext()) {
            final CacheEntry<T> cacheEntry = currentDir.files.remove(p);
            if (cacheEntry != null) {
              result.add(
                  Entries.setExists(Entries.resolve(currentDir.getPath(), cacheEntry), false));
            }
            final CachedDirectoryImpl<T> dir = currentDir.subdirectories.remove(p);
            if (dir != null) {
              final Iterator<CacheEntry<T>> removeIt =
                  dir.list(Integer.MAX_VALUE, AllPass).iterator();
              while (removeIt.hasNext()) {
                result.add(Entries.setExists(removeIt.next(), false));
              }
              result.add(Entries.setExists(dir.getEntry(), false));
            }
          } else {
            currentDir = currentDir.subdirectories.get(p);
          }
        }
      } finally {
        this.subdirectories.unlock();
      }
    }
    return result;
  }

  public CachedDirectoryImpl<T> init() throws IOException {
    return init(getTypedPath().getPath());
  }

  private CachedDirectoryImpl<T> init(final Path realPath) throws IOException {
    if (subdirectories.lock()) {
      try {
        subdirectories.clear();
        files.clear();
        if (depth >= 0
            && (!this.getPath().startsWith(realPath) || this.getPath().equals(realPath))) {
          final Iterator<TypedPath> it =
              fileTreeView.list(this.getPath(), 0, pathFilter).iterator();
          while (it.hasNext()) {
            final TypedPath file = it.next();
            final Path path = file.getPath();
            final Path key = this.getTypedPath().getPath().relativize(path).getFileName();
            if (file.isDirectory()) {
              if (depth > 0) {
                if (!file.isSymbolicLink() || !isLoop(path, TypedPaths.expanded(file))) {
                  final CachedDirectoryImpl<T> dir =
                      new CachedDirectoryImpl<>(
                          file,
                          converter,
                          subdirectoryDepth(),
                          pathFilter,
                          followLinks,
                          fileTreeView);
                  try {
                    dir.init();
                    subdirectories.put(key, dir);
                  } catch (final IOException e) {
                    if (Files.exists(dir.getPath())) {
                      subdirectories.put(key, dir);
                    }
                  }
                } else {
                  subdirectories.put(
                      key,
                      new CachedDirectoryImpl<>(
                          file, converter, -1, pathFilter, followLinks, fileTreeView));
                }
              } else {
                files.put(key, Entries.get(TypedPaths.getDelegate(key, file), converter, file));
              }
            } else {
              files.put(key, Entries.get(TypedPaths.getDelegate(key, file), converter, file));
            }
          }
        }
      } finally {
        subdirectories.unlock();
      }
    }
    return this;
  }
}
