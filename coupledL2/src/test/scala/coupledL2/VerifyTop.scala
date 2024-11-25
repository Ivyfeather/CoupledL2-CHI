package coupledL2

import chisel3._
import chisel3.stage.ChiselStage
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import chiselFv._
import coupledL2.tl2chi._
import coupledL2.tl2tl.TL2TLCoupledL2
import coupledL2AsL1.prefetch.CoupledL2AsL1PrefParam
import coupledL2AsL1.tl2tl.{TL2TLCoupledL2 => TLCoupledL2AsL1}
import coupledL2AsL1.tl2chi.{TL2CHICoupledL2 => CHICoupledL2AsL1}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tile.MaxHartIdBits
import freechips.rocketchip.tilelink._
import huancun._
import org.chipsalliance.cde.config._
import utility._


class VerifyTop_L2L3L2()(implicit p: Parameters) extends LazyModule {

  /* L1D   L1D
   *  |     |
   * L2    L2
   *  \    /
   *    L3
   */

  println("class VerifyTop_L2L3L2:")

  override lazy val desiredName: String = "VerifyTop"
  val delayFactor = 0.2
  val cacheParams = p(L2ParamKey)

  val nrL2 = 2

  def createClientNode(name: String, sources: Int) = {
    val masterNode = TLClientNode(Seq(
      TLMasterPortParameters.v2(
        masters = Seq(
          TLMasterParameters.v1(
            name = name,
            sourceId = IdRange(0, sources),
            supportsProbe = TransferSizes(cacheParams.blockBytes)
          )
        ),
        channelBytes = TLChannelBeatBytes(cacheParams.blockBytes),
        minLatency = 1,
        echoFields = Nil,
        requestFields = Seq(AliasField(2)),
        responseKeys = cacheParams.respKey
      )
    ))
    masterNode
  }

  val l0_nodes = (0 until nrL2).map(i => createClientNode(s"l0$i", 32))

  val coupledL2AsL1 = (0 until nrL2).map(i => LazyModule(new TLCoupledL2AsL1()(baseConfig(1).alterPartial({
    case L2ParamKey => L2Param(
      name = s"l1$i",
      ways = 2,
      sets = 2,
      blockBytes = 2,
      channelBytes = TLChannelBeatBytes(1),
      clientCaches = Seq(L1Param(aliasBitsOpt = Some(2))),
      echoField = Seq(),
      prefetch = Seq(CoupledL2AsL1PrefParam()),
      mshrs = 4,
      hartId = i
    )
    case BankBitsKey => 0
  }))))
  val l1_nodes = coupledL2AsL1.map(_.node)

  val coupledL2 = (0 until nrL2).map(i => LazyModule(new TL2TLCoupledL2()(baseConfig(1).alterPartial({
    case L2ParamKey => L2Param(
      name = s"l2$i",
      ways = 2,
      sets = 4,
      blockBytes = 2,
      channelBytes = TLChannelBeatBytes(1),
      clientCaches = Seq(L1Param(aliasBitsOpt = Some(2))),
      echoField = Seq(DirtyField()),
      mshrs = 4,
      hartId = i
    )
    case BankBitsKey => 0
  }))))
  val l2_nodes = coupledL2.map(_.node)

  val l3 = LazyModule(new HuanCun()(baseConfig(1).alterPartial({
    case HCCacheParamsKey => HCCacheParameters(
      name = "L3",
      level = 3,
      ways = 2,
      sets = 4,
      blockBytes = 2,
      channelBytes = TLChannelBeatBytes(1),
      inclusive = false,
      clientCaches = (0 until nrL2).map(i =>
        CacheParameters(
          name = s"l2",
          sets = 4,
          ways = 2 + 2,
          blockGranularity = log2Ceil(2)
        ),
      ),
      echoField = Seq(DirtyField()),
      simulation = true,
      mshrs = 6
    )
  })))


  val xbar = TLXbar()
  val ram = LazyModule(new TLRAM(AddressSet(0, 0x1FL), beatBytes = 1))

  l0_nodes.zip(l1_nodes).zipWithIndex map {
    case ((l0, l1), i) => l1 := l0
  }

