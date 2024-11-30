package retry.mtl.syntax

import cats.effect.Temporal
import cats.mtl.Handle
import retry.{ResultHandler, RetryPolicy}

trait RetrySyntax:
  implicit final def retrySyntaxMtlError[M[_], A](
      action: => M[A]
  ): RetryingMtlErrorOps[M, A] =
    new RetryingMtlErrorOps[M, A](action)

final class RetryingMtlErrorOps[M[_], A](action: => M[A]):

  def retryingOnMtlErrors[E](
      policy: RetryPolicy[M],
      errorHandler: ResultHandler[M, E, A]
  )(using T: Temporal[M], AH: Handle[M, E]): M[A] =
    retry.mtl.retryingOnErrors(
      policy = policy,
      errorHandler = errorHandler
    )(action)
