package retry

import java.util.concurrent.TimeUnit

import retry.RetryPolicies.*
import cats.Id
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Prop.forAll
import munit.ScalaCheckSuite
import retry.PolicyDecision.{DelayAndRetry, GiveUp}

import scala.concurrent.duration.*
import munit.Location

class RetryPoliciesSuite extends ScalaCheckSuite:

  implicit val arbRetryStatus: Arbitrary[RetryStatus] = Arbitrary {
    for
      a <- Gen.choose(0, 1000)
      b <- Gen.choose(0, 1000)
      c <- Gen.option(Gen.choose(b, 10000))
    yield RetryStatus(
      a,
      FiniteDuration(b.toLong, TimeUnit.MILLISECONDS),
      c.map(x => FiniteDuration(x.toLong, TimeUnit.MILLISECONDS))
    )
  }

  val genFiniteDuration: Gen[FiniteDuration] =
    Gen.posNum[Long].map(FiniteDuration(_, TimeUnit.NANOSECONDS))

  case class LabelledRetryPolicy(policy: RetryPolicy[Id], description: String):
    override def toString: String = description

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

  property("constantDelay - always retry with the same delay") {
    forAll((status: RetryStatus) =>
      assertEquals(
        constantDelay[Id](1.second).decideNextRetry(status),
        PolicyDecision.DelayAndRetry(1.second)
      )
    )
  }

  test("exponentialBackoff - start with the base delay and double the delay after each iteration") {
    val policy                   = exponentialBackoff[Id](100.milliseconds)
    val arbitraryCumulativeDelay = 999.milliseconds
    val arbitraryPreviousDelay   = Some(999.milliseconds)

    def check(retriesSoFar: Int, expectedDelay: FiniteDuration) =
      val status = RetryStatus(
        retriesSoFar,
        arbitraryCumulativeDelay,
        arbitraryPreviousDelay
      )
      val verdict = policy.decideNextRetry(status)
      assertEquals(verdict, PolicyDecision.DelayAndRetry(expectedDelay))

    check(0, 100.milliseconds)
    check(1, 200.milliseconds)
    check(2, 400.milliseconds)
    check(3, 800.milliseconds)
  }

  test("fibonacciBackoff - start with the base delay and increase the delay in a Fibonacci-y way") {
    val policy                   = fibonacciBackoff[Id](100.milliseconds)
    val arbitraryCumulativeDelay = 999.milliseconds
    val arbitraryPreviousDelay   = Some(999.milliseconds)

    def check(retriesSoFar: Int, expectedDelay: FiniteDuration) =
      val status = RetryStatus(
        retriesSoFar,
        arbitraryCumulativeDelay,
        arbitraryPreviousDelay
      )
      val verdict = policy.decideNextRetry(status)
      assertEquals(verdict, PolicyDecision.DelayAndRetry(expectedDelay))

    check(0, 100.milliseconds)
    check(1, 100.milliseconds)
    check(2, 200.milliseconds)
    check(3, 300.milliseconds)
    check(4, 500.milliseconds)
    check(5, 800.milliseconds)
    check(6, 1300.milliseconds)
    check(7, 2100.milliseconds)
  }

  test("fullJitter - implement the AWS Full Jitter backoff algorithm") {
    val policy                   = fullJitter[Id](100.milliseconds)
    val arbitraryCumulativeDelay = 999.milliseconds
    val arbitraryPreviousDelay   = Some(999.milliseconds)

    def check(retriesSoFar: Int, expectedMaximumDelay: FiniteDuration): Unit =
      val status = RetryStatus(
        retriesSoFar,
        arbitraryCumulativeDelay,
        arbitraryPreviousDelay
      )
      for _ <- 1 to 1000 do
        val verdict = policy.decideNextRetry(status)
        val delay   = verdict.asInstanceOf[PolicyDecision.DelayAndRetry].delay
        assert(delay >= Duration.Zero)
        assert(delay < expectedMaximumDelay)

    check(0, 100.milliseconds)
    check(1, 200.milliseconds)
    check(2, 400.milliseconds)
    check(3, 800.milliseconds)
    check(4, 1600.milliseconds)
    check(5, 3200.milliseconds)
  }

  property(
    "all built-in policies - never try to create a FiniteDuration of more than Long.MaxValue nanoseconds"
  ) {
    forAll((labelledPolicy: LabelledRetryPolicy, status: RetryStatus) =>
      labelledPolicy.policy.decideNextRetry(status) match
        case PolicyDecision.DelayAndRetry(nextDelay) =>
          nextDelay.toNanos <= Long.MaxValue
        case PolicyDecision.GiveUp => true
    )
  }

  property("limitRetries - retry with no delay until the limit is reached") {
    forAll { (status: RetryStatus) =>
      val limit = 500
      val verdict =
        limitRetries[Id](limit).decideNextRetry(status)
      if status.retriesSoFar < limit then verdict == PolicyDecision.DelayAndRetry(Duration.Zero)
      else verdict == PolicyDecision.GiveUp
    }
  }

  property("capDelay - limits the maximum delay to the given duration") {
    forAll { (status: RetryStatus) =>
      assertEquals(
        capDelay(100.milliseconds, constantDelay[Id](101.milliseconds)).decideNextRetry(status),
        DelayAndRetry(100.milliseconds)
      )
      assertEquals(
        capDelay(100.milliseconds, constantDelay[Id](99.milliseconds)).decideNextRetry(status),
        DelayAndRetry(99.milliseconds)
      )
    }
  }

  test(
    "limitRetriesByDelay - give up if the underlying policy chooses a delay greater than the threshold"
  ) {
    forAll { (status: RetryStatus) =>
      assertEquals(
        limitRetriesByDelay(100.milliseconds, constantDelay[Id](101.milliseconds)).decideNextRetry(status),
        GiveUp
      )
      assertEquals(
        limitRetriesByDelay(100.milliseconds, constantDelay[Id](99.milliseconds)).decideNextRetry(status),
        DelayAndRetry(99.milliseconds)
      )
    }
  }

  test(
    "limitRetriesByCumulativeDelay - give up if cumulativeDelay + underlying policy's next delay >= threshold"
  ) {
    val cumulativeDelay        = 400.milliseconds
    val arbitraryRetriesSoFar  = 5
    val arbitraryPreviousDelay = Some(123.milliseconds)
    val status = RetryStatus(
      arbitraryRetriesSoFar,
      cumulativeDelay,
      arbitraryPreviousDelay
    )

    val threshold = 500.milliseconds

    def check(
        underlyingPolicy: RetryPolicy[Id],
        expectedDecision: PolicyDecision
    ) =
      val policy = limitRetriesByCumulativeDelay(threshold, underlyingPolicy)
      assertEquals(policy.decideNextRetry(status), expectedDecision)

    check(constantDelay(98.milliseconds), DelayAndRetry(98.milliseconds))
    check(constantDelay(99.milliseconds), DelayAndRetry(99.milliseconds))
    check(constantDelay(100.milliseconds), GiveUp)
    check(constantDelay(101.milliseconds), GiveUp)
  }
