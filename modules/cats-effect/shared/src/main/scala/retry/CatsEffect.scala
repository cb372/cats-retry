package retry

import scala.concurrent.duration.FiniteDuration

import cats.effect.Timer

object CatsEffect {

  implicit def sleepUsingTimer[F[_]](implicit timer: Timer[F]): Sleep[F] =
    new Sleep[F] {
      def sleep(delay: FiniteDuration): F[Unit] = timer.sleep(delay)
    }
}
