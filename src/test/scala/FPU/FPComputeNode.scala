// See LICENSE for license details.

package dandelion.fpu

import chisel3._

import chisel3.iotesters.{ChiselFlatSpec, Driver, PeekPokeTester, OrderedDecoupledHWIOTester}
import org.scalatest.{Matchers, FlatSpec}

import dandelion.config._




// Tester.
class FPComputeNodeTester(df: FPComputeNode)
                  (implicit p: Parameters) extends PeekPokeTester(df)  {


  poke(df.io.LeftIO.bits.data, 0x40800000.U)
  poke(df.io.LeftIO.valid, false.B)
  poke(df.io.LeftIO.bits.predicate, false.B)

  poke(df.io.RightIO.bits.data, 0x40800000.U)
  poke(df.io.RightIO.valid, false.B)
  poke(df.io.RightIO.bits.predicate, false.B)

  poke(df.io.enable.bits.control , false.B)
  poke(df.io.enable.valid, false.B)
  poke(df.io.Out(0).ready, false.B)
  println(s"Output: ${peek(df.io.Out(0))}\n")


  step(1)

  poke(df.io.enable.bits.control , true.B)
  poke(df.io.enable.valid, true.B)
  poke(df.io.Out(0).ready, true.B)


  poke(df.io.LeftIO.valid, true.B)
  poke(df.io.RightIO.valid, true.B)
  poke(df.io.LeftIO.bits.predicate, true.B)
  poke(df.io.RightIO.bits.predicate, true.B)

  println(s"Output: ${peek(df.io.Out(0))}\n")

  println(s"t: -1\n -------------------------------------")
  step(1)


  for( i <- 0 until 10){
    println(s"Output: ${peek(df.io.Out(0))}\n")

    println(s"t: ${i}\n -------------------------------------")
    step(1)
  } 
}

class FPComputeTests extends  FlatSpec with Matchers {
   implicit val p = Parameters.root((new SinglePrecisionFPConfig).toInstance)
  it should "FP MAC tester" in {
     chisel3.iotesters.Driver(
       () => new FPComputeNode(NumOuts = 1, ID = 0, opCode = "Mul")(t = FType.S)) {
       c => new FPComputeNodeTester(c)
     } should be(true)
   }
 }



