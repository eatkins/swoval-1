// Do not edit this file manually. It is autogenerated.

package com.swoval.files

import java.util.ArrayList
import java.util.Collections
import java.util.HashMap
import java.util.Iterator
import java.util.List
import java.util.Map
import java.util.Map.Entry
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

class Lockable(private val reentrantLock: ReentrantLock) {

  def lock(): Boolean =
    try reentrantLock.tryLock(1, TimeUnit.MINUTES)
    catch {
      case e: InterruptedException => false

    }

  def unlock(): Unit = {
    reentrantLock.unlock()
  }

}

class LockableMap[K, V <: AutoCloseable](private val map: Map[K, V], reentrantLock: ReentrantLock)
    extends Lockable(reentrantLock) {

  def this() = this(new HashMap[K, V](), new ReentrantLock())

  def clear(): Unit = {
    if (lock()) {
      try {
        val values: Iterator[V] = new ArrayList(map.values).iterator()
        while (values.hasNext) try {
          val v: V = values.next()
          v.close()
        } catch {
          case e: Exception => {}

        }
        map.clear()
      } finally unlock()
    }
  }

  def iterator(): Iterator[Entry[K, V]] =
    if (lock()) {
      try new ArrayList(map.entrySet()).iterator()
      finally unlock()
    } else {
      Collections.emptyListIterator()
    }

  def keys(): List[K] =
    if (lock()) {
      try new ArrayList(map.keySet)
      finally unlock()
    } else {
      Collections.emptyList()
    }

  def values(): List[V] =
    if (lock()) {
      try new ArrayList(map.values)
      finally unlock()
    } else {
      Collections.emptyList()
    }

  def get(key: K): V =
    if (lock()) {
      try map.get(key)
      finally unlock()
    } else {
      null.asInstanceOf[V]
    }

  def put(key: K, value: V): V =
    if (lock()) {
      try map.put(key, value)
      finally unlock()
    } else {
      null.asInstanceOf[V]
    }

  def remove(key: K): V =
    if (lock()) {
      try map.remove(key)
      finally unlock()
    } else {
      null.asInstanceOf[V]
    }

  override def toString(): String = "LockableMap(" + map + ")"

}