  l1_nodes.zip(l2_nodes).zipWithIndex map {
    case ((l1d, l2), i) => l2 := TLLogger(s"L2_L1_${i}") := TLBuffer() := l1d
  }

  l2_nodes.zipWithIndex map {
    case (l2, i) => xbar := TLLogger(s"L3_L2_${i}") := TLBuffer() := l2
  }

  ram.node :=
    TLXbar() :=*
      TLFragmenter(1, 2) :=*
      TLCacheCork() :=*
      TLDelayer(delayFactor) :=*
      TLLogger(s"MEM_L3") :=*
      l3.node :=* xbar

  lazy val module = new LazyModuleImp(this) with Formal {
    val timer = WireDefault(0.U(64.W))
    val logEnable = WireDefault(false.B)
    val clean = WireDefault(false.B)
    val dump = WireDefault(false.B)

    dontTouch(timer)
    dontTouch(logEnable)
    dontTouch(clean)
    dontTouch(dump)

    coupledL2AsL1.foreach(_.module.io.debugTopDown := DontCare)
    coupledL2.foreach(_.module.io.debugTopDown := DontCare)


    coupledL2AsL1.foreach(_.module.io.l2_tlb_req <> DontCare)
    coupledL2.foreach(_.module.io.l2_tlb_req <> DontCare)

    coupledL2AsL1.foreach(_.module.io.hartId <> DontCare)
    coupledL2.foreach(_.module.io.hartId <> DontCare)

    l1_nodes.foreach { node =>
      val (l1_in, _) = node.in.head
      dontTouch(l1_in)
    }

    // Input signals for formal verification
    val io = IO(new Bundle {
      val topInputRandomAddrs = Input(Vec(nrL2, UInt(5.W)))
      val topInputNeedT = Input(Vec(nrL2, Bool()))
    })

    coupledL2AsL1.zipWithIndex.foreach { case (l2AsL1, i) =>
      l2AsL1.module.io.prefetcherInputRandomAddr := io.topInputRandomAddrs(i)
      l2AsL1.module.io.prefetcherNeedT := io.topInputNeedT(i)
      dontTouch(l2AsL1.module.io)
    }

    val verify_timer = RegInit(0.U(50.W))
    verify_timer := verify_timer + 1.U
    val dir_resetFinish = WireDefault(false.B)
    BoringUtils.addSink(dir_resetFinish, "coupledL2_0_dir")
    assume(verify_timer < 100.U || dir_resetFinish)

    val offsetBits = 1
    val bankBits = 0
    val setBits = 2
    val tagBits = 2

    def parseAddress(x: UInt): (UInt, UInt, UInt) = {
      val offset = x
      val set = offset >> (offsetBits + bankBits)
      val tag = set >> setBits
      (tag(tagBits - 1, 0), set(setBits - 1, 0), offset(offsetBits - 1, 0))
    }

    val stateArray = Seq.fill(nrL2)(WireDefault(VecInit.fill(4, 2)(0.U(2.W))))
    val tagArray = Seq.fill(nrL2)(WireDefault(VecInit.fill(4, 2)(0.U(2.W))))

    for (i <- 0 until nrL2) {
      BoringUtils.addSink(stateArray(i), s"stateArray_${i}")
      BoringUtils.addSink(tagArray(i), s"tagArray_${i}")
    }

    def generateAssert(addr: UInt, state1: UInt, state2: UInt): Unit = {
      val (tag, set, offset) = parseAddress(addr)
      val match_tag0 = tagArray(0)(set).map(_ === tag)
      val match_tag1 = tagArray(1)(set).map(_ === tag)
      val hit0 = match_tag0.reduce(_ || _)
      val hit1 = match_tag1.reduce(_ || _)
      val way0 = MuxCase(0.U, match_tag0.zipWithIndex.map {
        case (matched, indx) =>
          matched -> indx.U
      })

      val way1 = MuxCase(0.U, match_tag1.zipWithIndex.map {
        case (matched, indx) =>
          matched -> indx.U
      })
      assert(!(hit0 && stateArray(0)(set)(way0) === state1 &&
        hit1 && stateArray(1)(set)(way1) === state2))
    }
    generateAssert(0.U(32.W), MetaData.TIP, MetaData.TIP)
    generateAssert(0.U(32.W), MetaData.TIP, MetaData.TRUNK)
    generateAssert(0.U(32.W), MetaData.INVALID, MetaData.TIP)
    generateAssert(0.U(32.W), MetaData.BRANCH, MetaData.BRANCH)
    generateAssert(0.U(32.W), MetaData.TIP, MetaData.BRANCH)
    generateAssert(0.U(32.W), MetaData.TRUNK, MetaData.INVALID)
  }
}

