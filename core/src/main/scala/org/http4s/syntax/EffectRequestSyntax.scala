package org.http4s
package syntax

import cats._
import cats.implicits._

trait EffectRequestSyntax {
  implicit def http4sEffectRequestSyntax[F[+_]: Functor](req: F[Request[F]]): EffectRequestOps[F] =
    new EffectRequestOps[F](req)
}

final class EffectRequestOps[F[+_]: Functor](val self: F[Request[F]])
    extends EffectMessageSyntax[F, Request[F]]
    with RequestOps[F] {
  def decodeWith[A](decoder: EntityDecoder[F, A], strict: Boolean)(f: A => F[Response[F]])(implicit F: Monad[F]): F[Response[F]] =
    self.flatMap(_.decodeWith(decoder, strict)(f))

  def withPathInfo(pi: String): F[Request[F]] =
    self.map(_.withPathInfo(pi))
}