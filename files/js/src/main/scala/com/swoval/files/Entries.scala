// Do not edit this file manually. It is autogenerated.

package com.swoval.files

import com.swoval.files.LinkOption.NOFOLLOW_LINKS
import com.swoval.functional.Either.leftProjection
import com.swoval.files.FileTreeDataViews.Converter
import com.swoval.files.FileTreeDataViews.Entry
import com.swoval.functional.Either
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import scala.beans.{ BeanProperty, BooleanBeanProperty }

object Entries {

  val DIRECTORY: Int = 1

  val FILE: Int = 2

  val LINK: Int = 4

  val UNKNOWN: Int = 8

  val NONEXISTENT: Int = 16

  def get[T](typedPath: TypedPath, converter: Converter[T], converterPath: TypedPath): Entry[T] =
    try new ValidEntry(typedPath, converter.apply(converterPath))
    catch {
      case e: IOException => new InvalidEntry(typedPath, e)

    }

  def setExists[T](entry: Entry[T], exists: Boolean): Entry[T] = {
    val typedPath: TypedPath = entry.getTypedPath
    val kind: Int = (if (exists) 0 else NONEXISTENT) | (if (typedPath.isFile)
                                                          FILE
                                                        else 0) |
      (if (typedPath.isDirectory) DIRECTORY else 0) |
      (if (typedPath.isSymbolicLink) LINK else 0)
    if (entry.getValue.isLeft) {
      new InvalidEntry(typedPath, Either.leftProjection(entry.getValue).getValue)
    } else {
      new ValidEntry(typedPath, entry.getValue.get)
    }
  }

  def resolve[T](path: Path, entry: Entry[T]): Entry[T] = {
    val value: Either[IOException, T] = entry.getValue
    val kind: Int = getKind(entry)
    val typedPath: TypedPath =
      TypedPaths.get(path.resolve(entry.getTypedPath.getPath), kind)
    if (value.isRight) new ValidEntry(typedPath, value.get)
    else new InvalidEntry[T](typedPath, leftProjection(value).getValue)
  }

  private def getKindFromAttrs(path: Path, attrs: BasicFileAttributes): Int =
    if (attrs.isSymbolicLink)
      LINK | (if (Files.isDirectory(path)) DIRECTORY else FILE)
    else if (attrs.isDirectory) DIRECTORY
    else FILE

  /**
   * Compute the underlying file type for the path.
   *
   * @param path The path whose type is to be determined.
   * @return The file type of the path
   */
  def getKind(path: Path): Int = {
    val attrs: BasicFileAttributes =
      NioWrappers.readAttributes(path, NOFOLLOW_LINKS)
    getKindFromAttrs(path, attrs)
  }

  private def getKind(entry: Entry[_]): Int = {
    val typedPath: TypedPath = entry.getTypedPath
    (if (typedPath.isSymbolicLink) LINK else 0) | (if (typedPath.isDirectory)
                                                     DIRECTORY
                                                   else 0) |
      (if (typedPath.isFile) FILE else 0)
  }

  private abstract class EntryImpl[T](@BeanProperty val typedPath: TypedPath) extends Entry[T] {

    override def hashCode(): Int = {
      val value: Int =
        com.swoval.functional.Either.getOrElse(getValue, 0).hashCode
      typedPath.hashCode ^ value
    }

    override def equals(other: Any): Boolean =
      other.isInstanceOf[Entry[_]] &&
        other
          .asInstanceOf[Entry[_]]
          .getTypedPath
          .getPath == getTypedPath.getPath &&
        getValue == other.asInstanceOf[Entry[_]].getValue

    override def compareTo(that: Entry[T]): Int =
      this.getTypedPath.getPath.compareTo(that.getTypedPath.getPath)

  }

  private class ValidEntry[T](typedPath: TypedPath, private val value: T)
      extends EntryImpl[T](typedPath) {

    override def getValue(): Either[IOException, T] = Either.right(value)

    override def toString(): String =
      "ValidEntry(" + getTypedPath.getPath + ", " + value +
        ")"

  }

  private class InvalidEntry[T](typedPath: TypedPath, private val exception: IOException)
      extends EntryImpl[T](typedPath) {

    override def getValue(): Either[IOException, T] = Either.left(exception)

    override def toString(): String =
      "InvalidEntry(" + getTypedPath.getPath + ", " + exception +
        ")"

  }

}
