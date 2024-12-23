package retry

import cats.{Applicative, Functor}
import cats.syntax.functor.*

/** A handler that inspects the result of an action and decides what to do next. This is also a good place to
  * do any logging.
  */
type ResultHandler[F[_], -Res, A] = (Res, RetryDetails) => F[HandlerDecision[F[A]]]

// Type aliases for different flavours of handler
type ValueHandler[F[_], A]        = ResultHandler[F, A, A]
type ErrorHandler[F[_], A]        = ResultHandler[F, Throwable, A]
type ErrorOrValueHandler[F[_], A] = ResultHandler[F, Either[Throwable, A], A]

object ResultHandler:
  /** Construct an ErrorHandler that always chooses to retry the same action, no matter what the error.
    *
    * @param log
    *   A chance to do logging, increment metrics, etc
    */
  def retryOnAllErrors[F[_]: Functor, A](
      log: (Throwable, RetryDetails) => F[Unit]
  ): ErrorHandler[F, A] =
    (error: Throwable, retryDetails: RetryDetails) => log(error, retryDetails).as(HandlerDecision.Continue)

  /** Construct a ValueHandler that chooses to retry the same action until it returns a successful result.
    *
    * @param log
    *   A chance to do logging, increment metrics, etc
    */
  def retryUntilSuccessful[F[_]: Functor, A](
      isSuccessful: A => Boolean,
      log: (A, RetryDetails) => F[Unit]
  ): ValueHandler[F, A] =
    (value: A, retryDetails: RetryDetails) =>
      log(value, retryDetails)
        .as(if isSuccessful(value) then HandlerDecision.Stop else HandlerDecision.Continue)

  /** Pass this to [[retryOnAllErrors]] or [[retryUntilSuccessful]] if you don't need to do any logging */
  def noop[F[_]: Applicative, A]: (A, RetryDetails) => F[Unit] =
    (_, _) => Applicative[F].unit
