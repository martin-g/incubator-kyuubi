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

package org.apache.kyuubi.engine

import java.util.UUID
import java.util.concurrent.TimeUnit

import scala.util.Random

import com.codahale.metrics.MetricRegistry
import com.google.common.annotations.VisibleForTesting
import org.apache.hadoop.security.UserGroupInformation

import org.apache.kyuubi.{KYUUBI_VERSION, KyuubiSQLException, Logging, Utils}
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.config.KyuubiConf._
import org.apache.kyuubi.config.KyuubiReservedKeys.KYUUBI_ENGINE_SUBMIT_TIME_KEY
import org.apache.kyuubi.engine.EngineType.{EngineType, FLINK_SQL, HIVE_SQL, SPARK_SQL, TRINO}
import org.apache.kyuubi.engine.ShareLevel.{CONNECTION, GROUP, SERVER, ShareLevel}
import org.apache.kyuubi.engine.flink.FlinkProcessBuilder
import org.apache.kyuubi.engine.hive.HiveProcessBuilder
import org.apache.kyuubi.engine.spark.SparkProcessBuilder
import org.apache.kyuubi.engine.trino.TrinoProcessBuilder
import org.apache.kyuubi.ha.HighAvailabilityConf.HA_ZK_ENGINE_REF_ID
import org.apache.kyuubi.ha.HighAvailabilityConf.HA_ZK_NAMESPACE
import org.apache.kyuubi.ha.client.DiscoveryClient
import org.apache.kyuubi.ha.client.DiscoveryPaths
import org.apache.kyuubi.metrics.MetricsConstants.{ENGINE_FAIL, ENGINE_TIMEOUT, ENGINE_TOTAL}
import org.apache.kyuubi.metrics.MetricsSystem
import org.apache.kyuubi.operation.log.OperationLog

/**
 * The description and functionality of an engine at server side
 *
 * @param conf Engine configuration
 * @param user Caller of the engine
 * @param engineRefId Id of the corresponding session in which the engine is created
 */
