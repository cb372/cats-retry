package retry

import java.util.concurrent.TimeUnit

import cats.Id
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.FlatSpec
import org.scalatest.prop.Checkers

import scala.concurrent.duration._

class RetryPoliciesSpec extends FlatSpec with Checkers {

  implicit val arbRetryStatus: Arbitrary[RetryStatus] = Arbitrary {
    for {
      a <- Gen.choose(0, 1000)
      b <- Gen.choose(0, 1000)
      c <- Gen.option(Gen.choose(b, 10000))
    } yield
      RetryStatus(
        a,
        FiniteDuration(b, TimeUnit.MILLISECONDS),
        c.map(FiniteDuration(_, TimeUnit.MILLISECONDS))
      )
  }

  behavior of "constantDelay"

  it should "always retry with the same delay" in check {
    (status: RetryStatus) =>
      RetryPolicies
        .constantDelay[Id](1.second)
        .decideNextRetry(status) == PolicyDecision.DelayAndRetry(1.second)
  }

  behavior of "exponentialBackoff"

  it should "start with the base delay and double the delay after each iteration" in {
    val policy                   = RetryPolicies.exponentialBackoff[Id](100.milliseconds)
    val arbitraryCumulativeDelay = 999.milliseconds
    val arbitraryPreviousDelay   = Some(999.milliseconds)

    def test(retriesSoFar: Int, expectedDelay: FiniteDuration) = {
      val status = RetryStatus(retriesSoFar,
                               arbitraryCumulativeDelay,
                               arbitraryPreviousDelay)
      val verdict = policy.decideNextRetry(status)
      assert(verdict == PolicyDecision.DelayAndRetry(expectedDelay))
    }

    test(0, 100.milliseconds)
    test(1, 200.milliseconds)
    test(2, 400.milliseconds)
    test(3, 800.milliseconds)
  }

  behavior of "limitRetries"

  it should "retry with no delay until the limit is reached" in check {
    (status: RetryStatus) =>
      val limit = 500
      val verdict =
        RetryPolicies.limitRetries[Id](limit).decideNextRetry(status)
      if (status.retriesSoFar < limit) {
        verdict == PolicyDecision.DelayAndRetry(Duration.Zero)
      } else {
        verdict == PolicyDecision.GiveUp
      }
  }

}
