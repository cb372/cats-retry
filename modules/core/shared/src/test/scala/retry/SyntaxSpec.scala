package retry

import cats.{Id, catsInstancesForId}
import org.scalatest.flatspec.AnyFlatSpec
import retry.syntax.all._

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration._

class SyntaxSpec extends AnyFlatSpec {
  type StringOr[A] = Either[String, A]

  behavior of "retryingOnFailures"

  it should "retry until the action succeeds" in new TestContext {
    val policy: RetryPolicy[Id] =
      RetryPolicies.constantDelay[Id](1.second)
    def onFailure: (String, RetryDetails) => Id[Unit] = onError
    def wasSuccessful(res: String): Id[Boolean]       = res.toInt > 3
    val sleeps                                        = ArrayBuffer.empty[FiniteDuration]

    implicit val dummySleep: Sleep[Id] =
      (delay: FiniteDuration) => sleeps.append(delay)

    def action: Id[String] = {
      attempts = attempts + 1
      attempts.toString
    }

    val finalResult: Id[String] =
      action.retryingOnFailures(wasSuccessful, policy, onFailure)

    assert(finalResult == "4")
    assert(attempts == 4)
    assert(errors.toList == List("1", "2", "3"))
    assert(delays.toList == List(1.second, 1.second, 1.second))
    assert(sleeps.toList == delays.toList)
    assert(!gaveUp)
  }

  it should "retry until the policy chooses to give up" in new TestContext {
    val policy: RetryPolicy[Id]        = RetryPolicies.limitRetries[Id](2)
    implicit val dummySleep: Sleep[Id] = _ => ()

    def action: Id[String] = {
      attempts = attempts + 1
      attempts.toString
    }

    val finalResult: Id[String] =
      action.retryingOnFailures(_.toInt > 3, policy, onError)

    assert(finalResult == "3")
    assert(attempts == 3)
    assert(errors.toList == List("1", "2", "3"))
    assert(delays.toList == List(Duration.Zero, Duration.Zero))
    assert(gaveUp)
  }

  behavior of "retryingOnSomeErrors"

  it should "retry until the action succeeds" in new TestContext {
    implicit val sleepForEither: Sleep[StringOr] = _ => Right(())

    val policy: RetryPolicy[StringOr] =
      RetryPolicies.constantDelay[StringOr](1.second)

    def action: StringOr[String] = {
      attempts = attempts + 1
      if (attempts < 3)
        Left("one more time")
      else
        Right("yay")
    }

    val finalResult: StringOr[String] =
      action.retryingOnSomeErrors(
        s => Right(s == "one more time"),
        policy,
        (err, rd) => onError(err, rd)
      )

    assert(finalResult == Right("yay"))
    assert(attempts == 3)
    assert(errors.toList == List("one more time", "one more time"))
    assert(!gaveUp)
  }

  it should "retry only if the error is worth retrying" in new TestContext {
    implicit val sleepForEither: Sleep[StringOr] = _ => Right(())

    val policy: RetryPolicy[StringOr] =
      RetryPolicies.constantDelay[StringOr](1.second)

    def action: StringOr[Nothing] = {
      attempts = attempts + 1
      if (attempts < 3)
        Left("one more time")
      else
        Left("nope")
    }

    val finalResult =
      action.retryingOnSomeErrors(
        s => Right(s == "one more time"),
        policy,
        (err, rd) => onError(err, rd)
      )

    assert(finalResult == Left("nope"))
    assert(attempts == 3)
    assert(errors.toList == List("one more time", "one more time"))
    assert(
      !gaveUp
    ) // false because onError is only called when the error is worth retrying
  }

  it should "retry until the policy chooses to give up" in new TestContext {
    implicit val sleepForEither: Sleep[StringOr] = _ => Right(())

    val policy: RetryPolicy[StringOr] =
      RetryPolicies.limitRetries[StringOr](2)

    def action: StringOr[Nothing] = {
      attempts = attempts + 1

      Left("one more time")
    }

    val finalResult: StringOr[Nothing] =
      action.retryingOnSomeErrors(
        s => Right(s == "one more time"),
        policy,
        (err, rd) => onError(err, rd)
      )

    assert(finalResult == Left("one more time"))
    assert(attempts == 3)
    assert(
      errors.toList == List("one more time", "one more time", "one more time")
    )
    assert(gaveUp)
  }

  behavior of "retryingOnAllErrors"

  it should "retry until the action succeeds" in new TestContext {
    implicit val sleepForEither: Sleep[StringOr] = _ => Right(())

    val policy: RetryPolicy[StringOr] =
      RetryPolicies.constantDelay[StringOr](1.second)

    def action: StringOr[String] = {
      attempts = attempts + 1
      if (attempts < 3)
        Left("one more time")
      else
        Right("yay")
    }

    val finalResult: StringOr[String] =
      action.retryingOnAllErrors(policy, (err, rd) => onError(err, rd))

    assert(finalResult == Right("yay"))
    assert(attempts == 3)
    assert(errors.toList == List("one more time", "one more time"))
    assert(!gaveUp)
  }

  it should "retry until the policy chooses to give up" in new TestContext {
    implicit val sleepForEither: Sleep[StringOr] = _ => Right(())

    val policy: RetryPolicy[StringOr] =
      RetryPolicies.limitRetries[StringOr](2)

    def action: StringOr[Nothing] = {
      attempts = attempts + 1
      Left("one more time")
    }

    val finalResult =
      action.retryingOnAllErrors(policy, (err, rd) => onError(err, rd))

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
