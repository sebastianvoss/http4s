package org.http4s
package server
package blaze

import cats._
import cats.data.Kleisli
import cats.implicits._
import cats.effect._
import java.io.FileInputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import javax.security.cert.X509Certificate
import java.security.{KeyStore, Security}
import javax.net.ssl.{KeyManagerFactory, SSLContext, SSLEngine, TrustManagerFactory}
import org.http4s.blaze.{BuildInfo => BlazeBuildInfo}
import org.http4s.blaze.channel
import org.http4s.blaze.channel.SocketConnection
import org.http4s.blaze.channel.nio1.NIO1SocketServerGroup
import org.http4s.blaze.channel.nio2.NIO2SocketServerGroup
import org.http4s.blaze.http.http2.server.ALPNServerSelector
import org.http4s.blaze.pipeline.LeafBuilder
import org.http4s.blaze.pipeline.stages.{QuietTimeoutStage, SSLStage}
import org.http4s.server.SSLKeyStoreSupport.StoreInfo
import org.log4s.getLogger
import scala.collection.immutable
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.Try

/**
  * BlazeBuilder is the component for the builder pattern aggregating
  * different components to finally serve requests.
  *
  * Variables:
  * @param socketAddress: Socket Address the server will be mounted at
  * @param executionContext: Execution Context the underlying blaze futures
  *    will be executed upon.
  * @param responseHeaderTimeout: Time from when the request is made until a
  *    response line is generated before a 503 response is returned and the
  *    `HttpApp` is canceled
  * @param idleTimeout: Period of Time a connection can remain idle before the
  *    connection is timed out and disconnected.
  *    Duration.Inf disables this feature.
  * @param isNio2: Whether or not to use NIO2 or NIO1 Socket Server Group
  * @param connectorPoolSize: Number of worker threads for the new Socket Server Group
  * @param bufferSize: Buffer size to use for IO operations
  * @param enableWebsockets: Enables Websocket Support
  * @param sslBits: If defined enables secure communication to the server using the
  *    sslContext
  * @param isHttp2Enabled: Whether or not to enable Http2 Server Features
  * @param maxRequestLineLength: Maximum request line to parse
  *    If exceeded returns a 400 Bad Request.
  * @param maxHeadersLen: Maximum data that composes the headers.
  *    If exceeded returns a 400 Bad Request.
  * @param serviceMounts: The services that are mounted on this server to serve.
  *    These services get assembled into a Router with the longer prefix winning.
  * @param serviceErrorHandler: The last resort to recover and generate a response
  *    this is necessary to recover totality from the error condition.
  * @param banner: Pretty log to display on server start. An empty sequence
  *    such as Nil disables this
  */
