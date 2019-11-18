
package dnnnode

import chisel3._
import chisel3.util._
import config._
import dnn.memory.{TensorClient, TensorMaster, TensorParams}
import interfaces.{CustomDataBundle, DataBundle}
import node.Shapes
import shell._
//import vta.util.config._
import dnn.memory.ISA._


/** TensorLoad.
  *
  * Load 1D and 2D tensors from main memory (DRAM) to input/weight
  * scratchpads (SRAM). Also, there is support for zero padding, while
  * doing the load. Zero-padding works on the y and x axis, and it is
  * managed by TensorPadCtrl. The TensorDataCtrl is in charge of
  * handling the way tensors are stored on the scratchpads.
  */
class PWShapeTransformerIO[gen <: Shapes](NumRows: Int, NumOuts: Int, memTensorType: String = "none")(macShape: => gen)(implicit val p: Parameters)
  extends Module {
  val tp = new TensorParams(memTensorType)
  val mp = p(ShellKey).memParams
  val io = IO(new Bundle {
    val start = Input(Bool())
    val done = Output(Bool())
    val rowWidth = Input(UInt(mp.addrBits.W))
    val depth = Input(UInt(mp.addrBits.W))
    val tensor = Vec(NumRows, new TensorMaster(memTensorType))
    val Out = Vec(NumRows, Vec(NumOuts, Decoupled(new CustomDataBundle(UInt(macShape.getWidth.W)))))
  })
}

class PWShapeTransformer[L <: Shapes](NumRows: Int, NumOuts: Int, bufSize: Int, memTensorType: String = "none")
                                     (macShape: => L)(implicit p: Parameters)
  extends PWShapeTransformerIO(NumRows, NumOuts, memTensorType)(macShape)(p) {

  val wgtNum = io.rowWidth * io.depth / macShape.getLength().U
  val memTensorRows = Mux(io.rowWidth * io.depth % tp.tensorWidth.U === 0.U,
    io.rowWidth * io.depth / tp.tensorWidth.U,
    (io.rowWidth * io.depth / tp.tensorWidth.U) + 1.U)

  val readTensorCnt = Counter(tp.memDepth)
  val wgtOutCnt = Counter(tp.memDepth)

  val sIdle :: sRead :: sClear :: Nil = Enum(3)
  val state = RegInit(sIdle)


  val queue = for (i <- 0 until NumRows) yield {
    val buffer = Module(new MIMOQueue(UInt(p(XLEN).W), bufSize, tp.tensorWidth, macShape.getLength()))
    buffer
  }

  for (i <- 0 until NumRows) {
    queue(i).io.clear := false.B
    queue(i).io.enq.bits := io.tensor(i).rd.data.bits.asTypeOf(queue(i).io.enq.bits)
    queue(i).io.enq.valid := io.tensor(i).rd.data.valid
    io.tensor(i).rd.idx.valid := queue(i).io.enq.ready && state === sRead
    io.tensor(i).rd.idx.bits := readTensorCnt.value
    io.tensor(i).wr <> DontCare

    for (j <- 0 until NumOuts){
      io.Out(i)(j).bits.data := queue(i).io.deq.bits.asUInt()
      io.Out(i)(j).bits.valid := true.B
      io.Out(i)(j).bits.predicate := true.B
      io.Out(i)(j).bits.taskID := 0.U

      io.Out(i)(j).valid := queue(i).io.deq.valid
    }
    queue(i).io.deq.ready := io.Out(i).map(_.ready).reduceLeft(_&&_)
  }

  io.done := false.B

  when(queue.map(_.io.enq.fire()).reduceLeft(_&&_)) {readTensorCnt.inc()}

  when(queue.map(_.io.deq.fire()).reduceLeft(_&&_)) {wgtOutCnt.inc()}
  when(wgtOutCnt.value === wgtNum) {wgtOutCnt.value := 0.U}



  switch(state){
    is(sIdle){
      when(io.start){
        state := sRead
      }
    }
    is(sRead){
      when(readTensorCnt.value === memTensorRows){
        readTensorCnt.value := 0.U
        state := sClear
      }
    }
    is(sClear){
      when(wgtOutCnt.value === wgtNum){
        wgtOutCnt.value := 0.U
        queue.foreach(_.io.clear := true.B)
        io.done := true.B
        state := sIdle
      }
    }
  }


}