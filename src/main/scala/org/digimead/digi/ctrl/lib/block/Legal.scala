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

package org.digimead.digi.ctrl.lib.block

import scala.annotation.implicitNotFound

import org.digimead.digi.ctrl.lib.aop.RichLogger.rich2plain
import org.digimead.digi.ctrl.lib.aop.Loggable
import org.digimead.digi.ctrl.lib.aop.Logging
import org.digimead.digi.ctrl.lib.declaration.DMessage.Dispatcher
import org.digimead.digi.ctrl.lib.declaration.DMessage
import org.digimead.digi.ctrl.lib.util.Android

import com.commonsware.cwac.merge.MergeAdapter

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.Html
import android.view.ContextMenu
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView.BufferType
import android.widget.ListView
import android.widget.TextView

class Legal(val context: Activity, items: List[Legal.Item],
  _imageGetter: Html.ImageGetter = null,
  tagHandler: Html.TagHandler = null)(implicit @transient val dispatcher: Dispatcher) extends Block[Legal.Item] with Logging {
  private lazy val imageGetter = _imageGetter match {
    case null => new Block.ImageGetter(context)
    case getter => getter
  }
  private lazy val header = context.getLayoutInflater.inflate(Android.getId(context, "header", "layout"), null).asInstanceOf[TextView]
  private lazy val adapter = new Legal.Adapter(context, items, imageGetter, tagHandler)
  @Loggable
  def appendTo(mergeAdapter: MergeAdapter) = {
    log.debug("append " + getClass.getName + " to MergeAdapter")
    header.setText(Android.getString(context, "block_legal_title").getOrElse("legal"))
    mergeAdapter.addView(header)
    mergeAdapter.addAdapter(adapter)
  }
  @Loggable
  def onListItemClick(l: ListView, v: View, item: Legal.Item) = {
    item match {
      case item: Legal.Item => // show context menu
        log.debug("open context menu for item " + item)
        l.showContextMenuForChild(v)
      case item =>
        log.fatal("unsupported context menu item " + item)
    }
  }
  @Loggable
  override def onCreateContextMenu(menu: ContextMenu, v: View, menuInfo: ContextMenu.ContextMenuInfo, item: Legal.Item) {
    log.debug("create context menu for " + item)
    menu.setHeaderTitle(Android.getString(context, "context_menu").getOrElse("Context Menu"))
    //inner.icon(this).map(menu.setHeaderIcon(_))
    menu.add(Menu.NONE, Android.getId(context, "block_legal_open"), 1,
      Android.getString(context, "block_legal_open").getOrElse("Open license"))
    menu.add(Menu.NONE, Android.getId(context, "block_legal_send"), 1,
      Android.getString(context, "block_legal_send").getOrElse("Send link to ..."))
  }
  @Loggable
  override def onContextItemSelected(menuItem: MenuItem, item: Legal.Item): Boolean = {
    menuItem.getItemId match {
      case id if id == Android.getId(context, "block_legal_open") =>
        log.debug("open link from " + item.uri)
        try {
          val intent = new Intent(Intent.ACTION_VIEW, Uri.parse(item.uri))
          intent.addCategory(Intent.CATEGORY_BROWSABLE)
          context.startActivity(intent)
          true
        } catch {
          case e =>
            DMessage.IAmYell("Unable to open license link " + item.uri, e)
            false
        }
      case id if id == Android.getId(context, "block_legal_send") =>
        log.debug("send link to " + item.uri)
        try {
          val intent = new Intent("Intent.ACTION_SEND")
          intent.setType("text/plain")
          context.startActivity(Intent.createChooser(intent, "Send Link"))
          true
        } catch {
          case e =>
            DMessage.IAmYell("Unable to send license link " + item.uri, e)
            false
        }
      case id =>
        log.fatal("unknown context menu id " + id)
        false
    }
  }
}

object Legal {
  case class Item(val text: String)(val uri: String) {
    override def toString() = text
  }
  class Adapter(context: Context, data: Seq[Item], imageGetter: Html.ImageGetter, tagHandler: Html.TagHandler)
    extends ArrayAdapter(context, android.R.layout.simple_list_item_1, android.R.id.text1, data.toArray) {
    override def getView(position: Int, convertView: View, parent: ViewGroup): View = {
      convertView match {
        case null =>
          val view = super.getView(position, convertView, parent)
          val item = data(position)
          val text1 = view.findViewById(android.R.id.text1).asInstanceOf[TextView]
          text1.setTextAppearance(context, android.R.style.TextAppearance_Small)
          text1.setText(Html.fromHtml(item.text, imageGetter, tagHandler), BufferType.SPANNABLE)
          view
        case view: View =>
          view
      }
    }
  }
}