object VerifyTop_L2L3L2 extends App {

  println("object VerifyTop_L2L3L2:")

  val config = baseConfig(1).alterPartial({
    case L2ParamKey => L2Param(
      clientCaches = Seq(L1Param(aliasBitsOpt = Some(2))),
    )
    case HCCacheParamsKey => HCCacheParameters(
      echoField = Seq(DirtyField())
    )
  })
  val top = DisableMonitors(p => LazyModule(new VerifyTop_L2L3L2()(p)))(config)

  (new ChiselStage).emitSystemVerilog(
    top.module,
    Array("--target-dir", "Verilog/L2L3L2")
  )
}

class VerifyTop_L2L3()(implicit p: Parameters) extends LazyModule {

  /* L1D  
   *  |   
   * L2
   *  |
   * L3
   */

  println("class VerifyTop_L2L3:")

  override lazy val desiredName: String = "VerifyTop"
  val delayFactor = 0.2
  val cacheParams = p(L2ParamKey)

  val nrL1 = 1
  val nrL2 = 1

  def createClientNode(name: String, sources: Int) = {
    val masterNode = TLClientNode(Seq(
      TLMasterPortParameters.v2(
        masters = Seq(
          TLMasterParameters.v1(
            name = name,
            sourceId = IdRange(0, sources),
            supportsProbe = TransferSizes(cacheParams.blockBytes)
          )
        ),
        channelBytes = TLChannelBeatBytes(cacheParams.blockBytes),
        minLatency = 1,
        echoFields = Nil,
        requestFields = Seq(AliasField(2)),
        responseKeys = cacheParams.respKey
      )
    ))
    masterNode
  }

  val l0_nodes = (0 until nrL1).map(i => createClientNode(s"l0$i", 32))

  val coupledL2AsL1 = (0 until nrL1).map(i => LazyModule(new TLCoupledL2AsL1()(baseConfig(1).alterPartial({
    case L2ParamKey => L2Param(
      name = s"l1$i",
      ways = 2,
      sets = 2,
      blockBytes = 2,
      channelBytes = TLChannelBeatBytes(1),
      clientCaches = Seq(L1Param(aliasBitsOpt = Some(2))),
      echoField = Seq(),
      prefetch = Seq(CoupledL2AsL1PrefParam()),
      mshrs = 4,
      hartId = i
    )
    case BankBitsKey => 0
  }))))
  val l1_nodes = coupledL2AsL1.map(_.node)

  val coupledL2 = (0 until nrL2).map(i => LazyModule(new TL2TLCoupledL2()(baseConfig(1).alterPartial({
    case L2ParamKey => L2Param(
      name = s"l2$i",
      ways = 2,
      sets = 4,
      blockBytes = 2,
      channelBytes = TLChannelBeatBytes(1),
      clientCaches = Seq(L1Param(aliasBitsOpt = Some(2))),
      echoField = Seq(DirtyField()),
      mshrs = 4,
      hartId = i
    )
    case BankBitsKey => 0
  }))))
  val l2_nodes = coupledL2.map(_.node)

  val l3 = LazyModule(new HuanCun()(baseConfig(1).alterPartial({
    case HCCacheParamsKey => HCCacheParameters(
      name = "L3",
      level = 3,
      ways = 2,
      sets = 4,
      blockBytes = 2,
      channelBytes = TLChannelBeatBytes(1),
      inclusive = false,
      clientCaches = (0 until nrL2).map(i =>
        CacheParameters(
          name = s"l2",
          sets = 4,
          ways = 2 + 2,
          blockGranularity = log2Ceil(2)
        ),
      ),
      echoField = Seq(DirtyField()),
      simulation = true,
      mshrs = 6
    )
  })))


