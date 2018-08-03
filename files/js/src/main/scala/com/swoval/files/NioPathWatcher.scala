// Do not edit this file manually. It is autogenerated.

package com.swoval.files

import com.swoval.files.PathWatchers.Event.Kind.Create
import com.swoval.files.PathWatchers.Event.Kind.Delete
import com.swoval.files.PathWatchers.Event.Kind.Modify
import com.swoval.functional.Filters.AllPass
import java.util.Map.Entry
import com.swoval.files.FileTreeDataViews.CacheObserver
import com.swoval.files.FileTreeDataViews.Converter
import com.swoval.files.FileTreeViews.Observer
import com.swoval.files.PathWatchers.Event
import com.swoval.files.PathWatchers.Event.Kind
import com.swoval.files.PathWatchers.Overflow
import com.swoval.functional.Consumer
import com.swoval.functional.Either
import com.swoval.functional.Filter
import com.swoval.runtime.Platform
import java.io.IOException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.ArrayList
import java.util.HashSet
import java.util.Iterator
import java.util.List
import java.util.Set
import java.util.concurrent.atomic.AtomicBoolean

class RootDirectories extends LockableMap[Path, CachedDirectory[WatchedDirectory]]

/**
 Provides a PathWatcher that is backed by a [[java.nio.file.WatchService]].
 */
