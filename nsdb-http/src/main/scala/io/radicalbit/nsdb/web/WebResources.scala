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

package io.radicalbit.nsdb.web

import java.util.concurrent.TimeUnit

import akka.Done
import akka.actor.ActorRef
import akka.event.LoggingAdapter
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import akka.util.Timeout
import com.typesafe.config.Config
import io.radicalbit.nsdb.security.NsdbSecurity
import io.radicalbit.nsdb.ui.StaticResources
import org.json4s.DefaultFormats

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success}

/**
  * Instantiate NSDb Http Server exposing apis defined in [[ApiResources]]
  * If SSL/TLS protocol is enable in [[SSLSupport]] an Https server is started instead Http ones.
  */
trait WebResources extends StaticResources with WsResources with CorsSupport with SSLSupport { this: NsdbSecurity =>

  def config: Config

  implicit val formats = DefaultFormats

  implicit lazy val materializer = ActorMaterializer()
  implicit lazy val dispatcher   = system.dispatcher
  implicit lazy val httpTimeout: Timeout =
    Timeout(config.getDuration("nsdb.http-endpoint.timeout", TimeUnit.SECONDS), TimeUnit.SECONDS)

  def initWebEndpoint(writeCoordinator: ActorRef, readCoordinator: ActorRef, publisher: ActorRef)(
      implicit logger: LoggingAdapter) =
    authProvider match {
      case Success(provider) =>
        val api: Route = wsResources(publisher, provider) ~ new ApiResources(publisher,
                                                                             readCoordinator,
                                                                             writeCoordinator,
                                                                             provider).apiResources(config)

        val httpExt = akka.http.scaladsl.Http()

        val http: Future[Http.ServerBinding] =
          if (isSSLEnabled) {
            val port = config.getInt("nsdb.http.https-port")
            logger.info(s"Cluster Apis started with https protocol on port $port")
            httpExt.bindAndHandle(withCors(api),
                                  config.getString("nsdb.http.interface"),
                                  config.getInt("nsdb.http.https-port"),
                                  connectionContext = serverContext)
          } else {
            val port = config.getInt("nsdb.http.port")
            logger.info(s"Cluster Apis started with http protocol on port $port")
            httpExt.bindAndHandle(withCors(api), config.getString("nsdb.http.interface"), port)
          }

        val uiEnabled = config.getBoolean("nsdb.ui.enabled")

        val httpUI =
          if (uiEnabled) {
            if (isSSLEnabled) {
              val port = config.getInt("nsdb.ui.https-port")
              logger.info(s"Cluster UI started with https protocol on port $port")
              httpExt
                .bindAndHandle(withCors(staticResources),
                               config.getString("nsdb.ui.interface"),
                               config.getInt("nsdb.ui.https-port"),
                               connectionContext = serverContext)
                .map(Some(_))
            } else {
              val port = config.getInt("nsdb.ui.port")
              logger.info(s"Cluster UI started with http protocol on port $port")
              httpExt
                .bindAndHandle(withCors(staticResources), config.getString("nsdb.http.interface"), port)
                .map(Some(_))
            }
          } else {
            logger.info(s"UI is disabled")
            Future(None)
          }

        scala.sys.addShutdownHook {
          Future
            .sequence(
              Seq(http.flatMap(_.unbind()), httpUI.flatMap(opt => opt.map(_.unbind()).getOrElse(Future(Done)))))
            .onComplete { _ =>
              system.terminate()
            }
          Await.result(system.whenTerminated, 60 seconds)
        }
      case Failure(ex) =>
        logger.error("error on loading authorization provider", ex)
        System.exit(1)
    }

}
