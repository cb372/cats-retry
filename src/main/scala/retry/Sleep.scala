package retry

import cats.{Eval, Id}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.blocking

trait Sleep[M[_]] {

  def sleep(delay: FiniteDuration): M[Unit]

}

object Sleep {

  def apply[M[_]](implicit sleep: Sleep[M]): Sleep[M] = sleep

  implicit val threadSleepId: Sleep[Id] = (delay: FiniteDuration) =>
    Thread.sleep(delay.toMillis)

  implicit val threadSleepEval: Sleep[Eval] = (delay: FiniteDuration) =>
    Eval.later(Thread.sleep(delay.toMillis))

  implicit def threadSleepFuture(implicit ec: ExecutionContext): Sleep[Future] =
    (delay: FiniteDuration) =>
      Future(blocking(Thread.sleep(delay.toMillis)))(ec)

}

// TODO cats-effect instance in separate module
