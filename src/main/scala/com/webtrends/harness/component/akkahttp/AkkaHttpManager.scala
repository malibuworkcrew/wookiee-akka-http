/*
 *  Copyright (c) 2020 Oracle and/or its affiliates. All rights reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.webtrends.harness.component.akkahttp

import java.util.concurrent.TimeUnit

import akka.actor.ActorRef
import akka.http.scaladsl.settings.ServerSettings
import com.typesafe.config.Config
import com.webtrends.harness.component.Component
import com.webtrends.harness.component.akkahttp.logging.AccessLog
import com.webtrends.harness.component.akkahttp.routes.{AkkaHttpUnbind, ExternalAkkaHttpActor, InternalAkkaHttpActor, WebsocketAkkaHttpActor}
import com.webtrends.harness.logging.Logger
import com.webtrends.harness.utils.ConfigUtil
import AkkaHttpManager._

import scala.concurrent.duration._
import scala.util.Try

case class AkkaHttpMessage()

class AkkaHttpManager(name:String) extends Component(name) {
  val settings = AkkaHttpSettings(config)
  val starMonitor = new Object()

  var internalAkkaHttpRef: Option[ActorRef] = None
  var externalAkkaHttpRef: Option[ActorRef] = None
  var wsAkkaHttpRef: Option[ActorRef] = None
  implicit val logger: Logger = log

  def startAkkaHttp(): Unit = {
    starMonitor.synchronized {
      log.info("Starting Wookiee Akka HTTP Actors...")
      internalAkkaHttpRef = Some(context.actorOf(InternalAkkaHttpActor.props(settings.internal), AkkaHttpManager.InternalAkkaHttpName))
      if (settings.external.enabled) {
        externalAkkaHttpRef = Some(context.actorOf(ExternalAkkaHttpActor.props(settings.external), AkkaHttpManager.ExternalAkkaHttpName))
      }
      if (settings.ws.enabled) {
        wsAkkaHttpRef = Some(context.actorOf(WebsocketAkkaHttpActor.props(settings.ws), AkkaHttpManager.WebsocketAkkaHttpName))
      }
      log.info("Wookiee Akka HTTP Actors Ready, Request Line is Open!")
    }
  }

  def stopAkkaHttp(): Unit = {
    Seq(internalAkkaHttpRef, externalAkkaHttpRef, wsAkkaHttpRef).flatten.foreach(_ ! AkkaHttpUnbind)
  }

  /**
   * We add super.receive because if you override the receive message from the component
   * and then do not include super.receive it will not handle messages from the
   * ComponentManager correctly and basically not start up properly
   *
   * @return
   */
  override def receive: PartialFunction[Any, Unit] = super.receive orElse {
    case AkkaHttpMessage => println("DO SOMETHING HERE")
  }

  /**
   * Start function will start any child actors that will be managed by the ComponentManager
    *
    * @return
   */
  override def start: Unit = {
    startAkkaHttp()
    super.start
  }

  /**
   * Stop will execute any cleanup work to be done for the child actors
   * if not necessary this can be deleted
    *
    * @return
   */
  override def stop: Unit = {
    stopAkkaHttp()
    super.stop
  }

}

object AkkaHttpManager {
  val ComponentName = "wookiee-akka-http"

  def KeyStaticRoot = s"$ComponentName.static-content.root-path"
  def KeyStaticType = s"$ComponentName.static-content.type"

  val ExternalAkkaHttpName = "ExternalAkkaHttp"
  val InternalAkkaHttpName = "InternalAkkaHttp"
  val WebsocketAkkaHttpName = "WebsocketAkkaHttp"
}

final case class InternalAkkaHttpSettings(interface: String, port: Int, serverSettings: ServerSettings, httpsPort: Option[Int])
final case class ExternalAkkaHttpSettings(enabled: Boolean, interface: String, port: Int,
                                          serverSettings: ServerSettings, httpsPort: Option[Int])
final case class WebsocketAkkaHttpSettings(enabled: Boolean, interface: String, port: Int, httpsPort: Option[Int],
                                           serverSettings: ServerSettings, keepAliveFrequency: FiniteDuration, keepAliveOn: Boolean)
final case class AkkaHttpSettings(internal: InternalAkkaHttpSettings, external: ExternalAkkaHttpSettings,
                                  ws: WebsocketAkkaHttpSettings)

object AkkaHttpSettings {
  val InternalServer = "internal-server"
  val ExternalServer = "external-server"
  val WebsocketServer = "websocket-server"

  def apply(config: Config): AkkaHttpSettings = {
    def getHttps(server: String): Option[Int] = {
      if (config.hasPath(s"$ComponentName.$server.https-port"))
        Some(config.getInt(s"$ComponentName.$server.https-port"))
      else None
    }

    val internalPort = ConfigUtil.getDefaultValue(s"$ComponentName.$InternalServer.http-port", config.getInt, 8080)
    val internalInterface = ConfigUtil.getDefaultValue(s"$ComponentName.$InternalServer.interface", config.getString, "0.0.0.0")
    val internalHttpsPort = getHttps(InternalServer)

    val externalServerEnabled = ConfigUtil.getDefaultValue(
      s"$ComponentName.$ExternalServer.enabled", config.getBoolean, false)
    val externalPort = ConfigUtil.getDefaultValue(
      s"$ComponentName.$ExternalServer.http-port", config.getInt, 8082)
    val externalHttpsPort = getHttps(ExternalServer)
    val externalInterface = ConfigUtil.getDefaultValue(
      s"$ComponentName.$ExternalServer.interface", config.getString, "0.0.0.0")
    val wsEnabled = ConfigUtil.getDefaultValue(
      s"$ComponentName.$WebsocketServer.enabled", config.getBoolean, false)
    val wsPort = ConfigUtil.getDefaultValue(
      s"$ComponentName.$WebsocketServer.port", config.getInt, 8081)
    val wssPort = getHttps(WebsocketServer)
    val wsInterface = ConfigUtil.getDefaultValue(
      s"$ComponentName.$WebsocketServer.interface", config.getString, "0.0.0.0")
    // How often to send a keep alive heartbeat message back
    val keepAliveFrequency: FiniteDuration = Try(config.getDuration(
      s"$ComponentName.websocket-keep-alives.interval", TimeUnit.SECONDS).toInt).getOrElse(30) seconds
    val keepAliveOn: Boolean = Try(config.getBoolean(
      s"$ComponentName.websocket-keep-alives.enabled")).getOrElse(false)
    val serverSettings = ServerSettings(config)


    AkkaHttpSettings(
      InternalAkkaHttpSettings(internalInterface, internalPort, serverSettings, internalHttpsPort),
      ExternalAkkaHttpSettings(externalServerEnabled, externalInterface, externalPort, serverSettings, externalHttpsPort),
      WebsocketAkkaHttpSettings(wsEnabled, wsInterface, wsPort, wssPort, serverSettings, keepAliveFrequency, keepAliveOn)
    )
  }
}