package retry
import cats.Id
import cats.catsInstancesForId
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration._
import munit.FunSuite

class PackageObjectSpec extends FunSuite {
  type StringOr[A] = Either[String, A]

  implicit val sleepForEither: Sleep[StringOr] = _ => Right(())

  test("retryingOnFailures should retry until the action succeeds") {
    new TestContext {
      val policy = RetryPolicies.constantDelay[Id](1.second)

      val sleeps = ArrayBuffer.empty[FiniteDuration]

      implicit val dummySleep: Sleep[Id] =
        (delay: FiniteDuration) => sleeps.append(delay)

      val finalResult = retryingOnFailures[String][Id](
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
  }

  test("retryingOnFailures should retry until the policy chooses to give up") {
    new TestContext {
      val policy = RetryPolicies.limitRetries[Id](2)

      implicit val dummySleep: Sleep[Id] = _ => ()

      val finalResult = retryingOnFailures[String][Id](
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
  }

  test("retryingOnFailures should retry in a stack-safe way") {
    new TestContext {
      val policy = RetryPolicies.limitRetries[Id](10000)

      implicit val dummySleep: Sleep[Id] = _ => ()

      val finalResult = retryingOnFailures[String][Id](
        policy,
        _.toInt > 20000,
        onError
      ) {
        attempts = attempts + 1
        attempts.toString
      }

      assert(finalResult == "10001")
      assert(attempts == 10001)
      assert(gaveUp)
    }
  }

  test("retryingOnSomeErrors should retry until the action succeeds") {
    new TestContext {
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
  }

  test(
    "retryingOnSomeErrors should retry only if the error is worth retrying"
  ) {
    new TestContext {
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
      assert(
        !gaveUp
      ) // false because onError is only called when the error is worth retrying
    }
  }

  test(
    "retryingOnSomeErrors should retry until the policy chooses to give up"
  ) {
    new TestContext {
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
  }

  test("retryingOnSomeErrors should retry in a stack-safe way") {
    new TestContext {
      val policy = RetryPolicies.limitRetries[StringOr](10000)

      val finalResult = retryingOnSomeErrors(
        policy,
        (_: String) == "one more time",
        onError
      ) {
        attempts = attempts + 1
        Left("one more time")
      }

      assert(finalResult == Left("one more time"))
      assert(attempts == 10001)
      assert(gaveUp)
    }
  }

  test("retryingOnAllErrors should retry until the action succeeds") {
    new TestContext {
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
  }

  test("retryingOnAllErrors should retry until the policy chooses to give up") {
    new TestContext {
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
  }

  test("retryingOnAllErrors should retry in a stack-safe way") {
    new TestContext {
      val policy = RetryPolicies.limitRetries[StringOr](10000)

      val finalResult = retryingOnAllErrors(
        policy,
        onError
      ) {
        attempts = attempts + 1
        Left("one more time")
      }

      assert(finalResult == Left("one more time"))
      assert(attempts == 10001)
      assert(gaveUp)
    }
  }

  test(
    "retryingOnFailuresAndSomeErrors should retry until the action succeeds"
  ) {
    new TestContext {
      val policy = RetryPolicies.constantDelay[StringOr](1.second)

      val finalResult = retryingOnFailuresAndSomeErrors[String](
        policy,
        _ == "yay",
        (_: String) == "one more time",
        onError,
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
  }

  test(
    "retryingOnFailuresAndSomeErrors should retry only if the error is worth retrying"
  ) {
    new TestContext {
      val policy = RetryPolicies.constantDelay[StringOr](1.second)

      val finalResult = retryingOnFailuresAndSomeErrors[String](
        policy,
        _ == "will never happen",
        (_: String) == "one more time",
        onError,
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
      assert(
        !gaveUp
      ) // false because onError is only called when the error is worth retrying
    }
  }

  test(
    "retryingOnFailuresAndSomeErrors should retry until the policy chooses to give up due to errors"
  ) {
    new TestContext {
      val policy = RetryPolicies.limitRetries[StringOr](2)

      val finalResult = retryingOnFailuresAndSomeErrors[String](
        policy,
        _ == "will never happen",
        (_: String) == "one more time",
        onError,
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
  }

  test(
    "retryingOnFailuresAndSomeErrors should retry until the policy chooses to give up due to failures"
  ) {
    new TestContext {
      val policy = RetryPolicies.limitRetries[StringOr](2)

      val finalResult = retryingOnFailuresAndSomeErrors[String](
        policy,
        _ == "yay",
        (_: String) == "one more time",
        onError,
        onError
      ) {
        attempts = attempts + 1
        Right("boo")
      }

      assert(finalResult == Right("boo"))
      assert(attempts == 3)
      assert(errors.toList == List("boo", "boo", "boo"))
      assert(gaveUp)
    }
  }

  test("retryingOnFailuresAndSomeErrors should retry in a stack-safe way") {
    new TestContext {
      val policy = RetryPolicies.limitRetries[StringOr](10000)

      val finalResult = retryingOnFailuresAndSomeErrors[String](
        policy,
        _ == "yay",
        (_: String) == "one more time",
        onError,
        onError
      ) {
        attempts = attempts + 1
        Left("one more time")
      }

      assert(finalResult == Left("one more time"))
      assert(attempts == 10001)
      assert(gaveUp)
    }
  }

  test(
    "retryingOnFailuresAndAllErrors should retry until the action succeeds"
  ) {
    new TestContext {
      val policy = RetryPolicies.constantDelay[StringOr](1.second)

      val finalResult = retryingOnFailuresAndAllErrors[String](
        policy,
        _ == "yay",
        onError,
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
  }

  test(
    "retryingOnFailuresAndAllErrors should retry until the policy chooses to give up due to errors"
  ) {
    new TestContext {
      val policy = RetryPolicies.limitRetries[StringOr](2)

      val finalResult = retryingOnFailuresAndAllErrors[String](
        policy,
        _ == "will never happen",
        onError,
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
  }

  test(
    "retryingOnFailuresAndAllErrors should retry until the policy chooses to give up due to failures"
  ) {
    new TestContext {
      val policy = RetryPolicies.limitRetries[StringOr](2)

      val finalResult = retryingOnFailuresAndAllErrors[String](
        policy,
        _ == "yay",
        onError,
        onError
      ) {
        attempts = attempts + 1
        Right("boo")
      }

      assert(finalResult == Right("boo"))
      assert(attempts == 3)
      assert(errors.toList == List("boo", "boo", "boo"))
      assert(gaveUp)
    }
  }

  test("retryingOnFailuresAndAllErrors should retry in a stack-safe way") {
    new TestContext {
      val policy = RetryPolicies.limitRetries[StringOr](10000)

      val finalResult = retryingOnFailuresAndAllErrors[String](
        policy,
        _ == "will never happen",
        onError,
        onError
      ) {
        attempts = attempts + 1
        Left("one more time")
      }

      assert(finalResult == Left("one more time"))
      assert(attempts == 10001)
      assert(gaveUp)
    }
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
