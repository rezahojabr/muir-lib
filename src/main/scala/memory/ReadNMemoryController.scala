package memory

// Generic Packages
import chisel3._
import chisel3.Module
import chisel3.util._
import org.scalacheck.Prop.False

// Modules needed
import arbiters._
import muxes._

// Config
import config._
import utility._
import interfaces._
import node._

// Cache requests
import accel._

// Memory constants
import Constants._

abstract class WriteTypEntryIO()(implicit val p: Parameters)
  extends Module
  with CoreParams {

  val io = IO(new Bundle {
    // Read Request Type
    val NodeReq = Flipped(Decoupled(Input(new WriteReq)))
    val NodeResp = Decoupled(new WriteResp)

    //Memory interface
    val MemReq = Decoupled(new CacheReq)
    val MemResp = Input(new CacheResp)

    // val Output 
    val output = Decoupled(new WriteResp)

    val free = Output(Bool())
    val done = Output(Bool())
  })
}
/**
 * @brief Read Table Entry
 * @details [long description]
 * 
 * @param ID [Read table IDs]
 * @return [description]
 */
class WriteTypTableEntry(id: Int)(implicit p: Parameters) extends WriteTypEntryIO()(p) {
  val ID = RegInit(id.U)
  val request_R = RegInit(WriteReq.default)
  val request_valid_R = RegInit(false.B)
  // Data buffers for misaligned accesses

  // Mask for final ANDing and output of data
  val bitmask = RegInit(0.U(((Typ_SZ+1) * xlen).W))
  // Send word mask for tracking how many words need to be read
  val sendbytemask = RegInit(0.U(((Typ_SZ+1) * xlen/8).W))

  // Is the request valid and request to memory
  val ReqValid = RegInit(false.B)
  val ReqAddress = RegInit(0.U(xlen.W))

  // Incoming data valid and data operand.
  val DataValid = RegInit(false.B)
  // Can optimize to be a shift bit.
  val ptr        = RegInit(0.U(log2Ceil(Typ_SZ+1).W))
  val linebuffer = RegInit(Vec(Seq.fill(Typ_SZ+1)(0.U(xlen.W))))
  val bytes_overflow = RegInit(0.U((2 * xlen).W))
  val linemask   = RegInit(Vec(Seq.fill(Typ_SZ+1)(0.U((xlen/8).W))))
  val xlen_bytes = xlen / 8
 
  // State machine
  val s_idle :: s_SENDING :: s_RECEIVING :: s_Done :: Nil = Enum(4)
  val state = RegInit(s_idle)

// Check if entry free. 
/*================================================
=            Indicate Table State                =
=================================================*/


  // Table entry indicates free to outside world
  io.free := (state === s_idle)
  // Table entry ready to latch new requests
  io.NodeReq.ready := (state === s_idle)
  // Table entry to output demux
  io.done := (state === s_Done)

/*=================================================================
=            Default values for external communication            =
==================================================================*/
  io.output.valid := 0.U
  io.MemReq.valid := 0.U


/*=======================================================
=            Latch Inputs. Calculate masks              =
========================================================*/
  when(io.NodeReq.fire() && (ptr =/= Typ_SZ.U)) {
    request_R := io.NodeReq.bits
    // Calculate things to start the sending process
    // Base word address
    ReqAddress   := (io.NodeReq.bits.address >> log2Ceil(xlen_bytes)) << log2Ceil(xlen_bytes)
    // Bitmask of data  for final ANDing
    bitmask := ReadBitMask(io.NodeReq.bits.Typ, io.NodeReq.bits.address,xlen)
    // Bytemask of bytes within words that need to be fetched.
    sendbytemask := ReadByteMask(io.NodeReq.bits.Typ, io.NodeReq.bits.address,xlen)
    // Alignment
    val alignment = io.NodeReq.bits.address(log2Ceil(xlen_bytes) - 1, 0)
    // Move data to line buffer.
    linebuffer(ptr)    := (io.NodeReq.bits.data << Cat(alignment, 0.U(3.W))) | bytes_overflow(2*xlen-1,xlen)
    // Overflown bytes
    bytes_overflow     := io.NodeReq.bits.data << Cat(alignment, 0.U(3.W))
    // Final top bytes
    linebuffer(Typ_SZ) := bytes_overflow(2*xlen-1,xlen)
    // Move to receive next word
    ptr := ptr + 1.U
}


 printf(p"\n MSHR $ID ptr: $ptr linebuffer: ${Hexadecimal(linebuffer.asUInt)}")

/*===========================================================
=            Sending values to the cache request            =
===========================================================*/

