/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kyuubi.engine.trino

import java.io.File
import java.nio.file.Paths
import java.util.LinkedHashSet

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer

import org.apache.kyuubi.{Logging, SCALA_COMPILE_VERSION, Utils}
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.config.KyuubiConf.{ENGINE_TRINO_CONNECTION_CATALOG, ENGINE_TRINO_CONNECTION_URL}
import org.apache.kyuubi.config.KyuubiReservedKeys.KYUUBI_SESSION_USER_KEY
import org.apache.kyuubi.engine.ProcBuilder
import org.apache.kyuubi.operation.log.OperationLog

class TrinoProcessBuilder(
    override val proxyUser: String,
    override val conf: KyuubiConf,
    val extraEngineLog: Option[OperationLog] = None) extends ProcBuilder with Logging {

  override protected def module: String = "kyuubi-trino-engine"

  override protected def mainClass: String = "org.apache.kyuubi.engine.trino.TrinoSqlEngine"

  override protected def commands: Array[String] = {
    require(
      conf.get(ENGINE_TRINO_CONNECTION_URL).nonEmpty,
      s"Trino server url can not be null! Please set ${ENGINE_TRINO_CONNECTION_URL.key}")
    require(
      conf.get(ENGINE_TRINO_CONNECTION_CATALOG).nonEmpty,
      s"Trino default catalog can not be null! Please set ${ENGINE_TRINO_CONNECTION_CATALOG.key}")
    val buffer = new ArrayBuffer[String]()
    buffer += executable

    // TODO: How shall we deal with proxyUser,
    // user.name
    // kyuubi.session.user
    // or just leave it, because we can handle it at operation layer
    buffer += s"-D$KYUUBI_SESSION_USER_KEY=$proxyUser"

    // TODO: add Kyuubi.engineEnv.TRINO_ENGINE_MEMORY or kyuubi.engine.trino.memory to configure
    // -Xmx5g
    // java options
    for ((k, v) <- conf.getAll) {
      buffer += s"-D$k=$v"
    }

    buffer += "-cp"
    val classpathEntries = new LinkedHashSet[String]
    // trino engine runtime jar
    mainResource.foreach(classpathEntries.add)

    mainResource.foreach { path =>
      val parent = Paths.get(path).getParent
      if (Utils.isTesting) {
        // add dev classpath
        val trinoDeps = parent
          .resolve(s"scala-$SCALA_COMPILE_VERSION")
          .resolve("jars")
        classpathEntries.add(s"$trinoDeps${File.separator}*")
      } else {
        // add prod classpath
        classpathEntries.add(s"$parent${File.separator}*")
      }
    }

    buffer += classpathEntries.asScala.mkString(File.pathSeparator)
    buffer += mainClass
    buffer.toArray
  }

  override protected def shortName: String = "trino"

  override def toString: String = commands.mkString("\n")
}
