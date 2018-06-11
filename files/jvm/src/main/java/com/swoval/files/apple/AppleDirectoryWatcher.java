package com.swoval.files.apple;

import static com.swoval.files.DirectoryWatcher.Event.Create;
import static com.swoval.files.DirectoryWatcher.Event.Delete;
import static com.swoval.files.DirectoryWatcher.Event.Modify;

import com.swoval.concurrent.Lock;
import com.swoval.files.DirectoryWatcher;
import com.swoval.files.Executor;
import com.swoval.files.apple.FileEventsApi.ClosedFileEventsApiException;
import com.swoval.files.apple.FileEventsApi.Consumer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Implements the DirectoryWatcher for Mac OSX using the <a
 * href="https://developer.apple.com/library/content/documentation/Darwin/Conceptual/FSEvents_ProgGuide/UsingtheFSEventsFramework/UsingtheFSEventsFramework.html"
 * target="_blank">Apple File System Events Api</a>
 */
public class AppleDirectoryWatcher extends DirectoryWatcher {
  private final Map<Path, Stream> streams = new HashMap<>();
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final Lock lock = new Lock();
  private final double latency;
  private final Executor executor;
  private final Flags.Create flags;
  private final FileEventsApi fileEventsApi;
  private static final DefaultOnStreamRemoved DefaultOnStreamRemoved = new DefaultOnStreamRemoved();

  private static class Stream {
    public final int id;
    public final int maxDepth;
    public final Path path;
    private final int compDepth;

    Stream(final Path path, final int id, final int maxDepth) {
      this.path = path;
      this.id = id;
      this.maxDepth = maxDepth;
      compDepth = maxDepth == Integer.MAX_VALUE ? maxDepth : maxDepth + 1;
    }

    public boolean accept(final Path child) {
      final int depth =
          child.startsWith(path) ? path.relativize(child).getNameCount() : Integer.MAX_VALUE;
      return depth <= compDepth;
    }

    @Override
    public String toString() {
      return "Stream(" + path + ", " + maxDepth + ")";
    }
  }

  /**
   * Registers a path
   *
   * @param path The directory to watch for file events
   * @param maxDepth The maximum number of subdirectory levels to visit
   * @return true if the path is a directory and has not previously been registered
   */
  @Override
  public boolean register(final Path path, final int maxDepth) {
    return register(path, flags, maxDepth);
  }

  /**
   * Registers with additional flags
   *
   * @param path The directory to watch for file events
   * @param flags The flags {@link com.swoval.files.apple.Flags.Create} to set for the directory
   * @param maxDepth The maximum number of subdirectory levels to visit
   * @return true if the path is a directory and has not previously been registered
   */
  public boolean register(final Path path, final Flags.Create flags, final int maxDepth) {
    boolean result = true;
    Path realPath = null;
    try {
      realPath = path.toRealPath();
    } catch (IOException e) {
      result = false;
    }
    if (lock.lock()) {
      try {
        if (result && Files.isDirectory(realPath) && !realPath.equals(realPath.getRoot())) {
          final Entry<Path, Stream> entry = find(realPath);
          if (entry == null) {
            try {
              int id = fileEventsApi.createStream(realPath.toString(), latency, flags.getValue());
              if (id == -1) {
                result = false;
                System.err.println("Error watching " + realPath + ".");
              } else {
                final int newMaxDepth = removeRedundantStreams(realPath, maxDepth);
                streams.put(realPath, new Stream(realPath, id, newMaxDepth));
              }
            } catch (ClosedFileEventsApiException e) {
              close();
              result = false;
            }
          } else {
            final Path key = entry.getKey();
            final Stream stream = entry.getValue();
            final int depth = key.equals(realPath) ? 0 : key.relativize(realPath).getNameCount();
            final int newMaxDepth = removeRedundantStreams(key, maxDepth);
            if (newMaxDepth != stream.maxDepth && stream.maxDepth >= depth) {
              streams.put(key, new Stream(key, stream.id, newMaxDepth));
            } else {
              streams.put(realPath, new Stream(realPath, -1, maxDepth));
            }
          }
        }
      } finally {
        lock.unlock();
      }
    } else {
      result = false;
    }
    return result;
  }

  private int removeRedundantStreams(final Path path, final int maxDepth) {
    final List<Path> toRemove = new ArrayList<>();
    final Iterator<Entry<Path, Stream>> it = streams.entrySet().iterator();
    int newMaxDepth = maxDepth;
    while (it.hasNext()) {
      final Entry<Path, Stream> e = it.next();
      final Path key = e.getKey();
      if (key.startsWith(path) && !key.equals(path)) {
        final Stream stream = e.getValue();
        final int depth = key.equals(path) ? 0 : key.relativize(path).getNameCount();
        if (depth <= newMaxDepth) {
          toRemove.add(stream.path);
          if (stream.maxDepth > newMaxDepth - depth) {
            int diff = stream.maxDepth - newMaxDepth + depth;
            newMaxDepth =
                newMaxDepth < Integer.MAX_VALUE - diff ? newMaxDepth + diff : Integer.MAX_VALUE;
          }
        }
      }
    }
    final Iterator<Path> pathIterator = toRemove.iterator();
    while (pathIterator.hasNext()) {
      unregister(pathIterator.next());
    }
    return newMaxDepth;
  }

