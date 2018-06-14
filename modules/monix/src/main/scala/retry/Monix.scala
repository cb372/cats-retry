package retry

import monix.eval.Task
import monix.execution.{CancelableFuture, Scheduler}

import scala.concurrent.duration.FiniteDuration

object Monix {

  implicit def sleepUsingScheduler(
      implicit
      scheduler: Scheduler
  ): Sleep[CancelableFuture] =
    (delay: FiniteDuration) => Task.sleep(delay).runAsync

}
