package com.swoval.files.apple

import java.nio.file.{ Files => JFiles }

import com.swoval.files.apple.FileEventsApi.Consumer
import com.swoval.files.test.{ CountDownLatch, _ }
import com.swoval.test._
import utest._

object FileEventApiTest extends TestSuite {

  def getFileEventsApi(onFileEvent: FileEvent => Unit, onStreamClosed: String => Unit = _ => {}) =
    FileEventsApi.apply(new Consumer[FileEvent] {
      override def accept(fe: FileEvent): Unit = onFileEvent(fe)
    }, new Consumer[String] {
      override def accept(s: String): Unit = onStreamClosed(s)
    })

  val tests = testOn(MacOS) {
    'register - withTempDirectory { dir =>
      val latch = new CountDownLatch(1)
      val file = dir.resolve("file")
      val api = getFileEventsApi(fe => {
        assert(fe.fileName.startsWith(dir.toString))
        JFiles.deleteIfExists(file)
        latch.countDown()
      })
      api.createStream(dir.toString, 0.05, new Flags.Create().setNoDefer.getValue)
      JFiles.createFile(file)

      latch.waitFor(DEFAULT_TIMEOUT) {}
    }
    'removeStream - withTempDirectory { dir =>
      withTempDirectory(dir) { subdir =>
        val latch = new CountDownLatch(1)
        val api = getFileEventsApi(_ => {}, s => {
          assert(s == subdir.toString)
          latch.countDown()
        })
        api.createStream(subdir.toString, 0.05, new Flags.Create().setNoDefer.getValue)
        api.createStream(dir.toString, 0.05, new Flags.Create().setNoDefer.getValue)
        latch.waitFor(DEFAULT_TIMEOUT) {}
      }
    }
    'close - {
      val api = getFileEventsApi(_ => {})
      api.close()
      api.close()
    }
  }
}