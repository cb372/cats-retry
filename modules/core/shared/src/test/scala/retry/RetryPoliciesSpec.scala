package retry

import java.util.concurrent.TimeUnit

import retry.RetryPolicies._
import cats.Id
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatestplus.scalacheck.Checkers
import retry.PolicyDecision.{DelayAndRetry, GiveUp}

import scala.concurrent.duration._

class RetryPoliciesSpec extends AnyFlatSpec with Checkers {
  override implicit val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSuccessful = 100)

  implicit val arbRetryStatus: Arbitrary[RetryStatus] = Arbitrary {
    for {
      a <- Gen.choose(0, 1000)
      b <- Gen.choose(0, 1000)
      c <- Gen.option(Gen.choose(b, 10000))
    } yield RetryStatus(
      a,
      FiniteDuration(b, TimeUnit.MILLISECONDS),
      c.map(FiniteDuration(_, TimeUnit.MILLISECONDS))
    )
  }

  val genFiniteDuration: Gen[FiniteDuration] =
    Gen.posNum[Long].map(FiniteDuration(_, TimeUnit.NANOSECONDS))

  case class LabelledRetryPolicy(policy: RetryPolicy[Id], description: String) {
    override def toString: String = description
  }

  implicit val arbRetryPolicy: Arbitrary[LabelledRetryPolicy] = Arbitrary {
    Gen.oneOf(
      Gen.const(LabelledRetryPolicy(alwaysGiveUp[Id], "alwaysGiveUp")),
      genFiniteDuration.map(delay =>
        LabelledRetryPolicy(
          constantDelay[Id](delay),
          s"constantDelay($delay)"
        )
      ),
      genFiniteDuration.map(baseDelay =>
        LabelledRetryPolicy(
          exponentialBackoff[Id](baseDelay),
          s"exponentialBackoff($baseDelay)"
        )
      ),
      for {
        delay1 <- genFiniteDuration
        delay2 <- genFiniteDuration
      } yield {
        val min = delay1 min delay2
        val max = delay1 max delay2
        LabelledRetryPolicy(
          exponentialBackoffCaped[Id](min, max),
          s"exponentialBackoffCaped($min, $max)"
        )
      },
      for {
        delay1       <- genFiniteDuration
        delay2       <- genFiniteDuration
        maxRetries   <- Gen.posNum[Int]
        randomFactor <- Gen.posNum[Double]
      } yield {
        val min = delay1 min delay2
        val max = delay1 max delay2
        LabelledRetryPolicy(
          exponentialBackoffCapedRandom[Id](min, max, randomFactor, maxRetries),
          s"exponentialBackoffCapedRandom($min, $max, $randomFactor, $maxRetries)"
        )
      },
      Gen
        .posNum[Int]
        .map(maxRetries =>
          LabelledRetryPolicy(
            limitRetries(maxRetries),
            s"limitRetries($maxRetries)"
          )
        ),
      genFiniteDuration.map(baseDelay =>
        LabelledRetryPolicy(
          fibonacciBackoff[Id](baseDelay),
          s"fibonacciBackoff($baseDelay)"
        )
      ),
      genFiniteDuration.map(baseDelay =>
        LabelledRetryPolicy(
          fullJitter[Id](baseDelay),
          s"fullJitter($baseDelay)"
        )
      )
    )
  }

  behavior of "constantDelay"

  it should "always retry with the same delay" in check { status: RetryStatus =>
    constantDelay[Id](1.second)
      .decideNextRetry(status) == PolicyDecision.DelayAndRetry(1.second)
  }

  behavior of "exponentialBackoff"

  it should "start with the base delay and double the delay after each iteration" in {
    val policy                   = exponentialBackoff[Id](100.milliseconds)
    val arbitraryCumulativeDelay = 999.milliseconds
    val arbitraryPreviousDelay   = Some(999.milliseconds)

    def test(retriesSoFar: Int, expectedDelay: FiniteDuration) = {
      val status = RetryStatus(
        retriesSoFar,
        arbitraryCumulativeDelay,
        arbitraryPreviousDelay
      )
      val verdict = policy.decideNextRetry(status)
      assert(verdict == PolicyDecision.DelayAndRetry(expectedDelay))
    }

    test(0, 100.milliseconds)
    test(1, 200.milliseconds)
    test(2, 400.milliseconds)
    test(3, 800.milliseconds)
  }

  behavior of "exponentialBackoffCaped"

  it should "start with the base delay, double the delay after each iteration and caped by max delay" in {
    val policy                   = exponentialBackoffCaped[Id](100.milliseconds, 500.milliseconds)
    val arbitraryCumulativeDelay = 999.milliseconds
    val arbitraryPreviousDelay   = Some(999.milliseconds)

    def test(retriesSoFar: Int, expectedDelay: FiniteDuration) = {
      val status = RetryStatus(
        retriesSoFar,
        arbitraryCumulativeDelay,
        arbitraryPreviousDelay
      )
      val verdict = policy.decideNextRetry(status)
      assert(verdict == PolicyDecision.DelayAndRetry(expectedDelay))
    }

    test(0, 100.milliseconds)
    test(1, 200.milliseconds)
    test(2, 400.milliseconds)
    test(3, 500.milliseconds)
    test(4, 500.milliseconds)
  }

  behavior of "exponentialBackoffCapedRandom"

  it should "start with the base delay, double the delay after each iteration and caped by max delay" in {
    val policy = exponentialBackoffCapedRandom[Id](
      100.milliseconds,
      500.milliseconds,
      0.0,
      4
    )
    val arbitraryCumulativeDelay = 999.milliseconds
    val arbitraryPreviousDelay   = Some(999.milliseconds)

    def test(retriesSoFar: Int, expectedDecision: PolicyDecision) = {
      val status = RetryStatus(
        retriesSoFar,
        arbitraryCumulativeDelay,
        arbitraryPreviousDelay
      )
      val verdict = policy.decideNextRetry(status)
      assert(verdict == expectedDecision)
    }

    test(0, DelayAndRetry(100.milliseconds))
    test(1, DelayAndRetry(200.milliseconds))
    test(2, DelayAndRetry(400.milliseconds))
    test(3, DelayAndRetry(500.milliseconds))
    test(4, GiveUp)
  }

  behavior of "fibonacciBackoff"

  it should "start with the base delay and increase the delay in a Fibonacci-y way" in {
    val policy                   = fibonacciBackoff[Id](100.milliseconds)
    val arbitraryCumulativeDelay = 999.milliseconds
    val arbitraryPreviousDelay   = Some(999.milliseconds)

    def test(retriesSoFar: Int, expectedDelay: FiniteDuration) = {
      val status = RetryStatus(
        retriesSoFar,
        arbitraryCumulativeDelay,
        arbitraryPreviousDelay
      )
      val verdict = policy.decideNextRetry(status)
      assert(verdict == PolicyDecision.DelayAndRetry(expectedDelay))
    }

    test(0, 100.milliseconds)
    test(1, 100.milliseconds)
    test(2, 200.milliseconds)
    test(3, 300.milliseconds)
    test(4, 500.milliseconds)
    test(5, 800.milliseconds)
    test(6, 1300.milliseconds)
    test(7, 2100.milliseconds)
  }

  behavior of "fullJitter"

  it should "implement the AWS Full Jitter backoff algorithm" in {
    val policy                   = fullJitter[Id](100.milliseconds)
    val arbitraryCumulativeDelay = 999.milliseconds
    val arbitraryPreviousDelay   = Some(999.milliseconds)

    def test(retriesSoFar: Int, expectedMaximumDelay: FiniteDuration): Unit = {
      val status = RetryStatus(
        retriesSoFar,
        arbitraryCumulativeDelay,
        arbitraryPreviousDelay
      )
      for (_ <- 1 to 1000) {
        val verdict = policy.decideNextRetry(status)
        val delay   = verdict.asInstanceOf[PolicyDecision.DelayAndRetry].delay
        assert(delay >= Duration.Zero)
        assert(delay < expectedMaximumDelay)
      }
    }

    test(0, 100.milliseconds)
    test(1, 200.milliseconds)
    test(2, 400.milliseconds)
    test(3, 800.milliseconds)
    test(4, 1600.milliseconds)
    test(5, 3200.milliseconds)
  }

  behavior of "all built-in policies"

  it should "never try to create a FiniteDuration of more than Long.MaxValue nanoseconds" in check {
    (labelledPolicy: LabelledRetryPolicy, status: RetryStatus) =>
      labelledPolicy.policy.decideNextRetry(status) match {
        case PolicyDecision.DelayAndRetry(nextDelay) =>
          nextDelay.toNanos <= Long.MaxValue
        case PolicyDecision.GiveUp => true
      }
  }

  behavior of "limitRetries"

  it should "retry with no delay until the limit is reached" in check {
    status: RetryStatus =>
      val limit = 500
      val verdict =
        limitRetries[Id](limit).decideNextRetry(status)
      if (status.retriesSoFar < limit) {
        verdict == PolicyDecision.DelayAndRetry(Duration.Zero)
      } else {
        verdict == PolicyDecision.GiveUp
      }
  }

  behavior of "capDelay"

  it should "cap the delay" in {
    check { status: RetryStatus =>
      capDelay(100.milliseconds, constantDelay(101.milliseconds))
        .decideNextRetry(status) == DelayAndRetry(100.milliseconds)
    }

    check { status: RetryStatus =>
      capDelay(100.milliseconds, constantDelay(99.milliseconds))
        .decideNextRetry(status) == DelayAndRetry(99.milliseconds)
    }
  }

  behavior of "limitRetriesByDelay"

  it should "give up if the underlying policy chooses a delay greater than the threshold" in {
    check { status: RetryStatus =>
      limitRetriesByDelay(100.milliseconds, constantDelay(101.milliseconds))
        .decideNextRetry(status) == GiveUp
    }

    check { status: RetryStatus =>
      limitRetriesByDelay(100.milliseconds, constantDelay(99.milliseconds))
        .decideNextRetry(status) == DelayAndRetry(99.milliseconds)
    }
  }

  behavior of "limitRetriesByCumulativeDelay"

  it should "give up if cumulativeDelay + underlying policy's next delay >= threshold" in {
    val cumulativeDelay        = 400.milliseconds
    val arbitraryRetriesSoFar  = 5
    val arbitraryPreviousDelay = Some(123.milliseconds)
    val status = RetryStatus(
      arbitraryRetriesSoFar,
      cumulativeDelay,
      arbitraryPreviousDelay
    )

    val threshold = 500.milliseconds

    def test(
        underlyingPolicy: RetryPolicy[Id],
        expectedDecision: PolicyDecision
    ) = {
      val policy = limitRetriesByCumulativeDelay(threshold, underlyingPolicy)
      assert(policy.decideNextRetry(status) == expectedDecision)
    }

    test(constantDelay(98.milliseconds), DelayAndRetry(98.milliseconds))
    test(constantDelay(99.milliseconds), DelayAndRetry(99.milliseconds))
    test(constantDelay(100.milliseconds), GiveUp)
    test(constantDelay(101.milliseconds), GiveUp)
  }
}
