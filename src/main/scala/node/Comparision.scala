package dandelion.node

import chisel3._
import chisel3.util._

/**
 * List of comparision operations
 */
object CmpOpCode {
  val EQ      = 1
  val NE      = 2
  val UGT     = 3
  val UGE     = 4
  val ULT     = 5
  val ULE     = 6
  val SGT     = 7
  val SGE     = 8
  val SLT     = 9
  val SLE     = 10
  val length  = 11

  val CompMap = Map(
  "EQ" -> EQ,
  "NE" -> NE,
  "UGT" -> UGT,
  "UGE" -> UGE,
  "ULT" -> ULT,
  "ULE" -> ULE,
  "SGT" -> SGT,
  "SGE" -> SGE,
  "SLT" -> SLT,
  "SLE" -> SLE,
  )
}


/**
 * This way you can not pick our operation correctly!!
 * @param key a key to search for
 * @param default a default value if nothing is found
 * @param mapping a sequence to search of keys and values
 * @return the value found or the default if not
 *
 */
object CMPGenerator{
  def apply[S <: Int, T <: Data] (key : S, mapping: Seq[(S, T)]): T = {
    
    //Default value for mapping
    var res = mapping(0)._2

    for((k,v) <- mapping){
      if(k == key)
        res = v
    }

    res
  }
}


class UCMP(val xlen: Int, val opCode: String) extends Module {
  val io = IO(new Bundle {
    val in1 = Input(UInt(xlen.W))
    val in2 = Input(UInt(xlen.W))
    val out = Output(UInt(xlen.W))
  })

  val cmpOp = Array(
      CmpOpCode.EQ  -> (io.in1 === io.in2),
      CmpOpCode.NE  -> (io.in1 =/= io.in2),
      CmpOpCode.UGT -> (io.in1  >  io.in2),
      CmpOpCode.UGE -> (io.in1  >=  io.in2),
      CmpOpCode.ULT -> (io.in1  <  io.in2),
      CmpOpCode.ULE -> (io.in1  <= io.in2)
    )

  assert(!CmpOpCode.CompMap.get(opCode.toUpperCase).isEmpty, "Wrong CMP OP!")
  io.out := CMPGenerator(CmpOpCode.CompMap(opCode.toUpperCase), cmpOp)

}


class SCMP(val xlen: Int, val opCode: String) extends Module {
  val io = IO(new Bundle {
    val in1 = Input(SInt(xlen.W))
    val in2 = Input(SInt(xlen.W))
    val out = Output(SInt(xlen.W))
  })


  val aluOp = Array(
      CmpOpCode.SGT -> (io.in1  >  io.in2),
      CmpOpCode.SGE -> (io.in1  >=  io.in2),
      CmpOpCode.SLT -> (io.in1  <  io.in2),
      CmpOpCode.SLE -> (io.in1  <= io.in2)

    )


  assert(!CmpOpCode.CompMap.get(opCode).isEmpty, "Wrong CMP OP!")

  io.out := CMPGenerator(CmpOpCode.CompMap(opCode), aluOp)

}


