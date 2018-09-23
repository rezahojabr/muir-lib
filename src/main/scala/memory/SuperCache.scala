package memory


import Chisel.experimental.chiselName
import accel.Cache
import chisel3._
import chisel3.Module
import chisel3.util._
import junctions._
import muxes.{Demux, DemuxGen}

import scala.collection.immutable


// Config
import config._
import utility._
import interfaces._
import scala.math._


class PeekQueue[T <: Data](gen: T, val entries: Int)(implicit val p: Parameters) extends Module with CoreParams {
  /** The I/O for this queue */
  val io = new QueueIO(gen, entries)

  val fetch_queue = Module(new Queue(gen, 2))

  fetch_queue.io.enq.bits <> io.enq.bits

  //  Pipe registers
  val pipe = Module(new Pipe(gen, entries))
  pipe.io.enq.bits <> fetch_queue.io.deq.bits
  pipe.io.enq.valid := false.B

  fetch_queue.io.enq.valid := io.enq.valid | pipe.io.deq.valid
  io.enq.ready := fetch_queue.io.enq.ready & (~pipe.io.deq.valid).toBool

  // Recycle the request.
  when(pipe.io.deq.valid) {
    fetch_queue.io.enq.bits <> pipe.io.deq.bits
  }

  def recycle() = {
    pipe.io.enq.valid := true.B
  }

  io.deq <> fetch_queue.io.deq
}

class NCacheIO(val NumTiles: Int = 1, val NumBanks: Int = 1)(implicit val p: Parameters)
  extends Module with CoreParams with UniformPrintfs {
  val io = IO(new Bundle {
    val cpu   = new Bundle {
      val MemReq  = Vec(NumTiles, Flipped(Decoupled(new MemReq)))
      val MemResp = Vec(NumTiles, Output(Valid(new MemResp)))

    }
    val nasti = new NastiIO
    val stat  = Output(Vec(NumBanks, UInt(xlen.W)))
  })
}

class NastiWriteBundle(implicit val p: Parameters) extends Bundle {
  val aw = new NastiWriteAddressChannel( )
  val w  = new NastiWriteDataChannel( )
}

class CacheSlotsBundle(val NumTiles: Int)(implicit val p: Parameters) extends Bundle {
  val bits  = new MemResp
  val tile  = UInt(max(1, log2Ceil(NumTiles)).W)
  val alloc = new Bool
  val ready = new Bool

  def fire(): Bool = {
    alloc && ready
  }

  override def cloneType = new CacheSlotsBundle(NumTiles).asInstanceOf[this.type]
}

object CacheSlotsBundle {
  def default(NumTiles: Int)(implicit p: Parameters): CacheSlotsBundle = {
    val wire = Wire(new CacheSlotsBundle(NumTiles))
    wire.alloc := false.B
    wire.tile := 0.U
    wire.ready := false.B
    wire.bits := MemResp.default
    wire.bits.valid := false.B
    wire
  }
}

class NCache(NumTiles: Int = 1, NumBanks: Int = 1)(implicit p: Parameters) extends NCacheIO(NumTiles, NumBanks)(p) {

  //  Declare a vector of cache objects
  val caches = for (i <- 0 until NumBanks) yield {
    val cache1 = Module(new Cache(i))
    io.stat(i) := cache1.io.stat
    cache1
  }

  /*============================*
   *    Wiring  Cache to CPU  *
   *============================*/
  assert(isPow2(NumBanks) && NumBanks != 0)
  //
  val NumBankBits = max(1, log2Ceil(NumBanks))
  val NumTileBits = max(1, log2Ceil(NumBanks))

  //  Per-Tile stateink
  val fetch_queue = Module(new Queue(new MemReq( ), 3))

  //  Pipe registers
  val fetch_pipe = Module(new Pipe(new MemReq( ), 2))
  fetch_pipe.io.enq.bits <> fetch_queue.io.enq.bits

  val NumSlots = NumTiles * NumBanks
  val slots    = RegInit(VecInit(Seq.fill(NumSlots)(CacheSlotsBundle.default(NumTiles))))

