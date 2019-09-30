// Do not edit this file manually. It is autogenerated.

package com.swoval.logging

import java.io.IOException
import java.io.OutputStream
import scala.beans.{ BeanProperty, BooleanBeanProperty }

object Loggers {

  private var global: Logger = null

  private val lock: AnyRef = new AnyRef()

  object Level {

    def fromString(string: String): Level = string.toLowerCase() match {
      case "verbose" => VERBOSE
      case "debug"   => DEBUG
      case "info"    => INFO
      case "warn"    => WARN
      case _         => ERROR

    }

    val DEBUG: Level = new Level() {
      override def compareTo(that: Level): Int =
        if (that == VERBOSE) 1 else if (that == DEBUG) 0 else -1

      override def toString(): String = "DEBUG"
    }

    val INFO: Level = new Level() {
      override def compareTo(that: Level): Int =
        if (that == INFO) 0
        else if ((that == DEBUG || that == VERBOSE)) 1
        else -1

      override def toString(): String = "INFO"
    }

    val WARN: Level = new Level() {
      override def compareTo(that: Level): Int =
        if (that == WARN) 0
        else if ((that == DEBUG || that == INFO || that == VERBOSE)) 1
        else -1

      override def toString(): String = "WARN"
    }

    val ERROR: Level = new Level() {
      override def compareTo(that: Level): Int = if (that == ERROR) 0 else 1

      override def toString(): String = "ERROR"
    }

    val VERBOSE: Level = new Level() {
      override def compareTo(that: Level): Int = if (that == VERBOSE) 0 else -1

      override def toString(): String = "VERBOSE"
    }

  }

  abstract class Level() extends Comparable[Level]

  private class LoggerImpl(
      @BeanProperty val level: Level,
      private val infoStream: OutputStream,
      private val errorStream: OutputStream,
      private val errorLevel: Level
  ) extends Logger {

    override def verbose(message: String): Unit = {
      val outputStream: OutputStream =
        if (errorLevel == Level.VERBOSE) errorStream else infoStream
      try {
        outputStream.write(message.getBytes)
        outputStream.write('\n')
      } catch {
        case e: IOException => {}

      }
    }

    override def debug(message: String): Unit = {
      val outputStream: OutputStream =
        if ((errorLevel.compareTo(Level.DEBUG) < 0)) errorStream
        else infoStream
      try {
        outputStream.write(message.getBytes)
        outputStream.write('\n')
      } catch {
        case e: IOException => {}

      }
    }

    override def info(message: String): Unit = {
      val outputStream: OutputStream =
        if ((errorLevel.compareTo(Level.INFO) < 0)) errorStream else infoStream
      try {
        outputStream.write(message.getBytes)
        outputStream.write('\n')
      } catch {
        case e: IOException => {}

      }
    }

    override def warn(message: String): Unit = {
      val outputStream: OutputStream =
        if ((errorLevel.compareTo(Level.WARN) < 0)) errorStream else infoStream
      try {
        outputStream.write(message.getBytes)
        outputStream.write('\n')
      } catch {
        case e: IOException => {}

      }
    }

    override def error(message: String): Unit = {
      val outputStream: OutputStream =
        if ((errorLevel.compareTo(Level.ERROR) < 0)) errorStream
        else infoStream
      try {
        outputStream.write(message.getBytes)
        outputStream.write('\n')
      } catch {
        case e: IOException => {}

      }
    }

  }

  def getLogger(): Logger = lock.synchronized {
    if (global == null) {
      val level: Level =
        Level.fromString(System.getProperty("swoval.log.level", "error"))
      Loggers.global = DefaultLogger.get(System.getProperty("swoval.logger"))
      if (Loggers.global == null) {
        Loggers.global = new LoggerImpl(level, System.out, System.err, Level.ERROR)
      }
    }
    Loggers.global
  }

  def shouldLog(logger: Logger, level: Level): Boolean =
    logger.getLevel.compareTo(level) <= 0

}
