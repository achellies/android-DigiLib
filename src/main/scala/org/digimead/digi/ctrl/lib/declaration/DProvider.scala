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

package org.digimead.digi.ctrl.lib.declaration

object DProvider {
  val authority = "org.digimead.digi.ctrl"
  case class Row(val session_id: Int,
    val name: String,
    val description: String,
    val componentPackage: String,
    val process_id: Int,
    val executable_id: Int,
    val connection_id: Int,
    val connection: Array[Byte])
  object Field extends Enumeration {
    val ID = Value("_id")
    val Name = Value("name")
    val Description = Value("description")
    val ComponentPackage = Value("component_package")
    val ProcessID = Value("process_id")
    val ExecutableID = Value("executable_id")
    val ConnectionID = Value("connection_id")
    val Connection = Value("connection")
  }
  object Uri extends Enumeration {
    val Session = Value("content://" + authority + "/session")
    val SessionID = Value("content://" + authority + "/session/#")
  }
}