package retry

import cats.effect.kernel.Temporal

import scala.concurrent.duration.FiniteDuration

trait Sleep[M[_]] {
  def sleep(delay: FiniteDuration): M[Unit]
}

object Sleep {
  def apply[M[_]](implicit sleep: Sleep[M]): Sleep[M] = sleep

  implicit def sleepUsingTimer[F[_]](implicit t: Temporal[F]): Sleep[F] =
    (delay: FiniteDuration) => t.sleep(delay)
}
