package dataflow

import chisel3._
import chisel3.util._
import chisel3.Module
import chisel3.testers._
import chisel3.iotesters._
import org.scalatest.{FlatSpec, Matchers}
import muxes._
import config._
import control._
import util._
import interfaces._
import regfile._
import memory._
import stack._
import arbiters._
import loop._
import accel._
import node._
import junctions._

class covarianceMainIO(implicit val p: Parameters) extends Module with CoreParams with CacheParams {
  val io = IO(new CoreBundle {
    val in = Flipped(Decoupled(new Call(List(32, 32, 32, 32))))
    val req = Flipped(Decoupled(new MemReq))
    val resp = Output(Valid(new MemResp))
    val out = Decoupled(new Call(List()))
  })
}

class covarianceMain(implicit p: Parameters) extends covarianceMainIO {

  val cache = Module(new Cache) // Simple Nasti Cache
  //  val memModel = Module(new NastiCMemSlave) // Model of DRAM to connect to Cache
  val memModel = Module(new NastiMemSlave) // Model of DRAM to connect to Cache

  // Connect the wrapper I/O to the memory model initialization interface so the
  // test bench can write contents at start.
  memModel.io.nasti <> cache.io.nasti
  memModel.io.init.bits.addr := 0.U
  memModel.io.init.bits.data := 0.U
  memModel.io.init.valid := false.B
  cache.io.cpu.abort := false.B

  // Wire up the cache and modules under test.
  val covariance = Module(new kernel_covarianceDF())

  //Put an arbiter infront of cache
  val CacheArbiter = Module(new MemArbiter(2))

  // Connect input signals to cache
  CacheArbiter.io.cpu.MemReq(0) <> covariance.io.MemReq
  covariance.io.MemResp <> CacheArbiter.io.cpu.MemResp(0)

  //Connect main module to cache arbiter
  CacheArbiter.io.cpu.MemReq(1) <> io.req
  io.resp <> CacheArbiter.io.cpu.MemResp(1)

  //Connect cache to the arbiter
  cache.io.cpu.req <> CacheArbiter.io.cache.MemReq
  CacheArbiter.io.cache.MemResp <> cache.io.cpu.resp

  //Connect in/out ports
  covariance.io.in <> io.in
  io.out <> covariance.io.out

  // Check if trace option is on or off
  if (p(TRACE) == false) {
    println(Console.RED + "****** Trace option is off. *********" + Console.RESET)
  }
  else
    println(Console.BLUE + "****** Trace option is on. *********" + Console.RESET)


}

class CovarianceTest01[T <: covarianceMainIO](c: T) extends PeekPokeTester(c) {


  /**
  *  test11DF interface:
  *
  *    in = Flipped(new CallDecoupled(List(...)))
  *    out = new CallDecoupled(List(32))
  */

  // Initializing the signals
  poke(c.io.in.bits.enable.control, false.B)
  poke(c.io.in.valid, false.B)
  poke(c.io.in.bits.data("field0").data, 0.U)
  poke(c.io.in.bits.data("field0").predicate, false.B)
  poke(c.io.in.bits.data("field1").data, 0.U)
  poke(c.io.in.bits.data("field1").predicate, false.B)
  poke(c.io.in.bits.data("field2").data, 0.U)
  poke(c.io.in.bits.data("field2").predicate, false.B)
  poke(c.io.in.bits.data("field3").data, 0.U)
  poke(c.io.in.bits.data("field3").predicate, false.B)
  poke(c.io.out.ready, false.B)
  step(1)
  poke(c.io.in.bits.enable.control, true.B)
  poke(c.io.in.valid, true.B)

  //Value: 1400.0f
  poke(c.io.in.bits.data("field0").data, 1152319488.U)
  poke(c.io.in.bits.data("field0").predicate, true.B)

  //Memory
//  poke(c.io.in.bits.data("field1").data, 196890624.U)
  poke(c.io.in.bits.data("field1").data, 0.U)
  poke(c.io.in.bits.data("field1").predicate, true.B)
//  poke(c.io.in.bits.data("field2").data, 210337792.U)
  poke(c.io.in.bits.data("field2").data, 10.U)
  poke(c.io.in.bits.data("field2").predicate, true.B)
//  poke(c.io.in.bits.data("field3").data, 397620428.U)
  poke(c.io.in.bits.data("field3").data, 20.U)
  poke(c.io.in.bits.data("field3").predicate, true.B)
  poke(c.io.out.ready, true.B)
  step(1)
  poke(c.io.in.bits.enable.control, false.B)
  poke(c.io.in.valid, false.B)
  poke(c.io.in.bits.data("field0").data, 0.U)
  poke(c.io.in.bits.data("field0").predicate, false.B)
  poke(c.io.in.bits.data("field1").data, 0.U)
  poke(c.io.in.bits.data("field1").predicate, false.B)
  poke(c.io.in.bits.data("field2").data, 0.U)
  poke(c.io.in.bits.data("field2").predicate, false.B)
  poke(c.io.in.bits.data("field3").data, 8.U)
  poke(c.io.in.bits.data("field3").predicate, true.B)

  step(1)

  // NOTE: Don't use assert().  It seems to terminate the writing of VCD files
  // early (before the error) which makes debugging very difficult. Check results
  // using if() and fail command.
  var time = 1  //Cycle counter
  var result = false
  while (time < 70000) {
    time += 1
    step(1)
    //println(s"Cycle: $time")
    if (peek(c.io.out.valid) == 1 ){
      step(100)
      println(Console.BLUE + s"[LOG] *** Covariance ran to completion at cycle: $time" + Console.RESET)
      result = true
    }
  }

  if(!result) {
    println("*** Timeout.")
    fail
  }

}

class covarianceTester extends FlatSpec with Matchers {
  implicit val p = config.Parameters.root((new MiniConfig).toInstance)
  it should "Check that test11 works correctly." in {
    // iotester flags:
    // -ll  = log level <Error|Warn|Info|Debug|Trace>
    // -tbn = backend <firrtl|verilator|vcs>
    // -td  = target directory
    // -tts = seed for RNG
    chisel3.iotesters.Driver.execute(
     Array(
//       "-ll", "Info",
       "-tbn", "verilator",
       "-td", "test_run_dir/covariance",
       "-tts", "0001"),
      () => new covarianceMain()) {
     c => new CovarianceTest01(c)
    } should be(true)
  }
}

