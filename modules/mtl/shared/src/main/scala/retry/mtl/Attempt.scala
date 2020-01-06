package retry.mtl

import cats.MonadError
import cats.mtl.ApplicativeHandle
import cats.syntax.functor._

object Attempt {

  def attempt[M[_], ME, AH, A](action: => M[A])(
      implicit ME: MonadError[M, ME],
      AH: ApplicativeHandle[M, AH]
  ): M[Result[ME, AH, A]] =
    ME.attempt(AH.attempt(action)).map {
      case Left(error)         => Result.MonadErrorRaised(error)
      case Right(Left(error))  => Result.ApplicativeHandleRaised(error)
      case Right(Right(value)) => Result.Success(value)
    }

  def rethrow[M[_], ME, AH, A](result: Result[ME, AH, A])(
      implicit ME: MonadError[M, ME],
      AH: ApplicativeHandle[M, AH]
  ): M[A] =
    result match {
      case Result.MonadErrorRaised(error)        => ME.raiseError(error)
      case Result.ApplicativeHandleRaised(error) => AH.raise(error)
      case Result.Success(value)                 => ME.pure(value)
    }

  /**
    * Represents a result of the execution attempt
    *
    * @tparam ME the error type that can be produced by [[MonadError]]
    * @tparam AH the error type that can be produced by [[ApplicativeHandle]]
    * @tparam A the result type
    */
  sealed abstract class Result[+ME, +AH, +A]

  object Result {
    final case class Success[A](value: A) extends Result[Nothing, Nothing, A]

    final case class ApplicativeHandleRaised[AH](cause: AH)
        extends Result[Nothing, AH, Nothing]

    final case class MonadErrorRaised[ME](cause: ME)
        extends Result[ME, Nothing, Nothing]
  }

}
