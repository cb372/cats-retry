package retry

import org.scalatest.FlatSpec

class FibonacciSpec extends FlatSpec {

  it should "calculate the Fibonacci sequence" in {
    assert(Fibonacci.fibonacci(0) == 0)
    assert(Fibonacci.fibonacci(1) == 1)
    assert(Fibonacci.fibonacci(2) == 1)
    assert(Fibonacci.fibonacci(3) == 2)
    assert(Fibonacci.fibonacci(4) == 3)
    assert(Fibonacci.fibonacci(5) == 5)
    assert(Fibonacci.fibonacci(6) == 8)
    assert(Fibonacci.fibonacci(7) == 13)
    assert(Fibonacci.fibonacci(75) == 2111485077978050L)
  }

}