  /**
   * Unregisters a path
   *
   * @param path The directory to remove from monitoring
   */
  @Override
  public void unregister(Path path) {
    if (lock.lock()) {
      try {
        if (!closed.get()) {
          final Stream stream = streams.remove(path);
          if (stream != null && stream.id != -1) {
            executor.run(
                new Runnable() {
                  @Override
                  public void run() {
                    fileEventsApi.stopStream(stream.id);
                  }
                });
          }
        }
      } finally {
        lock.unlock();
      }
    }
  }

  /** Closes the FileEventsApi and shuts down the {@code executor}. */
  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      super.close();
      if (lock.lock()) {
        try {
          streams.clear();
        } finally {
          lock.unlock();
        }
        fileEventsApi.close();
        executor.close();
      }
    }
  }

  /**
   * Callback to run when the native file events api removes a redundant stream. This can occur when
   * a child directory is registered with the watcher before the parent.
   */
  public interface OnStreamRemoved {
    void apply(String stream);
  }

  static class DefaultOnStreamRemoved implements OnStreamRemoved {
    DefaultOnStreamRemoved() {}

    @Override
    public void apply(String stream) {}
  }

  public AppleDirectoryWatcher(
      final double latency, final Flags.Create flags, final DirectoryWatcher.Callback onFileEvent)
      throws InterruptedException {
    this(
        latency,
        flags,
        Executor.make("com.swoval.files.apple.AppleDirectoryWatcher.executorThread"),
        onFileEvent,
        DefaultOnStreamRemoved);
  }

  public AppleDirectoryWatcher(
      final double latency,
      final Flags.Create flags,
      final Executor executor,
      final DirectoryWatcher.Callback onFileEvent)
      throws InterruptedException {
    this(latency, flags, executor, onFileEvent, DefaultOnStreamRemoved);
  }

  /**
   * Creates a new AppleDirectoryWatcher which is a wrapper around {@link FileEventsApi}, which in
   * turn is a native wrapper around <a
   * href="https://developer.apple.com/library/content/documentation/Darwin/Conceptual/FSEvents_ProgGuide/Introduction/Introduction.html#//apple_ref/doc/uid/TP40005289-CH1-SW1">
   * Apple File System Events</a>
   *
   * @param latency specified in fractional seconds
   * @param flags Native flags
   * @param executor Executor to run callbacks on
   * @param onFileEvent Callback to run on file events
   * @param onStreamRemoved Callback to run when a redundant stream is removed from the underlying
   *     native file events implementation
   * @throws InterruptedException if the native file events implementation is interrupted during
   *     initialization
   */
  public AppleDirectoryWatcher(
      final double latency,
      final Flags.Create flags,
      final Executor executor,
      final DirectoryWatcher.Callback onFileEvent,
      final OnStreamRemoved onStreamRemoved)
      throws InterruptedException {
    this.latency = latency;
    this.flags = flags;
    this.executor = executor;
    fileEventsApi =
        FileEventsApi.apply(
            new Consumer<FileEvent>() {
              @Override
              public void accept(final FileEvent fileEvent) {
                executor.run(
                    new Runnable() {
                      @Override
                      public void run() {
                        final String fileName = fileEvent.fileName;
                        final Path path = Paths.get(fileName);
                        final Iterator<Entry<Path, Stream>> it = streams.entrySet().iterator();
                        boolean validKey = false;
                        while (it.hasNext() && !validKey) {
                          final Entry<Path, Stream> entry = it.next();
                          final Path key = entry.getKey();
                          final Stream stream = entry.getValue();
                          validKey = path.equals(key) || stream.accept(path);
                        }
                        if (validKey) {
                          DirectoryWatcher.Event event;
                          if (fileEvent.itemIsFile()) {
                            if (fileEvent.isNewFile() && Files.exists(path)) {
                              event = new DirectoryWatcher.Event(path, Create);
                            } else if (fileEvent.isRemoved() || !Files.exists(path)) {
                              event = new DirectoryWatcher.Event(path, Delete);
                            } else {
                              event = new DirectoryWatcher.Event(path, Modify);
                            }
                          } else if (Files.exists(path)) {
                            event = new DirectoryWatcher.Event(path, Modify);
                          } else {
                            event = new DirectoryWatcher.Event(path, Delete);
                          }
                          onFileEvent.apply(event);
                        }
                      }
                    });
              }
            },
            new Consumer<String>() {
              @Override
              public void accept(final String stream) {
                executor.run(
                    new Runnable() {
                      @Override
                      public void run() {
                        if (lock.lock()) {
                          try {
                            streams.remove(Paths.get(stream));
                          } finally {
                            lock.unlock();
                          }
                          onStreamRemoved.apply(stream);
                        }
                      }
                    });
              }
            });
  }

  private Entry<Path, Stream> find(final Path path) {
    final Iterator<Entry<Path, Stream>> it = streams.entrySet().iterator();
    Entry<Path, Stream> result = null;
    while (result == null && it.hasNext()) {
      final Entry<Path, Stream> entry = it.next();
      if (path.startsWith(entry.getKey())) {
        result = entry;
      }
    }
    return result;
  }
}