  // Per-Cache bank state
  //  val slots         = RegInit(VecInit(Seq.fill(NumTiles)(VecInit(Seq.fill(NumBanks)(CacheSlotsBundle.default(NumTiles))))))
  val cache_ready   = VecInit(caches.map(_.io.cpu.req.ready))
  val cache_req_io  = VecInit(caches.map(_.io.cpu.req))
  val cache_serving = RegInit(VecInit(Seq.fill(NumBanks)(0.U(max(1, log2Ceil(NumSlots)).W))))


  //  Per-tile Structures

  //  Fetch queue arbiter
  val fetch_arbiter = Module(new RRArbiter(new MemReq, NumTiles))
  fetch_arbiter.io.in <> io.cpu.MemReq

  //  Arbiter to queue
  //  Wire up everything but chosen.
  fetch_queue.io.enq.bits <> fetch_arbiter.io.out.bits.clone_and_set_tile_id(fetch_arbiter.io.chosen)
  //  Set chosen now. [HACK]. This is to avoid setting sink twice.
  fetch_pipe.io.enq.bits <> fetch_queue.io.deq.bits
  fetch_pipe.io.enq.valid := false.B


  //  enqueue into queue from arbiter.
  fetch_queue.io.enq.valid := fetch_arbiter.io.out.valid | fetch_pipe.io.deq.valid
  fetch_arbiter.io.out.ready := fetch_queue.io.enq.ready & (~fetch_pipe.io.deq.valid).toBool

  //  If NumBanks are 0 then use the default bank indexing
  var bank_idx = if (NumBanks == 1) {
    0.U
  } else {
    fetch_queue.io.deq.bits.addr(caches(0).bankbitindex + NumBankBits - 1, caches(0).bankbitindex)
  }

  //  Slot allocation arbiter
  val slot_arbiter = Module(new RRArbiter(new Bool, NumSlots))
  val slot_idx     = slot_arbiter.io.chosen
  slot_arbiter.io.in zip slots foreach { case (a, b) =>
    a.valid := ~(b.alloc)
  }

  //  Handshaking fetch queue with slot arbiter
  fetch_queue.io.deq.ready := slot_arbiter.io.out.valid
  slot_arbiter.io.out.ready := fetch_queue.io.deq.valid

  //  Wiring up queue with appropriate cache
  cache_req_io foreach { rq =>
    rq.valid := false.B
    rq.bits <> fetch_queue.io.deq.bits
  }

  //  Queueing Logic.
  when(fetch_queue.io.deq.fire) {
    when(cache_ready(bank_idx)) {
      //  Fetch queue fires only if slot is free.
      //  Slot is free and cache is ready

      slots(slot_idx).tile := 0.U
      slots(slot_idx).alloc := true.B
      // Setting cache metadata before sending request request.
      cache_req_io(bank_idx).valid := true.B
      cache_serving(bank_idx) := slot_idx
    }.otherwise {
      //    Cache is not ready
      //      Recycling logic
      fetch_pipe.io.enq.valid := true.B
    }
  }
  // Recycle the request.
  when(fetch_pipe.io.deq.valid) {
    fetch_queue.io.enq.bits <> fetch_pipe.io.deq.bits
  }


  for (i <- 0 until NumBanks) {
    when(caches(i).io.cpu.resp.valid) {
      slots(cache_serving(i)).ready := true.B
      slots(cache_serving(i)).bits := caches(i).io.cpu.resp.bits
    }
  }

  val resp_arbiter = Module(new RRArbiter(
    new MemResp, NumSlots))

  for (i <- 0 until NumSlots) {
    resp_arbiter.io.in(i).bits := slots(i).bits
    resp_arbiter.io.in(i).bits.tile := slots(i).tile
    resp_arbiter.io.in(i).valid := slots(i).fire
    when(resp_arbiter.io.in(i).fire) {
      slots(i).alloc := false.B
      slots(i).ready := false.B
    }
  }

  val resp_tile = resp_arbiter.io.out.bits.tile
  val resp_slot = resp_arbiter.io.chosen
  io.cpu.MemResp foreach {
    _.valid := false.B
  }

  io.cpu.MemResp(resp_tile).bits := slots(resp_slot).bits
  when(resp_arbiter.io.out.fire( )) {
    io.cpu.MemResp(resp_tile).valid := true.B
  }

  //  Debug statements
  printf(p"Slots: ${slot_arbiter.io.out} \n FQ: ${fetch_queue.io.deq} \n")

