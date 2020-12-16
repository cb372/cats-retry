package retry
package alleycats

import cats.{Eval, Id}
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Future, Promise}
import java.util.concurrent.Executors

object instances {
  implicit val threadSleepId: Sleep[Id] =
    (delay: FiniteDuration) => Thread.sleep(delay.toMillis)

  implicit val threadSleepEval: Sleep[Eval] =
    (delay: FiniteDuration) => Eval.later(Thread.sleep(delay.toMillis))

  private lazy val scheduler =
    Executors.newSingleThreadScheduledExecutor((runnable: Runnable) => {
      val t = new Thread(runnable)
      t.setDaemon(true)
      t.setName("cats-retry scheduler")
      t
    })

  implicit val threadSleepFuture: Sleep[Future] =
    (delay: FiniteDuration) => {
      val promise = Promise[Unit]()
      scheduler.schedule(
        new Runnable {
          def run(): Unit = {
            promise.success(())
            ()
          }
        },
        delay.length,
        delay.unit
      )
      promise.future
    }
}
