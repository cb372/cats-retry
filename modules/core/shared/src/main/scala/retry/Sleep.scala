package retry

import cats.effect.Temporal

import scala.concurrent.duration.FiniteDuration

trait Sleep[M[_]]:
  def sleep(delay: FiniteDuration): M[Unit]

object Sleep:
  def apply[M[_]](using sleep: Sleep[M]): Sleep[M] = sleep

  given [F[_]](using t: Temporal[F]): Sleep[F] =
    (delay: FiniteDuration) => t.sleep(delay)
