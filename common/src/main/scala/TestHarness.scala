package zynq

import chisel3._
import chisel3.util.Queue
import config.Parameters
import diplomacy.LazyModule
import rocket.PAddrBits
import rocketchip._
import uncore.coherence.ClientMetadata
import junctions.{SerialIO, NastiArbiter, NastiKey, NastiParameters}
import testchipip._

class TestHarness(implicit val p: Parameters) extends Module {
  val io = IO(new Bundle {
    val success = Output(Bool())
  })

  val config = p(ExtIn)
  val driverParams = p.alterPartial({
    case NastiKey => NastiParameters(
      dataBits = config.beatBytes * 8,
      addrBits = p(PAddrBits),
      idBits = config.idBits)
  })
  val driver = Module(new TestHarnessDriver()(driverParams))
  val dut = Module(LazyModule(new FPGAZynqTop).module)
  dut.reset := driver.io.sys_reset
  driver.io.serial <> dut.io.serial
  driver.io.bdev <> dut.io.bdev
  io.success := driver.io.success

  val nMemChannels = p(coreplex.BankedL2Config).nMemoryChannels
  val mem = Module(LazyModule(new SimAXIMem(nMemChannels)).module)
  mem.io.axi4 <> dut.io.mem_axi4
}

class TestHarnessDriver(implicit p: Parameters) extends Module {
  val serialWidth = p(SerialInterfaceWidth)
  val io = IO(new Bundle {
    val serial = Flipped(new SerialIO(serialWidth))
    val bdev = Flipped(new BlockDeviceIO)
    val sys_reset = Output(Bool())
    val success = Output(Bool())
  })

  val arbiter = Module(new NastiArbiter(3))
  val zynq = Module(LazyModule(
    new ZynqAdapter(p(ZynqAdapterBase), p(ExtIn))).module)
  val serDriver = Module(new SerialDriver)
  val resetDriver = Module(new ResetDriver)
  val blkdevDriver = Module(new BlockDeviceDriver)
  val simSerial = Module(new SimSerialWrapper(serialWidth))
  val simBlockDev = Module(new SimBlockDevice)
  simBlockDev.io.clock := clock
  simBlockDev.io.reset := reset
  serDriver.reset := zynq.io.sys_reset
  blkdevDriver.reset := zynq.io.sys_reset

  arbiter.io.master <> Seq(
    serDriver.io.axi, resetDriver.io.axi, blkdevDriver.io.axi)
  NastiAXIConnect(zynq.io.axi.head, arbiter.io.slave)
  zynq.io.serial <> io.serial
  simSerial.io.serial <> serDriver.io.serial
  zynq.io.bdev <> io.bdev
  simBlockDev.io.bdev <> blkdevDriver.io.bdev

  io.sys_reset := zynq.io.sys_reset
  io.success := simSerial.io.exit
}
