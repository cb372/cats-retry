package retry

import retry.Monix._

import org.scalatest.FlatSpec
import monix.eval.Task
import monix.execution.schedulers.TestScheduler

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration._
import scala.util.Success

class MonixSpec extends FlatSpec {

  behavior of "retryingM"

  it should "retry until the action succeeds" in new TestContext {
    val policy = RetryPolicies.constantDelay[Task](10.second)

    val finalResult = retryingM[Int][Task](
      policy,
      _.toInt > 3,
      onError
    ) {
      Task {
        attempts = attempts + 1
        attempts
      }
    }

    val scheduler = TestScheduler()
    val f         = finalResult.runToFuture(scheduler)

    scheduler.tick(30.seconds)

    assert(f.value == Some(Success(4)))
    assert(attempts == 4)
    assert(errors.toList == List(1, 2, 3))
    assert(delays.toList == List(10.second, 10.second, 10.second))
    assert(!gaveUp)
  }

  private class TestContext {

    var attempts = 0
    val errors   = ArrayBuffer.empty[Int]
    val delays   = ArrayBuffer.empty[FiniteDuration]
    var gaveUp   = false

    def onError(error: Int, details: RetryDetails): Task[Unit] = {
      errors.append(error)
      details match {
        case RetryDetails.WillDelayAndRetry(delay, _, _) => delays.append(delay)
        case RetryDetails.GivingUp(_, _)                 => gaveUp = true
      }
      Task(())
    }

  }
}
