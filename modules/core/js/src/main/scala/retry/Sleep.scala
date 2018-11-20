package retry

import scala.concurrent.duration.FiniteDuration

trait Sleep[M[_]] {

  def sleep(delay: FiniteDuration): M[Unit]

}

object Sleep {

  def apply[M[_]](implicit sleep: Sleep[M]): Sleep[M] = sleep

}
