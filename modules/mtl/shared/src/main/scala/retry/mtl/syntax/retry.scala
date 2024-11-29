package retry.mtl.syntax

import cats.Monad
import cats.mtl.Handle
import retry.{ResultHandler, RetryDetails, RetryPolicy, Sleep}

trait RetrySyntax:
  // TODO how to migrate this to scala 3?
  implicit final def retrySyntaxMtlError[M[_], A](
      action: => M[A]
  )(using M: Monad[M]): RetryingMtlErrorOps[M, A] =
    new RetryingMtlErrorOps[M, A](action)

final class RetryingMtlErrorOps[M[_], A](action: => M[A])(using
    M: Monad[M]
):

  def retryingOnMtlErrors[E](
      policy: RetryPolicy[M],
      errorHandler: ResultHandler[M, E, A]
  )(using S: Sleep[M], AH: Handle[M, E]): M[A] =
    retry.mtl.retryingOnErrors(
      policy = policy,
      errorHandler = errorHandler
    )(action)
