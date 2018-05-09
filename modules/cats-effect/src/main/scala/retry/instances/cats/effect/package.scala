package retry.instances.cats

import cats.effect.Timer
import retry.Sleep

import scala.concurrent.duration.FiniteDuration

package object effect {

  implicit def sleepUsingTimer[F[_]](implicit timer: Timer[F]): Sleep[F] =
    (delay: FiniteDuration) => timer.sleep(delay)

}
