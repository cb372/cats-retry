package retry

import cats.effect.Timer
import scala.concurrent.duration.FiniteDuration

trait Sleep[M[_]] {
  def sleep(delay: FiniteDuration): M[Unit]
}

object Sleep {
  def apply[M[_]](implicit sleep: Sleep[M]): Sleep[M] = sleep

  implicit def sleepUsingTimer[F[_]](implicit timer: Timer[F]): Sleep[F] =
    (delay: FiniteDuration) => timer.sleep(delay)
}
