package retry

import cats.{Eval, Id}
import cats.effect.Timer
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future, blocking}

trait Sleep[M[_]] {
  def sleep(delay: FiniteDuration): M[Unit]
}

object Sleep {
  def apply[M[_]](implicit sleep: Sleep[M]): Sleep[M] = sleep

  implicit def sleepUsingTimer[F[_]](implicit timer: Timer[F]): Sleep[F] =
    new Sleep[F] {
      def sleep(delay: FiniteDuration): F[Unit] = timer.sleep(delay)
    }
}
