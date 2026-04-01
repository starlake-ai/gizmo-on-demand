package ai.starlake.gizmo.proxy

import org.apache.arrow.flight.{FlightClient, Location}
import org.apache.arrow.memory.RootAllocator
import org.scalatest.funsuite.AnyFunSuite

/** Verifies that Arrow/Netty/gRPC dependency versions are mutually compatible.
  *
  * These tests catch classpath conflicts that only manifest at runtime:
  * - Arrow's NettyAllocationManager accesses internal Netty fields via Unsafe
  * - Arrow Flight uses grpc-netty which must match the resolved grpc-core version
  */
class ArrowNettyCompatibilityTest extends AnyFunSuite {

  test("Arrow RootAllocator initializes without Netty field errors") {
    val allocator = new RootAllocator(Long.MaxValue)
    try {
      val buf = allocator.buffer(1024)
      try {
        assert(buf.capacity() >= 1024)
      } finally {
        buf.close()
      }
    } finally {
      allocator.close()
    }
  }

  test("Arrow FlightClient builder does not throw AbstractMethodError on gRPC transport") {
    val allocator = new RootAllocator(Long.MaxValue)
    try {
      // Building a FlightClient triggers grpc-netty's NettyTransportFactory
      // which must implement all methods from the resolved grpc-core version
      val client = FlightClient
        .builder(allocator, Location.forGrpcInsecure("localhost", 0))
        .build()
      client.close()
    } finally {
      allocator.close()
    }
  }
}