  /*============================*
   *    Wiring  Cache to NASTI  *
   *============================*/

  val nasti_ar_arbiter = Module(new Arbiter(new NastiReadAddressChannel( ), NumBanks))
  //  This is a twin channel arbiter (arbitrates for both the W and AW channels at once)
  //  AW and W go together
  val nasti_aw_arbiter = Module(new Arbiter(new NastiWriteAddressChannel( ), NumBanks))
  val nasti_r_demux    = Module(new DemuxGen(new NastiReadDataChannel( ), NumBanks))
  val nasti_b_demux    = Module(new DemuxGen(new NastiWriteResponseChannel( ), NumBanks))

  //  Multiplex cache objects to NASTI interface.
  nasti_ar_arbiter.io.in zip caches.map {
    _.io.nasti.ar
  } foreach {
    case (a, b) => a <> b
  }

  //  Multiplex cache objects to NASTI interface.
  nasti_aw_arbiter.io.in zip caches.map {
    _.io.nasti.aw
  } foreach {
    case (a, b) => a <> b
  }


  //  Cache->NASTI AR
  io.nasti.ar <> nasti_ar_arbiter.io.out

  //  Cache->NASTI AW
  io.nasti.aw.bits <> nasti_aw_arbiter.io.out.bits
  io.nasti.aw.valid <> nasti_aw_arbiter.io.out.valid

  //  Vector of bools.
  val cache_wback = caches map {
    _.IsWBACK( )
  }
  nasti_aw_arbiter.io.out.ready := io.nasti.aw.ready & (~(cache_wback.reduceLeft(_ | _)))

  //  Cache->NASTI W . Sequence of cache io.nasti.w.bits
  val cache_nasti_w_bits = caches.map {
    _.io.nasti.w.bits
  }
  //  Feed into MUX and select one of these for the NASTI io w
  val w_mux              = Mux1H(cache_wback, cache_nasti_w_bits)
  //  Mux -> NASTI
  io.nasti.w.bits <> w_mux
  //  NASTI w valid  = true if one of the caches are writing back.
  io.nasti.w.valid := cache_wback.reduceLeft(_ | _)
  // Fanout io nasti w ready to all the caches. NASTI slave to all cache ready
  caches foreach {
    _.io.nasti.w.ready := io.nasti.w.ready
  }

  // NASTI->Cache b and r; ready.
  val b_ready = caches map {
    _.io.nasti.b.ready
  }

  val r_ready = caches map {
    _.io.nasti.r.ready
  }

  io.nasti.b.ready := b_ready.reduceLeft(_ | _)
  io.nasti.r.ready := r_ready.reduceLeft(_ | _)

  //  NASTI->Cache r
  nasti_r_demux.io.en := io.nasti.r.fire
  nasti_r_demux.io.input := io.nasti.r.bits
  nasti_r_demux.io.sel := io.nasti.r.bits.id


  for (i <- 0 until NumBanks) {
    caches(i).io.nasti.r.valid := nasti_r_demux.io.valids(i)
    caches(i).io.nasti.r.bits := nasti_r_demux.io.outputs(i)
  }


  //  NASTI->Cache b
  nasti_b_demux.io.en := io.nasti.b.fire
  nasti_b_demux.io.input := io.nasti.b.bits
  nasti_b_demux.io.sel := io.nasti.b.bits.id

  for (i <- 0 until NumBanks) {
    caches(i).io.nasti.b.valid := nasti_b_demux.io.valids(i)
    caches(i).io.nasti.b.bits := nasti_b_demux.io.outputs(i)
  }

}

import java.io.{File, FileWriter}

object NCacheMain extends App {
  val dir = new File("RTL/NCache");
  dir.mkdirs
  implicit val p = config.Parameters.root((new MiniConfig).toInstance)
  val chirrtl = firrtl.Parser.parse(chisel3.Driver.emit(() => new NCache(1, 1)))

  val verilogFile   = new File(dir, s"${chirrtl.main}.v")
  val verilogWriter = new FileWriter(verilogFile)
  val compileResult = (new firrtl.VerilogCompiler).compileAndEmit(firrtl.CircuitState(chirrtl, firrtl.ChirrtlForm))
  val compiledStuff = compileResult.getEmittedCircuit
  verilogWriter.write(compiledStuff.value)
  verilogWriter.close( )
}