package retry.alleycats

import cats.instances.future._
import munit.FunSuite
import retry._
import retry.alleycats.instances._

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration._
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

class PackageObjectLazinessSpec extends FunSuite {

  // this test reproduces issue #116
  test(
    "retryingOnFailures does not evaluate the next attempt until it has finished sleeping"
  ) {
    new TestContext {
      val policy = RetryPolicies.constantDelay[Future](5.second)

      // Kick off an operation which will fail, wait 5 seconds, and repeat forever, in a Future
      val _ = retryingOnFailures[String][Future](
        policy,
        _ => false,
        (a, retryDetails) => onError(a, retryDetails)
      ) {
        Future {
          attempts = attempts + 1
          attempts.toString
        }
      }

      // Wait for the first attempt to complete. By now we should be part way through the first delay.
      Thread.sleep(1000)

      // Assert that we haven't eagerly evaluated any more attempts.
      assert(attempts == 1)
      assert(errors.toList == List("1"))
      assert(delays.toList == List(5.second))
    }
  }

  private class TestContext {
    var attempts = 0
    val errors   = ArrayBuffer.empty[String]
    val delays   = ArrayBuffer.empty[FiniteDuration]
    var gaveUp   = false

    def onError(error: String, details: RetryDetails): Future[Unit] =
      Future.successful {
        errors.append(error)
        details match {
          case RetryDetails.WillDelayAndRetry(delay, _, _) =>
            delays.append(delay)
            ()
          case RetryDetails.GivingUp(_, _) => gaveUp = true
        }
      }
  }
}
