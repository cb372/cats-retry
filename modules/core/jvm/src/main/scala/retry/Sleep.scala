package retry

import cats.{Eval, Id}

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future, blocking}

trait Sleep[M[_]] {
  def sleep(delay: FiniteDuration): M[Unit]
}

object Sleep {
  def apply[M[_]](implicit sleep: Sleep[M]): Sleep[M] = sleep

  implicit val threadSleepId: Sleep[Id] = new Sleep[Id] {
    def sleep(delay: FiniteDuration): Id[Unit] = Thread.sleep(delay.toMillis)
  }

  implicit val threadSleepEval: Sleep[Eval] = new Sleep[Eval] {
    def sleep(delay: FiniteDuration): Eval[Unit] =
      Eval.later(Thread.sleep(delay.toMillis))
  }

  implicit def threadSleepFuture(implicit ec: ExecutionContext): Sleep[Future] =
    new Sleep[Future] {
      def sleep(delay: FiniteDuration): Future[Unit] =
        Future(blocking(Thread.sleep(delay.toMillis)))(ec)
    }
}
