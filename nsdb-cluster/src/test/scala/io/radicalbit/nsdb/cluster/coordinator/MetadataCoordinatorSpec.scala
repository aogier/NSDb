/*
 * Copyright 2018 Radicalbit S.r.l.
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

package io.radicalbit.nsdb.cluster.coordinator

import akka.actor.ActorSystem
import akka.cluster.Cluster
import akka.cluster.pubsub.DistributedPubSubMediator.Publish
import akka.pattern._
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import akka.util.Timeout
import com.typesafe.config.{ConfigFactory, ConfigValueFactory}
import io.radicalbit.nsdb.cluster.coordinator.MetadataCoordinator.commands._
import io.radicalbit.nsdb.cluster.coordinator.MetadataCoordinator.events._
import io.radicalbit.nsdb.cluster.index.MetricInfo
import io.radicalbit.nsdb.model.Location
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, Matchers, WordSpecLike}

import scala.concurrent.Await
import scala.concurrent.duration._

class MetadataCoordinatorSpec
    extends TestKit(
      ActorSystem(
        "MetadataCoordinatorSpec",
        ConfigFactory
          .load()
          .withValue("akka.actor.provider", ConfigValueFactory.fromAnyRef("cluster"))
          .withValue("nsdb.sharding.interval", ConfigValueFactory.fromAnyRef("60s"))
      ))
    with ImplicitSender
    with WordSpecLike
    with Matchers
    with BeforeAndAfterEach
    with BeforeAndAfterAll {

  import FakeMetadataCache._

  val probe               = TestProbe()
  val metadataCache       = system.actorOf(FakeMetadataCache.props)
  val metadataCoordinator = system.actorOf(MetadataCoordinator.props(metadataCache, probe.ref))

  val db        = "testDb"
  val namespace = "testNamespace"
  val metric    = "testMetric"

  override def beforeAll = {
    val cluster = Cluster(system)
    cluster.join(cluster.selfAddress)

    probe.send(metadataCoordinator, WarmUpMetadata(List.empty))
    probe.expectMsgType[Publish]
    probe.expectNoMessage(1 second)
  }

  override def beforeEach: Unit = {
    implicit val timeout = Timeout(5 seconds)
    Await.result(metadataCache ? DeleteAll, 5 seconds)
  }

  "MetadataCoordinator" should {
    "start in warm-up and then change state" in {

      probe.send(metadataCoordinator, GetLocations(db, namespace, metric))
      val cachedLocation: LocationsGot = awaitAssert {
        probe.expectMsgType[LocationsGot]
      }

      cachedLocation.locations.size shouldBe 0
    }

    "add a Location" in {
      probe.send(metadataCoordinator, AddLocation(db, namespace, Location(metric, "node_01", 0L, 60000L)))
      val locationAdded = awaitAssert {
        probe.expectMsgType[LocationsAdded]
      }

      locationAdded.db shouldBe db
      locationAdded.namespace shouldBe namespace
      locationAdded.locations.size shouldBe 1
      locationAdded.locations.head shouldBe Location(metric, "node_01", 0L, 60000L)
    }

    "retrieve a Location for a metric" in {
      probe.send(metadataCoordinator, AddLocation(db, namespace, Location(metric, "node_01", 0L, 30000L)))
      awaitAssert {
        probe.expectMsgType[LocationsAdded]
      }

      probe.send(metadataCoordinator, AddLocation(db, namespace, Location(metric, "node_02", 0L, 30000L)))
      awaitAssert {
        probe.expectMsgType[LocationsAdded]
      }

      probe.send(metadataCoordinator, GetLocations(db, namespace, metric))
      val retrievedLocations = awaitAssert {
        probe.expectMsgType[LocationsGot]
      }

      retrievedLocations.locations.size shouldBe 2
      val loc = retrievedLocations.locations.head
      loc.metric shouldBe metric
      loc.from shouldBe 0L
      loc.to shouldBe 30000L
      loc.node shouldBe "node_01"

      val loc2 = retrievedLocations.locations.last
      loc2.metric shouldBe metric
      loc2.from shouldBe 0L
      loc2.to shouldBe 30000L
      loc2.node shouldBe "node_02"
    }

    "retrieve Locations for a metric" in {
      probe.send(metadataCoordinator, AddLocation(db, namespace, Location(metric, "node_01", 0L, 30000L)))
      awaitAssert {
        probe.expectMsgType[LocationsAdded]
      }

      probe.send(metadataCoordinator, AddLocation(db, namespace, Location(metric, "node_02", 0L, 30000L)))
      awaitAssert {
        probe.expectMsgType[LocationsAdded]
      }

      probe.send(metadataCoordinator, AddLocation(db, namespace, Location(metric, "node_01", 30000L, 60000L)))
      awaitAssert {
        probe.expectMsgType[LocationsAdded]
      }

      probe.send(metadataCoordinator, AddLocation(db, namespace, Location(metric, "node_02", 30000L, 60000L)))
      awaitAssert {
        probe.expectMsgType[LocationsAdded]
      }

      probe.send(metadataCoordinator, GetLocations(db, namespace, metric))
      val retrievedLocations = awaitAssert {
        probe.expectMsgType[LocationsGot]
      }

      retrievedLocations.locations.size shouldBe 4
      val loc = retrievedLocations.locations
      loc.map(_.metric) shouldBe Seq(metric, metric, metric, metric)
      loc.map(_.node) shouldBe Seq("node_01", "node_02", "node_01", "node_02")
      loc.map(_.from) shouldBe Seq(0L, 0L, 30000L, 30000L)
      loc.map(_.to) shouldBe Seq(30000L, 30000L, 60000L, 60000L)
    }

    "retrieve correct default write Location given a timestamp" in {

      probe.send(metadataCoordinator, GetWriteLocations(db, namespace, metric, 1))
      val locationGot = awaitAssert {
        probe.expectMsgType[LocationsGot]
      }

      locationGot.db shouldBe db
      locationGot.namespace shouldBe namespace
      locationGot.metric shouldBe metric

      locationGot.locations.size shouldBe 1
      locationGot.locations.head.from shouldBe 0L
      locationGot.locations.head.to shouldBe 60000L

      probe.send(metadataCoordinator, GetWriteLocations(db, namespace, metric, 60001))
      val locationGot_2 = awaitAssert {
        probe.expectMsgType[LocationsGot]
      }

      locationGot_2.db shouldBe db
      locationGot_2.namespace shouldBe namespace
      locationGot_2.metric shouldBe metric

      locationGot_2.locations.size shouldBe 1
      locationGot_2.locations.head.from shouldBe 60000L
      locationGot_2.locations.head.to shouldBe 120000L

      probe.send(metadataCoordinator, GetWriteLocations(db, namespace, metric, 60002))
      val locationGot_3 = awaitAssert {
        probe.expectMsgType[LocationsGot]
      }

      locationGot_3.db shouldBe db
      locationGot_3.namespace shouldBe namespace
      locationGot_3.metric shouldBe metric

      locationGot_3.locations.size shouldBe 1
      locationGot_3.locations.head.from shouldBe 60000L
      locationGot_3.locations.head.to shouldBe 120000L

    }

    "retrieve correct write Location for a initialized metric with a different shard interval" in {

      val metricInfo = MetricInfo(metric, 100)

      probe.send(metadataCoordinator, PutMetricInfo(db, namespace, metricInfo))
      awaitAssert {
        probe.expectMsgType[MetricInfoPut]
      }.metricInfo shouldBe metricInfo

      probe.send(metadataCoordinator, GetMetricInfo(db, namespace, metric))
      awaitAssert {
        probe.expectMsgType[MetricInfoGot]
      }.metricInfo shouldBe Some(metricInfo)

      probe.send(metadataCoordinator, GetWriteLocations(db, namespace, metric, 1))
      val locationGot = awaitAssert {
        probe.expectMsgType[LocationsGot]
      }

      locationGot.db shouldBe db
      locationGot.namespace shouldBe namespace
      locationGot.metric shouldBe metric
      locationGot.locations.size shouldBe 1

      locationGot.locations.head.from shouldBe 0L
      locationGot.locations.head.to shouldBe 100L

      probe.send(metadataCoordinator, GetWriteLocations(db, namespace, metric, 101))
      val locationGot_2 = awaitAssert {
        probe.expectMsgType[LocationsGot]
      }

      locationGot_2.db shouldBe db
      locationGot_2.namespace shouldBe namespace
      locationGot_2.metric shouldBe metric

      locationGot_2.locations.size shouldBe 1
      locationGot_2.locations.head.from shouldBe 100L
      locationGot_2.locations.head.to shouldBe 200L

      probe.send(metadataCoordinator, GetWriteLocations(db, namespace, metric, 202))
      val locationGot_3 = awaitAssert {
        probe.expectMsgType[LocationsGot]
      }

      locationGot_3.db shouldBe db
      locationGot_3.namespace shouldBe namespace
      locationGot_3.metric shouldBe metric

      locationGot_3.locations.size shouldBe 1
      locationGot_3.locations.head.from shouldBe 200L
      locationGot_3.locations.head.to shouldBe 300L
    }

    "retrieve metric infos" in {

      val metricInfo = MetricInfo(metric, 100)

      probe.send(metadataCoordinator, GetMetricInfo(db, namespace, metric))
      awaitAssert {
        probe.expectMsgType[MetricInfoGot]
      }.metricInfo.isEmpty shouldBe true

      probe.send(metadataCoordinator, PutMetricInfo(db, namespace, metricInfo))
      awaitAssert {
        probe.expectMsgType[MetricInfoPut]
      }.metricInfo shouldBe metricInfo

      probe.send(metadataCoordinator, GetMetricInfo(db, namespace, metric))
      awaitAssert {
        probe.expectMsgType[MetricInfoGot]
      }.metricInfo shouldBe Some(metricInfo)
    }

    "not allow to insert a metric info already inserted" in {

      val metricInfo = MetricInfo(metric, 100)

      probe.send(metadataCoordinator, PutMetricInfo(db, namespace, metricInfo))
      awaitAssert {
        probe.expectMsgType[MetricInfoPut]
      }.metricInfo shouldBe metricInfo

      probe.send(metadataCoordinator, GetMetricInfo(db, namespace, metric))
      awaitAssert {
        probe.expectMsgType[MetricInfoGot]
      }.metricInfo shouldBe Some(metricInfo)

      probe.send(metadataCoordinator, PutMetricInfo(db, namespace, metricInfo))
      awaitAssert {
        probe.expectMsgType[MetricInfoFailed]
      }
    }
  }

}