class NioPathWatcher(private val directoryRegistry: DirectoryRegistry,
                     watchService: RegisterableWatchService,
                     private val followLinks: Boolean)
    extends PathWatcher[PathWatchers.Event]
    with AutoCloseable {

  private val closed: AtomicBoolean = new AtomicBoolean(false)

  private val observers: Observers[PathWatchers.Event] = new Observers()

  private val rootDirectories: RootDirectories = new RootDirectories()

  private val converter: Converter[WatchedDirectory] =
    new Converter[WatchedDirectory]() {
      override def apply(typedPath: TypedPath): WatchedDirectory =
        if (typedPath.isDirectory)
          Either.getOrElse(service.register(typedPath.getPath), WatchedDirectories.INVALID)
        else WatchedDirectories.INVALID
    }

  private def updateCacheObserver(events: List[Event]): CacheObserver[WatchedDirectory] =
    new CacheObserver[WatchedDirectory]() {
      override def onCreate(newEntry: FileTreeDataViews.Entry[WatchedDirectory]): Unit = {
        events.add(new Event(newEntry, Create))
        try {
          val it: Iterator[TypedPath] = FileTreeViews
            .list(newEntry.getPath, 0, new Filter[TypedPath]() {
              override def accept(typedPath: TypedPath): Boolean =
                directoryRegistry.accept(typedPath.getPath)
            })
            .iterator()
          while (it.hasNext) {
            val tp: TypedPath = it.next()
            events.add(new Event(tp, Create))
          }
        } catch {
          case e: IOException => {}

        }
      }

      override def onDelete(oldEntry: FileTreeDataViews.Entry[WatchedDirectory]): Unit = {
        if (oldEntry.getValue.isRight) {
          oldEntry.getValue.get.close()
        }
        events.add(new Event(oldEntry, Delete))
      }

      override def onUpdate(oldEntry: FileTreeDataViews.Entry[WatchedDirectory],
                            newEntry: FileTreeDataViews.Entry[WatchedDirectory]): Unit = {}

      override def onError(exception: IOException): Unit = {}
    }

  private val service: NioPathWatcherService = new NioPathWatcherService(
    new Consumer[Either[Overflow, Event]]() {
      override def accept(either: Either[Overflow, Event]): Unit = {
        if (!closed.get) {
          if (either.isRight) {
            val event: Event = either.get
            handleEvent(event)
          } else {
            handleOverflow(Either.leftProjection(either).getValue)
          }
        }
      }
    },
    watchService
  )

  /**
   * Similar to register, but tracks all of the new files found in the directory. It polls the
   * directory until the contents stop changing to ensure that a callback is fired for each path in
   * the newly created directory (up to the maxDepth). The assumption is that once the callback is
   * fired for the path, it is safe to assume that no event for a new file in the directory is
   * missed. Without the polling, it would be possible that a new file was created in the directory
   * before we registered it with the watch service. If this happened, then no callback would be
   * invoked for that file.
   *
   * @param typedPath The newly created directory to add
   */
  def add(typedPath: TypedPath, events: List[Event]): Unit = {
    if (directoryRegistry.maxDepthFor(typedPath.getPath) >= 0) {
      val dir: CachedDirectory[WatchedDirectory] = getOrAdd(typedPath.getPath)
      if (dir != null) {
        update(dir, typedPath, events)
      }
    }
  }

  override def register(path: Path, maxDepth: Int): Either[IOException, Boolean] = {
    val existingMaxDepth: Int = directoryRegistry.maxDepthFor(path)
    val result: Boolean = existingMaxDepth < maxDepth
    val typedPath: TypedPath = TypedPaths.get(path)
    val realPath: Path = typedPath.toRealPath()
    if (result) {
      directoryRegistry.addDirectory(typedPath.getPath, maxDepth)
    }
    val dir: CachedDirectory[WatchedDirectory] = getOrAdd(realPath)
    val events: List[Event] = new ArrayList[Event]()
    if (dir != null) {
      val directories: List[FileTreeDataViews.Entry[WatchedDirectory]] =
        dir.listEntries(typedPath.getPath, -1, AllPass)
      if (result || directories.isEmpty || directories
            .get(0)
            .getValue
            .isRight) {
        val toUpdate: Path = typedPath.getPath
        if (toUpdate != null) update(dir, typedPath, events)
      }
    }
    runCallbacks(events)
    Either.right(result)
  }

  private def find(rawPath: Path, toRemove: List[Path]): CachedDirectory[WatchedDirectory] = {
    val parent: Path = if (Platform.isMac) rawPath else rawPath.getRoot
    val path: Path = if (parent == null) rawPath.getRoot else parent
    assert((path != null))
    if (rootDirectories.lock()) {
      try {
        val it: Iterator[Entry[Path, CachedDirectory[WatchedDirectory]]] =
          rootDirectories.iterator()
        var result: CachedDirectory[WatchedDirectory] = null
        while (result == null && it.hasNext) {
          val entry: Entry[Path, CachedDirectory[WatchedDirectory]] = it.next()
          val root: Path = entry.getKey
          if (path.startsWith(root)) {
            result = entry.getValue
          } else if (root.startsWith(path) && path != root) {
            toRemove.add(root)
          }
        }
        result
      } finally rootDirectories.unlock()
    } else {
      null
    }
  }

  private def findOrAddRoot(rawPath: Path): CachedDirectory[WatchedDirectory] = {
    val parent: Path = if (Platform.isMac) rawPath else rawPath.getRoot
    val path: Path = if (parent == null) rawPath.getRoot else parent
    assert((path != null))
    val toRemove: List[Path] = new ArrayList[Path]()
    var result: CachedDirectory[WatchedDirectory] = find(rawPath, toRemove)
    if (result == null) {
      var toAdd: Path = path
      var init: Boolean = false
      while (!init && toAdd != null) try {
        result = new CachedDirectoryImpl(
          toAdd,
          toAdd,
          converter,
          java.lang.Integer.MAX_VALUE,
          new Filter[TypedPath]() {
            override def accept(typedPath: TypedPath): Boolean =
              typedPath.isDirectory && (followLinks || !typedPath.isSymbolicLink) &&
                directoryRegistry.acceptPrefix(typedPath.getPath)
          },
          FileTreeViews.getDefault(false)
        ).init()
        init = true
        rootDirectories.put(toAdd, result)
      } catch {
        case e: IOException => toAdd = toAdd.getParent

      }
    }
    val toRemoveIterator: Iterator[Path] = toRemove.iterator()
    while (toRemoveIterator.hasNext) rootDirectories.remove(toRemoveIterator.next())
    result
  }

  private def getOrAdd(path: Path): CachedDirectory[WatchedDirectory] = {
    var result: CachedDirectory[WatchedDirectory] = null
    if (rootDirectories.lock()) {
      try if (!closed.get) {
        result = findOrAddRoot(path)
      } finally rootDirectories.unlock()
    }
    result
  }

  override def unregister(path: Path): Unit = {
    directoryRegistry.removeDirectory(path)
    if (rootDirectories.lock()) {
      try {
        val dir: CachedDirectory[WatchedDirectory] =
          find(path, new ArrayList[Path]())
        if (dir != null) {
          val depth: Int = dir.getPath.relativize(path).getNameCount
          val toRemove: List[FileTreeDataViews.Entry[WatchedDirectory]] =
            dir.listEntries(
              depth,
              new Filter[FileTreeDataViews.Entry[WatchedDirectory]]() {
                override def accept(entry: FileTreeDataViews.Entry[WatchedDirectory]): Boolean =
                  !directoryRegistry.acceptPrefix(entry.getPath)
              }
            )
          val it: Iterator[FileTreeDataViews.Entry[WatchedDirectory]] =
            toRemove.iterator()
          while (it.hasNext) {
            val entry: FileTreeDataViews.Entry[WatchedDirectory] = it.next()
            if (!directoryRegistry.acceptPrefix(entry.getPath)) {
              val toCancel: Iterator[FileTreeDataViews.Entry[WatchedDirectory]] =
                dir.remove(entry.getPath).iterator()
              while (toCancel.hasNext) {
                val either: Either[IOException, WatchedDirectory] =
                  toCancel.next().getValue
                if (either.isRight) either.get.close()
              }
            }
          }
          rootDirectories.remove(dir.getPath)
        }
      } finally rootDirectories.unlock()
    }
  }

  override def close(): Unit = {
    if (closed.compareAndSet(false, true)) {
      service.close()
      rootDirectories.clear()
    }
  }

  private def update(dir: CachedDirectory[WatchedDirectory],
                     typedPath: TypedPath,
                     events: List[Event]): Unit = {
    try dir.update(typedPath).observe(updateCacheObserver(events))
    catch {
      case e: NoSuchFileException => {
        dir.remove(typedPath.getPath)
        val newTypedPath: TypedPath = TypedPaths.get(typedPath.getPath)
        events.add(new Event(newTypedPath, if (newTypedPath.exists()) Kind.Modify else Kind.Delete))
        val root: CachedDirectory[WatchedDirectory] =
          rootDirectories.remove(typedPath.getPath)
        if (root != null) {
          val it: Iterator[FileTreeDataViews.Entry[WatchedDirectory]] =
            root.listEntries(java.lang.Integer.MAX_VALUE, AllPass).iterator()
          while (it.hasNext) it.next().getValue.get.close()
        }
      }

      case e: IOException => {}

    }
  }

  private def handleOverflow(overflow: Overflow): Unit = {
    val path: Path = overflow.getPath
    val events: List[Event] = new ArrayList[Event]()
    if (rootDirectories.lock()) {
      try {
        val root: CachedDirectory[WatchedDirectory] =
          find(path, new ArrayList[Path]())
        if (root != null) {
          try {
            val it: Iterator[TypedPath] = FileTreeViews
              .list(
                path,
                0,
                new Filter[TypedPath]() {
                  override def accept(typedPath: TypedPath): Boolean =
                    typedPath.isDirectory && directoryRegistry.acceptPrefix(typedPath.getPath)
                }
              )
              .iterator()
            while (it.hasNext) {
              val file: TypedPath = it.next()
              add(file, events)
            }
          } catch {
            case e: IOException => {
              val removed: List[FileTreeDataViews.Entry[WatchedDirectory]] =
                root.remove(path)
              val removedIt: Iterator[FileTreeDataViews.Entry[WatchedDirectory]] =
                removed.iterator()
              while (removedIt.hasNext) events.add(
                new Event(Entries.setExists(removedIt.next(), false), Delete))
            }

          }
        }
      } finally rootDirectories.unlock()
    }
    val tp: TypedPath = TypedPaths.get(path)
    events.add(new Event(tp, if (tp.exists()) Modify else Delete))
    runCallbacks(events)
  }

  private def runCallbacks(events: List[Event]): Unit = {
    val it: Iterator[Event] = events.iterator()
    val handled: Set[Path] = new HashSet[Path]()
    while (it.hasNext) {
      val event: Event = it.next()
      if (directoryRegistry.accept(event.getPath) && handled.add(event.getPath)) {
        observers.onNext(new Event(TypedPaths.get(event.getPath), event.getKind))
      }
    }
  }

  private def handleEvent(event: Event): Unit = {
    val events: List[Event] = new ArrayList[Event]()
    if (!closed.get && rootDirectories.lock()) {
      try if (directoryRegistry.acceptPrefix(event.getPath)) {
        val typedPath: TypedPath = TypedPaths.get(event.getPath)
        if (!typedPath.exists()) {
          val root: CachedDirectory[WatchedDirectory] = getOrAdd(event.getPath)
          if (root != null) {
            val isRoot: Boolean = root.getPath == event.getPath
            val it: Iterator[FileTreeDataViews.Entry[WatchedDirectory]] =
              if (isRoot)
                root.listEntries(root.getMaxDepth, AllPass).iterator()
              else root.remove(event.getPath).iterator()
            while (it.hasNext) {
              val entry: FileTreeDataViews.Entry[WatchedDirectory] = it.next()
              val either: Either[IOException, WatchedDirectory] =
                entry.getValue
              if (either.isRight) {
                either.get.close()
              }
              events.add(new Event(Entries.setExists(entry, false), Kind.Delete))
            }
            val parent: CachedDirectory[WatchedDirectory] =
              find(event.getPath.getParent, new ArrayList[Path]())
            if (parent != null) {
              update(parent, parent.getEntry, events)
            }
            if (isRoot) {
              rootDirectories.remove(root.getPath)
              getOrAdd(event.getPath)
            }
          }
        }
        events.add(event)
        if (typedPath.isDirectory) {
          add(typedPath, events)
        }
      } finally rootDirectories.unlock()
    }
    runCallbacks(events)
  }

  override def addObserver(observer: Observer[Event]): Int =
    observers.addObserver(observer)

  override def removeObserver(handle: Int): Unit = {
    observers.removeObserver(handle)
  }

}