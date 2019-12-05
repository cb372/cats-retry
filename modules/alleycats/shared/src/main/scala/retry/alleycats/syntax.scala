package retry
package alleycats

import cats.{Id, Monad}

object syntax {
  def retrying[A](
      policy: RetryPolicy[Id],
      wasSuccessful: A => Boolean,
      onFailure: (A, RetryDetails) => Unit
  )(
      action: => A
  )(
      implicit
      M: Monad[Id],
      S: Sleep[Id]
  ): A =
    retryingM[A][Id](policy, wasSuccessful, onFailure)(action)
}
