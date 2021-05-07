package retry

import munit._

class FibonacciSuite extends FunSuite {
  test("Fibonacci should calculate the Fibonacci sequence") {
    assertEquals(Fibonacci.fibonacci(0), 0L)
    assertEquals(Fibonacci.fibonacci(1), 1L)
    assertEquals(Fibonacci.fibonacci(2), 1L)
    assertEquals(Fibonacci.fibonacci(3), 2L)
    assertEquals(Fibonacci.fibonacci(4), 3L)
    assertEquals(Fibonacci.fibonacci(5), 5L)
    assertEquals(Fibonacci.fibonacci(6), 8L)
    assertEquals(Fibonacci.fibonacci(7), 13L)
    assertEquals(Fibonacci.fibonacci(75), 2111485077978050L)
  }
}
