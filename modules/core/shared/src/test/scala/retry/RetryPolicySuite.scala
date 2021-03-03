package retry

import cats.Id
import cats.catsInstancesForId
import cats.syntax.semigroup._
import munit._

import scala.concurrent.duration._

class RetryPolicySuite extends FunSuite {
  test(
    "BoundedSemilattice append should give up if either of the composed policies decides to give up"
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
    "BoundedSemilattice append should choose the maximum of the delays if both of the composed policies decides to retry"
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
