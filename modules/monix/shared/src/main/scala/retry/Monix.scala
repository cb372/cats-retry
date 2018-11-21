package retry

import monix.eval.Task

import scala.concurrent.duration.FiniteDuration

object Monix {

  implicit val taskSleep: Sleep[Task] =
    (delay: FiniteDuration) => Task.sleep(delay)

}
