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

import scala.Array.canBuildFrom
import scala.annotation.implicitNotFound
import scala.collection.JavaConversions._
import scala.collection.mutable.HashMap
import scala.collection.mutable.HashSet
import scala.collection.mutable.SynchronizedMap
import scala.collection.mutable.SynchronizedSet

import org.digimead.digi.ctrl.lib.aop.Loggable
import org.digimead.digi.ctrl.lib.base.AppComponent
import org.digimead.digi.ctrl.lib.dialog.FailedMarket
import org.digimead.digi.ctrl.lib.dialog.InstallControl
import org.digimead.digi.ctrl.lib.dialog.Report
import org.digimead.digi.ctrl.lib.log.Logging
import org.digimead.digi.ctrl.lib.message.Dispatcher
import org.digimead.digi.ctrl.lib.util.Android

import android.accounts.AccountManager
import android.app.Activity
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView

/*
 * trait hasn't ability to use @Loggable
 */
trait DActivity extends AnyBase with Logging {
  implicit val dispatcher: Dispatcher
  val onPrepareDialogStash = new HashMap[Int, Any]() with SynchronizedMap[Int, Any]
  /*
   * sometimes in life cycle onCreate stage invoked without onDestroy stage
   */
  def onCreateExt(activity: Activity with DActivity): Unit = {
    log.trace("Activity::onCreateExt")
    onCreateBase(activity)
    AppComponent.Inner.lockRotationCounter.set(0)
    AppComponent.Inner.resetDialogSafe
    // sometimes onDestroy skipped, there is no harm to drop garbage
    DActivity.registeredReceivers.clear
    DActivity.activeReceivers.clear
  }
  def onStartExt(activity: Activity with DActivity, origRegisterReceiver: (BroadcastReceiver, IntentFilter, String, Handler) => Intent) = {
    log.trace("Activity::onStartExt")
    onStartBase(activity)
    DActivity.registeredReceivers.foreach(t => {
      if (DActivity.activeReceivers(t._1)) {
        log.trace("onResumeExt skip registerReceiver " + t._1)
      } else {
        log.trace("onResumeExt registerReceiver " + t._1)
        DActivity.activeReceivers(t._1) = true
        origRegisterReceiver(t._1, t._2._1, t._2._2, t._2._3)
      }
    })
  }
  def onResumeExt(activity: Activity with DActivity) = {
    log.trace("Activity::onResumeExt")
    onResumeBase(activity)
    Report.searchAndSubmitLock.set(false)
    Report.submitInProgressLock.set(false)
    AppComponent.Inner.lockRotationCounter.set(0)
    AppComponent.Inner.resetDialogSafe
  }
  def onPauseExt(activity: Activity with DActivity) {
    log.trace("Activity::onPauseExt")
    AppComponent.Inner.lockRotationCounter.set(0)
    AppComponent.Inner.disableSafeDialogs
    Android.enableRotation(activity)
    onPauseBase(activity)
  }
  def onStopExt(activity: Activity with DActivity, shutdownIfActive: Boolean, origUnregisterReceiver: (BroadcastReceiver) => Unit) = {
    log.trace("Activity::onStopExt")
    DActivity.registeredReceivers.foreach(t => {
      if (DActivity.activeReceivers(t._1)) {
        log.trace("onPauseExt unregisterReceiver " + t._1)
        DActivity.activeReceivers(t._1) = false
        try {
          origUnregisterReceiver(t._1)
        } catch {
          case e =>
            log.error(e.getMessage)
        }
      } else {
        log.trace("onPauseExt skip unregisterReceiver " + t._1)
      }
    })
    onStopBase(activity, shutdownIfActive)
  }
  /*
   * sometimes in life cycle onCreate stage invoked without onDestroy stage
   * in fact AppComponent.deinit is a sporadic event
   */
  def onDestroyExt(activity: Activity with DActivity) = {
    log.trace("Activity::onDestroyExt")
    DActivity.registeredReceivers.clear
    DActivity.activeReceivers.clear
    onDestroyBase(activity)
  }
  def onCreateDialogExt(activity: Activity with DActivity, id: Int, args: Bundle): Dialog = {
    log.trace("Activity::onCreateDialogExt")
    id match {
      case id if id == Report.getId(activity) =>
        log.debug("show Report dialog")
        Report.createDialog(activity)
      case id if id == InstallControl.getId(activity) =>
        InstallControl.createDialog(activity)
      case id if id == FailedMarket.getId(activity) =>
        FailedMarket.createDialog(activity)
      case id =>
        null
    }
  }
  def onPrepareDialogExt(activity: Activity with DActivity, id: Int, dialog: Dialog, args: Bundle): Boolean = {
    log.trace("Activity::onPrepareDialogExt")
    id match {
      case id if id == Report.getId(activity) =>
        log.debug("prepare Report dialog with id " + id)
        AppComponent.Inner.setDialogSafe(Some(Report.getClass.getName), Some(dialog))
        val summary = dialog.findViewById(android.R.id.text1).asInstanceOf[TextView]
        onPrepareDialogStash.remove(id) match {
          case Some(stash) =>
            summary.getRootView.post(new Runnable { def run = summary.setText(stash.asInstanceOf[String]) })
          case None =>
            summary.getRootView.post(new Runnable { def run = summary.setText("") })
        }
        val spinner = dialog.findViewById(android.R.id.text2).asInstanceOf[Spinner]
        val emails = AccountManager.get(activity).getAccounts().map(_.name).filter(_.contains('@')).toList :+ "none"
        val adapter = new ArrayAdapter(activity, android.R.layout.simple_spinner_item, emails)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.setAdapter(adapter)
        true
      case id if id == InstallControl.getId(activity) =>
        log.debug("prepare InstallControl dialog with id " + id)
        AppComponent.Inner.setDialogSafe(Some(InstallControl.getClass.getName), Some(dialog))
        true
      case id if id == FailedMarket.getId(activity) =>
        log.debug("prepare FailedMarket dialog with id " + id)
        AppComponent.Inner.setDialogSafe(Some(FailedMarket.getClass.getName), Some(dialog))
        true
      case _ =>
        false
    }
  }
  def registerReceiverExt(orig: () => Intent, receiver: BroadcastReceiver, filter: IntentFilter): Intent = try {
    log.trace("Activity::registerExt " + receiver)
    assert(!DActivity.registeredReceivers.isDefinedAt(receiver),
      { "receiver " + receiver + " already registered" })
    DActivity.registeredReceivers(receiver) = (filter, null, null)
    DActivity.activeReceivers(receiver) = true
    orig()
  } catch {
    case e =>
      log.error(e.getMessage, e)
      null
  }
  def registerReceiverExt(orig: () => Intent, receiver: BroadcastReceiver, filter: IntentFilter, broadcastPermission: String, scheduler: Handler): Intent = try {
    log.trace("Activity::registerExt " + receiver)
    assert(!DActivity.registeredReceivers.isDefinedAt(receiver),
      { "receiver " + receiver + " already registered" })
    DActivity.registeredReceivers(receiver) = (filter, broadcastPermission, scheduler)
    DActivity.activeReceivers(receiver) = true
    orig()
  } catch {
    case e =>
      log.error(e.getMessage, e)
      null
  }
  // orig = unregisterReceiverExt((super.un), receiver)
  def unregisterReceiverExt(orig: () => Unit, receiver: BroadcastReceiver) {
    log.trace("Activity::unregisterReceiverExt " + receiver)
    DActivity.registeredReceivers.remove(receiver)
    if (DActivity.activeReceivers.remove(receiver))
      orig()
  }
}

object DActivity extends Logging {
  /** BroadcastReceiver that recorded at registerReceiver/unregisterReceiver */
  private val registeredReceivers = new HashMap[BroadcastReceiver, (IntentFilter, String, Handler)] with SynchronizedMap[BroadcastReceiver, (IntentFilter, String, Handler)]
  private val activeReceivers = new HashSet[BroadcastReceiver] with SynchronizedSet[BroadcastReceiver]
  log.debug("alive")
}
