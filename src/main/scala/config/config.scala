package dandelion.config


import chisel3._
import regfile._
import dandelion.junctions._
import dandelion.fpu._
import dandelion.accel._

case object XLEN extends Field[Int]

case object TLEN extends Field[Int]

case object GLEN extends Field[Int]

case object MSHRLEN extends Field[Int]

case object TYPSZ extends Field[Int]

case object VERBOSITY extends Field[String]

case object COMPONENTS extends Field[String]

case object TRACE extends Field[Boolean]

case object CTRACE extends Field[Boolean]

case object BuildRFile extends Field[Parameters => AbstractRFile]

case object FTYP extends Field[FType]

case object NWays extends Field[Int]

case object NSets extends Field[Int]

case object CacheBlockBytes extends Field[Int]


abstract trait CoreParams {
  implicit val p: Parameters
  val xlen = p(XLEN)
  val tlen = p(TLEN)
  val glen = p(GLEN)
  val Typ_SZ = p(TYPSZ)
  val Beats = Typ_SZ / xlen
  val mshrlen = p(MSHRLEN)
  val Ftyp = p(FTYP)
  // Debugging dumps
  val log = p(TRACE)
  val clog = p(CTRACE)
  val verb = p(VERBOSITY)
  val comp = p(COMPONENTS)

}

abstract class CoreBundle(implicit val p: Parameters) extends ParameterizedBundle()(p) with CoreParams
abstract class AXICoreBundle(implicit val p: Parameters) extends GenericParameterizedBundle(p) with CoreParams


class MiniConfig extends Config((site, here, up) => {
  // Core
  case XLEN => 32
  case TLEN => 5
  case GLEN => 16
  // # Max bits of cache request tag.
  case MSHRLEN => 8
  case TYPSZ => 64
  case VERBOSITY => "low"
  //    case COMPONENTS => "TYPLOAD;TYPOP;TYPSTORE"
  case COMPONENTS => ""
  // Max size of type memory system may see
  case TRACE => true
  case CTRACE => false
  case BuildRFile => (p: Parameters) => Module(new RFile(32)(p))

  //-------------------------
  // Cache
  case NWays => 1 // TODO: set-associative
  case NSets => 256
  case CacheBlockBytes => 4 * (here(XLEN) >> 3) // 4 x 32 bits = 16B

  // NastiIO
  case NastiKey => new NastiParameters(
    idBits = 12,
    dataBits = 64,
    addrBits = 32)

  case FTYP => site(XLEN) match {
    case 32 => FType.S
    case 64 => FType.D
    case 16 => FType.H
    case _ => FType.S
  }
}

)