  val xbar = TLXbar()
  val ram = LazyModule(new TLRAM(AddressSet(0, 0x1FL), beatBytes = 1))

  l0_nodes.zip(l1_nodes).zipWithIndex map {
    case ((l0, l1), i) => l1 := l0
  }

  l1_nodes.zipWithIndex map {
    case (l1d, i) => l2_nodes(0) := TLLogger(s"L2_L1_${i}") := TLBuffer() := l1d
  }

  l2_nodes.zipWithIndex map {
    case (l2, i) => xbar := TLLogger(s"L3_L2_${i}") := TLBuffer() := l2
  }

  ram.node :=
    TLXbar() :=*
      TLFragmenter(1, 2) :=*
      TLCacheCork() :=*
      TLDelayer(delayFactor) :=*
      TLLogger(s"MEM_L3") :=*
      l3.node :=* xbar

  lazy val module = new LazyModuleImp(this) with Formal {
    val timer = WireDefault(0.U(64.W))
    val logEnable = WireDefault(false.B)
    val clean = WireDefault(false.B)
    val dump = WireDefault(false.B)

    dontTouch(timer)
    dontTouch(logEnable)
    dontTouch(clean)
    dontTouch(dump)

    coupledL2AsL1.foreach(_.module.io.debugTopDown := DontCare)
    coupledL2.foreach(_.module.io.debugTopDown := DontCare)


    coupledL2AsL1.foreach(_.module.io.l2_tlb_req <> DontCare)
    coupledL2.foreach(_.module.io.l2_tlb_req <> DontCare)

    coupledL2AsL1.foreach(_.module.io.hartId <> DontCare)
    coupledL2.foreach(_.module.io.hartId <> DontCare)

    l1_nodes.foreach { node =>
      val (l1_in, _) = node.in.head
      dontTouch(l1_in)
    }

    // Input signals for formal verification
    val io = IO(new Bundle {
      val topInputRandomAddrs = Input(Vec(nrL2, UInt(5.W)))
      val topInputNeedT = Input(Vec(nrL2, Bool()))
    })

    coupledL2AsL1.zipWithIndex.foreach { case (l2AsL1, i) =>
      l2AsL1.module.io.prefetcherInputRandomAddr := io.topInputRandomAddrs(i)
      l2AsL1.module.io.prefetcherNeedT := io.topInputNeedT(i)
      dontTouch(l2AsL1.module.io)
    }

    val verify_timer = RegInit(0.U(50.W))
    verify_timer := verify_timer + 1.U
    val dir_resetFinish = WireDefault(false.B)
    BoringUtils.addSink(dir_resetFinish, "coupledL2_0_dir")
    assume(verify_timer < 100.U || dir_resetFinish)

    val stateArray = Seq.fill(nrL2)(WireDefault(VecInit.fill(4, 2)(0.U(2.W))))
    val tagArray = Seq.fill(nrL2)(WireDefault(VecInit.fill(4, 2)(0.U(2.W))))

    for (i <- 0 until nrL2) {
      BoringUtils.addSink(stateArray(i), s"stateArray_${i}")
      BoringUtils.addSink(tagArray(i), s"tagArray_${i}")
    }
  }
}

object VerifyTop_L2L3 extends App {

  println("object VerifyTop_L2L3:")

  val config = baseConfig(1).alterPartial({
    case L2ParamKey => L2Param(
      clientCaches = Seq(L1Param(aliasBitsOpt = Some(2))),
    )
    case HCCacheParamsKey => HCCacheParameters(
      echoField = Seq(DirtyField())
    )
  })
  val top = DisableMonitors(p => LazyModule(new VerifyTop_L2L3()(p)))(config)

  (new ChiselStage).emitSystemVerilog(
    top.module,
    Array("--target-dir", "Verilog/L2L3")
  )
}

