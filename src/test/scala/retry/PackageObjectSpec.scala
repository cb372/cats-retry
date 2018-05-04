package retry

import cats.Id
import cats.instances.either._
import org.scalatest.FlatSpec

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration._

class PackageObjectSpec extends FlatSpec {

  behavior of "retrying"

  it should "retry until the action succeeds" in {
    val policy = RetryPolicies.constantDelay[Id](1.second)

    var attempts      = 0
    val failedResults = ArrayBuffer.empty[Int]
    val delays        = ArrayBuffer.empty[FiniteDuration]
    val sleeps        = ArrayBuffer.empty[FiniteDuration]
    var gaveUp        = false

    implicit val dummySleep: Sleep[Id] =
      (delay: FiniteDuration) => sleeps.append(delay)

    def onFailure(x: Int, nextStep: NextStep): Unit = {
      failedResults.append(x)
      nextStep match {
        case WillRetryAfterDelay(delay, prev, updated) =>
          println(s"Previous: $prev ... Updated: $updated")
          delays.append(delay)
        case WillGiveUp(_) => gaveUp = true
      }
    }

    val finalResult = retrying[Int][Id](
      policy,
      _ > 3,
      onFailure
    ) {
      attempts = attempts + 1
      attempts
    }

    assert(finalResult == 4)
    assert(attempts == 4)
    assert(failedResults.toList == List(1, 2, 3))
    assert(delays.toList == List(1.second, 1.second, 1.second))
    assert(sleeps.toList == delays.toList)
    assert(!gaveUp)
  }

  it should "retry until the policy chooses to give up" in {
    val policy = RetryPolicies.limitRetries[Id](2)

    var attempts      = 0
    val failedResults = ArrayBuffer.empty[Int]
    val delays        = ArrayBuffer.empty[FiniteDuration]
    var gaveUp        = false

    def onFailure(x: Int, nextStep: NextStep): Unit = {
      failedResults.append(x)
      nextStep match {
        case WillRetryAfterDelay(delay, _, _) => delays.append(delay)
        case WillGiveUp(_)                    => gaveUp = true
      }
    }

    val finalResult = retrying[Int][Id](
      policy,
      _ > 3,
      onFailure
    ) {
      attempts = attempts + 1
      attempts
    }

    assert(finalResult == 3)
    assert(attempts == 3)
    assert(failedResults.toList == List(1, 2, 3))
    assert(delays.toList == List(Duration.Zero, Duration.Zero))
    assert(gaveUp)
  }

  behavior of "retryingOnSomeErrors"

  it should "retry only if the error is worth retrying" in {
    type StringOr[A] = Either[String, A]
    implicit val sleepForEither: Sleep[StringOr] =
      (delay: FiniteDuration) => Right(())

    val policy = RetryPolicies.constantDelay[StringOr](1.second)

    var attempts = 0
    val errors   = ArrayBuffer.empty[String]
    val delays   = ArrayBuffer.empty[FiniteDuration]
    var gaveUp   = false

    def onError(error: String, nextStep: NextStep): Either[String, Unit] = {
      errors.append(error)
      nextStep match {
        case WillRetryAfterDelay(delay, _, _) => delays.append(delay)
        case WillGiveUp(_)                    => gaveUp = true
      }
      Right(())
    }

    val finalResult = retryingOnSomeErrors(
      policy,
      (_: String) == "one more time",
      onError
    ) {
      attempts = attempts + 1
      if (attempts < 3)
        Left("one more time")
      else
        Left("nope")
    }

    assert(finalResult == Left("nope"))
    assert(attempts == 3)
    // TODO Should onError be called when giving up because the user-supplied predicate was false?
    assert(errors.toList == List("one more time", "one more time", "nope"))
    assert(gaveUp)
  }

  // TODO test for retryingOnAllErrors

}
