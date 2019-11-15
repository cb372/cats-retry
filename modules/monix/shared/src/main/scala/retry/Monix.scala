package retry

import monix.eval.Task

import scala.concurrent.duration.FiniteDuration

trait Monix {
  implicit val taskSleep: Sleep[Task] = new Sleep[Task] {
    def sleep(delay: FiniteDuration): Task[Unit] =
      Task.sleep(delay)
  }
}

object Monix extends Monix
