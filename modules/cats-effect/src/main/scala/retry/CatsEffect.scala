package retry

import cats.effect.Timer

import scala.concurrent.duration.FiniteDuration

object CatsEffect {

  implicit def sleepUsingTimer[F[_]](implicit timer: Timer[F]): Sleep[F] =
    (delay: FiniteDuration) => timer.sleep(delay)

}
