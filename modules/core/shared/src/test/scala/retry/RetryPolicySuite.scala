package retry

import cats.Id
import cats.syntax.semigroup.*
import munit.FunSuite

import scala.concurrent.duration.*

class RetryPolicySuite extends FunSuite {

  test(
    "BoundedSemilattice append - gives up if either of the composed policies decides to give up"
  ) {
    val alwaysGiveUp = RetryPolicy.lift[Id](_ => PolicyDecision.GiveUp)
    val alwaysRetry  = RetryPolicies.constantDelay[Id](1.second)

    assertEquals(
      (alwaysGiveUp |+| alwaysRetry).decideNextRetry(RetryStatus.NoRetriesYet),
      PolicyDecision.GiveUp
    )
    assertEquals(
      (alwaysRetry |+| alwaysGiveUp).decideNextRetry(RetryStatus.NoRetriesYet),
      PolicyDecision.GiveUp
    )
  }

  test(
    "BoundedSemilattice append - chooses the maximum of the delays if both of the composed policies decides to retry"
  ) {
    val delayOneSecond =
      RetryPolicy.lift[Id](_ => PolicyDecision.DelayAndRetry(1.second))
    val delayTwoSeconds =
      RetryPolicy.lift[Id](_ => PolicyDecision.DelayAndRetry(2.seconds))

    assertEquals(
      (delayOneSecond |+| delayTwoSeconds).decideNextRetry(
        RetryStatus.NoRetriesYet
      ),
      PolicyDecision.DelayAndRetry(2.seconds)
    )
    assertEquals(
      (delayTwoSeconds |+| delayOneSecond).decideNextRetry(
        RetryStatus.NoRetriesYet
      ),
      PolicyDecision.DelayAndRetry(2.seconds)
    )
  }
}
