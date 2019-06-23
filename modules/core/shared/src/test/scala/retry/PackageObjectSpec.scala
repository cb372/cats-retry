package retry

import cats.Id
import cats.instances.either._
import org.scalatest.flatspec.AnyFlatSpec

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration._

class PackageObjectSpec extends AnyFlatSpec {

  type StringOr[A] = Either[String, A]

  behavior of "retryingM"

  it should "retry until the action succeeds" in new TestContext {
    val policy = RetryPolicies.constantDelay[Id](1.second)

    val sleeps = ArrayBuffer.empty[FiniteDuration]

    implicit val dummySleep: Sleep[Id] = new Sleep[Id] {
      def sleep(delay: FiniteDuration): Id[Unit] = sleeps.append(delay)
    }

    val finalResult = retryingM[String][Id](
      policy,
      _.toInt > 3,
      onError
    ) {
      attempts = attempts + 1
      attempts.toString
    }

    assert(finalResult == "4")
    assert(attempts == 4)
    assert(errors.toList == List("1", "2", "3"))
    assert(delays.toList == List(1.second, 1.second, 1.second))
    assert(sleeps.toList == delays.toList)
    assert(!gaveUp)
  }

  it should "retry until the policy chooses to give up" in new TestContext {
    val policy = RetryPolicies.limitRetries[Id](2)

    implicit val dummySleep: Sleep[Id] =
      new Sleep[Id] {
        def sleep(delay: FiniteDuration): Id[Unit] = ()
      }

    val finalResult = retryingM[String][Id](
      policy,
      _.toInt > 3,
      onError
    ) {
      attempts = attempts + 1
      attempts.toString
    }

    assert(finalResult == "3")
    assert(attempts == 3)
    assert(errors.toList == List("1", "2", "3"))
    assert(delays.toList == List(Duration.Zero, Duration.Zero))
    assert(gaveUp)
  }

  behavior of "retryingOnSomeErrors"

  it should "retry until the action succeeds" in new TestContext {
    implicit val sleepForEither: Sleep[StringOr] =
      new Sleep[StringOr] {
        def sleep(delay: FiniteDuration): StringOr[Unit] = Right(())
      }

    val policy = RetryPolicies.constantDelay[StringOr](1.second)

    val finalResult = retryingOnSomeErrors(
      policy,
      (_: String) == "one more time",
      onError
    ) {
      attempts = attempts + 1
      if (attempts < 3)
        Left("one more time")
      else
        Right("yay")
    }

    assert(finalResult == Right("yay"))
    assert(attempts == 3)
    assert(errors.toList == List("one more time", "one more time"))
    assert(!gaveUp)
  }

  it should "retry only if the error is worth retrying" in new TestContext {
    implicit val sleepForEither: Sleep[StringOr] =
      new Sleep[StringOr] {
        def sleep(delay: FiniteDuration): StringOr[Unit] = Right(())
      }

    val policy = RetryPolicies.constantDelay[StringOr](1.second)

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
    assert(errors.toList == List("one more time", "one more time"))
    assert(!gaveUp) // false because onError is only called when the error is worth retrying
  }

  it should "retry until the policy chooses to give up" in new TestContext {
    implicit val sleepForEither: Sleep[StringOr] =
      new Sleep[StringOr] {
        def sleep(delay: FiniteDuration): StringOr[Unit] = Right(())
      }

    val policy = RetryPolicies.limitRetries[StringOr](2)

    val finalResult = retryingOnSomeErrors(
      policy,
      (_: String) == "one more time",
      onError
    ) {
      attempts = attempts + 1
      Left("one more time")
    }

    assert(finalResult == Left("one more time"))
    assert(attempts == 3)
    assert(
      errors.toList == List("one more time", "one more time", "one more time")
    )
    assert(gaveUp)
  }

  behavior of "retryingOnAllErrors"

  it should "retry until the action succeeds" in new TestContext {
    implicit val sleepForEither: Sleep[StringOr] =
      new Sleep[StringOr] {
        def sleep(delay: FiniteDuration): StringOr[Unit] = Right(())
      }

    val policy = RetryPolicies.constantDelay[StringOr](1.second)

    val finalResult = retryingOnAllErrors(
      policy,
      onError
    ) {
      attempts = attempts + 1
      if (attempts < 3)
        Left("one more time")
      else
        Right("yay")
    }

    assert(finalResult == Right("yay"))
    assert(attempts == 3)
    assert(errors.toList == List("one more time", "one more time"))
    assert(!gaveUp)
  }

  it should "retry until the policy chooses to give up" in new TestContext {
    implicit val sleepForEither: Sleep[StringOr] =
      new Sleep[StringOr] {
        def sleep(delay: FiniteDuration): StringOr[Unit] = Right(())
      }

    val policy = RetryPolicies.limitRetries[StringOr](2)

    val finalResult = retryingOnAllErrors(
      policy,
      onError
    ) {
      attempts = attempts + 1
      Left("one more time")
    }

    assert(finalResult == Left("one more time"))
    assert(attempts == 3)
    assert(
      errors.toList == List("one more time", "one more time", "one more time")
    )
    assert(gaveUp)
  }

  private class TestContext {

    var attempts = 0
    val errors   = ArrayBuffer.empty[String]
    val delays   = ArrayBuffer.empty[FiniteDuration]
    var gaveUp   = false

    def onError(error: String, details: RetryDetails): Either[String, Unit] = {
      errors.append(error)
      details match {
        case RetryDetails.WillDelayAndRetry(delay, _, _) => delays.append(delay)
        case RetryDetails.GivingUp(_, _)                 => gaveUp = true
      }
      Right(())
    }

  }
}
