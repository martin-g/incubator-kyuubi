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

package org.apache.kyuubi.engine.spark.operation

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

import scala.collection.JavaConverters._

import org.apache.kyuubi.KyuubiSQLException
import org.apache.kyuubi.config.KyuubiConf._
import org.apache.kyuubi.config.KyuubiConf.OperationModes._
import org.apache.kyuubi.engine.spark.repl.KyuubiSparkILoop
import org.apache.kyuubi.engine.spark.session.SparkSessionImpl
import org.apache.kyuubi.engine.spark.shim.SparkCatalogShim
import org.apache.kyuubi.operation.{Operation, OperationManager}
import org.apache.kyuubi.session.{Session, SessionHandle}

class SparkSQLOperationManager private (name: String) extends OperationManager(name) {

  def this() = this(classOf[SparkSQLOperationManager].getSimpleName)

  private lazy val operationModeDefault = getConf.get(OPERATION_PLAN_ONLY_MODE)
  private lazy val operationIncrementalCollectDefault = getConf.get(OPERATION_INCREMENTAL_COLLECT)
  private lazy val operationLanguageDefault = getConf.get(OPERATION_LANGUAGE)

  private val sessionToRepl = new ConcurrentHashMap[SessionHandle, KyuubiSparkILoop]().asScala

  def closeILoop(session: SessionHandle): Unit = {
    val maybeRepl = sessionToRepl.remove(session)
    maybeRepl.foreach(_.close())
  }

  override def newExecuteStatementOperation(
      session: Session,
      statement: String,
      confOverlay: Map[String, String],
      runAsync: Boolean,
      queryTimeout: Long): Operation = {
    val spark = session.asInstanceOf[SparkSessionImpl].spark
    val lang = confOverlay.getOrElse(
      OPERATION_LANGUAGE.key,
      spark.conf.get(OPERATION_LANGUAGE.key, operationLanguageDefault))
    val operation =
      OperationLanguages.withName(lang.toUpperCase(Locale.ROOT)) match {
        case OperationLanguages.SQL =>
          val mode = spark.conf.get(OPERATION_PLAN_ONLY_MODE.key, operationModeDefault)
          OperationModes.withName(mode.toUpperCase(Locale.ROOT)) match {
            case NONE =>
              val incrementalCollect = spark.conf.getOption(OPERATION_INCREMENTAL_COLLECT.key)
                .map(_.toBoolean).getOrElse(operationIncrementalCollectDefault)
              new ExecuteStatement(session, statement, runAsync, queryTimeout, incrementalCollect)
            case mode =>
              new PlanOnlyStatement(session, statement, mode)
          }
        case OperationLanguages.SCALA =>
          val repl = sessionToRepl.getOrElseUpdate(session.handle, KyuubiSparkILoop(spark))
          new ExecuteScala(session, repl, statement)
      }
    addOperation(operation)
  }

  override def newGetTypeInfoOperation(session: Session): Operation = {
    val op = new GetTypeInfo(session)
    addOperation(op)
  }

  override def newGetCatalogsOperation(session: Session): Operation = {
    val op = new GetCatalogs(session)
    addOperation(op)
  }

  override def newGetSchemasOperation(
      session: Session,
      catalog: String,
      schema: String): Operation = {
    val op = new GetSchemas(session, catalog, schema)
    addOperation(op)
  }

  override def newGetTablesOperation(
      session: Session,
      catalogName: String,
      schemaName: String,
      tableName: String,
      tableTypes: java.util.List[String]): Operation = {
    val tTypes =
      if (tableTypes == null || tableTypes.isEmpty) {
        SparkCatalogShim.sparkTableTypes
      } else {
        tableTypes.asScala.toSet
      }
    val op = new GetTables(session, catalogName, schemaName, tableName, tTypes)
    addOperation(op)
  }

  override def newGetTableTypesOperation(session: Session): Operation = {
    val op = new GetTableTypes(session)
    addOperation(op)
  }

  override def newGetColumnsOperation(
      session: Session,
      catalogName: String,
      schemaName: String,
      tableName: String,
      columnName: String): Operation = {
    val op = new GetColumns(session, catalogName, schemaName, tableName, columnName)
    addOperation(op)
  }

  override def newGetFunctionsOperation(
      session: Session,
      catalogName: String,
      schemaName: String,
      functionName: String): Operation = {
    val op = new GetFunctions(session, catalogName, schemaName, functionName)
    addOperation(op)
  }

  override def newGetPrimaryKeysOperation(
      session: Session,
      catalogName: String,
      schemaName: String,
      tableName: String): Operation = {
    throw KyuubiSQLException.featureNotSupported()
  }

  override def newGetCrossReferenceOperation(
      session: Session,
      primaryCatalog: String,
      primarySchema: String,
      primaryTable: String,
      foreignCatalog: String,
      foreignSchema: String,
      foreignTable: String): Operation = {
    throw KyuubiSQLException.featureNotSupported()
  }
}
