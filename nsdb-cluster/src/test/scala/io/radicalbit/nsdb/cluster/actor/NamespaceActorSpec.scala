package io.radicalbit.nsdb.cluster.actor

import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.pattern.ask
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import akka.util.Timeout
import io.radicalbit.nsdb.common.protocol.Bit
import io.radicalbit.nsdb.protocol.MessageProtocol.Commands._
import io.radicalbit.nsdb.protocol.MessageProtocol.Events._
import org.scalatest.{BeforeAndAfter, FlatSpecLike, Matchers}

import scala.concurrent.Await
import scala.concurrent.duration._

class NamespaceActorSpec()
    extends TestKit(ActorSystem("namespaceActorSpec"))
    with ImplicitSender
    with FlatSpecLike
    with Matchers
    with BeforeAndAfter {

  val probe          = TestProbe()
  val probeActor     = probe.ref
  val basePath       = "target/test_index/NamespaceActorSpec"
  val db             = "db"
  val namespace      = "namespace"
  val namespace1     = "namespace1"
  val namespaceActor = TestActorRef[NamespaceDataActor](NamespaceDataActor.props(basePath))

  val interval = FiniteDuration(system.settings.config.getDuration("nsdb.write.scheduler.interval", TimeUnit.SECONDS),
                                TimeUnit.SECONDS) + (1 second)

  before {
    import scala.concurrent.duration._
    implicit val timeout: Timeout = 10 second

    Await.result(namespaceActor ? DeleteNamespace(db, namespace), 1 seconds)
    Await.result(namespaceActor ? DeleteNamespace(db, namespace1), 1 seconds)
  }

  "namespaceActor" should "write and delete properly" in {

    val record = Bit(System.currentTimeMillis, 0.5, Map("content" -> s"content"))

    probe.send(namespaceActor, AddRecord(db, namespace, "namespaceActorMetric", record))

    within(5 seconds) {
      val expectedAdd = probe.expectMsgType[RecordAdded]
      expectedAdd.metric shouldBe "namespaceActorMetric"
      expectedAdd.record shouldBe record
    }

    expectNoMessage(interval)

    probe.send(namespaceActor, GetCount(db, namespace, "namespaceActorMetric"))

    within(5 seconds) {
      val expectedCount = probe.expectMsgType[CountGot]
      expectedCount.metric shouldBe "namespaceActorMetric"
      expectedCount.count shouldBe 1
    }

    probe.send(namespaceActor, DeleteRecord(db, namespace, "namespaceActorMetric", record))

    within(5 seconds) {
      val expectedDelete = probe.expectMsgType[RecordDeleted]
      expectedDelete.metric shouldBe "namespaceActorMetric"
      expectedDelete.record shouldBe record
    }

    expectNoMessage(interval)

    probe.send(namespaceActor, GetCount(db, namespace, "namespaceActorMetric"))

    within(5 seconds) {
      val expectedCountDeleted = probe.expectMsgType[CountGot]
      expectedCountDeleted.metric shouldBe "namespaceActorMetric"
      expectedCountDeleted.count shouldBe 0
    }

  }

  "namespaceActor" should "write and delete properly in multiple namespaces" in {

    val record = Bit(System.currentTimeMillis, 24, Map("content" -> s"content"))

    probe.send(namespaceActor, AddRecord(db, namespace1, "namespaceActorMetric2", record))
    probe.expectMsgType[RecordAdded]

    expectNoMessage(interval)

    probe.send(namespaceActor, GetCount(db, namespace, "namespaceActorMetric"))

    within(5 seconds) {
      val expectedCount = probe.expectMsgType[CountGot]
      expectedCount.metric shouldBe "namespaceActorMetric"
      expectedCount.count shouldBe 0
    }

    probe.send(namespaceActor, GetCount(db, namespace1, "namespaceActorMetric2"))

    within(5 seconds) {
      val expectedCount2 = probe.expectMsgType[CountGot]
      expectedCount2.metric shouldBe "namespaceActorMetric2"
      expectedCount2.count shouldBe 1
    }

  }

  "namespaceActor" should "delete a namespace" in {

    val record = Bit(System.currentTimeMillis, 23, Map("content" -> s"content"))

    probe.send(namespaceActor, AddRecord(db, namespace1, "namespaceActorMetric2", record))
    probe.expectMsgType[RecordAdded]

    expectNoMessage(interval)

    probe.send(namespaceActor, GetCount(db, namespace1, "namespaceActorMetric2"))
    within(5 seconds) {
      val expectedCount2 = probe.expectMsgType[CountGot]
      expectedCount2.metric shouldBe "namespaceActorMetric2"
      expectedCount2.count shouldBe 1
    }

    namespaceActor.underlyingActor.childActors.keys.size shouldBe 1

    probe.send(namespaceActor, DeleteNamespace(db, namespace1))
    within(5 seconds) {
      probe.expectMsgType[NamespaceDeleted]
    }

    expectNoMessage(interval)

    namespaceActor.underlyingActor.childActors.keys.size shouldBe 0
  }

}
