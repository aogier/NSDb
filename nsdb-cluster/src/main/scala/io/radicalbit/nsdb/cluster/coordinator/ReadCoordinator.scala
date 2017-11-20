package io.radicalbit.nsdb.cluster.coordinator

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import io.radicalbit.nsdb.protocol.MessageProtocol.Commands._
import io.radicalbit.nsdb.protocol.MessageProtocol.Events._

import scala.concurrent.Future

class ReadCoordinator(schemaActor: ActorRef, namespaceActor: ActorRef) extends Actor with ActorLogging {

  implicit val timeout: Timeout = Timeout(
    context.system.settings.config.getDuration("nsdb.read-coordinatoor.timeout", TimeUnit.SECONDS),
    TimeUnit.SECONDS)

  import context.dispatcher

  override def receive: Receive = {

    case msg: GetNamespaces =>
      namespaceActor forward msg
    case msg: GetMetrics =>
      namespaceActor forward msg
    case msg: GetSchema =>
      schemaActor forward msg
      namespaceActor forward msg
    case ExecuteStatement(statement) =>
      log.debug(s"executing $statement")
      (schemaActor ? GetSchema(statement.db, statement.namespace, statement.metric))
        .mapTo[SchemaGot]
        .flatMap {
          case SchemaGot(_, _, _, Some(schema)) =>
            namespaceActor ? ExecuteSelectStatement(statement, schema)
          case _ => Future(SelectStatementFailed(s"No schema found for metric ${statement.metric}"))
        }
        .pipeTo(sender())
  }
}

object ReadCoordinator {

  def props(schemaActor: ActorRef, indexerActor: ActorRef): Props =
    Props(new ReadCoordinator(schemaActor, indexerActor))

}