class VerifyTop_CHIL2(numCores: Int = 2, numULAgents: Int = 0, banks: Int = 1)(implicit p: Parameters) extends LazyModule
  with HasCHIMsgParameters {

  /*   L1D(L1I)* L1D(L1I)* ... L1D(L1I)*
   *       \         |          /
   *       L2        L2   ...  L2
   *         \       |        /
   *          \      |       /
   *             CMN or VIP
   */

  override lazy val desiredName: String = "VerifyTop"
  val delayFactor = 0.5
  val cacheParams = p(L2ParamKey)

  def createClientNode(name: String, sources: Int) = {
    val masterNode = TLClientNode(Seq(
      TLMasterPortParameters.v2(
        masters = Seq(
          TLMasterParameters.v1(
            name = name,
            sourceId = IdRange(0, sources),
            supportsProbe = TransferSizes(cacheParams.blockBytes)
          )
        ),
        channelBytes = TLChannelBeatBytes(cacheParams.blockBytes),
        minLatency = 1,
        echoFields = Nil,
        requestFields = Seq(AliasField(2)),
        responseKeys = cacheParams.respKey
      )
    ))
    masterNode
  }

  val l0_nodes = (0 until numCores).map(i => createClientNode(s"l0$i", 32))

  val l1s = (0 until numCores).map(i => LazyModule(new TLCoupledL2AsL1()(baseConfig(1).alterPartial({
    case L2ParamKey => L2Param(
      name = s"l1$i",
      ways = 2,
      sets = 2,
      blockBytes = 2,
      channelBytes = TLChannelBeatBytes(1),
      clientCaches = Seq(L1Param(aliasBitsOpt = Some(2))),
      echoField = Seq(),
      prefetch = Seq(CoupledL2AsL1PrefParam()),
      mshrs = 4,
      hartId = i
    )
    case BankBitsKey => 0
  }))))
  val l1_nodes = l1s.map(_.node)

  val l2s = (0 until numCores).map(i => LazyModule(new TL2CHICoupledL2()(new Config((_, _, _) => {
    case L2ParamKey => 
    //   cacheParams.copy(
    //   name                = s"L2_$i",
    //   hartId              = i,
    // )
    L2Param(
      name = s"l2$i",
      ways = 2,
      sets = 4,
      blockBytes = 2,
      channelBytes = TLChannelBeatBytes(1),
      clientCaches = Seq(L1Param(aliasBitsOpt = Some(2))),
      echoField = Seq(DirtyField()),
      mshrs = 4,
      hartId = i
    )
    case EnableCHI => true
    case BankBitsKey => log2Ceil(banks)
    case MaxHartIdBits => log2Up(numCores)
  }))))
  val l2_nodes = l2s.map(_.node)

  val bankBinders = (0 until numCores).map(_ => BankBinder(banks, 2))

  l0_nodes.zip(l1_nodes).foreach { case (l0, l1) => l1 := l0 }

  l1s.zip(l2s).zipWithIndex.foreach { case ((l1d, l2), i) =>
    val l1xbar = TLXbar()
    l1xbar :=
      TLBuffer() :=
      TLLogger(s"L2_L1[${i}].C[0]", !cacheParams.FPGAPlatform && cacheParams.enableTLLog) :=
      l1d.node

    l2.managerNode :=
      TLXbar() :=*
        bankBinders(i) :*=
      l2.node :*=
      l1xbar
  }

  lazy val module = new LazyModuleImp(this){
    val timer = WireDefault(0.U(64.W))
    val logEnable = WireDefault(false.B)
    val clean = WireDefault(false.B)
    val dump = WireDefault(false.B)

    dontTouch(timer)
    dontTouch(logEnable)
    dontTouch(clean)
    dontTouch(dump)

    val io = IO(Vec(numCores, new Bundle() {
      val chi = new PortIO

      // Input signals for formal verification
      val topInputRandomAddrs = Input(UInt(5.W))
      val topInputNeedT = Input(Bool())
    }))

    l1s.zipWithIndex.foreach { case (l2AsL1, i) =>
      l2AsL1.module.io.prefetcherInputRandomAddr := io(i).topInputRandomAddrs
      l2AsL1.module.io.prefetcherNeedT := io(i).topInputNeedT
      l2AsL1.module.io.l2_tlb_req <> DontCare
      l2AsL1.module.io.debugTopDown <> DontCare
      l2AsL1.module.io.hartId <> DontCare

      dontTouch(l2AsL1.module.io)
    }

    l2s.zipWithIndex.foreach { case (l2, i) =>
      l2.module.io_chi <> io(i).chi
      dontTouch(l2.module.io)

      l2.module.io.hartId := i.U
      l2.module.io_nodeID := i.U(NODEID_WIDTH.W)
      l2.module.io.debugTopDown := DontCare
      l2.module.io.l2_tlb_req <> DontCare
    }

    val verify_timer = RegInit(0.U(50.W))
    verify_timer := verify_timer + 1.U
    val dir_resetFinish = WireDefault(false.B)
    BoringUtils.addSink(dir_resetFinish, "coupledL2_0_dir")
    assume(verify_timer < 100.U || dir_resetFinish)

    val offsetBits = 1
    val bankBits = 0
    val setBits = 2
    val tagBits = 2

    def parseAddress(x: UInt): (UInt, UInt, UInt) = {
      val offset = x
      val set = offset >> (offsetBits + bankBits)
      val tag = set >> setBits
      (tag(tagBits - 1, 0), set(setBits - 1, 0), offset(offsetBits - 1, 0))
    }

    val stateArray = Seq.fill(numCores)(WireDefault(VecInit.fill(4, 2)(0.U(2.W))))
    val tagArray = Seq.fill(numCores)(WireDefault(VecInit.fill(4, 2)(0.U(2.W))))

    for (i <- 0 until numCores) {
      BoringUtils.addSink(stateArray(i), s"stateArray_${i}")
      BoringUtils.addSink(tagArray(i), s"tagArray_${i}")
    }

    def generateAssert(addr: UInt, state1: UInt, state2: UInt): Unit = {
      val (tag, set, offset) = parseAddress(addr)
      val match_tag0 = tagArray(0)(set).map(_ === tag)
      val match_tag1 = tagArray(1)(set).map(_ === tag)
      val hit0 = match_tag0.reduce(_ || _)
      val hit1 = match_tag1.reduce(_ || _)
      val way0 = MuxCase(0.U, match_tag0.zipWithIndex.map {
        case (matched, indx) =>
          matched -> indx.U
      })

      val way1 = MuxCase(0.U, match_tag1.zipWithIndex.map {
        case (matched, indx) =>
          matched -> indx.U
      })
      assert(!(hit0 && stateArray(0)(set)(way0) === state1 &&
        hit1 && stateArray(1)(set)(way1) === state2))
    }
    generateAssert(0.U(32.W), MetaData.TIP, MetaData.TIP)
    generateAssert(0.U(32.W), MetaData.TIP, MetaData.TRUNK)
    generateAssert(0.U(32.W), MetaData.INVALID, MetaData.TIP)
    generateAssert(0.U(32.W), MetaData.BRANCH, MetaData.BRANCH)
    generateAssert(0.U(32.W), MetaData.TIP, MetaData.BRANCH)
    generateAssert(0.U(32.W), MetaData.TRUNK, MetaData.INVALID)
  }
}

