/*
 * Copyright (c) 2002-2019 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.procs

import org.neo4j.cypher.internal.plandescription.Argument
import org.neo4j.cypher.internal.result.InternalExecutionResult
import org.neo4j.cypher.internal.runtime.{InputDataStream, QueryContext, QueryStatistics => QueryStats}
import org.neo4j.cypher.internal.v4_0.util.InternalNotification
import org.neo4j.cypher.internal.{ExecutionEngine, ExecutionPlan, RuntimeName, SystemCommandRuntimeName}
import org.neo4j.cypher.result.RuntimeResult
import org.neo4j.graphdb.QueryStatistics
import org.neo4j.graphdb.security.AuthorizationViolationException
import org.neo4j.graphdb.security.AuthorizationViolationException.PERMISSION_DENIED
import org.neo4j.internal.kernel.api.security.AccessMode
import org.neo4j.kernel.api.KernelTransaction
import org.neo4j.kernel.impl.query.{QueryExecution, QuerySubscriber}
import org.neo4j.values.AnyValue
import org.neo4j.values.virtual.MapValue

/**
  * Execution plan for performing system commands, i.e. creating databases or showing roles and users.
  */
case class SystemCommandExecutionPlan(name: String, normalExecutionEngine: ExecutionEngine, query: String, systemParams: MapValue,
                                      queryHandler: QueryHandler = QueryHandler.handleError(identity),
                                      resultMapper: (QueryContext, QueryExecution) => SystemCommandExecutionResult = (_, q) => new SystemCommandExecutionResult(q.asInstanceOf[InternalExecutionResult]))
  extends ExecutionPlan {

  override def run(originalCtx: QueryContext,
                   doProfile: Boolean,
                   params: MapValue,
                   prePopulateResults: Boolean,
                   ignore: InputDataStream,
                   subscriber: QuerySubscriber): RuntimeResult = {

    val ctx = SystemUpdateCountingQueryContext.from(originalCtx)
    val tc = ctx.kernelTransactionalContext
    if (!name.equals("ShowDefaultDatabase") && !name.startsWith("ShowDatabase") && !tc.securityContext().isAdmin) throw new AuthorizationViolationException(PERMISSION_DENIED)

    var revertAccessModeChange: KernelTransaction.Revertable = null
    try {
      val fullReadAccess = tc.securityContext().withMode(AccessMode.Static.READ)
      revertAccessModeChange = tc.kernelTransaction().overrideWith(fullReadAccess)

      val systemSubscriber = new SystemCommandQuerySubscriber(ctx, subscriber, queryHandler)
      val execution = normalExecutionEngine.executeSubQuery(query, systemParams, tc, shouldCloseTransaction = false, doProfile, prePopulateResults, systemSubscriber)
      systemSubscriber.assertNotFailed()

      SystemCommandRuntimeResult(ctx, resultMapper(ctx, execution), systemSubscriber, fullReadAccess, tc.kernelTransaction())
    } finally {
      if (revertAccessModeChange != null) revertAccessModeChange
    }
  }

  override def runtimeName: RuntimeName = SystemCommandRuntimeName

  override def metadata: Seq[Argument] = Nil

  override def notifications: Set[InternalNotification] = Set.empty
}

/**
  * A wrapping QuerySubscriber that overrides the error handling to allow custom error messages for SystemCommands instead of the inner errors.
  * It also makes sure to return QueryStatistics that don't leak information about the system graph like how many nodes we created for a command etc.
  */
class SystemCommandQuerySubscriber(ctx: SystemUpdateCountingQueryContext, inner: QuerySubscriber, queryHandler: QueryHandler) extends QuerySubscriber {
  @volatile private var empty = true
  @volatile private var failed: Option[Throwable] = None
  private var currentOffset = -1

  override def onResult(numberOfFields: Int): Unit = if (failed.isEmpty) {
    inner.onResult(numberOfFields)
  }

  override def onResultCompleted(statistics: QueryStatistics): Unit = {
    if (empty) {
      queryHandler.onNoResults().foreach(error => {
        inner.onError(error)
        failed = Some(error)
      })
    }
    if (failed.isEmpty) {
      inner.onResultCompleted(if (statistics.containsUpdates()) ctx.getStatistics else QueryStats.empty)
    }
  }

  override def onRecord(): Unit = {
    currentOffset = 0
    if (failed.isEmpty) {
      empty = false
      inner.onRecord()
    }
  }

  override def onRecordCompleted(): Unit = if (failed.isEmpty) {
    currentOffset = -1
    inner.onRecordCompleted()
  }

  override def onField(value: AnyValue): Unit = {
    try {
      queryHandler.onResult(currentOffset, value).foreach( error => {
      inner.onError(error)
        failed = Some(error)
      })
      if (failed.isEmpty) {
        inner.onField(value)
      }
    } finally {
      currentOffset += 1
    }
  }

  override def onError(throwable: Throwable): Unit = {
    inner.onError(throwable)
    failed = Some(throwable)
  }

  def assertNotFailed(onFailure: Throwable => Unit = _ => ()): Unit = failed.foreach { exception =>
    onFailure(exception) // used to close resources
    throw exception
  }

  override def equals(obj: Any): Boolean = inner.equals(obj)
}
