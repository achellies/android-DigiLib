/**
 * Copyright (c) 2012 Alexey Aksenov ezh@ezh.msk.ru
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.digimead.digi.ctrl.lib.log

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

import org.digimead.digi.ctrl.lib.aop.Loggable
import org.digimead.digi.ctrl.lib.base.Report
import org.digimead.digi.ctrl.lib.util.Android
import org.digimead.digi.ctrl.lib.AnyBase

import android.content.Context

object FileLogger extends Logger with Logging {
  private[lib] var file: Option[File] = None
  private[lib] var output: Option[BufferedWriter] = None
  private val fileLimit = 102400 // 100kB
  private val checkEveryNLines = 1000
  private var counter = 0
  protected var f = (records: Array[Logging.Record]) => synchronized {
    // rotate
    for {
      output <- output
      file <- file
    } {
      counter += records.size
      if (counter > checkEveryNLines) {
        counter = 0
        if (file.length > fileLimit)
          openLogFile()
      }
    }
    // write
    output.foreach {
      output =>
        output.write(records.map(r => {
          r.toString() +
            r.throwable.map(t => try {
              "\n" + t.getStackTraceString
            } catch {
              case e =>
                "stack trace \"" + t.getMessage + "\" unaviable "
            }).getOrElse("")
        }).mkString("\n"))
        output.newLine
        output.flush
    }
  }
  @Loggable
  override def init(context: Context) = synchronized {
    openLogFile()
    output.foreach(_.flush)
  }
  override def deinit() = synchronized {
    try {
      // close output if any
      output.foreach(_.close)
      output = None
      file = None
    } catch {
      case e =>
        log.error(e.getMessage, e)
    }
  }
  override def flush() = synchronized {
    try { output.foreach(_.flush) } catch { case e => log.error(e.getMessage, e) }
  }
  private def getLogFileName() =
    Report.reportPrefix + "." + Report.logFilePrefix + Report.logFileExtension
  private def openLogFile() = try {
    deinit
    // open new
    file = AnyBase.info.get.flatMap(info => {
      val file = new File(info.reportPathInternal, getLogFileName)
      if (file.exists) {
        log.warn("log file " + file + " already exists")
        Some(file)
      } else if (file.createNewFile) {
        log.info("create new log file " + file)
        Some(file)
      } else {
        log.error("unable to create log file " + file)
        None
      }
    })
    log.debug("open new log file " + file)
    output = file.map(f => {
      // write header
      val writer = new FileWriter(f)
      writer.write(AnyBase.info.get.toString + "\n")
      writer.close
      // -rw-r--r--
      f.setReadable(true, false)
      new BufferedWriter(new FileWriter(f))
    })
    Report.compress
  } catch {
    case e =>
      log.error(e.getMessage, e)
  }
}
