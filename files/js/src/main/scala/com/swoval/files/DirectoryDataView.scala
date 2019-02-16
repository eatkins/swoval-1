// Do not edit this file manually. It is autogenerated.

package com.swoval.files

import com.swoval.files.FileTreeDataViews.Entry
import com.swoval.functional.Filter
import java.io.IOException
import java.nio.file.Path
import java.util.List

trait DirectoryDataView[T <: AnyRef] extends FileTreeDataView[T] with DirectoryView {

  /**
   * Returns the cache entry associated with the directory returned by [[DirectoryView.getTypedPath]] }.
   *
   * @return the cache entry.
   */
  def getEntry(): Entry[T]

  /**
   * List all of the files for the {@code path</code> that are accepted by the <code>filter}.
   *
   * @param maxDepth the maximum depth of subdirectories to return
   * @param filter include only paths accepted by this
   * @return a List of Entry instances accepted by the filter. The list will be empty if the path is
   *     not a subdirectory of this CachedDirectory or if it is a subdirectory, but the
   *     CachedDirectory was created without the recursive flag.
   */
  def list(maxDepth: Int, filter: Filter[_ >: Entry[T]]): List[Entry[T]]

  /**
   * List all of the files for the {@code path}, returning only those files that are accepted by the
   * provided filter.
   *
   * @param maxDepth the maximum depth of subdirectories to query
   * @param filter include only paths accepted by the filter
   * @return a List of [[java.nio.file.Path]] instances accepted by the filter.
   */
  def list(maxDepth: Int, filter: Filter[_ >: TypedPath]): List[TypedPath]

  /**
   * List all of the files for the {@code path</code> that are accepted by the <code>filter}.
   *
   * @param path the path to list. If this is a file, returns a list containing the Entry for the
   *     file or an empty list if the file is not monitored by the path.
   * @param maxDepth the maximum depth of subdirectories to return
   * @param filter include only paths accepted by this
   * @return a List of Entry instances accepted by the filter. The list will be empty if the path is
   *     not a subdirectory of this CachedDirectory or if it is a subdirectory, but the
   *     CachedDirectory was created without the recursive flag.
   */
  def list(path: Path, maxDepth: Int, filter: Filter[_ >: Entry[T]]): List[Entry[T]]

  /**
   * List all of the files for the {@code path}, returning only those files that are accepted by the
   * provided filter.
   *
   * @param path the root path to list
   * @param maxDepth the maximum depth of subdirectories to query
   * @param filter include only paths accepted by the filter
   * @return a List of [[java.nio.file.Path]] instances accepted by the filter.
   */
  def list(path: Path, maxDepth: Int, filter: Filter[_ >: TypedPath]): List[TypedPath]

}
