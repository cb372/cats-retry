package retry
package alleycats

import cats.{Eval, Id}
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Future, Promise}
import java.util.concurrent.{ThreadFactory, Executors}

object instances {
  implicit val threadSleepId: Sleep[Id] = new Sleep[Id] {
    def sleep(delay: FiniteDuration): Id[Unit] = Thread.sleep(delay.toMillis)
  }

  implicit val threadSleepEval: Sleep[Eval] = new Sleep[Eval] {
    def sleep(delay: FiniteDuration): Eval[Unit] =
      Eval.later(Thread.sleep(delay.toMillis))
  }

  private lazy val scheduler =
    Executors.newSingleThreadScheduledExecutor(new ThreadFactory {
      override def newThread(runnable: Runnable) = {
        val t = new Thread(runnable)
        t.setDaemon(true)
        t.setName("cats-retry scheduler")
        t
      }
    })

  implicit val threadSleepFuture: Sleep[Future] =
    new Sleep[Future] {
      def sleep(delay: FiniteDuration): Future[Unit] = {
        val promise = Promise[Unit]()
        scheduler.schedule(
          new Runnable {
            def run: Unit = {
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
}
