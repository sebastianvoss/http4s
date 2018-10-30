package com.example.http4s.blaze

import cats.effect.{ExitCode, IO, IOApp}
import org.http4s.HttpRoutes
import org.http4s.Request.Keys
import org.http4s.dsl.impl.Root
import org.http4s.dsl.io._
import org.http4s.server.SSLKeyStoreSupport
import org.http4s.server.blaze.BlazeServerBuilder

object BlazeMutualAuthExample extends IOApp {

  val service = HttpRoutes.of[IO] {
    case req@GET -> Root =>
      val sslInfo = req.attributes(Keys.SSLInfo)
      Ok(s"${sslInfo.certs.length}")
    case POST -> Root =>
      NoContent()
  }.orNotFound

  val keystorePath = "keystore.jks"
  val keystorePassword = "nosecret"

  val truststorePath = "truststore.jks"
  val truststorePassword = "nosecret"

  val keystore = SSLKeyStoreSupport.StoreInfo(keystorePath, keystorePassword)
  val truststore = Some(SSLKeyStoreSupport.StoreInfo(truststorePath, truststorePassword))

  override def run(args: List[String]): IO[ExitCode] =
    BlazeServerBuilder[IO]
      .withSSL(keystore, keystorePassword, "TLSv1.2", truststore, clientAuth = true)
      .bindHttp(8080, "0.0.0.0")
      .withHttpApp(service)
      .serve
      .compile
      .drain
      .map(_ => ExitCode.Success)

}
