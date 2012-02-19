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

package org.digimead.digi.ctrl.lib

import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.OutputStream
import java.net.NetworkInterface

import scala.annotation.tailrec
import scala.collection.JavaConversions._
import scala.collection.immutable.HashMap
import scala.concurrent.Lock

import org.digimead.digi.ctrl.lib.aop.Loggable
import org.digimead.digi.ctrl.lib.aop.Logging
import org.digimead.digi.ctrl.ICtrlComponent

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.drawable.BitmapDrawable
import android.graphics.BitmapFactory
import android.os.IBinder
import android.text.ClipboardManager
import android.util.DisplayMetrics
import android.widget.Toast

object Common extends Logging {
  protected val log = Logging.getLogger(this)
  log.debug("alive")
  @Loggable
  def onCreateDialog(id: Int, activity: Activity) = id match {
    case id if id == dialog.InstallControl.getId(activity) =>
      dialog.InstallControl.createDialog(activity)
    case id if id == dialog.FailedMarket.getId(activity) =>
      dialog.FailedMarket.createDialog(activity)
    case _ =>
      log.error("unknown dialog id " + id)
      null
  }
  @Loggable
  def listInterfaces(): Seq[String] = {
    var interfaces = HashMap[String, Seq[String]]()
    try {
      val nie = NetworkInterface.getNetworkInterfaces()
      while (nie.hasMoreElements) {
        val ni = nie.nextElement
        val name = ni.getName()
        if (name != "lo") {
          interfaces = interfaces.updated(name, Seq())
          val iae = ni.getInetAddresses
          while (iae.hasMoreElements) {
            val ia = iae.nextElement
            val address = ia.getHostAddress
            // skip ipv6
            if (address.matches("""\d+\.\d+\.\d+.\d+""") && !address.endsWith("127.0.0.1"))
              interfaces = interfaces.updated(name, interfaces(name) :+ address)
          }
        }
      }
    } catch {
      case e =>
        // suspect permission error at one of interfaces ;-)
        log.warn("NetworkInterface.getNetworkInterfaces() failed with " + e +
          (if (e.getMessage() != null) " " + e.getMessage))
    }
    // convert hash interface -> address to string interface:address
    interfaces.keys.map(i => {
      if (interfaces(i).isEmpty) Seq(i + ":0.0.0.0") else interfaces(i).map(s => i + ":" + s)
    }).flatten.toSeq
  }
  @Loggable
  def listPreparedFiles(context: Context): Option[Seq[File]] = for {
    inner <- AppActivity.Inner
    appNativePath <- inner.appNativePath
  } yield context.getAssets.list(Common.Constant.apkNativePath).map(name => new File(appNativePath, name)).filter(_.exists)
  @Loggable
  def copyPreparedFilesToClipboard(context: Context) = {
    val files = Common.listPreparedFiles(context).mkString("\n")
    val text = if (files.nonEmpty) {
      val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE).asInstanceOf[ClipboardManager]
      // TODO
      clipboard.setText(files)
      Android.getString(context, "notify_n_files_copy_to_clipboard").map(_.format(files.size)).
        getOrElse("Copy files to clipboard")
    } else {
      Android.getString(context, "notify_no_files_copy_to_clipboard").
        getOrElse("There are no files to copy to clipboard")
    }
    Toast.makeText(context, text, Constant.toastTimeout).show()
  }
  @Loggable
  def execChmod(permission: String, file: File, recursive: Boolean = false): Boolean = {
    val args = if (recursive)
      Array("/bin/chmod", permission, file.getAbsolutePath)
    else
      Array("/bin/chmod", "-R", permission, file.getAbsolutePath)
    val p = Runtime.getRuntime().exec(args)
    val err = new BufferedReader(new InputStreamReader(p.getErrorStream()))
    p.waitFor()
    val retcode = p.exitValue()
    if (retcode != 0) {
      var error = err.readLine()
      while (error != null) {
        log.error("/bin/chmod error: " + error)
        error = err.readLine()
      }
      false
    } else
      true
  }
  @Loggable
  def doComponentService(componentPackage: String)(f: (ICtrlComponent) => Any) = AppActivity.Context map {
    context =>
      // lock for bindService
      val lock = new Lock
      // service itself
      var service: ICtrlComponent = null
      // service connection
      val connection = new ComponentServiceConnection((_service) => {
        service = _service
        lock.release
      })
      val intent = new Intent(Common.Intent.componentService)
      intent.setPackage(componentPackage)
      lock.available = false
      if (context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
        lock.acquire
        try {
          f(service)
        } finally {
          service = null
          context.unbindService(connection)
        }
      } else {
        log.error("service bind failed")
      }
  }
  @Loggable(result = false)
  def serializeToList(o: java.io.Serializable): java.util.List[Byte] =
    serializeToArray(o).toList
  @Loggable(result = false)
  def serializeToArray(o: java.io.Serializable): Array[Byte] = {
    val baos = new ByteArrayOutputStream()
    val oos = new ObjectOutputStream(baos)
    oos.writeObject(o)
    oos.close()
    baos.toByteArray()
  }
  @Loggable(result = false)
  def deserializeFromList(s: java.util.List[Byte]): Option[Object] =
    deserializeFromArray(s.toList.toArray)
  @Loggable(result = false)
  def deserializeFromArray(s: Array[Byte]): Option[Object] = try {
    val ois = new ObjectInputStream(new ByteArrayInputStream(s.toList.toArray))
    val o = ois.readObject()
    ois.close()
    Some(o)
  } catch {
    case e =>
      log.error(e.getMessage())
      None
  }
  /**
   * Write to a stream
   *
   * @param in
   * @param out
   */
  def writeToStream(in: InputStream, out: OutputStream) {
    val buffer = new Array[Byte](8192)
    @tailrec
    def next(exit: Boolean = false) {
      if (exit) {
        in.close()
        out.close()
        return
      }
      val read = in.read(buffer)
      if (read > 0)
        out.write(buffer, 0, read)
      next(read == -1)
    }
    next()
  }
  private class ComponentServiceConnection(f: (ICtrlComponent) => Any) extends ServiceConnection() {
    def onServiceConnected(className: ComponentName, iservice: IBinder) {
      log.debug("connected to " + className + " service")
      f(ICtrlComponent.Stub.asInterface(iservice))
    }
    def onServiceDisconnected(className: ComponentName) {
      log.debug("unexpectedly disconnected from " + className + " service")
    }
  }
  case class ComponentInfo(val id: String,
    val name: String,
    val version: String, // Version Not Serializable
    val description: String,
    val project: String, // Uri Not Serializable
    val thumb: Option[Array[Byte]], // Bitmap Not Serializable
    val copyright: String,
    val license: String,
    val email: String,
    val iconHDPI: Array[Byte], // Bitmap Not Serializable
    val iconLDPI: Array[Byte], // Bitmap Not Serializable
    val iconMDPI: Array[Byte], // Bitmap Not Serializable
    val iconXHDPI: Array[Byte], // Bitmap Not Serializable
    val market: String,
    val componentPackage: String) extends java.io.Serializable {
    def getDescription(): String = {
      Seq("Name: " + name,
        "Version: " + version,
        "Description: " + description,
        "Project: " + project,
        "Market: " + market,
        "License: " + license,
        "E-Mail: " + email,
        "Publisher: " + copyright).mkString("\n")
    }
    def getBitmap(context: Context) = context.getResources.getDisplayMetrics.densityDpi match {
      case DisplayMetrics.DENSITY_LOW =>
        BitmapFactory.decodeByteArray(iconLDPI, 0, iconLDPI.length)
      case DisplayMetrics.DENSITY_MEDIUM =>
        BitmapFactory.decodeByteArray(iconMDPI, 0, iconMDPI.length)
      case DisplayMetrics.DENSITY_HIGH =>
        BitmapFactory.decodeByteArray(iconHDPI, 0, iconHDPI.length)
      case low if low < DisplayMetrics.DENSITY_LOW =>
        BitmapFactory.decodeByteArray(iconLDPI, 0, iconLDPI.length)
      case large if large > DisplayMetrics.DENSITY_HIGH =>
        BitmapFactory.decodeByteArray(iconXHDPI, 0, iconXHDPI.length)
    }
    def getDrawable(context: Context) =
      new BitmapDrawable(getBitmap(context))
  }
  class ComponentStatus(val componentPackage: String,
    val serviceStatus: List[ServiceStatus],
    val state: State.Value) extends java.io.Serializable {
  }
  // like ServiceEnvironment, keep it separate
  class ServiceStatus(val id: Int,
    val commandLine: Seq[String],
    val port: Int,
    val env: Seq[String] = Seq(),
    val state: State.Value) extends java.io.Serializable {
    assert(id >= 0 && id <= 0xFFFF)
    assert(port > 0 && id <= 0xFFFF)
    assert(commandLine.nonEmpty)
  }
  // like ServiceStatus, keep it separate
  class ServiceEnvironment(val id: Int,
    val commandLine: Seq[String],
    val port: Int,
    val env: Seq[String] = Seq(),
    val state: State.Value) extends java.io.Serializable {
    assert(id >= 0 && id <= 0xFFFF)
    assert(port > 0 && id <= 0xFFFF)
    assert(commandLine.nonEmpty)
  }
  object Timeout {
    val fast = 5000
    val normal = 10000
    val long = 60000
  }
  object Content {
    val commandline = "commandline"
    val port = "port"
    val environment = "environment"
  }
  object Constant {
    final val toastTimeout = 5
    final val marketPackage = "org.digimead.digi.ctrl"
    final val prefix = "org.digimead.digi.ctrl."
    final val serviceContentProviderSuffix = ".data"
    final val apkNativePath = "armeabi"
  }
  object State extends Enumeration {
    val Initializing, Broken, Passive, Busy, Active, Unknown = Value
  }
  object Preference {
    val main = getClass.getPackage.getName + "@main" // shared preferences name
    val filter = getClass.getPackage.getName + "@filter" // shared preferences name    
  }
  object Intent {
    val update = Constant.prefix + "update"
    val connection = Constant.prefix + "connection"
    val hostActivity = Constant.prefix + "host.activity"
    val hostService = Constant.prefix + "host.service"
    val componentActivity = Constant.prefix + "component.activity"
    val componentService = Constant.prefix + "component.service"
  }
  object Option extends Enumeration {
    // TODO rewrite with nameMap = LongMap(id) -> names and descriptionMap SoftReference
    val CachePeriod = Value("cache_period", "cache_period", "cache_period")
    val CacheFolder = Value("cache_dir", "cache_dir", "cache_dir")
    val CacheClass = Value("cache_class", "cache_class", "cache_class")
    val CommConfirmation = Value("comm_confirmation", "comm_confirmation_name", "comm_confirmation_description")
    val CommWriteLog = Value("comm_writelog", "comm_writelog_name", "comm_writelog_description")
    val AsRoot = Value("asroot", "service_asroot_name", "service_asroot_description")
    val Running = Value("running", "service_running_name", "service_running_description")
    val OnBoot = Value("onboot", "service_onboot_name", "service_onboot_description")
    class OptVal(val res: String, val name: String, val description: String) extends Val(nextId, name) {
      def name(context: Context) = Android.getString(context, res)
      def description(context: Context) = Android.getString(context, res)
    }
    protected final def Value(id: String, name: String, description: String): OptVal =
      new OptVal(id, name, description)
  }
}