  when((state === s_SENDING) && (sendbytemask =/= 0.U)) {
    io.MemReq.bits.addr := ReqAddress + Cat(ptr,0.U(log2Ceil(xlen_bytes).W))
    // Sending data; pick word from linebuffer 
    io.MemReq.bits.data  := linebuffer(ptr)
    io.MemReq.bits.tag  := ID
    io.MemReq.bits.mask := sendbytemask(xlen/8-1,0)
    io.MemReq.valid     := 1.U
    // io.MemReq.ready means arbitration succeeded and memory op has been passed on
    when(io.MemReq.fire()) {
      // Shift right by word length on machine.
      sendbytemask := sendbytemask >> (xlen / 8)
      // Increment ptr to next entry in linebuffer (for next write)
      ptr := ptr + 1.U
      // Move to receiving data
      state := s_RECEIVING
    }
  }

/*============================================================
=            Receiving values from cache response            =
=============================================================*/

  when((state === s_RECEIVING) && (io.MemResp.valid === true.B)) {
    // Check if more data needs to be sent 
    val y = (sendbytemask === 0.asUInt((xlen/4).W))
    state := Mux(y, s_Done, s_SENDING)
  }

/*============================================================
=            Cleanup and send output                         =
=============================================================*/

  when(state === s_Done) {
    // For the demux
    io.output.valid := 1.U
    // Valid write 
    // @todo: (done and valid are redundant. Need to cleanup at some point in the future) 
    io.output.bits.done    := true.B
    io.output.bits.valid   := true.B
    io.output.bits.RouteID := request_R.RouteID
    // Output driver demux tree has forwarded output (may not have reached receiving node yet)
    when(io.output.fire()) {
      state := s_idle
      request_valid_R := false.B
    }
  }
}

class WriteNMemoryController(NumOps: Int, BaseSize: Int)(implicit val p: Parameters) extends Module with CoreParams {
  val io = IO(new Bundle {
    val WriteIn = Vec(NumOps, Flipped(Decoupled(new WriteReq())))
    val WriteOut = Vec(NumOps, Output(new WriteResp()))
    val CacheReq = Decoupled(new CacheReq)
    val CacheResp = Flipped(Valid(new CacheResp))
  })
  require(rdmshrlen >= 0)
  // Number of MLP entries
  val MLPSize = 1 << wrmshrlen
  // Input arbiter
  val in_arb = Module(new ArbiterTree(BaseSize = BaseSize, NumOps = NumOps, new WriteReq()))
  // MSHR allocator
  val alloc_arb = Module(new LockingRRArbiter(Bool(),MLPSize,count=Typ_SZ))

  // Memory request
  val cachereq_arb = Module(new Arbiter(new CacheReq, MLPSize))
  // Memory response Demux
  val cacheresp_demux = Module(new Demux(new CacheResp, MLPSize))

  // Output arbiter and demuxes
  val out_arb = Module(new RRArbiter(new WriteResp, MLPSize))
  val out_demux = Module(new DeMuxTree(BaseSize = BaseSize, NumOps = NumOps, new WriteResp()))

/*=====================================================================
=            Wire up incoming reads from nodes to ReadMSHR            =
=====================================================================*/

  // Wire up input with in_arb
  for (i <- 0 until NumOps) {
    in_arb.io.in(i) <> io.WriteIn(i)
  }

/*=============================================
=           Declare Read Table                =
=============================================*/

  // Create WriteTable
  val WriteTable = for (i <- 0 until MLPSize) yield {
    val write_entry = Module(new WriteTypTableEntry(i))
    write_entry
  }

/*=========================================================================
=            Wire up arbiters and demux to Write table entries
             1. Allocator arbiter
             2. Output arbiter
             3. Output demux
             4. Cache request arbiter
             5. Cache response demux                                                             =
=========================================================================*/

  for (i <- 0 until MLPSize) {
    // val MSHR = Module(new WriteTableEntry(i))
    // Allocator wireup with table entries
    alloc_arb.io.in(i).valid := WriteTable(i).io.free
    WriteTable(i).io.NodeReq.valid := alloc_arb.io.in(i).ready
    WriteTable(i).io.NodeReq.bits := in_arb.io.out.bits

    // Table entries -> CacheReq arbiter.
    cachereq_arb.io.in(i) <> WriteTable(i).io.MemReq

    // CacheResp -> Table entries Demux
    WriteTable(i).io.MemResp <> cacheresp_demux.io.outputs(i)

    // Table entries -> Output arbiter 
    out_arb.io.in(i) <> WriteTable(i).io.output
  }

  //  Handshaking input arbiter with allocator
  in_arb.io.out.ready := alloc_arb.io.out.valid
  alloc_arb.io.out.ready := in_arb.io.out.valid

  // Cache request arbiter
  // cachereq_arb.io.out.ready := io.CacheReq.ready
  io.CacheReq <> cachereq_arb.io.out

  // Cache response Demux
  cacheresp_demux.io.en := io.CacheResp.valid
  cacheresp_demux.io.input := io.CacheResp.bits
  cacheresp_demux.io.sel := io.CacheResp.bits.tag

  // Output arbiter -> Demux
  out_arb.io.out.ready := true.B
  out_demux.io.enable := out_arb.io.out.fire()
  out_demux.io.input := out_arb.io.out.bits

  // printf(p"\n Arbiter out: ${in_arb.io.out}")

}