private[kyuubi] class EngineRef(
    conf: KyuubiConf,
    user: String,
    engineRefId: String = UUID.randomUUID().toString)
  extends Logging {
  // The corresponding ServerSpace where the engine belongs to
  private val serverSpace: String = conf.get(HA_ZK_NAMESPACE)

  private val timeout: Long = conf.get(ENGINE_INIT_TIMEOUT)

  // Share level of the engine
  private val shareLevel: ShareLevel = ShareLevel.withName(conf.get(ENGINE_SHARE_LEVEL))

  private val engineType: EngineType = EngineType.withName(conf.get(ENGINE_TYPE))

  // Server-side engine pool size threshold
  private val poolThreshold: Int = conf.get(ENGINE_POOL_SIZE_THRESHOLD)

  private val clientPoolSize: Int = conf.get(ENGINE_POOL_SIZE)

  private val clientPoolName: String = conf.get(ENGINE_POOL_NAME)

  @VisibleForTesting
  private[kyuubi] val subdomain: String = conf.get(ENGINE_SHARE_LEVEL_SUBDOMAIN) match {
    case Some(_subdomain) => _subdomain
    case None if clientPoolSize > 0 =>
      val poolSize = math.min(clientPoolSize, poolThreshold)
      if (poolSize < clientPoolSize) {
        warn(s"Request engine pool size($clientPoolSize) exceeds, fallback to " +
          s"system threshold $poolThreshold")
      }
      // TODO: Currently, we use random policy, and later we can add a sequential policy,
      //  such as AtomicInteger % poolSize.
      s"$clientPoolName-${Random.nextInt(poolSize)}"
    case _ => "default" // [KYUUBI #1293]
  }

  // Launcher of the engine
  private[kyuubi] val appUser: String = shareLevel match {
    case SERVER => Utils.currentUser
    case GROUP =>
      val clientUGI = UserGroupInformation.createRemoteUser(user)
      // Similar to `clientUGI.getPrimaryGroupName` (avoid IOE) to get the Primary GroupName of
      // the client user mapping to
      clientUGI.getGroupNames.headOption match {
        case Some(primaryGroup) => primaryGroup
        case None =>
          warn(s"There is no primary group for $user, use the client user name as group directly")
          user
      }
    case _ => user
  }

  /**
   * The default engine name, used as default `spark.app.name` if not set
   */
  @VisibleForTesting
  private[kyuubi] val defaultEngineName: String = {
    val commonNamePrefix = s"kyuubi_${shareLevel}_${engineType}_${appUser}"
    shareLevel match {
      case CONNECTION => s"${commonNamePrefix}_$engineRefId"
      case _ => s"${commonNamePrefix}_${subdomain}_$engineRefId"
    }
  }

  /**
   * The EngineSpace used to expose itself to the KyuubiServers in `serverSpace`
   *
   * For `CONNECTION` share level:
   *   /`serverSpace_version_CONNECTION_engineType`/`user`/`engineRefId`
   * For `USER` share level:
   *   /`serverSpace_version_USER_engineType`/`user`[/`subdomain`]
   * For `GROUP` share level:
   *   /`serverSpace_version_GROUP_engineType`/`primary group name`[/`subdomain`]
   * For `SERVER` share level:
   *   /`serverSpace_version_SERVER_engineType`/`kyuubi server user`[/`subdomain`]
   */
  @VisibleForTesting
  private[kyuubi] lazy val engineSpace: String = {
    val commonParent = s"${serverSpace}_${KYUUBI_VERSION}_${shareLevel}_$engineType"
    shareLevel match {
      case CONNECTION => DiscoveryPaths.makePath(commonParent, appUser, Array(engineRefId))
      case _ => DiscoveryPaths.makePath(commonParent, appUser, Array(subdomain))
    }
  }

  /**
   * The distributed lock path used to ensure only once engine being created for non-CONNECTION
   * share level.
   */
  private def tryWithLock[T](discoveryClient: DiscoveryClient)(f: => T): T =
    shareLevel match {
      case CONNECTION => f
      case _ =>
        val lockPath =
          DiscoveryPaths.makePath(
            s"${serverSpace}_$shareLevel",
            "lock",
            Array(appUser, subdomain))
        discoveryClient.tryWithLock(lockPath, timeout, TimeUnit.MILLISECONDS)(f)
    }

  private def create(
      discoveryClient: DiscoveryClient,
      extraEngineLog: Option[OperationLog]): (String, Int) = tryWithLock(discoveryClient) {
    // Get the engine address ahead if another process has succeeded
    var engineRef = discoveryClient.getServerHost(engineSpace)
    if (engineRef.nonEmpty) return engineRef.get

    conf.set(HA_ZK_NAMESPACE, engineSpace)
    conf.set(HA_ZK_ENGINE_REF_ID, engineRefId)
    val started = System.currentTimeMillis()
    conf.set(KYUUBI_ENGINE_SUBMIT_TIME_KEY, String.valueOf(started))
    val builder = engineType match {
      case SPARK_SQL =>
        conf.setIfMissing(SparkProcessBuilder.APP_KEY, defaultEngineName)
        // tag is a seq type with comma-separated
        conf.set(
          SparkProcessBuilder.TAG_KEY,
          conf.getOption(SparkProcessBuilder.TAG_KEY).map(_ + ",").getOrElse("") + "KYUUBI")
        new SparkProcessBuilder(appUser, conf, extraEngineLog)
      case FLINK_SQL =>
        conf.setIfMissing(FlinkProcessBuilder.APP_KEY, defaultEngineName)
        // tag is a seq type with comma-separated
        conf.set(
          FlinkProcessBuilder.TAG_KEY,
          conf.getOption(FlinkProcessBuilder.TAG_KEY).map(_ + ",").getOrElse("") + "KYUUBI")
        new FlinkProcessBuilder(appUser, conf, extraEngineLog)
      case TRINO =>
        new TrinoProcessBuilder(appUser, conf, extraEngineLog)
      case HIVE_SQL =>
        new HiveProcessBuilder(appUser, conf, extraEngineLog)
    }

    MetricsSystem.tracing(_.incCount(ENGINE_TOTAL))
    try {
      info(s"Launching engine:\n$builder")
      val process = builder.start
      var exitValue: Option[Int] = None
      while (engineRef.isEmpty) {
        if (exitValue.isEmpty && process.waitFor(1, TimeUnit.SECONDS)) {
          exitValue = Some(process.exitValue())
          if (exitValue.get != 0) {
            val error = builder.getError
            MetricsSystem.tracing { ms =>
              ms.incCount(MetricRegistry.name(ENGINE_FAIL, appUser))
              ms.incCount(MetricRegistry.name(ENGINE_FAIL, error.getClass.getSimpleName))
            }
            throw error
          }
        }
        if (started + timeout <= System.currentTimeMillis()) {
          val killMessage = builder.killApplication()
          process.destroyForcibly()
          MetricsSystem.tracing(_.incCount(MetricRegistry.name(ENGINE_TIMEOUT, appUser)))
          throw KyuubiSQLException(
            s"Timeout($timeout ms) to launched $engineType engine with $builder. $killMessage",
            builder.getError)
        }
        engineRef = discoveryClient.getEngineByRefId(engineSpace, engineRefId)
      }
      engineRef.get
    } finally {
      // we must close the process builder whether session open is success or failure since
      // we have a log capture thread in process builder.
      builder.close()
    }
  }

  /**
   * Get the engine ref from engine space first or create a new one
   *
   * @param discoveryClient the zookeeper client to get or create engine instance
   * @param extraEngineLog the launch engine operation log, used to inject engine log into it
   */
  def getOrCreate(
      discoveryClient: DiscoveryClient,
      extraEngineLog: Option[OperationLog] = None): (String, Int) = {
    discoveryClient.getServerHost(engineSpace)
      .getOrElse {
        create(discoveryClient, extraEngineLog)
      }
  }
}