class BlazeServerBuilder[F[_]](
    socketAddress: InetSocketAddress,
    executionContext: ExecutionContext,
    responseHeaderTimeout: Duration,
    idleTimeout: Duration,
    isNio2: Boolean,
    connectorPoolSize: Int,
    bufferSize: Int,
    enableWebSockets: Boolean,
    sslBits: Option[SSLConfig],
    isHttp2Enabled: Boolean,
    maxRequestLineLen: Int,
    maxHeadersLen: Int,
    httpApp: HttpApp[F],
    serviceErrorHandler: ServiceErrorHandler[F],
    banner: immutable.Seq[String]
)(implicit protected val F: ConcurrentEffect[F], timer: Timer[F])
    extends ServerBuilder[F]
    with IdleTimeoutSupport[F]
    with SSLKeyStoreSupport[F]
    with SSLContextSupport[F]
    with server.WebSocketSupport[F] {
  type Self = BlazeServerBuilder[F]

  private[this] val logger = getLogger

  private def copy(
      socketAddress: InetSocketAddress = socketAddress,
      executionContext: ExecutionContext = executionContext,
      idleTimeout: Duration = idleTimeout,
      responseHeaderTimeout: Duration = responseHeaderTimeout,
      isNio2: Boolean = isNio2,
      connectorPoolSize: Int = connectorPoolSize,
      bufferSize: Int = bufferSize,
      enableWebSockets: Boolean = enableWebSockets,
      sslBits: Option[SSLConfig] = sslBits,
      http2Support: Boolean = isHttp2Enabled,
      maxRequestLineLen: Int = maxRequestLineLen,
      maxHeadersLen: Int = maxHeadersLen,
      httpApp: HttpApp[F] = httpApp,
      serviceErrorHandler: ServiceErrorHandler[F] = serviceErrorHandler,
      banner: immutable.Seq[String] = banner
  ): Self =
    new BlazeServerBuilder(
      socketAddress,
      executionContext,
      responseHeaderTimeout,
      idleTimeout,
      isNio2,
      connectorPoolSize,
      bufferSize,
      enableWebSockets,
      sslBits,
      http2Support,
      maxRequestLineLen,
      maxHeadersLen,
      httpApp,
      serviceErrorHandler,
      banner
    )

  /** Configure HTTP parser length limits
    *
    * These are to avoid denial of service attacks due to,
    * for example, an infinite request line.
    *
    * @param maxRequestLineLen maximum request line to parse
    * @param maxHeadersLen maximum data that compose headers
    */
  def withLengthLimits(
      maxRequestLineLen: Int = maxRequestLineLen,
      maxHeadersLen: Int = maxHeadersLen): Self =
    copy(maxRequestLineLen = maxRequestLineLen, maxHeadersLen = maxHeadersLen)

  override def withSSL(
      keyStore: StoreInfo,
      keyManagerPassword: String,
      protocol: String,
      trustStore: Option[StoreInfo],
      clientAuth: Boolean): Self = {
    val bits = KeyStoreBits(keyStore, keyManagerPassword, protocol, trustStore, clientAuth)
    copy(sslBits = Some(bits))
  }

  override def withSSLContext(sslContext: SSLContext, clientAuth: Boolean): Self =
    copy(sslBits = Some(SSLContextBits(sslContext, clientAuth)))

  override def bindSocketAddress(socketAddress: InetSocketAddress): Self =
    copy(socketAddress = socketAddress)

  def withExecutionContext(executionContext: ExecutionContext): BlazeServerBuilder[F] =
    copy(executionContext = executionContext)

  override def withIdleTimeout(idleTimeout: Duration): Self = copy(idleTimeout = idleTimeout)

  def withResponseHeaderTimeout(responseHeaderTimeout: Duration): Self =
    copy(responseHeaderTimeout = responseHeaderTimeout)

  def withConnectorPoolSize(size: Int): Self = copy(connectorPoolSize = size)

  def withBufferSize(size: Int): Self = copy(bufferSize = size)

  def withNio2(isNio2: Boolean): Self = copy(isNio2 = isNio2)

  override def withWebSockets(enableWebsockets: Boolean): Self =
    copy(enableWebSockets = enableWebsockets)

  def enableHttp2(enabled: Boolean): Self = copy(http2Support = enabled)

  def withHttpApp(httpApp: HttpApp[F]): Self =
    copy(httpApp = httpApp)

  def withServiceErrorHandler(serviceErrorHandler: ServiceErrorHandler[F]): Self =
    copy(serviceErrorHandler = serviceErrorHandler)

  def withBanner(banner: immutable.Seq[String]): Self =
    copy(banner = banner)

  def resource: Resource[F, Server[F]] =
    Resource(F.delay {

      def resolveAddress(address: InetSocketAddress) =
        if (address.isUnresolved) new InetSocketAddress(address.getHostName, address.getPort)
        else address

      def connectionInfo(secure: Boolean, local: InetSocketAddress, remote: InetSocketAddress) =
        AttributeEntry(
          Request.Keys.ConnectionInfo,
          Request.Connection(
            local = local,
            remote = remote,
            secure = secure
          )
        )

      def sslInfo(engine: SSLEngine) = {
        val session = engine.getSession
        val certs =
          Try {
            session.getPeerCertificateChain
          } handleError { t =>
            logger.warn(t)(t.getMessage)
            Array.empty[X509Certificate]
          }

        AttributeEntry(
          Request.Keys.SSLInfo,
          Request.SSLInfo(
            sessionId = session.getId,
            cipherSuite = session.getCipherSuite,
            certs = certs.getOrElse(Array.empty[X509Certificate])
          )
        )
      }

      val pipelineFactory: SocketConnection => Future[LeafBuilder[ByteBuffer]] = {
        conn: SocketConnection =>
          def requestAttributes(secure: Boolean, engine: Option[SSLEngine]) =
            (conn.local, conn.remote, engine) match {
              case (local: InetSocketAddress, remote: InetSocketAddress, Some(sslEngine)) =>
                AttributeMap(
                  connectionInfo(secure, local, remote),
                  sslInfo(sslEngine)
                )
              case (local: InetSocketAddress, remote: InetSocketAddress, None) =>
                AttributeMap(
                  connectionInfo(secure, local, remote)
                )
              case _ =>
                AttributeMap.empty
            }

          def http1Stage(secure: Boolean, engine: Option[SSLEngine]) =
            Http1ServerStage(
              httpApp,
              requestAttributes(secure = secure, engine),
              executionContext,
              enableWebSockets,
              maxRequestLineLen,
              maxHeadersLen,
              serviceErrorHandler,
              responseHeaderTimeout
            )

          def http2Stage(engine: SSLEngine): ALPNServerSelector =
            ProtocolSelector(
              engine,
              httpApp,
              maxRequestLineLen,
              maxHeadersLen,
              requestAttributes(secure = true, engine.some),
              executionContext,
              serviceErrorHandler,
              responseHeaderTimeout
            )

          def prependIdleTimeout(lb: LeafBuilder[ByteBuffer]) =
            if (idleTimeout.isFinite) lb.prepend(new QuietTimeoutStage[ByteBuffer](idleTimeout))
            else lb

          Future.successful {
            getContext() match {
              case Some((ctx, clientAuth)) =>
                val engine = ctx.createSSLEngine()
                engine.setUseClientMode(false)
                engine.setNeedClientAuth(clientAuth)

                var lb = LeafBuilder(
                  if (isHttp2Enabled) http2Stage(engine)
                  else http1Stage(secure = true, engine.some)
                )
                lb = prependIdleTimeout(lb)
                lb.prepend(new SSLStage(engine))

              case None =>
                if (isHttp2Enabled)
                  logger.warn("HTTP/2 support requires TLS. Falling back to HTTP/1.")
                var lb = LeafBuilder(http1Stage(secure = false, None))
                lb = prependIdleTimeout(lb)
                lb
            }
          }
      }

      val factory =
        if (isNio2)
          NIO2SocketServerGroup.fixedGroup(connectorPoolSize, bufferSize)
        else
          NIO1SocketServerGroup.fixedGroup(connectorPoolSize, bufferSize)

      val address = resolveAddress(socketAddress)

      // if we have a Failure, it will be caught by the effect
      val serverChannel = factory.bind(address, pipelineFactory).get

      val server = new Server[F] {
        val address: InetSocketAddress =
          serverChannel.socketAddress

        val isSecure = sslBits.isDefined

        override def toString: String =
          s"BlazeServer($address)"
      }

      val shutdown = F.delay {
        serverChannel.close()
        factory.closeGroup()
      }

      Option(banner)
        .filter(_.nonEmpty)
        .map(_.mkString("\n", "\n", ""))
        .foreach(logger.info(_))

      logger.info(
        s"http4s v${BuildInfo.version} on blaze v${BlazeBuildInfo.version} started at ${server.baseUri}")

      server -> shutdown
    })

  private def getContext(): Option[(SSLContext, Boolean)] = sslBits.map {
    case KeyStoreBits(keyStore, keyManagerPassword, protocol, trustStore, clientAuth) =>
      val ksStream = new FileInputStream(keyStore.path)
      val ks = KeyStore.getInstance("JKS")
      ks.load(ksStream, keyStore.password.toCharArray)
      ksStream.close()

      val tmf = trustStore.map { auth =>
        val ksStream = new FileInputStream(auth.path)

        val ks = KeyStore.getInstance("JKS")
        ks.load(ksStream, auth.password.toCharArray)
        ksStream.close()

        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)

        tmf.init(ks)
        tmf.getTrustManagers
      }

      val kmf = KeyManagerFactory.getInstance(
        Option(Security.getProperty("ssl.KeyManagerFactory.algorithm"))
          .getOrElse(KeyManagerFactory.getDefaultAlgorithm))

      kmf.init(ks, keyManagerPassword.toCharArray)

      val context = SSLContext.getInstance(protocol)
      context.init(kmf.getKeyManagers, tmf.orNull, null)

      (context, clientAuth)

    case SSLContextBits(context, clientAuth) =>
      (context, clientAuth)
  }
}

object BlazeServerBuilder {
  def apply[F[_]](implicit F: ConcurrentEffect[F], timer: Timer[F]): BlazeServerBuilder[F] =
    new BlazeServerBuilder(
      socketAddress = ServerBuilder.DefaultSocketAddress,
      executionContext = ExecutionContext.global,
      responseHeaderTimeout = 1.minute,
      idleTimeout = IdleTimeoutSupport.DefaultIdleTimeout,
      isNio2 = false,
      connectorPoolSize = channel.DefaultPoolSize,
      bufferSize = 64 * 1024,
      enableWebSockets = true,
      sslBits = None,
      isHttp2Enabled = false,
      maxRequestLineLen = 4 * 1024,
      maxHeadersLen = 40 * 1024,
      httpApp = defaultApp[F],
      serviceErrorHandler = DefaultServiceErrorHandler[F],
      banner = ServerBuilder.DefaultBanner
    )

  private def defaultApp[F[_]: Applicative]: HttpApp[F] =
    Kleisli(_ => Response[F](Status.NotFound).pure[F])
}
