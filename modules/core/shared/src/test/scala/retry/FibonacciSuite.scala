package retry

import munit.FunSuite

class FibonacciSuite extends FunSuite:

  test("calculates the Fibonacci sequence") {
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

  test("should not overflow") {
    val fib91 = 4660046610375530309L
    val fib92 = 7540113804746346429L
    val fib93 = fib91 + fib92
    assert(fib93 < 0)
    assertEquals(Fibonacci.fibonacci(91), 4660046610375530309L)
    assertEquals(Fibonacci.fibonacci(92), 7540113804746346429L)
    //
    assertEquals(Fibonacci.fibonacci(93), Long.MaxValue)
  }
