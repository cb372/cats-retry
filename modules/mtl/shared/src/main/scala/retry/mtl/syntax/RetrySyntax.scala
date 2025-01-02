package retry.mtl.syntax

import cats.effect.Temporal
import cats.mtl.Handle
import retry.{ResultHandler, RetryPolicy}

extension [F[_], A](action: F[A])
  def retryingOnMtlErrors[E](
      policy: RetryPolicy[F],
      errorHandler: ResultHandler[F, E, A]
  )(using T: Temporal[F], AH: Handle[F, E]): F[A] =
    retry.mtl.retryingOnErrors(action)(
      policy = policy,
      errorHandler = errorHandler
    )
