package com.swoval.files

import com.swoval.concurrent.Lock
import com.swoval.files.Directory.Entry
import com.swoval.files.Directory.Observer
import com.swoval.files.Directory.OnChange
import com.swoval.files.Directory.OnError
import com.swoval.files.Directory.OnUpdate
import java.io.IOException
import java.nio.file.Path
import java.util.Collection
import java.util.HashMap
import java.util.Iterator
import java.util.Map
import java.util.concurrent.atomic.AtomicInteger
import Observers._

private[files] object Observers {

  /**
   * Simple observer that fires the same callback for all regular events and ignores any errors.
   *
   * @param onchange The callback to fire when a file is created/updated/deleted
   * @tparam T The generic type of the [[Directory.Entry]]
   * @return An [[Observer]] instance
   */
  def apply[T](onchange: OnChange[T]): Observer[T] = new Observer[T]() {
    override def onCreate(newEntry: Entry[T]): Unit = {
      onchange.apply(newEntry)
    }

    override def onDelete(oldEntry: Entry[T]): Unit = {
      onchange.apply(oldEntry)
    }

    override def onUpdate(oldEntry: Entry[T], newEntry: Entry[T]): Unit = {
      onchange.apply(newEntry)
    }

    override def onError(path: Path, e: IOException): Unit = {}
  }

  def apply[T](oncreate: OnChange[T],
               onupdate: OnUpdate[T],
               ondelete: OnChange[T],
               onerror: OnError): Observer[T] = new Observer[T]() {
    override def onCreate(newEntry: Entry[T]): Unit = {
      oncreate.apply(newEntry)
    }

    override def onDelete(oldEntry: Entry[T]): Unit = {
      ondelete.apply(oldEntry)
    }

    override def onUpdate(oldEntry: Entry[T], newEntry: Entry[T]): Unit = {
      onupdate.apply(oldEntry, newEntry)
    }

    override def onError(path: Path, ex: IOException): Unit = {
      onerror.apply(path, ex)
    }
  }

}

/**
 * Container class that wraps multiple [[Observer]] and runs the callbacks for each whenever the
 * [[FileCache]] detects an event.
 *
 * @tparam T The data type for the [[FileCache]] to which the observers correspond
 */
private[files] class Observers[T] extends Observer[T] with AutoCloseable {

  private val counter: AtomicInteger = new AtomicInteger(0)

  private val lock: Lock = new Lock()

  private val observers: Map[Integer, Observer[T]] = new HashMap()

  override def onCreate(newEntry: Entry[T]): Unit = {
    var cbs: Collection[Observer[T]] = null
    if (lock.lock()) {
      try cbs = observers.values
      finally lock.unlock()
      val it: Iterator[Observer[T]] = cbs.iterator()
      while (it.hasNext) it.next().onCreate(newEntry)
    }
  }

  override def onDelete(oldEntry: Entry[T]): Unit = {
    var cbs: Collection[Observer[T]] = null
    if (lock.lock()) {
      try cbs = observers.values
      finally lock.unlock()
      val it: Iterator[Observer[T]] = cbs.iterator()
      while (it.hasNext) it.next().onDelete(oldEntry)
    }
  }

  override def onUpdate(oldEntry: Entry[T], newEntry: Entry[T]): Unit = {
    var cbs: Collection[Observer[T]] = null
    if (lock.lock()) {
      try cbs = observers.values
      finally lock.unlock()
      val it: Iterator[Observer[T]] = cbs.iterator()
      while (it.hasNext) it.next().onUpdate(oldEntry, newEntry)
    }
  }

  override def onError(path: Path, exception: IOException): Unit = {
    var cbs: Collection[Observer[T]] = null
    if (lock.lock()) {
      try cbs = observers.values
      finally lock.unlock()
      val it: Iterator[Observer[T]] = cbs.iterator()
      while (it.hasNext) it.next().onError(path, exception)
    }
  }

  def addObserver(observer: Observer[T]): Int = {
    val key: Int = counter.getAndIncrement
    if (lock.lock()) {
      try observers.put(key, observer)
      finally lock.unlock()
    }
    key
  }

  def removeObserver(handle: Int): Unit = {
    if (lock.lock()) {
      try observers.remove(handle)
      finally lock.unlock()
    }
  }

  override def close(): Unit = {
    observers.clear()
  }

}