object VerifyTopCHIHelper {
  def gen(fTop: Parameters => VerifyTop_CHIL2)(args: Array[String]) = {
    val FPGAPlatform    = false
    val enableChiselDB  = !FPGAPlatform

    val config = new Config((_, _, _) => {
      case L2ParamKey => L2Param(
        clientCaches        = Seq(L1Param(aliasBitsOpt = Some(2))),
        // echoField        = Seq(DirtyField),
        enablePerf          = false,
        enableRollingDB     = enableChiselDB,
        enableMonitor       = enableChiselDB,
        enableTLLog         = enableChiselDB,
        elaboratedTopDown   = false,
        FPGAPlatform        = FPGAPlatform,

        // SAM for tester ICN: Home Node ID = 33
        sam                 = Seq(AddressSet.everything -> 33)
      )
    })

    ChiselDB.init(enableChiselDB)

    val top = DisableMonitors(p => LazyModule(fTop(p)))(config)

    (new ChiselStage).emitSystemVerilog(
      top.module,
      Array("--target-dir", "Verilog/CHI-L2L3")
    )
  }
}


object VerifyTop_CHI_DualCore_0UL extends App {
  VerifyTopCHIHelper.gen(p => new VerifyTop_CHIL2(
    numCores = 2,
    numULAgents = 0,
    banks = 1)(p)
  )(args)
}