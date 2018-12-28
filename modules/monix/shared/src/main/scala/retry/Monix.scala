package retry

import monix.eval.Task

import scala.concurrent.duration.FiniteDuration

object Monix {

  implicit val taskSleep: Sleep[Task] = new Sleep[Task] {
    def sleep(delay: FiniteDuration): Task[Unit] =
      Task.sleep(delay)
  }
}
