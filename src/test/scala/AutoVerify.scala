package openLLC

import scala.sys.process._
import chisel3.stage.ChiselStage
import freechips.rocketchip.diplomacy.{DisableMonitors, LazyModule}
import huancun.{DirtyField, HCCacheParameters, HCCacheParamsKey}
import org.chipsalliance.cde.config.Config

import java.io._
import scala.collection.mutable.ArrayBuffer
import org.chipsalliance.cde.config.Parameters


object AutoVerify_OpenLLC extends App {
  def modifyPy(filename: String): Unit = {
    val pylines = new ArrayBuffer[String]()
    val pyFile = new BufferedReader(new FileReader(new File("set_verify.py")))
    var line = pyFile.readLine()
    while(line != null) {
      pylines.append(
        line.replaceFirst("open\\('.*', 'w'\\) as fout:", s"open('${filename}', 'w') as fout:")
      )
      line = pyFile.readLine()
    }
    pyFile.close()

    val newPy = new BufferedWriter(new FileWriter(new File("set_verify_.py")))
    pylines.foreach { line =>
      newPy.write(line + "\n")
    }
    newPy.close()
  }

  val suffix = "mshrctl"
  val path = "/home/lyj238/VerifyL2/OpenLLC"
  VerifyTopCHIHelper.gen(p => new VerifyTop_CHIL2L3(
    numCores = 2,
    numULAgents = 0,
    banks = 1)(p)
  )(args)
  val cp = s"cp Verilog/CHI-L2L3/VerifyTop.sv .".!
  val filename = s"VerifyTop_${suffix}.sv"
  modifyPy(filename)
  val py = "python set_verify_.py".!
  val rm = s"rm -f ${path}/${suffix}/${filename}".!
  val cpjg = s"cp ${filename} ${path}/${suffix}".!
}