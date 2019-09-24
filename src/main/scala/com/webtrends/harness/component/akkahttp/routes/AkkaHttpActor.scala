package com.webtrends.harness.component.akkahttp.routes

import java.io.{FileInputStream, InputStream}
import java.security.{KeyStore, SecureRandom}

import akka.actor.ActorSystem
import akka.http.scaladsl.server.{Route, RouteResult}
import akka.http.scaladsl.settings.ServerSettings
import akka.http.scaladsl.{ConnectionContext, Http, HttpsConnectionContext}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import com.webtrends.harness.app.HActor
import com.webtrends.harness.component.StopComponent
import com.webtrends.harness.component.akkahttp.AkkaHttpManager
import com.webtrends.harness.component.akkahttp.client.SimpleHttpClient
import com.webtrends.harness.health.{ComponentState, HealthComponent}
import com.webtrends.harness.utils.FutureExtensions._
import javax.net.ssl.{KeyManagerFactory, SSLContext, TrustManagerFactory}

import scala.collection.mutable.ListBuffer
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}

trait AkkaHttpActor extends HActor with SimpleHttpClient {
  implicit val system: ActorSystem = context.system
  implicit val executionContext: ExecutionContextExecutor = context.dispatcher
  override implicit val materializer: ActorMaterializer = ActorMaterializer()

  val port: Int
  val interface: String
  val settings: ServerSettings
  val httpsPort: Option[Int]

  val pingUrl = s"http://$interface:$port/ping"
  val boundFutures: ListBuffer[Future[Http.ServerBinding]] = ListBuffer.empty[Future[Http.ServerBinding]]

  val httpsServerSource = httpsPort.map { sslPort =>
    // Manual HTTPS configuration

    val certFile: String = config.getString(s"${AkkaHttpManager.ComponentName}.ssl-cert-file")
    val password: Array[Char] = config.getString(
      s"${AkkaHttpManager.ComponentName}.ssl-cert-pwd").toCharArray

    val ks: KeyStore = KeyStore.getInstance("PKCS12")
    val keystore: InputStream = new FileInputStream(certFile)

    require(keystore != null, "Keystore required!")
    ks.load(keystore, password)

    val keyManagerFactory: KeyManagerFactory = KeyManagerFactory.getInstance("SunX509")
    keyManagerFactory.init(ks, password)

    val tmf: TrustManagerFactory = TrustManagerFactory.getInstance("SunX509")
    tmf.init(ks)

    val sslContext: SSLContext = SSLContext.getInstance("TLS")
    sslContext.init(keyManagerFactory.getKeyManagers, tmf.getTrustManagers, new SecureRandom)
    val https: HttpsConnectionContext = ConnectionContext.https(sslContext)

    Http().bind(interface, sslPort, https, settings)
  }

  val serverSource: Source[Http.IncomingConnection, Future[Http.ServerBinding]] =
    Http().bind(interface, port, settings = settings)

  def routes: Route

  def bindFuture(source: Source[Http.IncomingConnection, Future[Http.ServerBinding]]): Unit = {
    val bindingFuture: Future[Http.ServerBinding] = source
      .to(Sink.foreach { conn => conn.handleWith(RouteResult.route2HandlerFlow(routes)) })
      .run()

    bindingFuture.onComplete {
      case Success(_) =>
        log.info(s"Actor ${getClass.getSimpleName} bound to port $port on interface $interface")
      case Failure(f) =>
        log.error(s"Failed to bind akka-http external-server: $f")
    }

    boundFutures += bindingFuture
  }

  bindFuture(serverSource)
  httpsServerSource.foreach(bindFuture)

  def unbind(): Unit = boundFutures.foreach(f => f.flatMap(_.unbind()))


  override def receive: PartialFunction[Any, Unit] = super.receive orElse {
    case AkkaHttpUnbind => unbind()
    case StopComponent => unbind()
  }

  override def checkHealth : Future[HealthComponent] = {
    getPing(pingUrl).mapAll {
      case Success(true) =>
        HealthComponent(self.path.toString, ComponentState.NORMAL, s"Healthy: Ping to $pingUrl.")
      case Success(false) =>
        HealthComponent(self.path.toString, ComponentState.CRITICAL, s"Failed to ping server at $pingUrl.")
      case Failure(_) =>
        HealthComponent(self.path.toString, ComponentState.CRITICAL, s"Unexpected error pinging server.")
    }
  }
}
