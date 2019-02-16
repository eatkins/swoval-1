package com.swoval.files
package impl

import java.util.concurrent.TimeUnit

import com.swoval.files
import com.swoval.files.test.TestLogger

class Provider(implicit testLogger: TestLogger) extends SwovalProvider {
  override def getFileTreeRepositoryProvider: FileTreeRepositoryProvider =
    Provider.fileTreeRepository(testLogger)
  override def getFileTreeViewProvider: files.FileTreeViewProvider =
    new files.FileTreeViewProvider {
      override def followSymlinks(): _root_.com.swoval.files.FileTreeViews.FollowSymlinks = ???
      override def noFollowSymlinks(): _root_.com.swoval.files.FileTreeViews.NoFollowSymlinks = ???
    }
  override def getPathWatcherProvider: PathWatcherProvider = new PathWatcherProvider {
    override def noFollowSymlinks(): PathWatchers.NoFollowSymlinks[PathWatchers.Event] =
      PathWatcherProviderImpl.get(new DirectoryRegistryImpl, testLogger)

    override def followSymlinks(): PathWatchers.FollowSymlinks[PathWatchers.Event] = ???
    override def polling(pollInterval: Long, timeUnit: TimeUnit): PathWatcher[PathWatchers.Event] =
      new PathWatcherProviderImpl.NoFollowWrapper(
        new PollingPathWatcher(true, pollInterval, timeUnit, testLogger))
  }
}
object Provider {
  def fileTreeRepository(logger: TestLogger): FileTreeRepositoryProvider =
    new FileTreeRepositoryProviderImpl(logger)
}